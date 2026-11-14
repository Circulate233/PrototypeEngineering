package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.transaction.ReservedTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Owner-side controller upkeep. A successful payment is charged at most once per server tick.
 */
public final class ControllerEnergy {
    private long paidTick = Long.MIN_VALUE;

    public boolean canPay(MachineInstance machine, long tick) {
        long remaining = machine.networkEnergy(machine.energyPerTick());
        if (remaining == 0 || paidTick == tick) return true;
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var source : MachineHatches.get(machine).energyInputs()) {
            var context = source.context();
            if (!source.isValid() || !visited.add(context)) continue;
            context.lock();
            try {
                var snapshot = source.energySnapshot();
                remaining -= Math.min(remaining, Math.min(snapshot.availableEnergy(), snapshot.maxExtract()));
            } finally {
                context.unlock();
            }
            if (remaining == 0) return true;
        }
        return false;
    }

    /**
     * Call with no other payment outstanding for this controller. Null means insufficient FE.
     */
    public @Nullable Payment reserve(MachineInstance machine, long tick) {
        return reserve(machine, tick, machine.energyPerTick());
    }

    /**
     * Explicit nonnegative subsystem cost; the machine itself need not have an upkeep fee.
     */
    public @Nullable Payment reserve(MachineInstance machine, long tick, long remaining) {
        remaining = machine.networkEnergy(remaining);
        if (remaining == 0 || paidTick == tick) return Payment.FREE;
        var tokens = new ObjectArrayList<ResourceReservation>();
        var visited = new ReferenceOpenHashSet<StorageContext>();
        try {
            for (var source : MachineHatches.get(machine).energyInputs()) {
                var context = source.context();
                if (!source.isValid() || !visited.add(context)) continue;
                context.lock();
                try {
                    var snapshot = source.energySnapshot();
                    long amount = Math.min(remaining, Math.min(snapshot.availableEnergy(), snapshot.maxExtract()));
                    if (amount == 0) continue;
                    var reserved = source.reserveExtractEnergy(snapshot, amount);
                    if (reserved.status() != ReservationResult.Status.ACCEPTED) break;
                    tokens.add(Objects.requireNonNull(reserved.reservation()));
                    remaining -= amount;
                } finally {
                    context.unlock();
                }
                if (remaining == 0) return new Payment(this, paidTick, tick, tokens);
            }
        } catch (RuntimeException | Error failure) {
            try {
                ResourceTransactions.rollback(tokens);
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        ResourceTransactions.rollback(tokens);
        return null;
    }

    /**
     * ResourceBatch settles tokens; commit/rollback below only update the tick receipt.
     */
    public static final class Payment implements ReservedTransaction {
        public static final Payment FREE = new Payment(null, 0, 0, List.of());
        private final @Nullable ControllerEnergy owner;
        private final long previousTick;
        private final long tick;
        private final List<ResourceReservation> reservations;
        private boolean committed;
        private Resolution resolution = Resolution.REVERSIBLE;

        private Payment(@Nullable ControllerEnergy owner, long previousTick, long tick, List<ResourceReservation> reservations) {
            this.owner = owner;
            this.previousTick = previousTick;
            this.tick = tick;
            this.reservations = reservations;
        }

        @Override
        public List<ResourceReservation> reservations() {
            return reservations;
        }

        @Override
        public void commit() {
            Objects.requireNonNull(owner).paidTick = tick;
            committed = true;
        }

        @Override
        public void complete() {
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            if (committed) Objects.requireNonNull(owner).paidTick = previousTick;
            committed = false;
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }
}
