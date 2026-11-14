package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionProgressTest {
    @Test
    void unfinishedFluidPositionsPreventBlockMiningAcrossRetries() {
        var progress = new ConstructionProgress(4, index -> index == 1 || index == 3);
        var visits = new IntArrayList();
        progress.advance(8, index -> {
            visits.add(index);
            return index == 1 ? ConstructionProgress.Step.RETRY : ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(1, 3), visits);
        assertEquals(1, progress.done());
        assertTrue(progress.firstPass());
        visits.clear();
        progress.advance(8, index -> {
            visits.add(index);
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(1, visits.getInt(0));
        assertFalse(progress.firstPass());
        progress.advance(8, _ -> ConstructionProgress.Step.COMPLETE);
        assertEquals(4, progress.done());
    }

    @Test
    void fluidPositionCanContinueAsAPlacementAfterItsFluidIsRemoved() {
        var progress = new ConstructionProgress(3, index -> index == 1);
        var visits = new IntArrayList();
        progress.advance(1, index -> {
            visits.add(index);
            return ConstructionProgress.Step.DEFER;
        });
        assertEquals(IntList.of(1), visits);
        assertEquals(0, progress.done());
        assertFalse(progress.firstPass());
        visits.clear();
        progress.advance(8, index -> {
            visits.add(index);
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(2, 0, 1), visits);
        assertEquals(3, progress.done());
    }

    @Test
    void missingMaterialHoldsTheCurrentPositionUntilSupplyArrives() {
        var progress = new ConstructionProgress(3);
        var visits = new IntArrayList();
        progress.advance(8, index -> {
            visits.add(index);
            return index == 1 ? ConstructionProgress.Step.WAIT : ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(0, 1), visits);
        assertEquals(1, progress.done());
        assertTrue(progress.waiting());

        visits.clear();
        progress.advance(8, index -> {
            visits.add(index);
            return ConstructionProgress.Step.WAIT;
        });
        assertEquals(IntList.of(1), visits);
        assertEquals(1, progress.done());

        visits.clear();
        progress.advance(8, index -> {
            visits.add(index);
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(1, 2), visits);
        assertEquals(3, progress.done());
        assertFalse(progress.waiting());
    }

    @Test
    void skippedPositionsAreNotRetriedAndRemainingPositionsRespectTheBatchBudget() {
        var progress = new ConstructionProgress(4);
        var visits = new IntArrayList();
        progress.advance(2, index -> {
            visits.add(index);
            // COMPLETE also represents an explicitly skipped missing block.
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(0, 1), visits);
        assertEquals(2, progress.done());
        visits.clear();
        progress.advance(2, index -> {
            visits.add(index);
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(2, 3), visits);
        assertEquals(4, progress.done());
    }

    @Test
    void temporaryPlacementFailureRetriesWithoutBlockingOtherAvailablePositions() {
        var progress = new ConstructionProgress(3);
        var visits = new IntArrayList();
        progress.advance(8, index -> {
            visits.add(index);
            return index == 0 ? ConstructionProgress.Step.RETRY : ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(0, 1, 2), visits);
        assertEquals(2, progress.done());
        assertFalse(progress.waiting());
        visits.clear();
        progress.advance(1, index -> {
            visits.add(index);
            return ConstructionProgress.Step.COMPLETE;
        });
        assertEquals(IntList.of(0), visits);
        assertEquals(3, progress.done());
    }
}
