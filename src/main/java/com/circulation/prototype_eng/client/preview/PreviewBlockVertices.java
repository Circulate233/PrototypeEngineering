package com.circulation.prototype_eng.client.preview;

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
     * Preserves source alpha and multiplies tint; UVs/position untouched, lightmap forced fullbright.
     */
    static int[] prepare(int[] source, int tint, ByteOrder order) {
        if (source.length != 28) throw new IllegalArgumentException("Expected four BLOCK vertices in baked quad");
        var result = source.clone();
        boolean little = order == ByteOrder.LITTLE_ENDIAN;
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * 7 + 3, packed = result[index];
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
            result[index] = little ? r | (g << 8) | (b << 16) | (a << 24) : (r << 24) | (g << 16) | (b << 8) | a;
            result[vertex * 7 + 6] = FULLBRIGHT;
        }
        return result;
    }

}
