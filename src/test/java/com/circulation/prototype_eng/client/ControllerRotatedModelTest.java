package com.circulation.prototype_eng.client;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.item.OrientationToolOrientations;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControllerRotatedModelTest {
    @Test void allNativeOrientationsMapToTheOriginalCanonicalTopAndTurnOrder() {
        for (var front : EnumFacing.VALUES) {
            var orientation = OrientationToolOrientations.canonical(front);
            for (int twist = 0; twist < 4; twist++) {
                assertEquals(twist, MachineControllerModels.originalTwist(orientation), orientation.toString());
                orientation = orientation.rotateClockwiseAround(front);
            }
        }
    }

    @Test void horizontalTwistsRotateBothGeometryAndLightingFacesWithoutChangingTextureCoordinates() {
        int[] vertices = new int[28];
        for (int i = 0; i < 4; i++) {
            vertices[i * 7] = Float.floatToRawIntBits(.5f);
            vertices[i * 7 + 1] = Float.floatToRawIntBits(1f);
            vertices[i * 7 + 2] = Float.floatToRawIntBits(.5f);
            vertices[i * 7 + 4] = Float.floatToRawIntBits(.25f);
        }
        var sprite = new TextureAtlasSprite("parity_test") {};
        var source = new BakedQuad(vertices, 0, EnumFacing.UP, sprite, true, DefaultVertexFormats.ITEM);
        var base = new IBakedModel() {
            @Override public @NotNull List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) { return side == EnumFacing.UP ? List.of(source) : List.of(); }
            @Override public boolean isAmbientOcclusion() { return true; }
            @Override public boolean isGui3d() { return true; }
            @Override public boolean isBuiltInRenderer() { return false; }
            @Override public @NotNull TextureAtlasSprite getParticleTexture() { return sprite; }
            @Override public @NotNull ItemOverrideList getOverrides() { return ItemOverrideList.NONE; }
        };
        for (var orientation : BlockOrientation.values()) {
            var front = orientation.getSide(RelativeSide.FRONT);
            if (front.getAxis().isVertical()) continue;
            var top = orientation.getSide(RelativeSide.TOP);
            var rotated = new ControllerRotatedModel(base, front, MachineControllerModels.originalTwist(orientation));
            var quad = rotated.getQuads(null, top, 0).getFirst();
            assertEquals(top, quad.getFace());
            int[] data = quad.getVertexData();
            assertEquals(.5f + top.getXOffset() * .5f, Float.intBitsToFloat(data[0]), 1e-6);
            assertEquals(.5f + top.getYOffset() * .5f, Float.intBitsToFloat(data[1]), 1e-6);
            assertEquals(.5f + top.getZOffset() * .5f, Float.intBitsToFloat(data[2]), 1e-6);
            assertEquals(vertices[4], data[4]);
            assertEquals(1f, Float.intBitsToFloat(vertices[1]), "The shared source model remains unchanged");
        }
    }
}
