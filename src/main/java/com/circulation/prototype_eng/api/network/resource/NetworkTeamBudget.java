package com.circulation.prototype_eng.api.network.resource;

import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Owner-managed double resource balances and the fixed integer machine-concurrency limit.
 * Research shares resource balances with other devices; refunds affect only the issuing tick.
 */
public final class NetworkTeamBudget implements AutoCloseable {
    private final long tick;
    private final Runnable ownerCheck;
    private final Reference2DoubleOpenHashMap<NetworkResourceType> ceiling;
    private Reference2DoubleMap<NetworkResourceType> numericResources;
    private final Reference2DoubleOpenHashMap<NetworkResourceType> available;
    private long concurrencyCapacity;
    private final ObjectOpenHashSet<UUID> leasedMachines = new ObjectOpenHashSet<>();
    private final ObjectOpenHashSet<UUID> networkLeases = new ObjectOpenHashSet<>();
    private final ObjectOpenHashSet<Operation> operations = new ObjectOpenHashSet<>();
    private volatile boolean active = true;

    public NetworkTeamBudget(long tick, long concurrencyCapacity,
                             Reference2DoubleMap<NetworkResourceType> capacity, Runnable ownerCheck) {
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        ownerCheck.run();
        if (tick < 0 || concurrencyCapacity < 0)
            throw new IllegalArgumentException("Negative tick or concurrency capacity");
        NetworkResourceModifier.validateResources(capacity);
        this.tick = tick;
        this.concurrencyCapacity = concurrencyCapacity;
        ceiling = new Reference2DoubleOpenHashMap<>(capacity);
        numericResources = capacity;
        available = new Reference2DoubleOpenHashMap<>(capacity);
    }

    public long tick() {
        return tick;
    }

    public boolean active() {
        ownerCheck.run();
        return active;
    }

    /**
     * Current total supply, borrowed read-only for display; reservations use the remaining balance.
     */
    public Reference2DoubleMap<NetworkResourceType> numericResources() {
        return numericResources;
    }

    public Reference2DoubleMap<NetworkResourceType> availableResources() {
        ownerCheck.run();
        var result = new Reference2DoubleOpenHashMap<>(available);
        for (var entry : result.reference2DoubleEntrySet())
            if (entry.getDoubleValue() < 0) entry.setValue(0);
        return result;
    }

    @SuppressWarnings("unused") // Public owner-side budget API for addons.
    public long concurrencyCapacity() {
        ownerCheck.run();
        return concurrencyCapacity;
    }

    public int usedConcurrency() {
        ownerCheck.run();
        return leasedMachines.size();
    }

    public double occupiedResource(NetworkResourceType type) {
        ownerCheck.run();
        return Math.max(0, ceiling.getDouble(type) - available.getDouble(type));
    }

    public boolean hasLease(UUID machineId) {
        ownerCheck.run();
        return leasedMachines.contains(machineId);
    }

    public boolean canRequestLease(UUID machineId) {
        ownerCheck.run();
        return active && (hasLease(machineId) || leasedMachines.size() < concurrencyCapacity);
    }

    public boolean canRequestNetworkLease(UUID machineId) {
        ownerCheck.run();
        return active && (hasLease(machineId) || availableConcurrency(concurrencyCapacity) > 0);
    }

    public long availableConcurrency(long currentSupply) {
        ownerCheck.run();
        return Math.max(0, currentSupply - networkLeases.size());
    }

    public void retainNetworkLeases(Predicate<UUID> current) {
        ownerCheck.run();
        for (var iterator = networkLeases.iterator(); iterator.hasNext(); ) {
            var id = iterator.next();
            if (current.test(id)) continue;
            iterator.remove();
            leasedMachines.remove(id);
        }
    }

    /**
     * Infrastructure reclaims ordinary leases in stable UUID order without a temporary sorted list.
     */
    public LeaseStatus requestNetworkLease(UUID machineId) {
        ownerCheck.run();
        if (!active) return LeaseStatus.EXPIRED;
        if (hasLease(machineId)) {
            networkLeases.add(machineId);
            return LeaseStatus.ALREADY_GRANTED;
        }
        if (!canRequestNetworkLease(machineId)) return LeaseStatus.INSUFFICIENT_CAPACITY;
        while (leasedMachines.size() >= concurrencyCapacity) {
            UUID victim = null;
            for (var candidate : leasedMachines)
                if (!networkLeases.contains(candidate) && (victim == null || candidate.compareTo(victim) > 0))
                    victim = candidate;
            leasedMachines.remove(victim);
        }
        leasedMachines.add(machineId);
        networkLeases.add(machineId);
        return LeaseStatus.GRANTED;
    }

