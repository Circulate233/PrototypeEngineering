package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Operation;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Reservation.State;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkTeamBudgetTest {
    private static final UUID MACHINE = new UUID(1L, 1L);
    private static final ResourceLocation FIRST_SLOT = new ResourceLocation("test", "first_slot");
    private static final ResourceLocation SECOND_SLOT = new ResourceLocation("test", "second_slot");

    private static NetworkTeamBudget budget(long tick, Object2LongMap<NetworkResourceType> capacity) {
        var registry = new NetworkResourceRegistry();
        registry.freeze();
        return new NetworkTeamBudget(tick, registry, capacity, ownerCheck());
    }

    private static Runnable ownerCheck() {
        var owner = Thread.currentThread();
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Network budget owner required");
            }
        };
    }

    private static Object2LongOpenHashMap<NetworkResourceType> amounts(NetworkResourceType type, long amount) {
        var values = new Object2LongOpenHashMap<NetworkResourceType>();
        values.put(type, amount);
        return values;
    }

    @Test
    void insufficientDimensionPreservesTheWholeBalanceAndAllowsTheSameOperationToRetry() {
        var capacity = amounts(NetworkResources.COMPUTE, 10L);
        capacity.put(NetworkResources.QUBIT, 2L);
        try (var budget = budget(1L, capacity)) {
            var operation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var required = amounts(NetworkResources.COMPUTE, 7L);
            required.put(NetworkResources.QUBIT, 3L);
            assertNull(budget.reserve(operation, required, () -> true));
            assertEquals(10L, budget.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(2L, budget.availableResources().getLong(NetworkResources.QUBIT));
            required.put(NetworkResources.QUBIT, 2L);
            assertNull(budget.reserve(operation, required, () -> false));
            var reservation = Objects.requireNonNull(budget.reserve(operation, required, () -> true));
            required.clear();
            capacity.clear();
            assertEquals(7L, reservation.resources().getLong(NetworkResources.COMPUTE));
            assertEquals(3L, budget.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(0L, budget.availableResources().getLong(NetworkResources.QUBIT));
            assertEquals(10L, budget.numericResources().getLong(NetworkResources.COMPUTE));
            var retained = budget.availableResources();
            assertThrows(UnsupportedOperationException.class,
                () -> retained.object2LongEntrySet().iterator().next().setValue(100L));
            reservation.rollback();
            assertEquals(3L, retained.getLong(NetworkResources.COMPUTE));
            assertEquals(10L, budget.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(2L, budget.availableResources().getLong(NetworkResources.QUBIT));
        }
    }

    @Test
    void machineSlotsShareOneLeaseWhileCustomLogicalDimensionsHaveIndependentLongCapacity() {
        var registry = new NetworkResourceRegistry();
        var custom = registry.register(new NetworkResourceType(new ResourceLocation("test", "parallel"), true, 0L));
        registry.freeze();
        var capacity = amounts(NetworkResources.THREAD, 1L);
        capacity.put(custom, Long.MAX_VALUE);
        try (var budget = new NetworkTeamBudget(2L, registry, capacity, ownerCheck())) {
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(MACHINE));
            var first = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, FIRST_SLOT, 0L), Object2LongMaps.emptyMap(), () -> true));
            assertEquals(LeaseStatus.ALREADY_GRANTED, budget.requestLease(MACHINE));
            var second = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), Object2LongMaps.emptyMap(), () -> true));
            assertTrue(first.commit());
            second.rollback();
            var another = new UUID(2L, 2L);
            assertEquals(LeaseStatus.INSUFFICIENT_CAPACITY, budget.requestLease(another));
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(MACHINE, custom));
            assertEquals(LeaseStatus.ALREADY_GRANTED, budget.requestLease(MACHINE, custom));
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(another, custom));
            assertEquals(LeaseStatus.ALREADY_GRANTED, budget.requestLease(MACHINE));
        }
    }

    @Test
    void staleAuthorityAndClosedBooksRejectCommitsButCompensateOnlyTheirIssuingTick() {
        try (var oldBook = budget(3L, amounts(NetworkResources.COMPUTE, 10L));
             var newBook = budget(4L, amounts(NetworkResources.COMPUTE, 10L))) {
            var authorized = new boolean[]{true};
            var stale = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 0L), amounts(NetworkResources.COMPUTE, 4L), () -> authorized[0]));
            var committed = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 1L), amounts(NetworkResources.COMPUTE, 3L), () -> true));
            var pending = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 2L), amounts(NetworkResources.COMPUTE, 2L), () -> true));
            assertTrue(committed.commit());
            authorized[0] = false;
            assertFalse(stale.commit());
            assertEquals(State.RESERVED, stale.state());
            oldBook.close();
            oldBook.close();
            assertFalse(oldBook.active());
            assertFalse(pending.commit());
            assertEquals(State.RESERVED, pending.state());
            assertEquals(LeaseStatus.EXPIRED, oldBook.requestLease(MACHINE));
            assertNull(oldBook.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), amounts(NetworkResources.COMPUTE, 1L), () -> true));
            stale.rollback();
            pending.rollback();
            committed.rollback();
            committed.rollback();
            assertEquals(State.ROLLED_BACK, committed.state());
            assertEquals(10L, oldBook.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(10L, newBook.availableResources().getLong(NetworkResources.COMPUTE));
            assertTrue(newBook.active());
        }
    }

    @Test
    void acceptedOperationKeysStayConsumedAfterTerminalStatesAndSequencesAllowNewRounds() {
        try (var budget = budget(5L, amounts(NetworkResources.COMPUTE, 10L))) {
            var firstOperation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var requirement = amounts(NetworkResources.COMPUTE, 2L);
            var first = Objects.requireNonNull(budget.reserve(firstOperation, requirement, () -> true));
            assertThrows(IllegalStateException.class, () -> budget.reserve(firstOperation, requirement, () -> true));
            assertTrue(first.commit());
            assertThrows(IllegalStateException.class, first::commit);
            assertThrows(IllegalStateException.class, () -> budget.reserve(firstOperation, requirement, () -> true));
            first.rollback();
            assertThrows(IllegalStateException.class, first::commit);
            assertThrows(IllegalStateException.class, () -> budget.reserve(firstOperation, requirement, () -> true));
            var next = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, FIRST_SLOT, 1L), requirement, () -> true));
            var otherSlot = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), requirement, () -> true));
            assertTrue(next.commit());
            assertTrue(otherSlot.commit());
            assertEquals(6L, budget.availableResources().getLong(NetworkResources.COMPUTE));
        }
    }

    @Test
    void workersMayReadImmutableSupplyButCannotReadOrChangeOwnerState() throws Exception {
        var capacity = amounts(NetworkResources.COMPUTE, 10L);
        capacity.put(NetworkResources.THREAD, 1L);
        try (var budget = budget(6L, capacity)) {
            var operation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var requirement = amounts(NetworkResources.COMPUTE, 4L);
            var receipt = Objects.requireNonNull(budget.reserve(operation, requirement, () -> true));
            var task = new FutureTask<>(() -> {
                assertEquals(6L, budget.tick());
                assertEquals(10L, budget.numericResources().getLong(NetworkResources.COMPUTE));
                assertEquals(operation, receipt.operation());
                assertEquals(4L, receipt.resources().getLong(NetworkResources.COMPUTE));
                assertThrows(IllegalStateException.class, budget::active);
                assertThrows(IllegalStateException.class, budget::availableResources);
                assertThrows(IllegalStateException.class, () -> budget.requestLease(MACHINE));
                assertThrows(IllegalStateException.class,
                    () -> budget.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), requirement, () -> true));
                assertThrows(IllegalStateException.class, receipt::state);
                assertThrows(IllegalStateException.class, receipt::commit);
                assertThrows(IllegalStateException.class, receipt::rollback);
                assertThrows(IllegalStateException.class, budget::close);
                return null;
            });
            Thread.ofPlatform().daemon().start(task);
            task.get(5L, TimeUnit.SECONDS);
            assertTrue(budget.active());
            assertEquals(State.RESERVED, receipt.state());
            assertEquals(6L, budget.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(MACHINE));
            assertTrue(receipt.commit());
            receipt.rollback();
            assertEquals(10L, budget.availableResources().getLong(NetworkResources.COMPUTE));
        }
    }
}
