package com.circulation.prototype_eng.client.render;

import ae2.api.orientation.BlockOrientation;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineControllerRendererTest {
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

        Renderer() {
            super(IndependentTile.class);
        }

        @Override
        protected ClientMachineRenderSnapshots.@Nullable View view(IndependentTile tile) {
            captured = tile;
            return null;
        }

    }
}
