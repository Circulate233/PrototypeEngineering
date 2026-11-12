package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Background immutable byte/image reads for one resource epoch; late reads never repopulate a retired epoch. */
public class ModelAssets implements IResourceManager, AutoCloseable {
    private final ModelClientThread client;
    private final IResourceManager packs;
    private final Set<String> domains;
    private final Path mountedRoot;
    private final GeckoRenderRequest.Epoch epoch = new GeckoRenderRequest.Epoch();
    private final Map<ResourceLocation, Source> sources = new Object2ObjectOpenHashMap<>();
    private final Map<ResourceLocation, Texture> textures = new Object2ObjectOpenHashMap<>();
    private boolean retired;

    public ModelAssets(ModelClientThread client, IResourceManager packs, Path mountedRoot) {
        this.client = client;
        this.packs = packs;
        this.mountedRoot = mountedRoot.toAbsolutePath().normalize();
        client.requireOwner();
        domains = Set.copyOf(packs.getResourceDomains());
    }

    public GeckoRenderRequest.Epoch epoch() {
        return epoch;
    }

    private byte[] read(ResourceLocation location) throws IOException {
        Source source;
        synchronized (this) {
            source = sources.get(location);
        }
        if (source == null) throw new IOException("Model resource was not selected in its client epoch: " + location);
        return source.read();
    }

    /** Select/open exact sources on owner; CPU bytes, JSON parsing and image decoding remain on workers. */
    public void select(List<ResourceLocation> locations) throws IOException {
        client.requireOwner();
        Map<ResourceLocation, Source> captured = new Object2ObjectOpenHashMap<>(locations.size());
        try {
            for (var location : locations) {
                synchronized (this) {
                    if (retired) throw new IOException("Model resource epoch retired");
                    if (sources.containsKey(location) || captured.containsKey(location)) continue;
                }
                var selected = selectSource(location);
                try {
                    captured.put(location, selected);
                } catch (RuntimeException | Error failure) {
                    try {
                        selected.retire();
                    } catch (IOException | RuntimeException | Error exception) {
                        failure.addSuppressed(exception);
                    }
                    throw failure;
                }
            }
            synchronized (this) {
                if (retired) throw new IOException("Model resource epoch retired during source selection");
                sources.putAll(captured);
            }
        } catch (IOException | RuntimeException | Error failure) {
            synchronized (this) {
                for (var entry : captured.entrySet()) sources.remove(entry.getKey(), entry.getValue());
            }
            for (var selected : captured.values())
                try {
                    selected.retire();
                } catch (IOException | RuntimeException | Error exception) {
                    failure.addSuppressed(exception);
                }
            throw failure;
        }
    }

