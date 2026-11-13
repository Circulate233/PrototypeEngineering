package com.circulation.prototype_eng.storage;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One host's pending value, never a retained world/fence or cross-session permission.
 */
public final class HatchConfigurationRequest {
    private @Nullable HatchConfiguration target;
    private long generation;
    private long targetRevision;
    private boolean queued;
    private HatchReplacement last = HatchReplacement.of(HatchReconfigurationStatus.UNCHANGED);

    /**
     * Native host observation. Thread ownership is checked before touching world/position ownership.
     */
    public static Boundary boundary(AEBaseTile tile) {
        var world = Objects.requireNonNull(tile.getWorld(), "Native hatch boundary requires a world");
        var server = world.getMinecraftServer();
        if (world.isRemote || server == null || !server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Hatch configuration requires the server owner");
        var position = tile.getPos().toImmutable();
        return new Boundary(ServerTaskSchedulers.forServer(server), () -> !tile.isInvalid() && tile.getWorld() == world
            && world.getMinecraftServer() == server && tile.getPos().equals(position)
            && world.isBlockLoaded(position, false) && world.getTileEntity(position) == tile);
    }

    public HatchReconfigurationStatus status() {
        return last.status();
    }

    public @Nullable HatchConfiguration target() {
        return target;
    }

    public void accept(HatchConfiguration requested, HatchConfiguration effective) {
        if (Objects.equals(target, requested) && targetRevision == HatchConfig.targetRevision()) return;
        generation = Math.incrementExact(generation);
        targetRevision = HatchConfig.targetRevision();
        target = requested.equals(effective) ? null : requested;
        last = HatchReplacement.of(target == null ? HatchReconfigurationStatus.UNCHANGED : HatchReconfigurationStatus.PENDING_BUSY);
    }

    public void reset() {
        generation = Math.incrementExact(generation);
        target = null;
        last = HatchReplacement.of(HatchReconfigurationStatus.UNCHANGED);
    }

    @SuppressWarnings("resource") // Host/server lifecycle owns this borrowed scheduler.
    public void queue(@Nullable Boundary boundary, Supplier<HatchReplacement> apply, Runnable notifyApplied) {
        if (boundary == null) return;
        boundary.requireOwner();
        if (target == null || queued || last.status() == HatchReconfigurationStatus.BLOCKED_CAPACITY && last.unchanged())
            return;
        long expected = generation;
        var scheduler = boundary.scheduler();
        queued = true;
        scheduler.runAtBoundary(() -> {
            queued = false;
            if (expected != generation) return;
            if (targetRevision != HatchConfig.targetRevision()) {
                last = HatchReplacement.of(HatchReconfigurationStatus.STALE_TARGET);
                return;
            }
            if (scheduler.status().closing() || scheduler.status().closed() || !boundary.current().getAsBoolean()) {
                last = HatchReplacement.of(HatchReconfigurationStatus.CLOSED);
                return;
            }
            last = apply.get();
            if (last.status() == HatchReconfigurationStatus.APPLIED || last.status() == HatchReconfigurationStatus.UNCHANGED) {
                target = null;
                notifyApplied.run();
            }
        });
    }

    /**
     * A fresh host observation for one request. It is neither stored in NBT nor retained as authority.
     */
    public record Boundary(TaskScheduler scheduler, BooleanSupplier current) {
        public Boundary {
            Objects.requireNonNull(scheduler);
            Objects.requireNonNull(current);
        }

        public void requireOwner() {
            var status = scheduler.status(); // The scheduler itself verifies its exact owner thread.
            if (status.closing() || status.closed() || !current.getAsBoolean())
                throw new IllegalStateException("Hatch configuration owner is closed or no longer current");
        }
    }
}
