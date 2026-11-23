package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.RegionTime;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Section;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.FINAL_WIRE_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.REGION_FADE_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.WHITE_TRANSITION_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionAnimationTest {
    private static final ConstructionArea AREA = new ConstructionArea(new BlockPos(-1, 32, 2), new BlockPos(0, 63, 2));
    private static final long START = 100;
    private static final int WIRE = ConstructionEffectPayload.wireTicks(2);
    private static final Section SECTION = new Section(0, 1, 0);

    @Test
    void regionProgressChangesOpacityWithoutInvalidatingGeometry() {
        var animation = new ConstructionAnimation(false, START, WIRE, AREA);
        int upperCell = ConstructionAnimation.SLICE_CELLS;
        var cells = bits(0, upperCell, upperCell + 1);
        animation.geometry(geometry(START, cells, bits(0, upperCell), List.of()), 0);
        var lower = slice(animation, 4);
        var upper = slice(animation, 5);
        int lowerVersion = lower.version;
        int upperVersion = upper.version;
        assertTrue(animation.wireOpacity(0) > 0);
        assertEquals(0, animation.shellOpacity(lower.fluid, 0));

        long settle = START + WIRE + WHITE_TRANSITION_TICKS;
        long arrived = settle - START;
        animation.geometry(geometry(settle, null, new BitSet(), List.of(new RegionTime(ConstructionEffectPayload.region(0, true), -1, settle))), arrived);
        assertEquals(lowerVersion, lower.version);
        assertEquals(upperVersion, upper.version);
        assertEquals(0, animation.wireOpacity(arrived));
        assertEquals(1, animation.shellOpacity(lower.fluid, arrived));
        assertTrue(animation.shellOpacity(lower.fluid, arrived + 1) < animation.shellOpacity(upper.solid, arrived + 1));
        assertEquals(0, animation.shellOpacity(lower.fluid, arrived + REGION_FADE_TICKS));
        assertEquals(1, animation.shellOpacity(upper.fluid, arrived + REGION_FADE_TICKS));
        assertEquals(1, animation.shellOpacity(upper.solid, arrived + REGION_FADE_TICKS));
    }

    @Test
    void lateViewerUsesSettledRegionTimeWithoutReplayingTheIntro() {
        var animation = new ConstructionAnimation(false, START, WIRE, AREA);
        long settle = START + WIRE + WHITE_TRANSITION_TICKS;
        long elapsed = REGION_FADE_TICKS / 2;
        long local = 3;
        animation.geometry(geometry(settle + elapsed, bits(0), new BitSet(), List.of(new RegionTime(0, -1, settle))), local);
        assertEquals(0, animation.wireOpacity(local));
        assertEquals(.5, animation.shellOpacity(slice(animation, 4).solid, local));
        assertEquals(0, animation.shellOpacity(slice(animation, 4).solid, local + REGION_FADE_TICKS - elapsed));
    }

    @Test
    void dismantleEndCrossFadesTheSettledWhiteShellWithoutRestartingOnDuplicateEnd() {
        var animation = new ConstructionAnimation(true, START, WIRE, AREA);
        long local = 7;
        animation.geometry(geometry(START, bits(0, 8 * 256), new BitSet(), List.of(new RegionTime(1, START, -1))), local);
        var lower = slice(animation, 4);
        var upper = slice(animation, 5);
        assertEquals(0, animation.shellOpacity(lower.solid, local + WHITE_TRANSITION_TICKS));
        assertEquals(1, animation.shellOpacity(upper.solid, local + WHITE_TRANSITION_TICKS));
        assertTrue(animation.prepareOutline(local));
        assertEquals(0, animation.wireOpacity(local));
        long finished = START + WHITE_TRANSITION_TICKS + 2;
        long arrives = local + (finished - START);
        animation.geometry(geometry(finished, null, new BitSet(), List.of(new RegionTime(1, START, finished))), arrives);
        animation.finish(finished, arrives);
        assertEquals(1, animation.shellOpacity(upper.solid, arrives));
        assertEquals(0, animation.wireOpacity(arrives));
        double shell = animation.shellOpacity(upper.solid, arrives + 1);
        double wire = animation.wireOpacity(arrives + 1);
        assertTrue(shell > 0 && shell < 1);
        assertTrue(wire > 0);
        animation.finish(finished, arrives + 1);
        assertEquals(shell, animation.shellOpacity(upper.solid, arrives + 1));
        assertEquals(wire, animation.wireOpacity(arrives + 1));
        assertTrue(animation.wireOpacity(arrives + WHITE_TRANSITION_TICKS) > animation.wireOpacity(arrives + WHITE_TRANSITION_TICKS + 1));
        assertEquals(0, animation.shellOpacity(upper.solid, arrives + REGION_FADE_TICKS));
        assertFalse(animation.expired(arrives + FINAL_WIRE_TICKS - 1));
        assertTrue(animation.expired(arrives + FINAL_WIRE_TICKS));
        animation.owner = true;
        assertEquals(0, animation.wireOpacity(arrives + FINAL_WIRE_TICKS));
        assertFalse(animation.expired(arrives + FINAL_WIRE_TICKS));
    }

    @Test
    void lateBuildSnapshotAdvancesButCannotRestartTheGlobalIntro() {
        var animation = new ConstructionAnimation(false, START, WIRE, AREA);
        animation.geometry(geometry(START, bits(0), new BitSet(), List.of()), 0);
        assertTrue(animation.wireOpacity(1) > 0);
        long reveal = START + WIRE + WHITE_TRANSITION_TICKS;
        animation.geometry(geometry(reveal, bits(0, 1), new BitSet(), List.of()), 1);
        assertEquals(0, animation.wireOpacity(1));
        assertEquals(1, animation.shellOpacity(slice(animation, 4).solid, 1));
        animation.geometry(geometry(reveal + 1, bits(0, 1), new BitSet(), List.of()), 10);
        assertEquals(0, animation.wireOpacity(10));
    }

    @Test
    void lateDismantleViewerJoinsTheRemainingOutlineFade() {
        var animation = new ConstructionAnimation(true, START, WIRE, AREA);
        long end = START + WHITE_TRANSITION_TICKS;
        long elapsed = FINAL_WIRE_TICKS / 2;
        long local = 1;
        animation.geometry(geometry(end + elapsed, bits(0), new BitSet(), List.of(new RegionTime(0, START, end))), local);
        animation.finish(end, local);
        assertEquals(elapsed, animation.finishAge(local));
        assertTrue(animation.wireOpacity(local) > 0);
        assertTrue(animation.expired(local + FINAL_WIRE_TICKS - elapsed));
    }

    @Test
    void terminalSnapshotCannotRestartACompletedShellFade() {
        var animation = new ConstructionAnimation(true, START, WIRE, AREA);
        long end = START + WHITE_TRANSITION_TICKS;
        animation.finish(end, 0);
        long local = REGION_FADE_TICKS;
        animation.geometry(geometry(end + 1, bits(0), new BitSet(), List.of(new RegionTime(0, START, end))), local);
        animation.finish(end, local);
        assertEquals(0, animation.shellOpacity(slice(animation, 4).solid, local));
        assertEquals(local, animation.finishAge(local));
    }

    @Test
    void changingAnAdjacentSnapshotInvalidatesOnlyItsTouchedBoundaries() {
        var animation = new ConstructionAnimation(false, START, WIRE, AREA);
        var empty = new BitSet();
        animation.apply(-1, 4, 0, bits(ConstructionAnimation.index(15, 5, 2)), empty, empty);
        var west = animation.slices.get(ConstructionAnimation.key(-1, 4, 0));
        assertEquals(0, west.cell(16, 5, 2));
        int initial = west.version;
        animation.apply(0, 4, 0, bits(ConstructionAnimation.index(5, 5, 2)), empty, empty);
        assertEquals(initial, west.version);
        int boundary = ConstructionAnimation.index(0, 5, 2);
        animation.apply(0, 4, 0, bits(boundary), empty, empty);
        assertEquals(ConstructionSurface.SOLID, west.cell(16, 5, 2));
        assertTrue(west.version > initial);
        int connected = west.version;
        animation.apply(0, 4, 0, null, null, bits(boundary));
        assertEquals(0, west.cell(16, 5, 2));
        assertTrue(west.version > connected);
    }

    @Test
    void nativeChunkUnloadKeepsTheAdjacentChunkAndReturningGeometryContinuesTheFade() {
        var animation = new ConstructionAnimation(false, START, WIRE, AREA);
        int westEdge = ConstructionAnimation.index(15, 0, 2);
        int eastEdge = ConstructionAnimation.index(0, 0, 2);
        animation.apply(-1, 4, 0, bits(westEdge), new BitSet(), new BitSet());
        var west = animation.slices.get(ConstructionAnimation.key(-1, 4, 0));
        var east = bits(eastEdge, ConstructionAnimation.SLICE_CELLS + eastEdge);
        animation.geometry(geometry(START, east, new BitSet(), List.of()), 0);
        assertEquals(ConstructionSurface.SOLID, west.cell(16, 0, 2));

        animation.forgetChunk(0, 0);
        assertEquals(1, animation.slices.size());
        assertEquals(0, west.cell(16, 0, 2));

        long settled = START + WIRE + WHITE_TRANSITION_TICKS;
        long elapsed = REGION_FADE_TICKS / 2;
        long local = settled - START + elapsed;
        animation.geometry(geometry(settled + elapsed, east, new BitSet(), List.of(new RegionTime(0, -1, settled))), local);
        assertEquals(ConstructionSurface.SOLID, west.cell(16, 0, 2));
        assertEquals(.5, animation.shellOpacity(slice(animation, 4).solid, local));
        assertEquals(1, animation.shellOpacity(slice(animation, 5).solid, local));
    }

    private static ConstructionAnimation.Slice slice(ConstructionAnimation animation, int y) {
        return animation.slices.get(ConstructionAnimation.key(0, y, 0));
    }

    private static Geometry geometry(long serverTick, BitSet cells, BitSet fluids, List<RegionTime> times) {
        return new Geometry(0, 1, serverTick, SECTION, 0, cells != null, null,
            cells == null ? new BitSet() : cells, fluids, new BitSet(), times);
    }

    private static BitSet bits(int... indices) {
        var result = new BitSet();
        for (int index : indices) result.set(index);
        return result;
    }
}
