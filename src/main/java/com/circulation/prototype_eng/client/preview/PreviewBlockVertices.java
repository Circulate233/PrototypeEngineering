package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteOrder;

/**
 * Native BLOCK layout: position 0, RGBA 12, atlas UV 16, lightmap UV 24; seven ints per vertex.
 */
final class PreviewBlockVertices {
    static final int STRIDE = 28;
    static final int FULLBRIGHT = 0x00F000F0;
    static final float ALPHA = 0.65f;

    private PreviewBlockVertices() {
    }

    /**
     * Appends one BLOCK quad to a begun BLOCK builder. Null translation retains native attributes.
     */
    static void append(BufferBuilder buffer, int[] source, int offset, int tint, ByteOrder order,
                       @Nullable BlockPos translation) {
        if (translation == null) {
            append(buffer, source, offset, 28);
            return;
        }
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int vertex = 0; vertex < 4; vertex++) {
            var target = buffer.getByteBuffer();
            int from = offset + vertex * 7, to = buffer.getVertexCount() * STRIDE;
            target.putInt(to, translate(source[from], translation.getX()));
            target.putInt(to + 4, translate(source[from + 1], translation.getY()));
            target.putInt(to + 8, translate(source[from + 2], translation.getZ()));
            target.putInt(to + 12, color(source[from + 3], tint, little));
            target.putInt(to + 16, source[from + 4]);
            target.putInt(to + 20, source[from + 5]);
            target.putInt(to + 24, FULLBRIGHT);
            buffer.endVertex();
        }
    }

    static void append(IntArrayList target, int[] source, int offset, int tint, ByteOrder order,
                       @Nullable BlockPos translation) {
        if (translation == null) {
            target.addElements(target.size(), source, offset, 28);
            return;
        }
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int vertex = 0; vertex < 4; vertex++) {
            int from = offset + vertex * 7;
            target.add(translate(source[from], translation.getX()));
            target.add(translate(source[from + 1], translation.getY()));
            target.add(translate(source[from + 2], translation.getZ()));
            target.add(color(source[from + 3], tint, little));
            target.add(source[from + 4]);
            target.add(source[from + 5]);
            target.add(FULLBRIGHT);
        }
    }

    static void append(BufferBuilder buffer, int[] source, int offset, int length) {
        for (int vertex = 0; vertex < length; vertex += 7) {
            var target = buffer.getByteBuffer();
            int start = buffer.getVertexCount() * STRIDE;
            for (int field = 0; field < 7; field++) target.putInt(start + field * 4, source[offset + vertex + field]);
            buffer.endVertex();
        }
    }

    private static int translate(int packed, int offset) {
        return Float.floatToRawIntBits(Float.intBitsToFloat(packed) + offset);
    }

    private static int color(int packed, int tint, boolean little) {
        int r = little ? packed & 255 : packed >>> 24;
        int g = little ? (packed >>> 8) & 255 : (packed >>> 16) & 255;
        int b = little ? (packed >>> 16) & 255 : (packed >>> 8) & 255;
        int a = little ? packed >>> 24 : packed & 255;
        if (tint != -1) {
            r = r * ((tint >>> 16) & 255) / 255;
            g = g * ((tint >>> 8) & 255) / 255;
            b = b * (tint & 255) / 255;
        }
        a = (int) (a * ALPHA);
        return little ? r | (g << 8) | (b << 16) | (a << 24) : (r << 24) | (g << 16) | (b << 8) | a;
    }
}
