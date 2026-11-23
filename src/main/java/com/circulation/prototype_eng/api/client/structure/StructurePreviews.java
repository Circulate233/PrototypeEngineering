package com.circulation.prototype_eng.api.client.structure;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import com.circulation.prototype_eng.client.preview.StructurePreviewExpansion;
import com.circulation.prototype_eng.proxy.ClientProxy;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;


/**
 * Java library entry for read-only previews and native world projections; client-thread owned.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SideOnly(Side.CLIENT)
public final class StructurePreviews implements AutoCloseable {
    private final ClientStructureCatalog catalog;
    private final StructurePreviewCache<Request> requests;
    private final AutoCloseable subscription;
    private final ObjectArrayList<CloseListener> closeListeners = new ObjectArrayList<>();
    @Nullable
    private Projection projection;
    private long projectionStartedNanos, projectionDurationNanos;
    private boolean closed;

    /**
     * Logical model service. ClientProxy separately registers the native world event adapter.
     */
    public StructurePreviews(ClientStructureCatalog catalog) {
        this.catalog = catalog;
        requests = new StructurePreviewCache<>(catalog, 32);
        subscription = catalog.onInvalidated(() -> {
            var current = projection;
            if (catalog.view() == null || current != null && current.external() != null && current.external().fixed()
                && current.settings().orientation() != declaredOrientation(current.id())) clearProjection();
        });
    }

    /**
     * Default connected client service, callable by addon GUIs, commands or JEI integration.
     */
    public static StructurePreviews client() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Structure previews require the client thread");
        if (!(PrototypeEngineering.proxy instanceof ClientProxy proxy))
            throw new IllegalStateException("Structure previews require a client proxy");
        return proxy.previews();
    }

    private static Throwable mergeCloseFailure(Throwable original, Throwable next) {
        if (original == null) return next;
        if (original == next) return original;
        if (next instanceof VirtualMachineError && !(original instanceof VirtualMachineError)) {
            next.addSuppressed(original);
            return next;
        }
        original.addSuppressed(next);
        return original;
    }

    public @Nullable Request request(ResourceLocation id, StructurePreviewSettings settings) {
        requireOpen();
        return requests.get(id, settings, (view, _) -> new Request(this, view, id, settings));
    }

    public boolean current(StructurePreviewModel model) {
        requireOpen();
        return catalog.current(model.generation());
    }

    /**
     * Current authoritative names for client command completion; incomplete packets remain private.
     */
    public @Nullable StructureCatalogView catalogView() {
        requireOpen();
        return catalog.view();
    }

    public @Nullable BlockOrientation declaredOrientation(ResourceLocation id) {
        var view = catalogView();
        if (view == null) return null;
        var entry = view.entries().get(id);
        return entry == null ? null : view.nodes().get(entry.node()).orientation();
    }

    public AutoCloseable onInvalidated(Runnable callback) {
        requireOpen();
        return catalog.onInvalidated(callback);
    }

    /**
     * Starts presentation intent only; it never places blocks or alters the server.
     */
    public boolean project(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                           StructureProjectionOptions options) {
        return project(id, settings, anchor, options, null);
    }

    /** Starts an external anchor projection with the registration's orientation limits. */
    public boolean project(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                           StructureProjectionOptions options, StructureConstructionRegistry.@Nullable Entry external) {
        if (external != null) {
            if (!external.structure().equals(id)) throw new IllegalArgumentException("Projection anchor does not reference the requested structure");
            var rotation = external.rotation();
            var orientation = external.fixed() ? declaredOrientation(id)
                : rotation == null ? settings.orientation() : rotation.project(settings.orientation());
            if (orientation == null) return false;
            settings = settings.withOrientation(orientation);
        }
        if (request(id, settings) == null) return false;
        projection = new Projection(id, settings, anchor, options, external);
        projectionDurationNanos = 0;
        return true;
    }

    /**
     * GUI projections may expire without closing their logical preview service.
     */
    @SuppressWarnings("unused") // Public entry for timed standalone projections.
    public boolean projectFor(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                              StructureProjectionOptions options, long durationMillis) {
        return projectFor(id, settings, anchor, options, durationMillis, null);
    }

    public boolean projectFor(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                              StructureProjectionOptions options, long durationMillis,
                              StructureConstructionRegistry.@Nullable Entry external) {
        if (durationMillis <= 0 || durationMillis > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("Invalid projection lifetime");
        if (!project(id, settings, anchor, options, external)) return false;
        projectionStartedNanos = System.nanoTime();
        projectionDurationNanos = durationMillis * 1_000_000;
        return true;
    }

    /**
     * Changes presentation only for the exact active intent and never renews a timed lifetime.
     */
    public boolean replaceProjection(Projection expected, StructurePreviewSettings settings, StructureProjectionOptions options) {
        requireOpen();
        if (projection() != expected) return false;
        var external = expected.external();
        if (external != null && expected.settings().orientation() != settings.orientation()) {
            var rotation = external.rotation();
            if (rotation == null) return false;
            var projected = rotation.project(settings.orientation());
            if (projected == null) return false;
            settings = settings.withOrientation(projected);
        }
        if (request(expected.id(), settings) == null || projection() != expected) return false;
        projection = new Projection(expected.id(), settings, expected.anchor(), options, external);
        return true;
    }

    @SuppressWarnings("UnusedReturnValue") // Addon callers may detect that no projection is active.
    public boolean moveProjection(BlockPos anchor) {
        requireOpen();
        var current = projection();
        if (current == null) return false;
        projection = new Projection(current.id(), current.settings(), anchor, current.options(), current.external());
        return true;
    }

    public void stopProjection() {
        requireOpen();
        clearProjection();
    }

    public @Nullable Projection projection() {
        requireOpen();
        if (projection != null && projectionDurationNanos != 0
            && System.nanoTime() - projectionStartedNanos >= projectionDurationNanos) clearProjection();
        return projection;
    }

    private void clearProjection() {
        projection = null;
        projectionDurationNanos = 0;
    }

    public boolean closed() {
        catalog.view();
        return closed;
    }

    private void requireOpen() {
        catalog.view();
        if (closed) throw new IllegalStateException("Structure previews are closed");
    }

    /**
     * Owner-thread close subscriptions run in reverse registration order; closed registration is rejected.
     */
    public AutoCloseable onClosed(Runnable callback) {
        requireOpen();
        var listener = new CloseListener(callback);
        closeListeners.add(listener);
        return () -> {
            catalog.view();
            closeListeners.remove(listener);
        };
    }

    @Override
    public void close() {
        catalog.view();
        if (closed) return;
        requireOpen();
        clearProjection();
        closed = true;
        Throwable failure = null;
        try {
            requests.close();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            subscription.close();
        } catch (Exception caught) {
            failure = mergeCloseFailure(failure, new IllegalStateException("Cannot release preview subscription", caught));
        } catch (Error caught) {
            failure = mergeCloseFailure(failure, caught);
        }
        while (!closeListeners.isEmpty()) {
            var listener = closeListeners.removeLast();
            try {
                listener.callback.run();
            } catch (RuntimeException | Error caught) {
                failure = mergeCloseFailure(failure, caught);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    public enum State {BUILDING, READY, STALE, FAILED}

    @SuppressWarnings("ClassCanBeRecord") // Each subscription keeps its identity when the Runnable is shared.
    private static final class CloseListener {
        private final Runnable callback;

        private CloseListener(Runnable callback) {
            this.callback = callback;
        }
    }

    public record Projection(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                             StructureProjectionOptions options, StructureConstructionRegistry.@Nullable Entry external) {
        public Projection(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                          StructureProjectionOptions options) {
            this(id, settings, anchor, options, null);
        }

        public Projection {
            anchor = anchor.toImmutable();
        }

        public boolean rotatable() {
            return external == null || external.rotation() != null;
        }
    }

    public static final class Request {
        private final StructurePreviews owner;
        private final StructureCatalogView generation;
        @Nullable
        private StructurePreviewExpansion expansion;
        @Nullable
        private StructurePreviewModel completed;
        @Nullable
        private RuntimeException failure;

        private Request(StructurePreviews owner, StructureCatalogView generation, ResourceLocation id, StructurePreviewSettings settings) {
            this.owner = owner;
            this.generation = generation;
            expansion = new StructurePreviewExpansion(generation, id, settings);
        }

        public State state() {
            owner.catalog.view();
            if (owner.closed || !owner.catalog.current(generation)) return State.STALE;
            if (failure != null) return State.FAILED;
            return completed == null ? State.BUILDING : State.READY;
        }

        /**
         * Visits at most maxSteps expansion/assembly entries; no partial model is published.
         */
        @SuppressWarnings("DataFlowIssue") // BUILDING retains its expansion until completion or failure.
        public int advance(int maxSteps) {
            owner.requireOpen();
            if (maxSteps < 0) throw new IllegalArgumentException("Negative preview work budget");
            if (state() != State.BUILDING) return 0;
            try {
                var building = expansion;
                int used = building.advance(maxSteps);
                completed = building.model();
                if (completed != null) expansion = null;
                return used;
            } catch (RuntimeException cause) {
                failure = cause;
                expansion = null;
                throw cause;
            }
        }

        public @Nullable StructurePreviewModel model() {
            return state() == State.READY ? completed : null;
        }

        public @Nullable RuntimeException failure() {
            owner.catalog.view();
            return failure;
        }
    }
}