    private Source selectSource(ResourceLocation location) throws IOException {
        var path = mountedRoot.resolve(location.getNamespace()).resolve(location.getPath()).normalize();
        if (!path.startsWith(mountedRoot)) throw new IOException("Model asset escaped mounted resource root");
        var input = Files.isRegularFile(path) ? Files.newInputStream(path) : ModelResourceSources.capture(packs, location);
        try {
            return new Source(input);
        } catch (RuntimeException | Error failure) {
            try {
                input.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
    }

    @Override
    public @NotNull Set<String> getResourceDomains() {
        return domains;
    }

    @Override
    public @NotNull IResource getResource(@NotNull ResourceLocation location) throws IOException {
        return new ByteResource(location, read(location));
    }

    @Override
    public @NotNull List<IResource> getAllResources(@NotNull ResourceLocation location) throws IOException {
        return List.of(getResource(location));
    }

    public TextureLease texture(ResourceLocation location) throws IOException {
        synchronized (this) {
            var hit = textures.get(location);
            if (hit != null) {
                hit.readers = Math.incrementExact(hit.readers);
                return new TextureLease(this, hit);
            }
        }
        BufferedImage image;
        try (var input = new ByteArrayInputStream(read(location))) {
            image = ImageIO.read(input);
        }
        if (image == null) throw new IOException("Unsupported model texture: " + location);
        synchronized (this) {
            var prior = textures.get(location);
            if (prior != null) {
                image.flush();
                prior.readers = Math.incrementExact(prior.readers);
                return new TextureLease(this, prior);
            }
            var entry = new Texture(location, image);
            if (retired) {
                entry.retired = true;
                entry.readers = 1;
            } else textures.put(location, entry);
            return new TextureLease(this, entry);
        }
    }

    public boolean bind(TextureLease lease) {
        client.requireOwner();
        var entry = lease.entry();
        synchronized (this) {
            if (retired || entry.retired || textures.get(entry.source) != entry) return false;
        }
        if (entry.nativeTexture == null) register(entry);
        synchronized (this) {
            if (retired || entry.retired || textures.get(entry.source) != entry) return false;
        }
        bindNative(entry);
        return true;
    }

    public Object textureIdentity(TextureLease lease) {
        return lease.entry();
    }

    @Override
    public void close() {
        client.requireOwner();
        Map<ResourceLocation, Texture> old;
        Map<ResourceLocation, Source> oldSources;
        synchronized (this) {
            if (retired) return;
            retired = true;
            old = Map.copyOf(textures);
            textures.clear();
            oldSources = Map.copyOf(sources);
            sources.clear();
        }
        Throwable failure = null;
        for (var source : oldSources.values())
            try {
                source.retire();
            } catch (IOException | RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        for (var entry : old.values()) {
            boolean dispose;
            synchronized (this) {
                entry.retired = true;
                entry.readers--;
                dispose = claim(entry);
            }
            if (dispose) try {
                dispose(entry);
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        }
        BuiltModelBuffers.rethrow(failure);
    }

    private boolean claim(Texture entry) {
        if (!entry.retired || entry.readers != 0 || entry.disposed) return false;
        entry.disposed = true;
        return true;
    }

    private void release(Texture entry) {
        boolean dispose;
        synchronized (this) {
            entry.readers--;
            dispose = claim(entry);
        }
        if (dispose) dispose(entry);
    }

    private void dispose(Texture entry) {
        client.execute(() -> {
            try {
                if (entry.nativeTexture != null) deleteNative(entry);
            } finally {
                entry.image.flush();
            }
        });
    }

    protected void register(Texture entry) {
        var manager = Minecraft.getMinecraft().getTextureManager();
        var created = new DynamicTexture(entry.image);
        ResourceLocation location;
        try {
            location = manager.getDynamicTextureLocation("prototype_eng_gecko", created);
        } catch (RuntimeException | Error failure) {
            try {
                created.deleteGlTexture();
            } catch (RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
        entry.nativeTexture = created;
        entry.nativeLocation = location;
    }

    protected void bindNative(Texture entry) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(Objects.requireNonNull(entry.nativeLocation));
    }

    protected void deleteNative(Texture entry) {
        var manager = Minecraft.getMinecraft().getTextureManager();
        var location = Objects.requireNonNull(entry.nativeLocation);
        var texture = Objects.requireNonNull(entry.nativeTexture);
        if (manager.getTexture(location) == texture) manager.deleteTexture(location);
        else texture.deleteGlTexture();
    }

    protected static final class Texture {
        final ResourceLocation source;
        final BufferedImage image;
        int readers = 2; // Cache ownership plus the first worker's result reference.
        boolean retired;
        boolean disposed;
        @Nullable DynamicTexture nativeTexture;
        @Nullable ResourceLocation nativeLocation;

        Texture(ResourceLocation source, BufferedImage image) {
            this.source = source;
            this.image = image;
        }
    }

    public static final class TextureLease implements AutoCloseable {
        private final ModelAssets owner;
        private final Texture value;
        private boolean closed;

        private TextureLease(ModelAssets owner, Texture value) {
            this.owner = owner;
            this.value = value;
        }

        private synchronized Texture entry() {
            if (closed) throw new IllegalStateException("Texture lease closed");
            return value;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.release(value);
        }
    }

    private record ByteResource(ResourceLocation location, InputStream input) implements IResource {
        ByteResource(ResourceLocation location, byte[] bytes) {
            this(location, new ByteArrayInputStream(bytes));
        }

        @Override
        public @NotNull ResourceLocation getResourceLocation() {
            return location;
        }

        @Override
        public @NotNull InputStream getInputStream() {
            return input;
        }

        @Override
        public boolean hasMetadata() {
            return false;
        }

        @Override
        public <T extends IMetadataSection> @Nullable T getMetadata(@NotNull String section) {
            return null;
        }

        @Override
        public @NotNull String getResourcePackName() {
            return "PrototypeEngineering model epoch";
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class Source {
        private final InputStream input;
        private final CompletableFuture<byte[]> bytes = new CompletableFuture<>();
        private boolean reading;
        private boolean retired;
        private boolean closed;

        Source(InputStream input) {
            this.input = input;
        }

        byte[] read() throws IOException {
            boolean reader;
            synchronized (this) {
                reader = !reading && !bytes.isDone() && !retired;
                if (reader) reading = true;
            }
            if (reader) {
                byte[] value = null;
                Throwable failure = null;
                try {
                    value = input.readAllBytes();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
                try {
                    closeSource();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = BuiltModelBuffers.append(failure, exception);
                }
                if (failure == null) bytes.complete(value);
                else bytes.completeExceptionally(failure);
            }
            try {
                return bytes.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof IOException exception) throw exception;
                BuiltModelBuffers.rethrow(failure.getCause());
                throw failure;
            }
        }

        void retire() throws IOException {
            boolean close;
            synchronized (this) {
                retired = true;
                close = !reading && !closed;
            }
            if (close) {
                try {
                    closeSource();
                } finally {
                    bytes.completeExceptionally(new IOException("Selected model source retired before its reader started"));
                }
            }
            // Active reader closes its own exact source on exit; owner never waits for disk reads here.
        }

        private void closeSource() throws IOException {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            input.close();
        }
    }
}
