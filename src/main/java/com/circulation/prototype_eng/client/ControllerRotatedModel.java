package com.circulation.prototype_eng.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.vecmath.AxisAngle4f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;
import java.util.List;

/** PM RotatedBakedModel: precomputed quarter turns around the already facing-rotated model's front. */
final class ControllerRotatedModel implements IBakedModel {
    private final IBakedModel base;
    private final ObjectArrayList<ObjectList<BakedQuad>> quads = new ObjectArrayList<>(7);

    ControllerRotatedModel(IBakedModel base, EnumFacing facing, int twist) {
        this.base = base;
        var transform = new Matrix4f();
        transform.setIdentity();
        transform.setTranslation(new Vector3f(.5f, .5f, .5f));
        var rotation = new Matrix4f();
        rotation.setIdentity();
        rotation.setRotation(new AxisAngle4f(facing.getXOffset(), facing.getYOffset(), facing.getZOffset(), (float) Math.toRadians(twist * 90)));
        var offset = new Matrix4f();
        offset.setIdentity();
        offset.setTranslation(new Vector3f(-.5f, -.5f, -.5f));
        transform.mul(rotation);
        transform.mul(offset);
        for (int i = 0; i < 7; i++) quads.add(new ObjectArrayList<>());
        for (int source = 0; source < 7; source++) {
            for (var quad : base.getQuads(null, source == 6 ? null : EnumFacing.VALUES[source], 0)) {
                int[] data = quad.getVertexData().clone();
                for (int vertex = 0; vertex < 4; vertex++) {
                    int start = vertex * 7;
                    var position = new Vector4f(Float.intBitsToFloat(data[start]), Float.intBitsToFloat(data[start + 1]), Float.intBitsToFloat(data[start + 2]), 1);
                    transform.transform(position);
                    data[start] = Float.floatToRawIntBits(position.x);
                    data[start + 1] = Float.floatToRawIntBits(position.y);
                    data[start + 2] = Float.floatToRawIntBits(position.z);
                }
                var oldFace = quad.getFace();
                var direction = new Vector3f(oldFace.getXOffset(), oldFace.getYOffset(), oldFace.getZOffset());
                rotation.transform(direction);
                direction.normalize();
                var face = EnumFacing.getFacingFromVector(direction.x, direction.y, direction.z);
                quads.get(face.ordinal()).add(new BakedQuad(data, quad.getTintIndex(), face, quad.getSprite(), true, DefaultVertexFormats.ITEM));
            }
        }
        for (int i = 0; i < 7; i++) quads.set(i, ObjectLists.unmodifiable(quads.get(i)));
    }

    @Override public @NotNull List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) { return quads.get(side == null ? 6 : side.ordinal()); }
    @Override public boolean isAmbientOcclusion() { return base.isAmbientOcclusion(); }
    @Override public boolean isGui3d() { return base.isGui3d(); }
    @Override public boolean isBuiltInRenderer() { return base.isBuiltInRenderer(); }
    @Override public @NotNull TextureAtlasSprite getParticleTexture() { return base.getParticleTexture(); }
    @Override public @NotNull ItemOverrideList getOverrides() { return base.getOverrides(); }
}
