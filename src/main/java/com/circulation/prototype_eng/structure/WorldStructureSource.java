package com.circulation.prototype_eng.structure;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureReadWindow;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.ChunkProviderServer;
import org.jetbrains.annotations.Nullable;


/**
 * Real world adapter; only this capture phase calls world/TE/capability methods.
 */
@SuppressWarnings("unused") // Public adapter for structure hosts; actual controller scheduling is separate.
public final class WorldStructureSource implements StructureReadSource {
    private final MachineLifecycleHost host;

    public WorldStructureSource(MachineLifecycleHost host) {
        this.host = host;
    }

    @Override
    public void requireServerThread() {
        host.requireServerThread();
    }

    @Override
    public boolean isAreaLoaded(StructureBounds bounds) {
        requireServerThread();
        return host.world().isAreaLoaded(bounds.min(), bounds.max(), false);
    }

    @Override
    public <T> T read(StructureQuery<T> query, BlockPos position) {
        requireServerThread();
        return query.capture(host.world(), position);
    }

    /**
     * Explicit native adapter selection; never used to infer an unknown source or host override.
     * Provider lookup stays on the owner and neither loads chunks nor changes unloadQueued.
     * The resulting block callback is consumed only while that owner physically joins its worker.
     */
    @Nullable
    public <T> StructureReadWindow<T> beginNativeWorkerRead(StructureQuery<T> query, BlockPos position) {
        requireServerThread();
        var immutable = position.toImmutable();
        var provider = host.world().getChunkProvider();
        if (!query.supportsWorkerBlocks() || !(provider instanceof ChunkProviderServer chunks))
            return StructureReadSource.super.beginWorkerRead(query, immutable);
        if (immutable.getY() < 0 || immutable.getY() >= 256) return null;
        var chunk = chunks.loadedChunks.get(ChunkPos.asLong(immutable.getX() >> 4, immutable.getZ() >> 4));
        if (chunk == null || !chunk.isLoaded()) return null;
        return query.beginWorkerCapture(chunk::getBlockState, immutable);
    }

    /**
     * Explicit native candidate preparation visits the original World's loaded column query.
     */
    @SuppressWarnings("ReturnOfInnerClass") // The retained cursor owns only native owner-side column preparation.
    public StructureOperation<Boolean> beginNativeAreaRead(StructureBounds bounds) {
        requireServerThread();
        return new StructureOperation<>() {
            private int x = bounds.min().getX() >> 4;
            private int z = bounds.min().getZ() >> 4;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                requireServerThread();
                if (complete()) return true;
                if (bounds.max().getY() < 0 || bounds.min().getY() >= 256) {
                    if (!work.take()) return false;
                    finish(false);
                    return true;
                }
                while (x <= bounds.max().getX() >> 4) {
                    if (!work.take()) return false;
                    if (!host.world().isAreaLoaded(new BlockPos(x << 4, bounds.min().getY(), z << 4),
                        new BlockPos(x << 4, bounds.max().getY(), z << 4), false)) {
                        finish(false);
                        return true;
                    }
                    if (z == bounds.max().getZ() >> 4) {
                        z = bounds.min().getZ() >> 4;
                        x++;
                    } else z++;
                }
                finish(true);
                return true;
            }
        };
    }
}
