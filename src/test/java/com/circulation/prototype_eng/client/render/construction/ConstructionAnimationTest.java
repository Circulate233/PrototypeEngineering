package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.MeshKind;
import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Section;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.FINISH_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionAnimationTest {
    private static final ConstructionArea AREA = new ConstructionArea(new BlockPos(-1, 37, 2), new BlockPos(0, 37, 2));

    @Test
    void liveViewerPlaysTheFullRevealAfterDelayedEndWithoutRestartingOnDuplicates() {
        var animation = new ConstructionAnimation(false, 100, AREA);
        animation.sample(103, 5);
        long finishedAt = 140;
        long receivedAt = 200;
        assertTrue(animation.age(receivedAt) > finishedAt - 100 + FINISH_TICKS);

        animation.finish(finishedAt, receivedAt, true);
        assertEquals(0, animation.finishAge(receivedAt));
        animation.sample(151, receivedAt + 6);
        animation.finish(finishedAt, receivedAt + 7, true);
        assertEquals(10.5, animation.finishAge(receivedAt + 10.5));
        assertFalse(animation.expired(receivedAt + FINISH_TICKS - 1));
        assertTrue(animation.expired(receivedAt + FINISH_TICKS));
    }

    @Test
    void lateViewerContinuesFromTheServerSnapshotWithoutRestartingTheReveal() {
        var animation = new ConstructionAnimation(true, 100, AREA);
        long finishedAt = 140;
        long elapsed = FINISH_TICKS / 2;
        animation.sample(finishedAt + elapsed, 2);
        animation.finish(finishedAt, 2, true);
        assertEquals(elapsed, animation.finishAge(2));
        assertFalse(animation.expired(2 + FINISH_TICKS - elapsed - 1));
        assertTrue(animation.expired(2 + FINISH_TICKS - elapsed));
    }

    @Test
    void observingASecondSectionSuppressesInteriorSurfacesAcrossTheNegativeBoundary() {
        var animation = new ConstructionAnimation(false, 0, AREA);
        var first = new Section(-1, 1, 0);
        var second = new Section(0, 1, 0);
        long firstKey = ConstructionAnimation.key(first.x, first.y, first.z);
        long secondKey = ConstructionAnimation.key(second.x, second.y, second.z);
        first.plan.set(ConstructionAnimation.index(31, 5, 2));
        second.plan.set(ConstructionAnimation.index(0, 5, 2));
        animation.sections.put(firstKey, first);
        assertFalse(animation.occupied(first, 32, 5, 2, MeshKind.PLAN));
        animation.sections.put(secondKey, second);
        animation.markDirty(secondKey, true);
        assertTrue(animation.dirty.contains(firstKey));
        assertTrue(animation.occupied(first, 32, 5, 2, MeshKind.PLAN));
        assertTrue(animation.occupied(second, -1, 5, 2, MeshKind.PLAN));
        animation.dirty.clear();
        animation.sections.remove(secondKey);
        animation.markDirty(secondKey, true);
        assertTrue(animation.dirty.contains(firstKey));
        assertFalse(animation.occupied(first, 32, 5, 2, MeshKind.PLAN));
    }

    @Test
    void skippedAndCommittedPositionsContributeToDifferentMeshPasses() {
        var batch = new Section(0, 1, 0);
        int building = ConstructionAnimation.index(7, 11, 19);
        int skipped = ConstructionAnimation.index(8, 11, 19);
        batch.plan.set(building);
        batch.plan.set(skipped);
        assertTrue(batch.occupied(building, MeshKind.PENDING));
        assertFalse(batch.occupied(building, MeshKind.CHANGED));
        batch.changed.set(building);
        batch.excluded.set(skipped);
        assertFalse(batch.occupied(building, MeshKind.PENDING));
        assertTrue(batch.occupied(building, MeshKind.CHANGED));
        for (var kind : MeshKind.values()) assertFalse(batch.occupied(skipped, kind));
    }
}
