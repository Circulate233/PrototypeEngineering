package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** One long FE balance with exact quantity/capacity holds and a shared concurrency boundary. */
public final class EnergyBuffer implements EnergyHatch {
    private final long capacity;
    private final StorageContext context;
    private long maxReceive;
    private long maxExtract;
    private long stored;
    private long reservedExtract;
    private long reservedReceive;
    private int activeReservations;

    public EnergyBuffer(long capacity, Runnable onChanged) {
        this(capacity, capacity, capacity, onChanged);
    }

    public EnergyBuffer(long capacity, long maxReceive, long maxExtract, Runnable onChanged) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        checkLimits(maxReceive, maxExtract);
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        context = new StorageContext(onChanged);
    }

    @Override
    public StorageContext context() {
        return context;
    }

    /** Updates limits in place. Existing tokens are rechecked against lower limits before applying. */
    public void setTransferLimits(long maxReceive, long maxExtract) {
        checkLimits(maxReceive, maxExtract);
        context.lock();
        try {
            if (!context.isValid()) {
                throw new IllegalStateException("Cannot update invalid energy storage");
            }
            if (this.maxReceive != maxReceive || this.maxExtract != maxExtract) {
                requireChange();
                this.maxReceive = maxReceive;
                this.maxExtract = maxExtract;
                context.changed();
            }
        } finally {
            context.unlock();
        }
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public long storedEnergy() {
        context.lock();
        try {
            return context.isValid() ? stored : 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public EnergySnapshot energySnapshot() {
        context.lock();
        try {
            boolean valid = context.isValid();
            return new EnergySnapshot(context, context.revision(), valid ? stored - reservedExtract : 0,
                valid ? capacity - stored - reservedReceive : 0, maxReceive, maxExtract);
        } finally {
            context.unlock();
        }
    }

    @Override
    public long receiveEnergy(long amount, Actionable mode) {
        checkRequest(amount, mode);
        context.lock();
        try {
            long accepted = context.isValid() ? Math.min(Math.min(amount, maxReceive), capacity - stored - reservedReceive) : 0;
            if (accepted > 0 && mode == Actionable.MODULATE) {
                requireChange();
                stored = Math.addExact(stored, accepted);
                context.changed();
            }
            return accepted;
        } finally {
            context.unlock();
        }
    }

    @Override
    public long extractEnergy(long amount, Actionable mode) {
        checkRequest(amount, mode);
        context.lock();
        try {
            long extracted = context.isValid() ? Math.min(Math.min(amount, maxExtract), stored - reservedExtract) : 0;
            if (extracted > 0 && mode == Actionable.MODULATE) {
                requireChange();
                stored = Math.subtractExact(stored, extracted);
                context.changed();
            }
            return extracted;
        } finally {
            context.unlock();
        }
    }

    @Override
    public ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount) {
        return reserve(snapshot, amount, false);
    }

    @Override
    public ReservationResult reserveReceiveEnergy(EnergySnapshot snapshot, long amount) {
        return reserve(snapshot, amount, true);
    }

    private ReservationResult reserve(EnergySnapshot snapshot, long amount, boolean receive) {
        Objects.requireNonNull(snapshot);
        if (amount <= 0) {
            throw new IllegalArgumentException("An energy reservation needs a positive amount");
        }
        context.lock();
        try {
            if (!context.isValid()) {
                return rejected(ReservationResult.Status.INVALID_STORAGE);
            }
            if (snapshot.context() != context || snapshot.revision() != context.revision()) {
                return rejected(ReservationResult.Status.STALE_SNAPSHOT);
            }
            long available = receive ? capacity - stored - reservedReceive : stored - reservedExtract;
            long limit = receive ? maxReceive : maxExtract;
            if (amount > available || amount > limit) {
                return rejected(ReservationResult.Status.INSUFFICIENT);
            }
            if (activeReservations == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many active energy reservations");
            }
            long nextReceive = receive ? Math.addExact(reservedReceive, amount) : reservedReceive;
            long nextExtract = receive ? reservedExtract : Math.addExact(reservedExtract, amount);
            context.reserveChanges(3);
            reservedReceive = nextReceive;
            reservedExtract = nextExtract;
            activeReservations++;
            context.changed();
            return new ReservationResult(ReservationResult.Status.ACCEPTED, new Reservation(amount, receive));
        } finally {
            context.unlock();
        }
    }

    /** Restores committed energy only when no token owns quantity or refund capacity. */
    public void restore(long amount) {
        if (amount < 0 || amount > capacity) {
            throw new IllegalArgumentException("Saved energy is outside capacity");
        }
        context.lock();
        try {
            if (!context.isValid() || activeReservations != 0) {
                throw new IllegalStateException("Cannot restore invalid or reserved energy storage");
            }
            if (stored != amount) {
                requireChange();
                stored = amount;
                context.changed();
            }
        } finally {
            context.unlock();
        }
    }

    /** Persistence retains the committed balance after transfer views have been invalidated. */
    public long savedEnergy() {
        context.lock();
        try {
            return stored;
        } finally {
            context.unlock();
        }
    }

    @Override
    public boolean isValid() {
        return context.isValid();
    }

    public void invalidate() {
        context.invalidate();
    }

    private void requireChange() {
        context.requireChange();
    }

    private static void checkLimits(long maxReceive, long maxExtract) {
        if (maxReceive < 0 || maxExtract < 0) {
            throw new IllegalArgumentException("Negative energy transfer limit");
        }
    }

    private static void checkRequest(long amount, Actionable mode) {
        Objects.requireNonNull(mode, "mode");
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
    }

    private static ReservationResult rejected(ReservationResult.Status status) {
        return new ReservationResult(status, null);
    }

    private enum State { PENDING, APPLIED, STAGED, COMMITTED, ROLLED_BACK }

    private final class Reservation implements ResourceReservation {
        private final long amount;
        private final boolean receive;
        private State state = State.PENDING;
        private byte revisionChanges = 3;
        @Nullable private StorageFence stagedFence;

        private Reservation(long amount, boolean receive) {
            this.amount = amount;
            this.receive = receive;
        }

        @Override
        public StorageContext context() {
            return context;
        }

        @Override
        public Resolution resolution() {
            context.requireLock();
            requireStagedFence();
            return switch (state) {
                case PENDING -> Resolution.RESERVED;
                case APPLIED, STAGED -> Resolution.APPLIED;
                case COMMITTED -> Resolution.COMPLETED;
                case ROLLED_BACK -> Resolution.ROLLED_BACK;
            };
        }

        @Override public boolean supportsSequentialUse() { return true; }

        @Override public void exposeApplied() {
            context.requireFenceAccess();
            requireState(State.APPLIED);
            context.requireReservedChange(revisionChanges);
            long nextExtract = receive ? Math.subtractExact(reservedExtract, amount) : reservedExtract;
            long nextReceive = receive ? reservedReceive : Math.subtractExact(reservedReceive, amount);
            validateBalance(stored, nextExtract, nextReceive);
            // Retain isolation before returning refund/quantity holds to later steps. The exact
            // amount and direction still own this token's undo until its original fence settles.
            stagedFence = context.retainSequentialReservation();
            reservedExtract = nextExtract;
            reservedReceive = nextReceive;
            state = State.STAGED;
            context.reservedChanged(revisionChanges, false);
            revisionChanges--;
        }

        @Override
        public boolean canApply() {
            context.requireLock();
            requireState(State.PENDING);
            return context.isValid() && amount <= (receive ? maxReceive : maxExtract);
        }

        @Override
        public void apply() {
            context.requireLock();
            requireState(State.PENDING);
            if (!canApply()) {
                throw new IllegalStateException("Energy reservation cannot apply under current limits or lifetime");
            }
            context.requireReservedChange(revisionChanges);
            long nextStored = receive ? Math.addExact(stored, amount) : Math.subtractExact(stored, amount);
            long nextExtract = receive ? Math.addExact(reservedExtract, amount) : Math.subtractExact(reservedExtract, amount);
            long nextReceive = receive ? Math.subtractExact(reservedReceive, amount) : Math.addExact(reservedReceive, amount);
            validateBalance(nextStored, nextExtract, nextReceive);
            stored = nextStored;
            reservedExtract = nextExtract;
            reservedReceive = nextReceive;
            state = State.APPLIED;
            context.reservedChanged(revisionChanges, false);
            revisionChanges--;
        }

        @Override
        public void complete() {
            context.requireLock();
            if (state != State.APPLIED && state != State.STAGED) { throw new IllegalStateException("Cannot complete unapplied or terminal energy reservation"); }
            requireStagedFence();
            release(false);
            state = State.COMMITTED;
            releaseSequentialPin();
        }

        @Override
        public void rollback() {
            context.requireLock();
            if (state != State.PENDING && state != State.APPLIED && state != State.STAGED) {
                throw new IllegalStateException("Cannot roll back terminal energy reservation");
            }
            requireStagedFence();
            release(state == State.APPLIED || state == State.STAGED);
            state = State.ROLLED_BACK;
            releaseSequentialPin();
        }

        private void release(boolean restore) {
            context.requireReservedChange(revisionChanges);
            if (activeReservations <= 0) {
                throw new IllegalStateException("Energy reservation count is inconsistent");
            }
            // Ordinary applied input holds refund space and output holds produced quantity.
            // Exposed tokens already returned that hold; reverse settlement only restores energy.
            boolean holdsReceive = (state == State.PENDING) == receive;
            long nextExtract = state == State.STAGED || holdsReceive ? reservedExtract : Math.subtractExact(reservedExtract, amount);
            long nextReceive = state == State.STAGED || !holdsReceive ? reservedReceive : Math.subtractExact(reservedReceive, amount);
            long nextStored = stored;
            if (restore) {
                nextStored = receive ? Math.subtractExact(stored, amount) : Math.addExact(stored, amount);
            }
            validateBalance(nextStored, nextExtract, nextReceive);
            stored = nextStored;
            reservedExtract = nextExtract;
            reservedReceive = nextReceive;
            activeReservations--;
            context.reservedChanged(revisionChanges, true);
            revisionChanges = 0;
        }

        private void requireStagedFence() {
            if (state == State.STAGED && context.requireFenceAccess() != stagedFence) {
                throw new IllegalStateException("Sequential energy reservation belongs to another storage fence");
            }
        }

        private void releaseSequentialPin() {
            if (stagedFence != null) {
                context.releaseSequentialReservation(stagedFence);
                stagedFence = null;
            }
        }

        private void requireState(State expected) {
            if (state != expected) {
                throw new IllegalStateException("Energy reservation is " + state + ", expected " + expected);
            }
        }
    }

    private void validateBalance(long amount, long extract, long receive) {
        if (amount < 0 || amount > capacity) {
            throw new IllegalStateException("Energy reservation lost its balance bounds");
        }
        if (extract < 0 || extract > amount
            || receive < 0 || receive > capacity - amount) {
            throw new IllegalStateException("Energy reservation lost its quantity or capacity rights");
        }
    }
}
