package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * One team's fixed tick supply, shared machine leases and ordinary work reservations. Mutable state
 * belongs to the runtime owner; immutable numeric supply remains independent for research grants.
 * Closing revokes new work and commits, while receipts retain compensation against this book alone.
 */
public final class NetworkTeamBudget implements AutoCloseable {
    private final long tick;
    private final NetworkResourceRegistry registry;
    private final Runnable ownerCheck;
    private final Object2LongMap<NetworkResourceType> numericResources;
    private final Object2LongOpenHashMap<NetworkResourceType> available;
    private final Object2LongOpenHashMap<NetworkResourceType> logicalAvailable;
    private final Object2ObjectOpenHashMap<NetworkResourceType, ObjectOpenHashSet<UUID>> leasedMachines = new Object2ObjectOpenHashMap<>();
    // Retain only duplicate-detection keys, not receipts that capture older publications and hosts.
    private final ObjectOpenHashSet<Operation> operations = new ObjectOpenHashSet<>();
    private volatile boolean active = true;

    public NetworkTeamBudget(long tick, NetworkResourceRegistry registry,
                             Object2LongMap<NetworkResourceType> capacity, Runnable ownerCheck) {
        this.ownerCheck = Objects.requireNonNull(ownerCheck, "Network budget owner check");
        ownerCheck.run();
        if (tick < 0L) {
            throw new IllegalArgumentException("Network budget tick must be non-negative");
        }
        this.registry = Objects.requireNonNull(registry, "Computing resource registry");
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Computing resource registry is not ready");
        }
        Objects.requireNonNull(capacity, "Network budget capacity");
        var numeric = new Object2LongOpenHashMap<NetworkResourceType>(capacity.size());
        var logical = new Object2LongOpenHashMap<NetworkResourceType>(capacity.size());
        for (var entry : capacity.object2LongEntrySet()) {
            var type = registry.canonical(entry.getKey());
            long amount = entry.getLongValue();
            if (amount < 0L) {
                throw new IllegalArgumentException("Network budget capacity must be non-negative");
            }
            (type.logicalConcurrency() ? logical : numeric).put(type, amount);
        }
        this.tick = tick;
        this.numericResources = NetworkResourceModifier.copyResources(numeric);
        this.available = numeric;
        this.logicalAvailable = logical;
    }

    public long tick() {
        return tick;
    }

    public boolean active() {
        ownerCheck.run();
        return active;
    }

    /**
     * Immutable original numeric supply; ordinary reservations never consume this research vector.
     */
    public Object2LongMap<NetworkResourceType> numericResources() {
        return numericResources;
    }

    /**
     * An immutable snapshot of ordinary work's remaining numeric balance.
     */
    public Object2LongMap<NetworkResourceType> availableResources() {
        ownerCheck.run();
        return NetworkResourceModifier.copyResources(available);
    }

    public LeaseStatus requestLease(UUID machineId) {
        return requestLease(machineId, NetworkResources.THREAD);
    }

    /**
     * Non-mutating admission check; a probe must not consume the machine's lease.
     */
    public boolean canRequestLease(UUID machineId) {
        ownerCheck.run();
        Objects.requireNonNull(machineId);
        if (!active) {
            return false;
        }
        var machines = leasedMachines.get(NetworkResources.THREAD);
        return machines != null && machines.contains(machineId) || logicalAvailable.getLong(NetworkResources.THREAD) > 0;
    }

    /**
     * Checks the entire numeric demand without reserving balances or an operation identity.
     */
    public boolean canReserve(Object2LongMap<NetworkResourceType> required) {
        ownerCheck.run();
        boolean fits = active;
        for (var entry : Objects.requireNonNull(required).object2LongEntrySet()) {
            var type = registry.canonical(entry.getKey());
            long amount = entry.getLongValue();
            if (type.logicalConcurrency() || amount <= 0) {
                throw new IllegalArgumentException("Work demand must contain positive numeric resources");
            }
            fits &= available.getLong(type) >= amount;
        }
        return fits;
    }

    /**
     * Grants one non-releasable lease per machine and logical dimension, in owner call order.
     */
    public LeaseStatus requestLease(UUID machineId, NetworkResourceType logicalType) {
        ownerCheck.run();
        Objects.requireNonNull(machineId, "Network lease machine id");
        var type = registry.canonical(logicalType);
        if (!type.logicalConcurrency()) {
            throw new IllegalArgumentException("Machine leases require a logical computing resource");
        }
        if (!active) {
            return LeaseStatus.EXPIRED;
        }
        var machines = leasedMachines.get(type);
        if (machines != null && machines.contains(machineId)) {
            return LeaseStatus.ALREADY_GRANTED;
        }
        if (logicalAvailable.getLong(type) == 0L) {
            return LeaseStatus.INSUFFICIENT_CAPACITY;
        }
        if (machines == null) {
            machines = new ObjectOpenHashSet<>();
            leasedMachines.put(type, machines);
        }
        machines.add(machineId);
        logicalAvailable.addTo(type, -1L);
        return LeaseStatus.GRANTED;
    }

    /**
     * Validates the complete positive numeric vector before reserving it as one operation. Failed
     * capacity or authorization checks consume neither resources nor the operation's identity.
     * Empty vectors are accepted as permission receipts for work with no numeric cost.
     */
    public @Nullable Reservation reserve(Operation operation, Object2LongMap<NetworkResourceType> required,
                                         BooleanSupplier authorized) {
        ownerCheck.run();
        Objects.requireNonNull(operation, "Network budget operation");
        Objects.requireNonNull(required, "Network operation requirements");
        Objects.requireNonNull(authorized, "Network operation authorization");
        var quantities = new Object2LongOpenHashMap<NetworkResourceType>(required.size());
        for (var entry : required.object2LongEntrySet()) {
            var type = registry.canonical(entry.getKey());
            long amount = entry.getLongValue();
            if (type.logicalConcurrency() || amount <= 0L) {
                throw new IllegalArgumentException("Network operation requirements must be positive numeric resources");
            }
            quantities.put(type, amount);
        }
        requireUnused(operation);
        if (!active) {
            return null;
        }
        if (!authorized.getAsBoolean() || !active) {
            return null;
        }
        // Authorization runs owner code; recheck identities before touching balances if it admitted work.
        requireUnused(operation);
        for (var entry : quantities.object2LongEntrySet()) {
            if (available.getLong(entry.getKey()) < entry.getLongValue()) {
                return null;
            }
        }
        var reservation = new Reservation(this, operation, NetworkResourceModifier.copyResources(quantities), authorized);
        operations.add(operation);
        for (var entry : quantities.object2LongEntrySet()) {
            available.addTo(entry.getKey(), -entry.getLongValue());
        }
        return reservation;
    }

    private void requireUnused(Operation operation) {
        if (operations.contains(operation)) {
            throw new IllegalStateException("Duplicate network operation at tick " + tick + ": " + operation);
        }
    }

    @Override
    public void close() {
        ownerCheck.run();
        active = false;
    }

    /**
     * NOT_REQUIRED is used by the runtime for exempt centers; the book itself never returns it.
     */
    public enum LeaseStatus {GRANTED, ALREADY_GRANTED, INSUFFICIENT_CAPACITY, EXPIRED, NOT_REQUIRED}

    /**
     * Stable source and sequence distinguish work sites and new rounds on the same machine.
     */
    public record Operation(UUID machineId, ResourceLocation sourceId, long sequence) {
        public Operation {
            Objects.requireNonNull(machineId, "Network operation machine id");
            Objects.requireNonNull(sourceId, "Network operation source id");
            if (sequence < 0L) {
                throw new IllegalArgumentException("Network operation sequence must be non-negative");
            }
        }
    }

    public static final class Reservation {
        private final NetworkTeamBudget issuingBook;
        private final Operation operation;
        private final Object2LongMap<NetworkResourceType> resources;
        private final BooleanSupplier authorized;
        private State state = State.RESERVED;

        private Reservation(NetworkTeamBudget issuingBook, Operation operation,
                            Object2LongMap<NetworkResourceType> resources, BooleanSupplier authorized) {
            this.issuingBook = issuingBook;
            this.operation = operation;
            this.resources = resources;
            this.authorized = authorized;
        }

        public Operation operation() {
            return operation;
        }

        public Object2LongMap<NetworkResourceType> resources() {
            return resources;
        }

        public State state() {
            issuingBook.ownerCheck.run();
            return state;
        }

        /**
         * A stale authorization leaves the reservation intact for original-book compensation.
         */
        public boolean commit() {
            issuingBook.ownerCheck.run();
            requireReserved();
            if (!issuingBook.active) {
                return false;
            }
            if (!authorized.getAsBoolean() || !issuingBook.active) {
                return false;
            }
            requireReserved();
            state = State.COMMITTED;
            return true;
        }

        private void requireReserved() {
            if (state != State.RESERVED) {
                throw new IllegalStateException("Network operation cannot commit from " + state + ": " + operation);
            }
        }

        /**
         * Idempotent compensation also restores committed work, independently of current authority.
         */
        public void rollback() {
            issuingBook.ownerCheck.run();
            if (state == State.ROLLED_BACK) {
                return;
            }
            for (var entry : resources.object2LongEntrySet()) {
                long refunded = Math.addExact(issuingBook.available.getLong(entry.getKey()), entry.getLongValue());
                if (refunded > issuingBook.numericResources.getLong(entry.getKey())) {
                    throw new IllegalStateException("Network compensation exceeds original capacity for " + entry.getKey().id());
                }
            }
            for (var entry : resources.object2LongEntrySet()) {
                issuingBook.available.addTo(entry.getKey(), entry.getLongValue());
            }
            state = State.ROLLED_BACK;
        }

        public enum State {RESERVED, COMMITTED, ROLLED_BACK}
    }
}
