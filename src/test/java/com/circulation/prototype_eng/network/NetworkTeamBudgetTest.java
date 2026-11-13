package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Operation;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Reservation.State;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
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

    private static NetworkTeamBudget budget(long tick, Reference2DoubleMap<NetworkResourceType> capacity) {
        return new NetworkTeamBudget(tick, 1, capacity, ownerCheck());
    }

    private static Runnable ownerCheck() {
        var owner = Thread.currentThread();
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Network budget owner required");
            }
        };
    }

    private static Reference2DoubleOpenHashMap<NetworkResourceType> amounts(double amount) {
        var values = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        values.put(ComputingResourceFixtures.COMPUTE, amount);
        return values;
    }

    @Test
    void insufficientDimensionPreservesTheWholeBalanceAndAllowsTheSameOperationToRetry() {
        var capacity = amounts(10L);
        capacity.put(ComputingResourceFixtures.QUBIT, 2L);
        try (var budget = budget(1L, capacity)) {
            var operation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var required = amounts(7L);
            required.put(ComputingResourceFixtures.QUBIT, 3L);
            assertNull(budget.reserve(operation, required, () -> true));
            assertEquals(10L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(2L, budget.availableResources().getDouble(ComputingResourceFixtures.QUBIT));
            required.put(ComputingResourceFixtures.QUBIT, 2L);
            assertNull(budget.reserve(operation, required, () -> false));
            var reservation = Objects.requireNonNull(budget.reserve(operation, required, () -> true));
            assertEquals(7L, reservation.resources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(3L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(0L, budget.availableResources().getDouble(ComputingResourceFixtures.QUBIT));
            assertEquals(10L, budget.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
            var retained = budget.availableResources();
            reservation.rollback();
            assertEquals(3L, retained.getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(10L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(2L, budget.availableResources().getDouble(ComputingResourceFixtures.QUBIT));
        }
    }

    @Test
    void machineSlotsShareOneFixedLeaseAndAnotherMachineNeedsCapacity() {
        var capacity = amounts(10);
        try (var budget = new NetworkTeamBudget(2L, 1, capacity, ownerCheck())) {
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(MACHINE));
            var first = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, FIRST_SLOT, 0L), Reference2DoubleMaps.emptyMap(), () -> true));
            assertEquals(LeaseStatus.ALREADY_GRANTED, budget.requestLease(MACHINE));
            var second = Objects.requireNonNull(budget.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), Reference2DoubleMaps.emptyMap(), () -> true));
            assertTrue(first.commit());
            second.rollback();
            var another = new UUID(2L, 2L);
            assertEquals(LeaseStatus.INSUFFICIENT_CAPACITY, budget.requestLease(another));
            assertEquals(LeaseStatus.ALREADY_GRANTED, budget.requestLease(MACHINE));
        }
    }

    @Test
    void staleAuthorityAndClosedBooksRejectCommitsButCompensateOnlyTheirIssuingTick() {
        try (var oldBook = budget(3L, amounts(10L));
             var newBook = budget(4L, amounts(10L))) {
            var authorized = new boolean[]{true};
            var stale = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 0L), amounts(4L), () -> authorized[0]));
            var committed = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 1L), amounts(3L), () -> true));
            var pending = Objects.requireNonNull(oldBook.reserve(new Operation(MACHINE, FIRST_SLOT, 2L), amounts(2L), () -> true));
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
            assertNull(oldBook.reserve(new Operation(MACHINE, SECOND_SLOT, 0L), amounts(1L), () -> true));
            stale.rollback();
            pending.rollback();
            committed.rollback();
            committed.rollback();
            assertEquals(State.ROLLED_BACK, committed.state());
            assertEquals(10L, oldBook.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(10L, newBook.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertTrue(newBook.active());
        }
    }

    @Test
    void acceptedOperationKeysStayConsumedAfterTerminalStatesAndSequencesAllowNewRounds() {
        try (var budget = budget(5L, amounts(10L))) {
            var firstOperation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var requirement = amounts(2L);
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
            assertEquals(6L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void workersMayReadImmutableSupplyButCannotReadOrChangeOwnerState() throws Exception {
        var capacity = amounts(10L);
        try (var budget = budget(6L, capacity)) {
            var operation = new Operation(MACHINE, FIRST_SLOT, 0L);
            var requirement = amounts(4L);
            var receipt = Objects.requireNonNull(budget.reserve(operation, requirement, () -> true));
            var task = new FutureTask<>(() -> {
                assertEquals(6L, budget.tick());
                assertEquals(10L, budget.numericResources().getDouble(ComputingResourceFixtures.COMPUTE));
                assertEquals(operation, receipt.operation());
                assertEquals(4L, receipt.resources().getDouble(ComputingResourceFixtures.COMPUTE));
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
            assertEquals(6L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(LeaseStatus.GRANTED, budget.requestLease(MACHINE));
            assertTrue(receipt.commit());
            receipt.rollback();
            assertEquals(10L, budget.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }
}
