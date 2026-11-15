package com.circulation.prototype_eng.client.render;

import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Captures model bytes only: Gecko JSON loaders and ImageIO do not consume Minecraft resource metadata.
 */
final class ModelResourceSources {
    private ModelResourceSources() {
    }

    /**
     * Called on the client owner, before old pack close or in-place resource manager replacement.
     */
    static InputStream capture(IResourceManager manager, ResourceLocation location) throws IOException {
        if (manager.getClass() == SimpleReloadableResourceManager.class) {
            var domains = ((SimpleReloadableResourceManager) manager).domainResourceManagers;
            var domain = domains.get(location.getNamespace());
            if (domain == null) throw new FileNotFoundException(location.toString());
            if (domain.getClass() == FallbackResourceManager.class)
                return capturePacks(domain.resourcePacks, location);
        } else if (manager.getClass() == FallbackResourceManager.class) {
            return capturePacks(((FallbackResourceManager) manager).resourcePacks, location);
        }
        // An overridden manager or native subclass may change selection semantics; freeze its native result.
        try (var resource = manager.getResource(location)) {
            return new ByteArrayInputStream(resource.getInputStream().readAllBytes());
        }
    }

    static InputStream capturePacks(List<IResourcePack> packs, ResourceLocation location) throws IOException {
        if (location.getPath().contains("..")) throw new IOException("Invalid relative path to resource: " + location);
        for (int index = packs.size() - 1; index >= 0; index--) {
            var pack = packs.get(index);
            if (!pack.resourceExists(location)) continue;
            if (pack.getClass() == FileResourcePack.class) return independentZip((FileResourcePack) pack, location);
            if (pack.getClass() == FolderResourcePack.class) return pack.getInputStream(location);
            // Custom packs and overridden native subclasses retain the exact native stream semantics.
            try (var input = pack.getInputStream(location)) {
                return new ByteArrayInputStream(input.readAllBytes());
            }
        }
        throw new FileNotFoundException(location.toString());
    }

    private static InputStream independentZip(FileResourcePack pack, ResourceLocation location) throws IOException {
        var zip = new ZipFile(pack.getResourcePackFile());
        try {
            var entry = zip.getEntry("assets/" + location.getNamespace() + "/" + location.getPath());
            if (entry == null) throw new FileNotFoundException(location.toString());
            var input = zip.getInputStream(entry);
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    Throwable failure = null;
                    try {
                        super.close();
                    } catch (IOException | RuntimeException | Error exception) {
                        failure = exception;
                    }
                    try {
                        zip.close();
                    } catch (IOException | RuntimeException | Error exception) {
                        failure = BuiltModelBuffers.append(failure, exception);
                    }
                    if (failure instanceof IOException exception) throw exception;
                    BuiltModelBuffers.rethrow(failure);
                }
            };
        } catch (IOException | RuntimeException | Error failure) {
            try {
                zip.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
    }
}
