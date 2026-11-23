package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.structure.tool.ConstructionProgress.Phase;
import com.circulation.prototype_eng.structure.tool.ConstructionProgress.Step;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConstructionProgressTest {
    @Test
    void constructionPlacesFluidAfterTheLastSolidRetryAndItsNotifications() {
        var progress = new ConstructionProgress();
        progress.add(0, false, false);
        progress.add(1, false, false);
        progress.add(2, false, true);
        var visits = new IntArrayList();

        assertFalse(advance(progress, 0, index -> {
            visits.add(index);
            return index == 0 ? Step.RETRY : Step.COMPLETE;
        }));
        assertEquals(IntList.of(0, 1), visits);
        assertEquals(Phase.SOLIDS, progress.nextPhase());

        assertFalse(advance(progress, 1, index -> {
            visits.add(index);
            return Step.COMPLETE;
        }));
        assertEquals(Phase.FLUIDS, progress.nextPhase());
        assertFalse(advance(progress, 1, _ -> fail("Fluid ran before the end-of-tick notifications")));
        assertFalse(advance(progress, 2, index -> {
            visits.add(index);
            return Step.COMPLETE;
        }));
        assertEquals(IntList.of(0, 1, 0, 2), visits);
        assertEquals(Phase.DONE, progress.nextPhase());
    }

    @Test
    void skippedSolidPositionsSendTheRemainingFluidsToTheSkipPhase() {
        var progress = new ConstructionProgress();
        progress.add(0, false, false);
        progress.add(1, false, true);
        assertTrue(advance(progress, 0, _ -> {
            progress.missSolid();
            return Step.COMPLETE;
        }));
        assertEquals(Phase.SKIP_FLUIDS, progress.nextPhase());
        advance(progress, 0, _ -> Step.COMPLETE);
        assertEquals(Phase.DONE, progress.nextPhase());
    }

    @Test
    void clearingAFluidKeepsItsNewSolidInTheOriginalPlacementOrder() {
        var progress = new ConstructionProgress();
        progress.add(0, true, false);
        progress.add(1, false, false);
        var visits = new IntArrayList();
        assertTrue(advance(progress, 0, index -> {
            visits.add(index);
            return Step.CONTINUE;
        }));
        assertEquals(Phase.SOLIDS, progress.nextPhase());
        advance(progress, 0, index -> {
            visits.add(index);
            return Step.COMPLETE;
        });
        assertEquals(IntList.of(0, 0, 1), visits);
        assertEquals(2, progress.done());
    }

    @Test
    void dismantlingWaitsForFluidsAndDoesNotRemoveThemTwice() {
        var progress = new ConstructionProgress();
        progress.add(0, false, false);
        progress.add(1, true, false);
        var visits = new IntArrayList();
        assertFalse(advance(progress, 0, index -> {
            visits.add(index);
            return Step.RETRY;
        }));
        assertTrue(advance(progress, 1, index -> {
            visits.add(index);
            return Step.COMPLETE;
        }));
        advance(progress, 1, index -> {
            visits.add(index);
            return Step.COMPLETE;
        });
        assertEquals(IntList.of(1, 1, 0), visits);
        assertEquals(2, progress.done());
    }

    @Test
    void expiredShareKeepsTheNextPositionAndMissingMaterialWaitsForTheNextTick() {
        var progress = new ConstructionProgress();
        progress.add(0, false, false);
        progress.add(1, false, false);
        progress.add(2, false, false);
        var clock = new AtomicLong();
        var budget = new ConstructionBudget(clock::get);
        var tasks = List.of(progress);
        var visits = new IntArrayList();

        budget.run(clock.get(), tasks, (queue, slice) -> queue.advance(0, Phase.SOLIDS, slice::available, index -> {
            visits.add(index);
            clock.set(slice.deadlineNanos());
            return Step.COMPLETE;
        }));
        assertEquals(IntList.of(0), visits);
        assertFalse(advance(progress, 1, index -> {
            visits.add(index);
            return Step.WAIT;
        }));
        assertTrue(progress.waiting());
        assertFalse(advance(progress, 1, _ -> fail("A waiting position was retried in the same tick")));
        advance(progress, 2, index -> {
            visits.add(index);
            return Step.COMPLETE;
        });
        assertEquals(IntList.of(0, 1, 1, 2), visits);
        assertEquals(3, progress.done());
    }

    private static boolean advance(ConstructionProgress progress, long tick, IntFunction<Step> operation) {
        return progress.advance(tick, progress.nextPhase(), () -> true, operation);
    }
}
