package com.circulation.prototype_eng.api.client.structure;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.client.preview.StructurePreviewExpansion;
import com.circulation.prototype_eng.proxy.ClientProxy;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Java library entry for read-only previews and native world projections; client-thread owned.
 */
public final class StructurePreviews implements AutoCloseable {
    private final ClientStructureCatalog catalog;
    private final StructurePreviewCache<Request> requests;
    private final AutoCloseable subscription;
    private final ObjectArrayList<CloseListener> closeListeners = new ObjectArrayList<>();
    @Nullable
    private Projection projection;
    private boolean closed;

    /**
     * Logical model service. ClientProxy separately registers the native world event adapter.
     */
    public StructurePreviews(ClientStructureCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
        requests = new StructurePreviewCache<>(catalog, 32);
        subscription = catalog.onInvalidated(() -> {
            if (catalog.view() == null) projection = null;
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

    public @Nullable Request request(ResourceLocation id, StructurePreviewSettings settings) {
        requireOpen();
        return requests.get(id, settings, (view, _) -> new Request(this, view, id, settings));
    }

    public boolean current(StructurePreviewModel model) {
        requireOpen();
        return catalog.current(model.generation());
    }

    /**
     * Starts presentation intent only; it never places blocks or alters the server.
     */
    public boolean project(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                           StructureProjectionOptions options) {
        if (request(id, settings) == null) return false;
        projection = new Projection(id, settings, anchor, options);
        return true;
    }

    @SuppressWarnings("UnusedReturnValue") // Addon callers may detect that no projection is active.
    public boolean moveProjection(BlockPos anchor) {
        requireOpen();
        if (projection == null) return false;
        projection = new Projection(projection.id(), projection.settings(), anchor, projection.options());
        return true;
    }

    public void stopProjection() {
        requireOpen();
        projection = null;
    }

    public @Nullable Projection projection() {
        requireOpen();
        return projection;
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
        var listener = new CloseListener(Objects.requireNonNull(callback));
        closeListeners.add(listener);
        return () -> {
            catalog.view();
            closeListeners.remove(listener);
        };
    }

    @SuppressWarnings("ClassCanBeRecord") // Each subscription keeps its identity when the Runnable is shared.
    private static final class CloseListener {
        private final Runnable callback;

        private CloseListener(Runnable callback) {
            this.callback = callback;
        }
    }

    @Override
    public void close() {
        catalog.view();
        if (closed) return;
        requireOpen();
        projection = null;
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

    public record Projection(ResourceLocation id, StructurePreviewSettings settings, BlockPos anchor,
                             StructureProjectionOptions options) {
        public Projection {
            Objects.requireNonNull(id);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(options);
            anchor = Objects.requireNonNull(anchor).toImmutable();
        }
    }

    public enum State {BUILDING, READY, STALE, FAILED}

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
        public int advance(int maxSteps) {
            owner.requireOpen();
            if (maxSteps < 0) throw new IllegalArgumentException("Negative preview work budget");
            if (state() != State.BUILDING) return 0;
            try {
                var building = Objects.requireNonNull(expansion);
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