    public LeaseStatus requestLease(UUID machineId) {
        ownerCheck.run();
        Objects.requireNonNull(machineId);
        if (!active) return LeaseStatus.EXPIRED;
        if (leasedMachines.contains(machineId)) return LeaseStatus.ALREADY_GRANTED;
        if (leasedMachines.size() >= concurrencyCapacity) return LeaseStatus.INSUFFICIENT_CAPACITY;
        leasedMachines.add(machineId);
        return LeaseStatus.GRANTED;
    }

    public void releaseLease(UUID machineId) {
        ownerCheck.run();
        if (!networkLeases.contains(machineId)) leasedMachines.remove(machineId);
    }

    /**
     * Supply changes retain consumption and reservations, including across a drop and restoration.
     */
    public void refreshCapacity(long concurrency, Reference2DoubleMap<NetworkResourceType> supply) {
        ownerCheck.run();
        concurrencyCapacity = concurrency;
        for (var type : supply.keySet()) ceiling.putIfAbsent(type, 0);
        for (var entry : ceiling.reference2DoubleEntrySet()) {
            double before = entry.getDoubleValue();
            double after = supply.getDouble(entry.getKey());
            if (before == after) continue;
            entry.setValue(after);
            available.addTo(entry.getKey(), after - before);
        }
        numericResources = supply;
    }

    public boolean canReserve(Reference2DoubleMap<NetworkResourceType> required) {
        ownerCheck.run();
        boolean fits = active;
        for (var entry : required.reference2DoubleEntrySet()) {
            double amount = entry.getDoubleValue();
            if (!Double.isFinite(amount) || amount <= 0)
                throw new IllegalArgumentException("Work demands must be finite and positive");
            fits &= available.getDouble(entry.getKey()) >= amount;
        }
        return fits;
    }

    /**
     * The completed requirement map is borrowed unchanged until this receipt reaches its final state.
     * Admission prechecks the complete vector before any debit or operation-key consumption.
     */
    public @Nullable Reservation reserve(Operation operation, Reference2DoubleMap<NetworkResourceType> required, BooleanSupplier authorized) {
        ownerCheck.run();
        Objects.requireNonNull(operation);
        Objects.requireNonNull(authorized);
        if (operations.contains(operation))
            throw new IllegalStateException("Duplicate network operation at tick " + tick + ": " + operation);
        if (!canReserve(required) || !authorized.getAsBoolean() || !active) return null;
        var receipt = new Reservation(this, operation, required, authorized);
        operations.add(operation);
        for (var entry : required.reference2DoubleEntrySet()) available.addTo(entry.getKey(), -entry.getDoubleValue());
        return receipt;
    }

    @Override
    public void close() {
        ownerCheck.run();
        active = false;
    }

    public enum LeaseStatus {GRANTED, ALREADY_GRANTED, INSUFFICIENT_CAPACITY, EXPIRED, NOT_REQUIRED}

    public record Operation(UUID machineId, ResourceLocation sourceId, long sequence) {
        public Operation {
            Objects.requireNonNull(machineId);
            Objects.requireNonNull(sourceId);
            if (sequence < 0) throw new IllegalArgumentException("Negative work sequence");
        }
    }

    public static final class Reservation {
        private final NetworkTeamBudget issuingBook;
        private final Operation operation;
        private final Reference2DoubleMap<NetworkResourceType> resources;
        private final BooleanSupplier authorized;
        private State state = State.RESERVED;

        private Reservation(NetworkTeamBudget issuingBook, Operation operation, Reference2DoubleMap<NetworkResourceType> resources, BooleanSupplier authorized) {
            this.issuingBook = issuingBook;
            this.operation = operation;
            this.resources = resources;
            this.authorized = authorized;
        }

        public Operation operation() {
            return operation;
        }

        public Reference2DoubleMap<NetworkResourceType> resources() {
            return resources;
        }

        public State state() {
            issuingBook.ownerCheck.run();
            return state;
        }

        public boolean commit() {
            issuingBook.ownerCheck.run();
            if (state != State.RESERVED)
                throw new IllegalStateException("Network operation cannot commit from " + state);
            if (!issuingBook.active || !authorized.getAsBoolean() || !issuingBook.active) return false;
            state = State.COMMITTED;
            return true;
        }

        public void rollback() {
            issuingBook.ownerCheck.run();
            if (state == State.ROLLED_BACK) return;
            for (var entry : resources.reference2DoubleEntrySet())
                if (!Double.isFinite(issuingBook.available.getDouble(entry.getKey()) + entry.getDoubleValue()))
                    throw new ArithmeticException("Computing resource compensation overflows");
            for (var entry : resources.reference2DoubleEntrySet())
                issuingBook.available.addTo(entry.getKey(), entry.getDoubleValue());
            state = State.ROLLED_BACK;
        }

        public enum State {RESERVED, COMMITTED, ROLLED_BACK}
    }
}
