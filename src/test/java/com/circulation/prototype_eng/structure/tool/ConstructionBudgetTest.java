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
    void jobsInDifferentWorldsRotateAndKeepTheSameShareWhenAnotherJobFinishesEarly() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var visits = new ArrayList<String>();
        var jobs = List.of("overworld construction", "nether dismantle", "end construction");
        long share = ConstructionBudget.TICK_NANOS / jobs.size();
        for (int tick = 0; tick < jobs.size(); tick++) {
            budget.run(jobs, (job, slice) -> {
                visits.add(job);
                assertEquals(share, slice.allowanceNanos());
                if (!job.equals(jobs.getFirst())) {
                    clock.addAndGet(share - 1);
                    assertTrue(slice.available());
                    clock.incrementAndGet();
                    assertFalse(slice.available());
                }
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
        budget.run(jobs, (job, slice) -> {
            visits.add(job);
            clock.addAndGet(ConstructionBudget.TICK_NANOS);
            assertFalse(slice.available());
        });
        assertEquals(List.of(jobs.getFirst()), visits);
        visits.clear();
        budget.run(jobs, (job, _) -> visits.add(job));
        assertEquals(List.of(jobs.getLast(), jobs.getFirst()), visits);
    }

    @Test
    void laterQueueUsesOnlyTheTimeLeftAfterPreparationAndAnOverrun() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var jobs = List.of("preflight", "placement");
        long tickStart = clock.get();
        clock.addAndGet(ConstructionBudget.TICK_NANOS / 4);
        long equalShare = (ConstructionBudget.TICK_NANOS - clock.get()) / jobs.size();
        budget.run(tickStart, jobs, (job, slice) -> {
            if (job.equals(jobs.getFirst())) {
                assertEquals(equalShare, slice.allowanceNanos());
                clock.addAndGet(slice.allowanceNanos() + 1);
            } else {
                assertEquals(equalShare - 1, slice.allowanceNanos());
                clock.addAndGet(slice.allowanceNanos());
                assertFalse(slice.available());
            }
        });
        assertEquals(ConstructionBudget.TICK_NANOS, clock.get());
    }

    @Test
    void growingLightingReservationEndsTheCurrentShareAndReducesTheNextShare() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var visits = new ArrayList<String>();
        var jobs = List.of("queues light checks", "continues placement");
        long share = ConstructionBudget.TICK_NANOS / 3;
        var reserved = new AtomicLong(ConstructionBudget.TICK_NANOS - share * jobs.size());

        budget.run(clock.get(), jobs, reserved::get, (job, slice) -> {
            visits.add(job);
            if (job.equals(jobs.getFirst())) {
                long placementTime = share / 2;
                clock.addAndGet(placementTime);
                reserved.addAndGet(share - placementTime - 1);
                assertTrue(slice.available());
                reserved.addAndGet(2);
                assertFalse(slice.available());
            } else {
                clock.addAndGet(share - 2);
                assertTrue(slice.available());
                clock.incrementAndGet();
                assertFalse(slice.available());
            }
        });
        assertEquals(jobs, visits);
        assertEquals(ConstructionBudget.TICK_NANOS, clock.get() + reserved.get());
    }
}
