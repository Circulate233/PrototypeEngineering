package com.circulation.prototype_eng.structure.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionBudgetTest {
    @Test
    void firstRoundSharesTimeEquallyAndRotatesBetweenTicks() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var visits = new ArrayList<String>();
        var jobs = List.of("overworld construction", "nether dismantle", "end construction");
        long share = ConstructionBudget.TICK_NANOS / jobs.size();
        for (int tick = 0; tick < jobs.size(); tick++) {
            budget.run(clock.get(), jobs, (job, slice) -> {
                visits.add(job);
                assertEquals(share, slice.deadlineNanos() - clock.get());
                clock.addAndGet(share - 1);
                assertTrue(slice.available());
                clock.incrementAndGet();
                assertFalse(slice.available());
                return false;
            });
        }
        assertEquals(List.of(jobs.get(0), jobs.get(1), jobs.get(2),
            jobs.get(1), jobs.get(2), jobs.get(0), jobs.get(2), jobs.get(0), jobs.get(1)), visits);
    }

    @Test
    void callbackOverrunStopsFurtherWorkAndTheNextTickGivesTheOtherJobFirstAccess() {
        var clock = new AtomicLong(Long.MAX_VALUE - ConstructionBudget.TICK_NANOS / 4);
        var budget = new ConstructionBudget(clock::get);
        var visits = new ArrayList<String>();
        var jobs = List.of("slow callback", "ordinary placement");
        budget.run(clock.get(), jobs, (job, slice) -> {
            visits.add(job);
            clock.addAndGet(ConstructionBudget.TICK_NANOS);
            assertFalse(slice.available());
            return true;
        });
        assertEquals(List.of(jobs.getFirst()), visits);
        visits.clear();
        budget.run(clock.get(), jobs, (job, _) -> {
            visits.add(job);
            return false;
        });
        assertEquals(List.of(jobs.getLast(), jobs.getFirst()), visits);
    }

    @Test
    void laterQueueUsesOnlyTheTimeLeftAfterPreparationAndAnOverrun() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var jobs = List.of("preflight", "placement");
        long tickStart = clock.get();
        clock.addAndGet(ConstructionBudget.TICK_NANOS / 4);
        long deadline = tickStart + ConstructionBudget.TICK_NANOS;
        long equalShare = (deadline - clock.get()) / jobs.size();
        budget.run(tickStart, jobs, (job, slice) -> {
            if (job.equals(jobs.getFirst())) {
                assertEquals(equalShare, slice.deadlineNanos() - clock.get());
                clock.set(slice.deadlineNanos() + 1);
            } else {
                assertEquals(deadline, slice.deadlineNanos());
                clock.set(slice.deadlineNanos());
                assertFalse(slice.available());
            }
            return true;
        });
        assertEquals(deadline, clock.get());
    }

    @Test
    void reclaimUsesTimeActuallyLeftAfterPlacementAndLightingWithoutRetryingWaitingTasks() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var visits = new ArrayList<String>();
        var jobs = List.of("waiting for materials", "finished", "continuing");
        long start = clock.get();
        long deadline = start + ConstructionBudget.TICK_NANOS;
        clock.addAndGet(ConstructionBudget.TICK_NANOS / 5);
        long share = (deadline - clock.get()) / jobs.size();

        budget.run(start, jobs, (job, slice) -> {
            visits.add(job);
            if (visits.size() <= jobs.size()) {
                assertEquals(share, slice.deadlineNanos() - clock.get());
                if (!job.equals(jobs.getLast())) {
                    clock.addAndGet(share / 3);
                    return false;
                }
                clock.set(slice.deadlineNanos());
                return true;
            }
            assertEquals(deadline, slice.deadlineNanos());
            assertTrue(slice.deadlineNanos() - clock.get() > share);
            clock.set(slice.deadlineNanos());
            return true;
        });
        assertEquals(List.of(jobs.get(0), jobs.get(1), jobs.get(2), jobs.get(2)), visits);
        assertEquals(deadline, clock.get());
    }
}
