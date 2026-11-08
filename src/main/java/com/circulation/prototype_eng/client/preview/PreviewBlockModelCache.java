package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * PM's eight-model LRU, with owner-thread, visit-bounded grouping/baking/upload continuation.
 */
final class PreviewBlockModelCache implements AutoCloseable {
    static final int LAYERS = 4;
    private final Thread owner = Thread.currentThread();
    private final Predicate<StructurePreviewModel> current;
    private final Supplier<ChunkBaker> bakers;
    private final int capacity;
    private final Object2ObjectLinkedOpenHashMap<StructurePreviewModel, Build> entries = new Object2ObjectLinkedOpenHashMap<>();
    private Object epoch = new Object();
    private boolean closed;
    private boolean invalidating;

    PreviewBlockModelCache(Predicate<StructurePreviewModel> current, Supplier<ChunkBaker> bakers, int capacity) {
        this.current = Objects.requireNonNull(current);
        this.bakers = Objects.requireNonNull(bakers);
        if (capacity <= 0) throw new IllegalArgumentException("Nonpositive block-model cache capacity");
        this.capacity = capacity;
    }

    /**
     * Tests use the same concrete continuation with a recording baker, never initialize MC/GL.
     */
    interface ChunkBaker extends AutoCloseable {
        /**
         * One cell/layer/quad visit; false retains this exact cell for the next budget step.
         */
        boolean appendStep(StructurePreviewModel.Cell cell);

        /**
         * Ownership transfers only after this returns; a failed upload must release its local handle.
         */
        LayerMesh upload(int layer);

        @Override
        void close();
    }

    interface LayerMesh extends AutoCloseable {
        void draw();

        @Override
        void close();
    }

    record ChunkKey(int x, int y, int z) {
        static ChunkKey of(BlockPos position) {
            return new ChunkKey(Math.floorDiv(position.getX(), 16), Math.floorDiv(position.getY(), 16), Math.floorDiv(position.getZ(), 16));
        }
    }

    static final class ChunkMesh implements AutoCloseable {
        final ChunkKey key;
        final LayerMesh[] layers = new LayerMesh[LAYERS];
        final double centerX, centerY, centerZ, radius;
        boolean closed;

        ChunkMesh(ChunkKey key) {
            this.key = key;
            centerX = key.x() * 16.0 + 8;
            centerY = key.y() * 16.0 + 8;
            centerZ = key.z() * 16.0 + 8;
            radius = Math.sqrt(3 * 8.0 * 8.0);
        }

        double distanceSquared(BlockPos anchor, double cameraX, double cameraY, double cameraZ) {
            double x = anchor.getX() + centerX - cameraX, y = anchor.getY() + centerY - cameraY, z = anchor.getZ() + centerZ - cameraZ;
            return x * x + y * y + z * z;
        }

        boolean visible(BlockPos anchor, double x, double y, double z, double maxDistance) {
            double limit = maxDistance + radius;
            return distanceSquared(anchor, x, y, z) <= limit * limit;
        }

        boolean nonempty() {
            for (var layer : layers) if (layer != null) return true;
            return false;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            for (int i = 0; i < LAYERS; i++) {
                var layer = layers[i];
                layers[i] = null;
                if (layer != null) try {
                    layer.close();
                } catch (RuntimeException | Error caught) {
                    failure = merge(failure, caught);
                }
            }
            rethrow(failure);
        }
    }

    static final class Ready {
        final Build build;

        private Ready(Build build) {
            this.build = build;
        }

        List<ChunkMesh> chunks() {
            return build.meshes;
        }
    }

    private static final class Group {
        final ChunkKey key;
        final ObjectArrayList<StructurePreviewModel.Cell> cells = new ObjectArrayList<>();

        Group(ChunkKey key) {
            this.key = key;
        }
    }

    private static final class Build implements AutoCloseable {
        final StructurePreviewModel model;
        final Object epoch;
        final Object2ObjectLinkedOpenHashMap<ChunkKey, Group> groups = new Object2ObjectLinkedOpenHashMap<>();
        final ObjectArrayList<ChunkMesh> meshes = new ObjectArrayList<>();
        final Ready ready = new Ready(this);
        int groupCursor, cellCursor, uploadCursor, renderCursor;
        Iterator<Group> pendingGroups;
        Group group;
        ChunkBaker baker;
        ChunkMesh mesh;
        boolean complete, released;

        Build(StructurePreviewModel model, Object epoch) {
            this.model = model;
            this.epoch = epoch;
        }

        @Override
        public void close() {
            if (released) return;
            released = true;
            Throwable failure = null;
            var oldBaker = baker;
            baker = null;
            if (oldBaker != null) try {
                oldBaker.close();
            } catch (RuntimeException | Error caught) {
                failure = merge(failure, caught);
            }
            var oldMesh = mesh;
            mesh = null;
            if (oldMesh != null) try {
                oldMesh.close();
            } catch (RuntimeException | Error caught) {
                failure = merge(failure, caught);
            }
            for (var item : meshes)
                try {
                    item.close();
                } catch (RuntimeException | Error caught) {
                    failure = merge(failure, caught);
                }
            meshes.clear();
            groups.clear();
            group = null;
            pendingGroups = null;
            rethrow(failure);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Block-model cache requires its client owner thread");
    }

    private boolean valid(Build build) {
        return !closed && !invalidating && !build.released && build.epoch == epoch && current.test(build.model);
    }

