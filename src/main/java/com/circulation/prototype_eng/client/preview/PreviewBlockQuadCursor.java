package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * One published cell's actual state/layer/getQuads/quad work; shared by VBO and filtered CPU paths.
 */
final class PreviewBlockQuadCursor implements PreviewSampleBlockModels.Preparation {
    private static final BlockRenderLayer[] LAYERS = {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED, BlockRenderLayer.CUTOUT, BlockRenderLayer.TRANSLUCENT};
    private final Thread owner = Thread.currentThread();
    private final StructurePreviewModel model;
    private final StructurePreviewModel.Cell cell;
    private final BooleanSupplier current;
    private final long seed;
    private final PreviewBlockAccess access;
    private IBlockState state;
    private IBakedModel baked;
    private List<BakedQuad> quads;
    private int layer, face, quad;
    private boolean initialized, complete;
    private int[] nativeVertices;
    private int nativeOffset;

    PreviewBlockQuadCursor(StructurePreviewModel model, StructurePreviewModel.Cell cell, BooleanSupplier current, PreviewBlockAccess access) {
        this.model = model;
        this.cell = cell;
        this.current = current;
        this.access = access;
        seed = MathHelper.getPositionRandom(cell.position());
    }

    /**
     * Each call consumes exactly one library work visit, including empty/unsupported face/layer results.
     */
    @Override
    public boolean step(QuadSink sink) {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Preview quad cursor requires its client owner thread");
        if (!current.getAsBoolean() || complete) return complete;
        if (!initialized) {
            initialized = true;
            state = access == null ? PreviewBlockStates.first(model, cell) : access.represented(cell.position());
            if (state != null && access != null) state = state.getActualState(access, cell.position());
            complete = state == null || access != null && !access.visible(cell.position());
            return complete;
        }
        if (layer == LAYERS.length) {
            complete = true;
            baked = null;
            quads = null;
            state = null;
            return true;
        }
        var previous = MinecraftForgeClient.getRenderLayer();
        try {
            ForgeHooksClient.setRenderLayer(LAYERS[layer]);
            if (access != null) {
                if (nativeVertices == null) {
                    if (!state.getBlock().canRenderInLayer(state, LAYERS[layer])) {
                        layer++;
                        return false;
                    }
                    nativeVertices = access.renderLayer(state, cell.position());
                    nativeOffset = 0;
                    return false;
                }
                if (nativeOffset == nativeVertices.length) {
                    nativeVertices = null;
                    layer++;
                    return false;
                }
                if (!current.getAsBoolean()) return false;
                sink.put(layer, nativeVertices, nativeOffset, -1, null);
                nativeOffset += 28;
                return false;
            }
            if (baked == null) {
                boolean supported = state.getBlock().canRenderInLayer(state, LAYERS[layer]);
                if (!current.getAsBoolean()) return false;
                if (!supported) {
                    layer++;
                    return false;
                }
                baked = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(state);
                return false;
            }
            if (quads == null) {
                if (face == 7) {
                    layer++;
                    face = 0;
                    baked = null;
                    return false;
                }
                var direction = face == 0 ? null : EnumFacing.VALUES[face - 1];
                if (direction != null && access != null && access.visible(cell.position().offset(direction))
                    && !state.shouldSideBeRendered(access, cell.position(), direction)) {
                    face++;
                    return false;
                }
                var captured = baked.getQuads(state, direction, direction == null ? seed : seed + direction.getIndex() * 1315423911L);
                if (!current.getAsBoolean()) return false;
                face++;
                quad = 0;
                quads = captured.isEmpty() ? null : captured;
                return false;
            }
            var source = quads.get(quad++);
            int tint = source.hasTintIndex() ? Minecraft.getMinecraft().getBlockColors().colorMultiplier(state, access, access == null ? null : cell.position(), source.getTintIndex()) : -1;
            if (!current.getAsBoolean()) return false;
            sink.put(layer, source.getVertexData(), 0, tint, cell.position());
            if (quad == quads.size()) quads = null;
            return false;
        } finally {
            ForgeHooksClient.setRenderLayer(previous);
        }
    }

    /**
     * Consumes one four-vertex BLOCK span immediately; source arrays are borrowed read-only.
     */
    @FunctionalInterface
    interface QuadSink {
        void put(int layer, int[] vertices, int offset, int tint, @Nullable BlockPos translation);
    }
}
