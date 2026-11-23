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
            var report = budget.run(jobs, (job, slice) -> {
                visits.add(job);
                assertEquals(share, slice.allowanceNanos());
                if (!job.equals(jobs.getFirst())) {
                    clock.addAndGet(share - 1);
                    assertTrue(slice.available());
                    clock.incrementAndGet();
                    assertFalse(slice.available());
                }
            });
            assertEquals(0, report.overruns());
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
        var firstReport = budget.run(jobs, (job, slice) -> {
            visits.add(job);
            clock.addAndGet(ConstructionBudget.TICK_NANOS);
            assertFalse(slice.available());
        });
        assertEquals(List.of(jobs.getFirst()), visits);
        assertEquals(1, firstReport.overruns());
        assertEquals(ConstructionBudget.TICK_NANOS - ConstructionBudget.TICK_NANOS / jobs.size(), firstReport.maxOverrunNanos());
        visits.clear();
        budget.run(jobs, (job, _) -> visits.add(job));
        assertEquals(List.of(jobs.getLast(), jobs.getFirst()), visits);
    }

    @Test
    void tickPreparationAndEarlierOverrunCountAgainstTheGlobalDeadline() {
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var jobs = List.of("preflight", "placement");
        long tickStart = clock.get();
        clock.addAndGet(ConstructionBudget.TICK_NANOS / 4);
        long equalShare = (ConstructionBudget.TICK_NANOS - clock.get()) / jobs.size();
        var report = budget.run(tickStart, jobs, (job, slice) -> {
            if (job.equals(jobs.getFirst())) {
                assertEquals(equalShare, slice.allowanceNanos());
                clock.addAndGet(slice.allowanceNanos() + 1);
            } else {
                assertEquals(equalShare - 1, slice.allowanceNanos());
                clock.addAndGet(slice.allowanceNanos());
                assertFalse(slice.available());
            }
        });
        assertEquals(1, report.overruns());
        assertEquals(ConstructionBudget.TICK_NANOS, clock.get());
    }
}
