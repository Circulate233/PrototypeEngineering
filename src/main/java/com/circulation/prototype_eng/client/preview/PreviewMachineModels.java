package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.client.render.MachineModelPasses;
import com.circulation.prototype_eng.client.render.ModelBuildCache;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import com.circulation.prototype_eng.proxy.ClientProxy;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Concrete Scene model ownership. No machine instance, attached controller or accepted runtime state is created. */
final class PreviewMachineModels implements AutoCloseable {
    private static final GeckoModelBaker.BakeMode[] STATIC_MODES = {GeckoModelBaker.BakeMode.ALL};
    private static final GeckoModelBaker.BakeMode[] ANIMATION_MODES = {GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY,
        GeckoModelBaker.BakeMode.TEMP_STATIC_ONLY, GeckoModelBaker.BakeMode.ANIMATED_ONLY};
    private final StructurePreviewView view;
    private final ClientProxy proxy;
    private final MachineRenderBindings bindings;
    private final MachineRenderBindings.Registration subscription;
    private ModelBuildCache cache;
    private GeckoRenderRequest.Owner owner;
    private GeckoRenderRequest.Epoch bindingEpoch, resourceEpoch;
    private MachineRenderBindings.View bindingView;
    private StructurePreviewModel model;
    private Object token, world;
    private PreviewMachineAnchors selection;
    private int cursor;
    private boolean closed;

    PreviewMachineModels(StructurePreviewView view) {
        this.view = view;
        if (!(PrototypeEngineering.proxy instanceof ClientProxy client))
            throw new IllegalStateException("Machine model preview requires the client proxy");
        proxy = client;
        bindings = client.renderBindings();
        subscription = bindings.onInvalidated(this::invalidate);
    }

    /** The caller closes this concrete frame after immediate/deferred-pass handoff has acquired its own readers. */
    Frame request(double ticks) {
        var nextModel = view.model();
        var machine = view.machine();
        if (closed || !view.formingModelsVisible() || machine == null || nextModel == null) {
            invalidate();
            return new Frame(this, null, null, ObjectLists.emptyList());
        }
        var nextCache = proxy.modelBuilds();
        var nextBindings = bindings.view();
        var nextResource = nextCache.resourceEpoch();
        var nextWorld = Minecraft.getMinecraft().world;
        if (cache != nextCache || bindingView != nextBindings || resourceEpoch != nextResource
            || model != nextModel || token != view.token() || world != nextWorld) {
            invalidate();
            cache = nextCache;
            bindingView = nextBindings;
            resourceEpoch = nextResource;
            bindingEpoch = new GeckoRenderRequest.Epoch();
            owner = new GeckoRenderRequest.Owner();
            model = nextModel;
            token = view.token();
            world = nextWorld;
            selection = new PreviewMachineAnchors(nextModel, machine, nextBindings);
        }
        var held = new ObjectArrayList<MachineModelPasses.Entry>();
        var capturedOwner = owner;
        var frame = new Frame(this, cache, capturedOwner, ObjectLists.unmodifiable(held));
        try {
            selection.advance(512);
            var anchors = selection.completed();
            if (anchors == null || anchors.isEmpty()) return frame;
            var sample = GeckoRenderRequest.Sample.at(ticks, proxy.modelRenderOptions().animationStepTicks(), proxy.modelRenderOptions().sampleSnap());
            for (int visited = 0; visited < Math.min(512, anchors.size()); visited++) {
                var anchor = anchors.get(cursor);
                cursor = (cursor + 1) % anchors.size();
                var modes = anchor.binding().animation() == null ? STATIC_MODES : ANIMATION_MODES;
                for (var mode : modes) {
                    var request = GeckoRenderRequest.from(capturedOwner, bindingEpoch, resourceEpoch, anchor,
                        BlockPos.ORIGIN, mode, sample);
                    var lease = cache.request(request);
                    if (lease != null) {
                        var bounds = lease.buffers().bounds();
                        var center = bounds == null ? new Vec3d(anchor.origin()).add(anchor.modelOffset()).add(0.5, 0.5, 0.5) : bounds.getCenter();
                        try {
                            held.add(new MachineModelPasses.Entry(lease, center, frame::current));
                        } catch (RuntimeException | Error failure) {
                            try {
                                lease.close();
                            } catch (RuntimeException | Error cleanup) {
                                failure.addSuppressed(cleanup);
                            }
                            throw failure;
                        }
                    }
                    if (!frame.current()) {
                        frame.close();
                        return new Frame(this, null, null, ObjectLists.emptyList());
                    }
                }
            }
            return frame;
        } catch (RuntimeException | Error failure) {
            Throwable combined = failure;
            try {
                frame.close();
            } catch (RuntimeException | Error cleanup) {
                combined = PreviewBlockModelCache.merge(combined, cleanup);
            }
            try {
                invalidate();
            } catch (RuntimeException | Error cleanup) {
                combined = PreviewBlockModelCache.merge(combined, cleanup);
            }
            PreviewBlockModelCache.rethrow(combined);
            throw failure;
        }
    }

    void invalidate() {
        var previous = cache;
        var previousOwner = owner;
        cache = null;
        owner = null;
        bindingEpoch = resourceEpoch = null;
        bindingView = null;
        model = null;
        token = world = null;
        selection = null;
        cursor = 0;
        if (previous != null && previousOwner != null) previous.retire(previousOwner);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            invalidate();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            subscription.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        PreviewBlockModelCache.rethrow(failure);
    }

    static final class Frame implements AutoCloseable {
        private final PreviewMachineModels source;
        private final ModelBuildCache capturedCache;
        private final GeckoRenderRequest.Owner capturedOwner;
        private final List<MachineModelPasses.Entry> entries;
        private boolean released;

        private Frame(PreviewMachineModels source, ModelBuildCache cache, GeckoRenderRequest.Owner owner, List<MachineModelPasses.Entry> entries) {
            this.source = source;
            capturedCache = cache;
            capturedOwner = owner;
            this.entries = entries;
        }

        boolean current() {
            return !released && !source.closed && capturedOwner != null && source.owner == capturedOwner && source.cache == capturedCache
                && source.view.formingModelsVisible() && source.view.token() == source.token && source.view.model() == source.model
                && source.bindings.current(source.bindingView) && capturedCache.resourceEpoch() == source.resourceEpoch
                && Minecraft.getMinecraft().world == source.world;
        }

        void draw(Vec3d sortingCamera) {
            if (!current()) return;
            try {
                MachineModelPasses.draw(capturedCache, entries, Vec3d.ZERO, sortingCamera, false);
            } catch (RuntimeException | Error failure) {
                Throwable combined = failure;
                try {
                    source.invalidate();
                } catch (RuntimeException | Error cleanup) {
                    combined = PreviewBlockModelCache.merge(combined, cleanup);
                }
                PreviewBlockModelCache.rethrow(combined);
            }
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            Throwable failure = null;
            for (var entry : entries)
                try {
                    entry.lease().close();
                } catch (RuntimeException | Error caught) {
                    failure = PreviewBlockModelCache.merge(failure, caught);
                }
            PreviewBlockModelCache.rethrow(failure);
        }
    }
}