    boolean valid(Ready ready) {
        requireOwner();
        return valid(ready.build) && ready.build.complete;
    }

    Object epochToken() {
        requireOwner();
        return epoch;
    }

    boolean currentEpoch(Object captured) {
        requireOwner();
        return !closed && !invalidating && epoch == captured;
    }

    /**
     * Each grouped cell, opened chunk, baked cell and uploaded layer consumes one step, even when empty.
     */
    Ready advance(StructurePreviewModel model, int visits) {
        requireOwner();
        if (visits < 0) throw new IllegalArgumentException("Negative block-model build budget");
        if (closed || invalidating || !current.test(model)) return null;
        var build = entries.getAndMoveToLast(model);
        if (build == null) {
            build = new Build(model, epoch);
            entries.putAndMoveToLast(model, build);
            if (entries.size() > capacity) entries.removeFirst().close();
        }
        var captured = build;
        try {
            for (int used = 0; used < visits && valid(captured) && !captured.complete; used++) step(captured);
            return valid(captured) && captured.complete ? captured.ready : null;
        } catch (RuntimeException | Error failure) {
            entries.remove(model, captured);
            Throwable combined = failure;
            try {
                captured.close();
            } catch (RuntimeException | Error cleanup) {
                combined = merge(combined, cleanup);
            }
            rethrow(combined);
            throw failure;
        }
    }

    private void step(Build build) {
        if (build.groupCursor < build.model.cells().size()) {
            var cell = build.model.cells().get(build.groupCursor++);
            var key = ChunkKey.of(cell.position());
            var group = build.groups.get(key);
            if (group == null) {
                group = new Group(key);
                build.groups.put(key, group);
            }
            group.cells.add(cell);
            return;
        }
        if (build.pendingGroups == null) build.pendingGroups = build.groups.values().iterator();
        if (build.group == null) {
            if (!build.pendingGroups.hasNext()) {
                build.complete = true;
                build.groups.clear();
                build.pendingGroups = null;
                return;
            }
            build.group = build.pendingGroups.next();
            build.mesh = new ChunkMesh(build.group.key);
            var created = bakers.get();
            if (!valid(build)) {
                created.close();
                return;
            }
            build.baker = created;
            build.cellCursor = 0;
            build.uploadCursor = 0;
            return;
        }
        if (build.cellCursor < build.group.cells.size()) {
            var cell = build.group.cells.get(build.cellCursor);
            if (build.baker.appendStep(cell) && valid(build)) build.cellCursor++;
            return;
        }
        if (build.uploadCursor < LAYERS) {
            int layer = build.uploadCursor++;
            var uploaded = build.baker.upload(layer);
            if (!valid(build)) {
                if (uploaded != null) uploaded.close();
                return;
            }
            build.mesh.layers[layer] = uploaded;
            if (build.uploadCursor < LAYERS) return;
        }
        var oldBaker = build.baker;
        build.baker = null;
        oldBaker.close();
        if (!valid(build)) return;
        build.meshes.add(build.mesh);
        build.mesh = null;
        build.group = null;
    }

    /**
     * Empty and distant chunks consume visits too. Selection advances one rolling cursor per frame.
     */
    List<ChunkMesh> select(Ready ready, int visits, BlockPos anchor, double x, double y, double z, double maxDistance) {
        requireOwner();
        if (visits < 0) throw new IllegalArgumentException("Negative chunk render budget");
        if (!valid(ready)) return List.of();
        var build = ready.build;
        var result = new ObjectArrayList<ChunkMesh>();
        int count = Math.min(visits, build.meshes.size());
        for (int i = 0; i < count; i++) {
            var mesh = build.meshes.get(build.renderCursor);
            build.renderCursor = build.renderCursor + 1 == build.meshes.size() ? 0 : build.renderCursor + 1;
            if (mesh.nonempty() && mesh.visible(anchor, x, y, z, maxDistance)) result.add(mesh);
        }
        return result;
    }

    static List<ChunkMesh> translucentOrder(List<ChunkMesh> selected, BlockPos anchor, double x, double y, double z) {
        var result = new ObjectArrayList<ChunkMesh>();
        for (var mesh : selected) if (mesh.layers[3] != null) result.add(mesh);
        result.sort((left, right) -> Double.compare(right.distanceSquared(anchor, x, y, z), left.distanceSquared(anchor, x, y, z)));
        return result;
    }

    /**
     * Revokes upload rights before draining old builders/GL handles; reentrant callbacks cannot republish.
     */
    void invalidate() {
        requireOwner();
        boolean previous = invalidating;
        invalidating = true;
        Throwable failure = null;
        try {
            epoch = new Object();
        } catch (RuntimeException | Error caught) {
            closed = true;
            failure = caught;
        }
        // Drain without copying/allocation; callbacks cannot start new builds while this owner is invalidating.
        while (!entries.isEmpty()) {
            var entry = entries.removeFirst();
            try {
                entry.close();
            } catch (RuntimeException | Error caught) {
                failure = merge(failure, caught);
            }
        }
        invalidating = previous;
        rethrow(failure);
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        invalidate();
    }

    /**
     * Preserve a VM error's identity while still attempting the other owned cleanup obligations.
     */
    static Throwable merge(Throwable original, Throwable next) {
        if (original == null) return next;
        if (original == next) return original;
        if (next instanceof VirtualMachineError && !(original instanceof VirtualMachineError)) {
            next.addSuppressed(original);
            return next;
        }
        original.addSuppressed(next);
        return original;
    }

    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
