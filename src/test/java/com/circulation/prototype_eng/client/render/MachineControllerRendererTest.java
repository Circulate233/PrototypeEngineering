package com.circulation.prototype_eng.client.render;

import ae2.api.orientation.BlockOrientation;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class MachineControllerRendererTest {
    private static final class IndependentTile extends AEBaseTile implements MachineControllerHost {
        @Override
        public AEBaseTile blockEntity() {
            return this;
        }

        @Override
        public MachineType type() {
            throw new UnsupportedOperationException("No machine declaration is needed by a native global query");
        }

        @Override
        public @Nullable MachineInstance machine() {
            return null;
        }

        @Override
        public BlockOrientation orientation() {
            return BlockOrientation.NORTH_UP;
        }

        @Override
        public boolean formed() {
            return false;
        }

        @Override
        public void readGuiAtBoundary(Runnable read) {
            read.run();
        }

        @Override
        public void transformOrientation(UnaryOperator<BlockOrientation> transformation) {
        }

        @Override
        public void placedBy(EntityLivingBase placer) {
        }

        @Override
        public void controllerBroken() {
        }
    }

    private static final class Renderer extends MachineControllerRenderer<IndependentTile> {
        IndependentTile captured;
        AxisAlignedBB extents = new AxisAlignedBB(-400, -2, -2, 400, 2, 2);

        Renderer() {
            super(IndependentTile.class);
        }

        @Override
        protected ClientMachineRenderSnapshots.@Nullable View view(IndependentTile tile) {
            captured = tile;
            return null;
        }

        @Override
        public AxisAlignedBB bounds(IndependentTile tile) {
            return extents;
        }
    }

    @Test
    void independentTypedBoundsHookExtendsTheNativeDistanceAndFiniteGlobalHintAvoidsNaNCorners() {
        var renderer = new Renderer();
        var tile = new IndependentTile();
        assertTrue(renderer.maximumDistanceSquared(tile) > 400 * 400);
        renderer.extents = MachineControllerRenderer.CONSERVATIVE_BOUNDS;
        assertEquals(Double.MAX_VALUE, renderer.maximumDistanceSquared(tile));
        var bounds = renderer.bounds(tile);
        assertTrue(Double.isFinite(bounds.minX));
        assertTrue(Double.isFinite(bounds.maxX));
        // A zero plane coefficient remains zero for this finite native hint, unlike infinite corners.
        assertEquals(0, 0 * bounds.minY, 0);
        assertTrue(bounds.minX < 0 && bounds.maxX > 0);
    }

    @Test
    void sharedCameraOriginUsesNegativeSafeGridAndRejectsInvalidOrOverflowingCoordinates() {
        assertEquals(new BlockPos(-256, 0, 256), MachineModelRenderState.worldOrigin(-.1, 255.9, 511.99, 8));
        assertEquals(new BlockPos(-512, 0, 512), MachineModelRenderState.worldOrigin(-.1, 255.9, 512, 16));
        assertEquals(BlockPos.ORIGIN, MachineModelRenderState.worldOrigin(1, 127, 255, 0));
        assertThrows(IllegalArgumentException.class, () -> MachineModelRenderState.worldOrigin(Double.NaN, 0, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> MachineModelRenderState.worldOrigin(0x1p63, 0, 0, 8));
        assertThrows(ArithmeticException.class, () -> MachineModelRenderState.worldOrigin(Integer.MAX_VALUE + 256.0, 0, 0, 8));
        assertThrows(ArithmeticException.class, () -> MachineModelRenderState.worldOrigin(-1, 0, 0, Integer.MAX_VALUE));
    }

    @Test
    void typedExtensionUsesItsIndependentClassAndNativeWorkerQueryNeverEntersTheOwnerViewHook() throws Exception {
        var renderer = new Renderer();
        var tile = new IndependentTile();
        assertSame(IndependentTile.class, renderer.tileClass());
        assertNull(renderer.view(tile));
        assertSame(tile, renderer.captured);
        renderer.captured = null;
        var result = new CompletableFuture<Boolean>();
        var worker = Thread.ofPlatform().start(() -> result.complete(renderer.isGlobalRenderer(tile)));
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertTrue(result.get());
        assertNull(renderer.captured);
    }
}
