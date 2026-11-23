package com.circulation.prototype_eng.client.render.construction;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import static com.circulation.prototype_eng.client.render.construction.ConstructionMeshes.STRIDE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionMeshesTest {
    private static ByteBuffer vertices(float... xValues) {
        var buffer = ByteBuffer.allocate(xValues.length * STRIDE).order(ByteOrder.nativeOrder());
        for (float x : xValues) buffer.putFloat(x).putFloat(x + 1).putFloat(x - 1);
        return buffer.flip();
    }

    private static void assertVertices(ConstructionMeshes.Mesh mesh, float[] xValues) {
        var stored = Objects.requireNonNull(mesh.page().cpu);
        assertEquals(xValues.length, mesh.count());
        for (int i = 0; i < xValues.length; i++) {
            int offset = (mesh.first() + i) * STRIDE;
            assertEquals(xValues[i], stored.getFloat(offset));
            assertEquals(xValues[i] + 1, stored.getFloat(offset + Float.BYTES));
            assertEquals(xValues[i] - 1, stored.getFloat(offset + 2 * Float.BYTES));
        }
    }

    @Test
    void replacingAdjacentSectionsReusesTheirRangeAndPreservesOtherGeometry() {
        var arena = new ConstructionMeshes.Arena(BlockPos.ORIGIN);
        var first = arena.store(vertices(0, 1, 2, 3), false, true);
        var second = arena.store(vertices(4, 5, 6, 7), false, true);
        var otherVertices = new float[]{8, 9, 10, 11};
        var other = arena.store(vertices(otherVertices), false, true);
        assertSame(first.page(), other.page());

        second.delete();
        first.delete();
        var replacementVertices = new float[]{12, 13, 14, 15, 16, 17, 18, 19};
        var replacement = arena.store(vertices(replacementVertices), false, true);
        assertSame(first.page(), replacement.page());
        assertEquals(first.first(), replacement.first());
        assertVertices(other, otherVertices);
        assertVertices(replacement, replacementVertices);

        replacement.delete();
        other.delete();
        assertTrue(arena.pages.isEmpty());
    }
}
