package com.circulation.prototype_eng.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.LightUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PM RotatedBakedModel: precomputed quarter turns around the already facing-rotated model's front.
 */
final class ControllerRotatedModel implements IBakedModel {
    private final IBakedModel base;
    private final ObjectArrayList<ObjectList<BakedQuad>> quads = new ObjectArrayList<>(7);

    ControllerRotatedModel(IBakedModel base, EnumFacing facing, int twist) {
        this.base = base;
        for (int i = 0; i < 7; i++) quads.add(new ObjectArrayList<>());
        for (int source = 0; source < 7; source++) {
            for (var quad : base.getQuads(null, source == 6 ? null : EnumFacing.VALUES[source], 0)) {
                int[] data = quad.getVertexData().clone();
                var format = quad.getFormat();
                float[] values = new float[4];
                for (int element = 0; element < format.getElementCount(); element++) {
                    var usage = format.getElement(element).getUsage();
                    if (usage != VertexFormatElement.EnumUsage.POSITION && usage != VertexFormatElement.EnumUsage.NORMAL)
                        continue;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        LightUtil.unpack(data, values, format, vertex, element);
                        turn(values, facing, twist, usage == VertexFormatElement.EnumUsage.POSITION ? .5f : 0);
                        LightUtil.pack(values, data, format, vertex, element);
                    }
                }
                var face = turn(quad.getFace(), facing, twist);
                int target = source == 6 ? 6 : turn(EnumFacing.VALUES[source], facing, twist).ordinal();
                quads.get(target).add(new BakedQuad(data, quad.getTintIndex(), face, quad.getSprite(), quad.shouldApplyDiffuseLighting(), format));
            }
        }
        for (int i = 0; i < 7; i++) quads.set(i, ObjectLists.unmodifiable(quads.get(i)));
    }

    /**
     * Exact quarter turns avoid floating-point rotation drift at block boundaries.
     */
    private static void turn(float[] vector, EnumFacing axis, int twist, float center) {
        float x = vector[0] - center, y = vector[1] - center, z = vector[2] - center;
        int ax = axis.getXOffset(), ay = axis.getYOffset(), az = axis.getZOffset();
        for (int i = 0; i < Math.floorMod(twist, 4); i++) {
            float dot = ax * x + ay * y + az * z;
            float nx = ax * dot + ay * z - az * y;
            float ny = ay * dot + az * x - ax * z;
            z = az * dot + ax * y - ay * x;
            x = nx;
            y = ny;
        }
        vector[0] = x + center;
        vector[1] = y + center;
        vector[2] = z + center;
    }

    private static EnumFacing turn(EnumFacing side, EnumFacing axis, int twist) {
        float[] vector = {side.getXOffset(), side.getYOffset(), side.getZOffset()};
        turn(vector, axis, twist, 0);
        return EnumFacing.getFacingFromVector(vector[0], vector[1], vector[2]);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
        return quads.get(side == null ? 6 : side.ordinal());
    }

    @Override
    public boolean isAmbientOcclusion() {
        return base.isAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return base.isGui3d();
    }

    @Override
    public boolean isBuiltInRenderer() {
        return base.isBuiltInRenderer();
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleTexture() {
        return base.getParticleTexture();
    }

    @Override
    public @NotNull ItemOverrideList getOverrides() {
        return base.getOverrides();
    }
}
