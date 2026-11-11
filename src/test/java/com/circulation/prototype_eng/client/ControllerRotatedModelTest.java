package com.circulation.prototype_eng.client;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumFacing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerRotatedModelTest {
    private static IBakedModel model(BakedQuad quad, @Nullable EnumFacing cullSide) {
        return new IBakedModel() {
            @Override
            public @NotNull List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
                return side == cullSide ? List.of(quad) : List.of();
            }

            @Override
            public boolean isAmbientOcclusion() {
                return true;
            }

            @Override
            public boolean isGui3d() {
                return true;
            }

            @Override
            public boolean isBuiltInRenderer() {
                return false;
            }

            @Override
            public @NotNull TextureAtlasSprite getParticleTexture() {
                return quad.getSprite();
            }

            @Override
            public @NotNull ItemOverrideList getOverrides() {
                return ItemOverrideList.NONE;
            }
        };
    }

    @Test
    void horizontalTwistsRotateBothGeometryAndLightingFacesWithoutChangingTextureCoordinates() {
        int[] vertices = new int[28];
        for (int i = 0; i < 4; i++) {
            vertices[i * 7] = Float.floatToRawIntBits(.5f);
            vertices[i * 7 + 1] = Float.floatToRawIntBits(1f);
            vertices[i * 7 + 2] = Float.floatToRawIntBits(.5f);
            vertices[i * 7 + 4] = Float.floatToRawIntBits(.25f);
            vertices[i * 7 + 6] = 127 << 8;
        }
        var sprite = new TextureAtlasSprite("parity_test") {
        };
        var source = new BakedQuad(vertices, 3, EnumFacing.UP, sprite, false, DefaultVertexFormats.ITEM);
        var base = new IBakedModel() {
            @Override
            public @NotNull List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
                return side == EnumFacing.UP ? List.of(source) : List.of();
            }

            @Override
            public boolean isAmbientOcclusion() {
                return true;
            }

            @Override
            public boolean isGui3d() {
                return true;
            }

            @Override
            public boolean isBuiltInRenderer() {
                return false;
            }

            @Override
            public @NotNull TextureAtlasSprite getParticleTexture() {
                return sprite;
            }

            @Override
            public @NotNull ItemOverrideList getOverrides() {
                return ItemOverrideList.NONE;
            }
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
            assertEquals(top.getXOffset() * 127, (byte) data[6]);
            assertEquals(top.getYOffset() * 127, (byte) (data[6] >> 8));
            assertEquals(top.getZOffset() * 127, (byte) (data[6] >> 16));
            assertFalse(quad.shouldApplyDiffuseLighting());
            assertSame(source.getFormat(), quad.getFormat());
            assertEquals(3, quad.getTintIndex());
            assertEquals(1f, Float.intBitsToFloat(vertices[1]), "The shared source model remains unchanged");
        }
    }

    @Test
    void unculledQuadsStayUnculledAndCullSideIsIndependentOfLightingFace() {
        var sprite = new TextureAtlasSprite("cull_test") {
        };
        var source = new BakedQuad(new int[28], -1, EnumFacing.UP, sprite, true, DefaultVertexFormats.ITEM);
        var unculled = new ControllerRotatedModel(model(source, null), EnumFacing.NORTH, 1);
        assertEquals(EnumFacing.EAST, unculled.getQuads(null, null, 0).getFirst().getFace());
        for (var side : EnumFacing.VALUES) assertTrue(unculled.getQuads(null, side, 0).isEmpty());
        var culled = new ControllerRotatedModel(model(source, EnumFacing.NORTH), EnumFacing.NORTH, 1);
        assertEquals(EnumFacing.EAST, culled.getQuads(null, EnumFacing.NORTH, 0).getFirst().getFace());
        assertTrue(culled.getQuads(null, EnumFacing.EAST, 0).isEmpty());
        assertTrue(culled.getQuads(null, null, 0).isEmpty());
    }

    @Test
    void extendedVertexStrideAndLightmapArePreservedWithoutInventingNormals() {
        var format = new VertexFormat(DefaultVertexFormats.BLOCK).addElement(DefaultVertexFormats.NORMAL_3B).addElement(DefaultVertexFormats.PADDING_1B);
        int stride = format.getIntegerSize();
        int[] vertices = new int[stride * 4];
        for (int i = 0; i < 4; i++) {
            vertices[i * stride] = Float.floatToRawIntBits(.5f);
            vertices[i * stride + 1] = Float.floatToRawIntBits(1);
            vertices[i * stride + 2] = Float.floatToRawIntBits(.5f);
            vertices[i * stride + 3] = 0xffaabbcc;
            vertices[i * stride + 4] = Float.floatToRawIntBits(.25f);
            vertices[i * stride + 5] = Float.floatToRawIntBits(.75f);
            vertices[i * stride + 6] = 0x00ab00cd;
            vertices[i * stride + 7] = 127 << 8;
        }
        int[] original = vertices.clone();
        var sprite = new TextureAtlasSprite("format_test") {
        };
        var source = new BakedQuad(vertices, -1, EnumFacing.UP, sprite, false, format);
        var rotated = new ControllerRotatedModel(model(source, EnumFacing.UP), EnumFacing.NORTH, 1);
        var result = rotated.getQuads(null, EnumFacing.EAST, 0).getFirst();
        assertSame(format, result.getFormat());
        assertFalse(result.shouldApplyDiffuseLighting());
        int[] data = result.getVertexData();
        for (int i = 0; i < 4; i++) {
            assertEquals(1f, Float.intBitsToFloat(data[i * stride]));
            assertEquals(.5f, Float.intBitsToFloat(data[i * stride + 1]));
            for (int element = 3; element <= 6; element++)
                assertEquals(original[i * stride + element], data[i * stride + element]);
            assertEquals(127, data[i * stride + 7]);
        }
        assertArrayEquals(original, source.getVertexData());
    }

}
