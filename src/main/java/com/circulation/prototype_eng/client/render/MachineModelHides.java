package com.circulation.prototype_eng.client.render;

import com.cleanroommc.multiblocked.persistence.MultiblockWorldSavedData;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Exact native per-controller registrations preserve overlaps, foreign entries, and replacement identity.
 */
public class MachineModelHides implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Map<BlockPos, Lease> leases = new Object2ObjectOpenHashMap<>();
    @Nullable
    private List<Lease> capturedLeases;

    /**
     * Borrows positions read-only until the returned lease closes; the caller must not mutate the list.
     */
    public @Nullable Lease acquire(Object world, Object tile, BlockPos controller, List<BlockPos> positions, BooleanSupplier current) {
        requireOwner();
        if (!enabled()) return null;
        var previous = leases.get(controller);
        if (previous != null) previous.close();
        if (positions.isEmpty() || !current.getAsBoolean() || entries().containsKey(controller)) return null;
        var lease = new Lease(this, world, tile, controller.toImmutable(), positions, current);
        try {
            add(controller, positions);
            if (!current.getAsBoolean() || entries().get(controller) != positions) {
                lease.close();
                return null;
            }
            leases.put(controller, lease);
            capturedLeases = null;
            return lease;
        } catch (RuntimeException | Error failure) {
            try {
                lease.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void tick() {
        requireOwner();
        Throwable failure = null;
        for (var lease : leaseSnapshot())
            try {
                if (!lease.current.getAsBoolean() || entries().get(lease.controller) != lease.positions) lease.close();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    public void unload(Object world) {
        requireOwner();
        Throwable failure = null;
        for (var lease : leaseSnapshot())
            if (lease.world == world) try {
                lease.close();
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    @Override
    public void close() {
        requireOwner();
        Throwable failure = null;
        for (var lease : leaseSnapshot())
            try {
                lease.close();
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    private List<Lease> leaseSnapshot() {
        if (capturedLeases == null) capturedLeases = new ObjectArrayList<>(leases.values());
        return capturedLeases;
    }

    protected Map<BlockPos, Collection<BlockPos>> entries() {
        return MultiblockWorldSavedData.multiDisabled;
    }

    protected boolean enabled() {
        return Loader.isModLoaded("component_model_hider");
    }

    protected void add(BlockPos controller, Collection<BlockPos> positions) {
        MultiblockWorldSavedData.addDisableModel(controller, positions);
    }

    protected void remove(BlockPos controller) {
        MultiblockWorldSavedData.removeDisableModel(controller);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Model hiding requires client owner");
    }

    public static final class Lease implements AutoCloseable {
        private final MachineModelHides owner;
        private final Object world, tile;
        private final BlockPos controller;
        private final List<BlockPos> positions;
        private final BooleanSupplier current;
        private boolean closed;

        private Lease(MachineModelHides owner, Object world, Object tile, BlockPos controller, List<BlockPos> positions, BooleanSupplier current) {
            this.owner = owner;
            this.world = world;
            this.tile = tile;
            this.controller = controller;
            this.positions = positions;
            this.current = current;
        }

        public boolean current(Object expectedWorld, Object expectedTile) {
            owner.requireOwner();
            return !closed && world == expectedWorld && tile == expectedTile && owner.leases.get(controller) == this &&
                owner.entries().get(controller) == positions && current.getAsBoolean();
        }

        @Override
        public void close() {
            owner.requireOwner();
            if (closed) return;
            closed = true;
            var current = owner.leases.get(controller);
            if (current != null && current != this) return;
            if (owner.leases.remove(controller, this)) owner.capturedLeases = null;
            if (owner.entries().get(controller) == positions) owner.remove(controller);
        }
    }
}
