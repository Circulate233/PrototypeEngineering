package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.client.render.StructureRenderAnchors.Part;
import com.circulation.prototype_eng.client.render.gecko.GeckoAnimationDriver;
import com.circulation.prototype_eng.client.render.gecko.GeckoGeoModelInstances;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBuildTask;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Client-owned current/next slots. Time changes may reuse a completed frame; base/owner/epoch changes revoke it first. */
public class ModelBuildCache implements AutoCloseable {
    private final ModelClientThread client;
    private final ModelAssets assets;
    private final ModelBuildExecutor executor;
    private final ModelBufferPool builders;
    private final DirectVertexBufferPool direct;
    private final ReusableModelVboUploader uploader;
    private final boolean mapped;
    private final Reference2ObjectOpenHashMap<GeckoRenderRequest.Owner, Map<PartMode, Slot>> owners = new Reference2ObjectOpenHashMap<>();
    private boolean closed;

    public ModelBuildCache(ModelClientThread client, ModelAssets assets, ModelBuildExecutor executor,
                           ModelBufferPool builders, DirectVertexBufferPool direct, ReusableModelVboUploader uploader,
                           boolean mapped) {
        this.client = client;
        this.assets = assets;
        this.executor = executor;
        this.builders = builders;
        this.direct = direct;
        this.uploader = uploader;
        this.mapped = mapped;
        client.requireOwner();
        GeckoAnimationDriver.initialize();
    }

    private static void throwFailure(@Nullable Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
        throw new IllegalStateException("Gecko model build failed", failure);
    }

    public GeckoRenderRequest.Epoch resourceEpoch() {
        client.requireOwner();
        return assets.epoch();
    }

    public ReusableModelVboUploader uploader() {
        client.requireOwner();
        return uploader;
    }

    public BuiltModelBuffers.@Nullable Lease request(GeckoRenderRequest request) {
        client.requireOwner();
        if (closed || request.owner().retired() || request.resourceEpoch() != assets.epoch()) return null;
        var locations = new ObjectArrayList<ResourceLocation>();
        locations.add(request.binding().geo());
        locations.add(request.binding().texture());
        if (request.binding().animation() != null) locations.add(request.binding().animation());
        try {
            assets.select(locations);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        var parts = owners.computeIfAbsent(request.owner(), ignored -> new Object2ObjectOpenHashMap<>());
        var key = new PartMode(request.part(), request.mode());
        var slot = parts.get(key);
        if (slot != null && !slot.current.request().sameBase(request)) {
            retire(slot);
            parts.remove(key);
            slot = null;
        }
        if (slot == null) {
            var instance = new GeckoGeoModelInstances.Instance(request, assets, createBaker());
            var task = createTask(request, instance);
            slot = new Slot(instance, task);
            parts.put(key, slot);
            try {
                executor.submit(task);
            } catch (RuntimeException | Error failure) {
                parts.remove(key, slot);
                throw failure;
            }
        }
        if (slot.next != null && slot.next.done()) {
            var next = slot.next;
            slot.next = null;
            if (next.failure() != null) {
                var failure = next.failure();
                next.retire();
                throwFailure(failure);
            }
            var old = slot.current;
            slot.current = next;
            old.retire();
        }
        if (slot.current.failure() != null) {
            var failure = slot.current.failure();
            retire(slot);
            parts.remove(key);
            throwFailure(failure);
        }
        if (slot.current.done() && request.mode() == GeckoModelBaker.BakeMode.ANIMATED_ONLY &&
            !slot.current.request().sample().equals(request.sample()) && slot.next == null) {
            var task = createTask(request, slot.instance);
            slot.next = task;
            try {
                executor.submit(task);
            } catch (RuntimeException | Error failure) {
                slot.next = null;
                throw failure;
            }
        }
        return slot.current.borrow();
    }

    protected GeckoModelBaker createBaker() {
        return new GeckoModelBaker();
    }

    protected GeckoModelBuildTask createTask(GeckoRenderRequest request, GeckoGeoModelInstances.Instance instance) {
        return new GeckoModelBuildTask(request, instance, assets, builders, direct, client, uploader, mapped);
    }

    private void retire(Slot slot) {
        Throwable failure = null;
        try {
            slot.current.retire();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            if (slot.next != null) slot.next.retire();
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        try {
            uploader.retire(slot.current.request().baseKey());
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    public void retire(GeckoRenderRequest.Owner owner) {
        client.requireOwner();
        owner.retire();
        var parts = owners.get(owner);
        Throwable failure = null;
        if (parts != null) for (var slot : parts.values())
            try {
                retire(slot);
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        owners.remove(owner);
        try {
            uploader.retire(owner);
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    @Override
    public void close() {
        client.requireOwner();
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (var parts : owners.values())
            for (var slot : parts.values())
                try {
                    retire(slot);
                } catch (RuntimeException | Error exception) {
                    failure = BuiltModelBuffers.append(failure, exception);
                }
        owners.clear();
        for (var resource : new AutoCloseable[]{uploader, assets, executor, builders, direct})
            try {
                resource.close();
            } catch (Exception | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    private record PartMode(Part part, GeckoModelBaker.BakeMode mode) {
    }

    private static final class Slot {
        final GeckoGeoModelInstances.Instance instance;
        GeckoModelBuildTask current;
        @Nullable GeckoModelBuildTask next;

        Slot(GeckoGeoModelInstances.Instance instance, GeckoModelBuildTask current) {
            this.instance = instance;
            this.current = current;
        }
    }
}
