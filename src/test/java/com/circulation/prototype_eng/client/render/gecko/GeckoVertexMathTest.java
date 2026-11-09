package com.circulation.prototype_eng.client.render.gecko;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.circulation.prototype_eng.client.render.BModelFixtures.bone;
import static com.circulation.prototype_eng.client.render.BModelFixtures.cube;
import static com.circulation.prototype_eng.client.render.BModelFixtures.model;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeckoVertexMathTest {
    private static GeckoCubeQuadVertexCache.CubeData positions(int count) {
        float[] xs = new float[count], ys = new float[count], zs = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = i % 3 == 0 ? -0f : (i - 13) * .125f;
            ys[i] = i % 5 == 0 ? 0f : (11 - i) * .0625f;
            zs[i] = (i % 7 - 3) * .25f;
        }
        var model = new Matrix4f();
        model.setIdentity();
        var normal = new Matrix3f();
        normal.setIdentity();
        return new GeckoCubeQuadVertexCache.CubeData(xs, ys, zs, new int[count * 2], model, normal);
    }

    private static int[] referencePositions(GeckoCubeQuadVertexCache.CubeData cache, Matrix4f matrix,
                                            int offset, int count, int[] initial, int outOffset) {
        int[] expected = initial.clone();
        for (int i = 0; i < count; i++) {
            var position = new Point3f(cache.xs()[offset + i], cache.ys()[offset + i], cache.zs()[offset + i]);
            matrix.transform(position);
            expected[outOffset + i * 7] = Float.floatToRawIntBits(position.x);
            expected[outOffset + i * 7 + 1] = Float.floatToRawIntBits(position.y);
            expected[outOffset + i * 7 + 2] = Float.floatToRawIntBits(position.z);
        }
        return expected;
    }

    @Test
    void scalarVectorAndSelectedMatchVecmathCoordinatesWithOffsetsTailAndUntouchedAttributes() {
        assertTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "Enabled test JVM resolves the module");
        var affine = new Matrix4f(new float[]{
            1.03125f, .125f, -.0625f, 12.5f,
            -.25f, .9375f, .03125f, -7.75f,
            .0625f, -.125f, 1.015625f, 3.125f,
            0, 0, 0, 1
        });
        var reflected = new Matrix4f();
        reflected.rotY(.37f);
        reflected.m00 = -reflected.m00;
        reflected.m20 = -reflected.m20;
        var identity = new Matrix4f();
        identity.setIdentity();
        for (int count : new int[]{0, 4, 8, 12, 16, 20, 24, 28, 36}) {
            var cache = positions(count + 6);
            float[] xs = cache.xs().clone(), ys = cache.ys().clone(), zs = cache.zs().clone();
            for (var matrix : List.of(identity, affine, reflected)) {
              for (int[] offsets : new int[][]{{0, 0}, {2, 5}}) {
                int[] initial = new int[count * 7 + 10];
                Arrays.fill(initial, 0x5a5a5a5a);
                int[] expected = referencePositions(cache, matrix, offsets[0], count, initial, offsets[1]);
                int[] scalar = initial.clone(), vector = initial.clone(), selected = initial.clone();
                GeckoBakerMath.packPositionsScalar(cache, matrix, offsets[0], count, scalar, offsets[1]);
                GeckoBakerMath.packPositionsVector(cache, matrix, offsets[0], count, vector, offsets[1]);
                GeckoBakerMath.packPositions(cache, matrix, offsets[0], count, selected, offsets[1]);
                assertArrayEquals(expected, scalar);
                assertArrayEquals(expected, vector);
                assertArrayEquals(expected, selected);
              }
            }
            assertArrayEquals(xs, cache.xs());
            assertArrayEquals(ys, cache.ys());
            assertArrayEquals(zs, cache.zs());
        }
    }

    @Test
    void uvAliasAndInvalidRangesAreRejectedBeforeAnyWrite() {
        var cache = positions(4);
        Arrays.fill(cache.uv(), 0x7fc12345);
        int[] beforeUv = cache.uv().clone();
        assertThrows(IllegalArgumentException.class,
            () -> GeckoBakerMath.packPositions(cache, cache.model(), 0, 1, cache.uv(), 0));
        assertArrayEquals(beforeUv, cache.uv());
        int[] out = new int[28];
        Arrays.fill(out, 17);
        int[] before = out.clone();
        assertThrows(IndexOutOfBoundsException.class, () -> GeckoBakerMath.packPositions(cache, cache.model(), 1, 4, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> GeckoBakerMath.packPositions(cache, cache.model(), 0, 4, out, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> GeckoBakerMath.packPositions(cache, cache.model(), -1, 0, out, 0));
        assertArrayEquals(before, out);
    }

    private static int[] bake(GeckoModelBaker baker, GeoModel model, IntArrayList batchLengths) {
        var out = new IntArrayList();
        baker.bakeRoutedFilteredPacked(model, new GeckoBakerMath.MatrixStack(), pass -> {
            assertEquals(MachineRenderPass.DEFAULT, pass);
            return (data, offset, length) -> {
                assertEquals(0, offset);
                assertEquals(data.length, length);
                assertTrue(length > 0);
                assertEquals(0, length % 28);
                batchLengths.add(length);
                out.addElements(out.size(), data, offset, length);
            };
        }, Set.of(), GeckoModelBaker.BakeMode.ALL, Set.of());
        return out.toIntArray();
    }

    /** Independent vecmath position/normal transform over actual loader vertices, before any cache is built. */
    private static int[] reference(GeoBone bone) {
        var boneStack = new GeckoBakerMath.MatrixStack();
        boneStack.translate(bone);
        boneStack.moveToPivot(bone);
        boneStack.rotate(bone);
        boneStack.scale(bone);
        boneStack.moveBackFromPivot(bone);
        var expected = new IntArrayList();
        for (var cube : bone.childCubes) {
            var cubeStack = new GeckoBakerMath.MatrixStack();
            cubeStack.moveToPivot(cube);
            cubeStack.rotate(cube);
            cubeStack.moveBackFromPivot(cube);
            var matrix = new Matrix4f();
            matrix.mul(boneStack.model(), cubeStack.model());
            var normals = new Matrix3f();
            normals.mul(boneStack.normal(), cubeStack.normal());
            for (var quad : cube.quads) if (quad != null) {
                var normal = new Vector3f((float) quad.normal.getX(), (float) quad.normal.getY(), (float) quad.normal.getZ());
                normals.transform(normal);
                if (cube.size.y == 0 || cube.size.z == 0) normal.x = Math.abs(normal.x);
                if (cube.size.x == 0 || cube.size.z == 0) normal.y = Math.abs(normal.y);
                if (cube.size.x == 0 || cube.size.y == 0) normal.z = Math.abs(normal.z);
                int packedNormal = GeckoBakerMath.packNormal(normal.x, normal.y, normal.z);
                for (var vertex : quad.vertices) {
                    var position = new Point3f(vertex.position);
                    matrix.transform(position);
                    expected.add(Float.floatToRawIntBits(position.x));
                    expected.add(Float.floatToRawIntBits(position.y));
                    expected.add(Float.floatToRawIntBits(position.z));
                    expected.add(Float.floatToRawIntBits(vertex.textureU));
                    expected.add(Float.floatToRawIntBits(vertex.textureV));
                    expected.add(0xffffffff);
                    expected.add(packedNormal);
                }
            }
        }
        return expected.toIntArray();
    }

    @Test
    void nullQuadsRawUvsFlatReflectedNormalsAndCustomLargeCubesReachExactWholeBatches() {
        var root = bone("root", false);
        root.rotationPointX = 5;
        root.setRotationY(.43f);
        root.setScaleX(-1);
        root.setScaleY(2);
        var flat = cube(true, true);
        flat.pivot.set(3, 7, -2);
        flat.rotation.set(.12f, -.21f, .19f);
        flat.quads[1] = null;
        flat.quads[4] = null;
        flat.quads[0].vertices[0].textureU = Float.intBitsToFloat(0x7fc12345);
        flat.quads[0].vertices[0].textureV = -0f;
        root.childCubes.add(flat);
        var large = cube(false, false);
        var original = large.quads;
        large.quads = Arrays.copyOf(original, 10);
        for (int i = 6; i < large.quads.length; i++) large.quads[i] = original[i % 6];
        root.childCubes.add(large);
        var empty = cube(false, false);
        Arrays.fill(empty.quads, null);
        root.childCubes.add(empty);
        var expected = reference(root);
        var batches = new IntArrayList();
        var baker = new GeckoModelBaker();
        assertArrayEquals(expected, bake(baker, model(root), batches));
        assertArrayEquals(new int[]{16 * 7, 40 * 7}, batches.toIntArray());
        assertEquals(56 * 7, expected.length);
        var cache = new GeckoCubeQuadVertexCache();
        var hit = cache.get(flat);
        assertSame(hit, cache.get(flat));
        assertEquals(16, hit.vertexCount());
        assertEquals(0x7fc12345, hit.uv()[0]);
        assertEquals(0x80000000, hit.uv()[1]);
        batches.clear();
        assertArrayEquals(expected, bake(baker, model(root), batches));
    }

    @Test
    void repeatedAnimatedMatricesAndInterleavedInstancesKeepOriginalCachedCoordinates() {
        var rootA = bone("root", true);
        var rootB = bone("root", true);
        rootA.childCubes.getFirst().rotation.set(.17f, -.39f, .27f);
        rootB.childCubes.getFirst().pivot.set(7, 2, -3);
        rootB.setRotationX(.61f);
        var a = new GeckoModelBaker();
        var b = new GeckoModelBaker();
        var modelA = model(rootA);
        var modelB = model(rootB);
        int[] originalA = reference(rootA), originalB = reference(rootB);
        assertArrayEquals(originalA, bake(a, modelA, new IntArrayList()));
        assertArrayEquals(originalB, bake(b, modelB, new IntArrayList()));
        rootA.setRotationZ(.42f);
        rootA.setPositionX(-19);
        assertArrayEquals(reference(rootA), bake(a, modelA, new IntArrayList()));
        assertArrayEquals(originalB, bake(b, modelB, new IntArrayList()));
        rootA.setRotationZ(0);
        rootA.setPositionX(0);
        assertArrayEquals(originalA, bake(a, modelA, new IntArrayList()));
    }

    @Test
    void cubeCacheRejectsMalformedQuadBeforeExposingPartialData() {
        GeoCube malformed = cube(false, false);
        malformed.quads[0].vertices = Arrays.copyOf(malformed.quads[0].vertices, 3);
        var cache = new GeckoCubeQuadVertexCache();
        assertThrows(IllegalArgumentException.class, () -> cache.get(malformed));
        malformed.quads[0].vertices = Arrays.copyOf(malformed.quads[1].vertices, 4);
        assertEquals(24, cache.get(malformed).vertexCount());
    }
}
