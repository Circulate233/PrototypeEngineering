package com.circulation.prototype_eng.client.render.gecko;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.circulation.prototype_eng.client.render.BModelFixtures.bone;
import static com.circulation.prototype_eng.client.render.BModelFixtures.cube;
import static com.circulation.prototype_eng.client.render.BModelFixtures.model;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeckoModelGeometryTest {
    static Map<MachineRenderPass, int[]> packed(GeoModel model, GeckoModelBaker.BakeMode mode, Set<String> active, Set<String> potential) {
        var out = new Object2ObjectOpenHashMap<MachineRenderPass, IntArrayList>();
        new GeckoModelBaker().bakeRoutedFilteredPacked(model, new GeckoBakerMath.MatrixStack(), pass -> {
            var data = out.computeIfAbsent(pass, ignored -> new IntArrayList());
            return (ints, offset, length) -> data.addElements(data.size(), ints, offset, length);
        }, active, mode, potential);
        var result = new Object2ObjectOpenHashMap<MachineRenderPass, int[]>();
        out.forEach((pass, ints) -> result.put(pass, ints.toIntArray()));
        return result;
    }

    @Test
    void staticParentPotentialChildAndActiveSiblingPartitionEveryRoutedPassWithoutLoss() {
        for (String prefix : List.of("plain", "bloom", "transparent", "bloom_transparent")) {
            var root = bone(prefix + "_root", true);
            var inactive = bone("inactive", true);
            var active = bone("active", true);
            inactive.childBones.add(bone("grandchild", true));
            root.childBones.add(inactive);
            root.childBones.add(active);
            var geo = model(root);
            var potential = Set.of("inactive", "active");
            var current = Set.of("active");
            var all = packed(geo, GeckoModelBaker.BakeMode.ALL, current, potential);
            var permanent = packed(geo, GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY, current, potential);
            var temporary = packed(geo, GeckoModelBaker.BakeMode.TEMP_STATIC_ONLY, current, potential);
            var animated = packed(geo, GeckoModelBaker.BakeMode.ANIMATED_ONLY, current, potential);
            assertEquals(1, all.size());
            var pass = all.keySet().iterator().next();
            assertEquals(4 * 24 * 7, all.get(pass).length);
            assertEquals(24 * 7, permanent.get(pass).length);
            assertEquals(2 * 24 * 7, temporary.get(pass).length);
            assertEquals(24 * 7, animated.get(pass).length);
            var joined = new IntArrayList();
            joined.addElements(0, permanent.get(pass));
            joined.addElements(joined.size(), temporary.get(pass));
            joined.addElements(joined.size(), animated.get(pass));
            assertArrayEquals(all.get(pass), joined.toIntArray());
            int[] counts = new GeckoModelBaker().estimateRoutedFilteredVertexCounts(geo, current, GeckoModelBaker.BakeMode.TEMP_STATIC_ONLY, potential);
            assertEquals(48, Arrays.stream(counts).sum());
        }
    }

    @Test
    void ordinaryAndPackedAgreeForMirroredUvsReflectedScaleAndFlatNormals() {
        var root = bone("bloom_transparent_root", false);
        root.childCubes.add(cube(true, true));
        root.setRotationY(.4f);
        root.setScaleX(-1);
        root.setScaleY(2);
        var geo = model(root);
        var expected = packed(geo, GeckoModelBaker.BakeMode.ALL, Set.of(), Set.of());
        var buffers = new Object2ObjectOpenHashMap<MachineRenderPass, BufferBuilder>();
        new GeckoModelBaker().bakeRoutedFiltered(geo, new GeckoBakerMath.MatrixStack(), pass -> {
            var buffer = new BufferBuilder(8192);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
            buffers.put(pass, buffer);
            return buffer;
        }, Set.of(), GeckoModelBaker.BakeMode.ALL, Set.of());
        buffers.forEach((pass, buffer) -> {
            buffer.finishDrawing();
            int[] data = new int[buffer.getVertexCount() * 7];
            buffer.getByteBuffer().asIntBuffer().get(data);
            assertArrayEquals(expected.get(pass), data);
        });
        assertTrue(expected.containsKey(MachineRenderPass.BLOOM_TRANSPARENT));
        var mirrored = root.childCubes.getFirst();
        var normal = cube(false, true);
        boolean reversedUv = false;
        for (int face = 0; face < mirrored.quads.length; face++) {
            if (mirrored.quads[face] == null || normal.quads[face] == null) continue;
            for (int vertex = 0; vertex < 4; vertex++) {
                var uv = mirrored.quads[face].vertices[vertex];
                assertEquals(uv.textureU, Float.intBitsToFloat(expected.get(MachineRenderPass.BLOOM_TRANSPARENT)[(face * 4 + vertex) * 7 + 3]));
                reversedUv |= uv.textureU != normal.quads[face].vertices[vertex].textureU;
            }
        }
        assertTrue(reversedUv, "Native cube mirror must change emitted box UVs");
    }

    @Test
    void parentPivotRotationAndChildTranslationReachActualCubeVertices() {
        var root = bone("root", false);
        root.rotationPointX = 16;
        root.setRotationZ((float) (Math.PI / 2));
        var child = bone("child", true);
        child.setPositionX(-16);
        root.childBones.add(child);
        var raw = child.childCubes.getFirst().quads[0].vertices[0].position;
        int[] data = packed(model(root), GeckoModelBaker.BakeMode.ALL, Set.of(), Set.of()).get(MachineRenderPass.DEFAULT);
        assertEquals(1 - raw.y, Float.intBitsToFloat(data[0]), 1e-5);
        assertEquals(raw.x, Float.intBitsToFloat(data[1]), 1e-5);
        assertEquals(raw.z, Float.intBitsToFloat(data[2]), 1e-5);
    }

    @Test
    void hiddenParentStillTransformsVisibleChildrenUntilChildHideIsExplicit() {
        var root = bone("transparent_root", true);
        root.isHidden = true;
        root.childBones.add(bone("child", true));
        assertEquals(24 * 7, packed(model(root), GeckoModelBaker.BakeMode.ALL, Set.of(), Set.of()).get(MachineRenderPass.TRANSPARENT).length);
        root.hideChildBonesToo = true;
        assertTrue(packed(model(root), GeckoModelBaker.BakeMode.ALL, Set.of(), Set.of()).isEmpty());
    }

    @Test
    void all24OrientationsPreserveTheBlockCenterAndTransformBasisOnce() {
        for (var orientation : ExternalEnumValues.BLOCK_ORIENTATIONS) {
            var stack = new GeckoBakerMath.MatrixStack();
            stack.orientation(orientation);
            var center = new Point3f(0, .5f, 0);
            stack.model().transform(center);
            assertEquals(0, center.x, 1e-6);
            assertEquals(.5, center.y, 1e-6);
            assertEquals(0, center.z, 1e-6);
            var front = new Vector3f(0, 0, -1);
            stack.normal().transform(front);
            var facing = orientation.rotate(EnumFacing.NORTH);
            assertEquals(facing.getXOffset(), front.x, 1e-6);
            assertEquals(facing.getYOffset(), front.y, 1e-6);
            assertEquals(facing.getZOffset(), front.z, 1e-6);
        }
        assertEquals(24, ExternalEnumValues.BLOCK_ORIENTATIONS.length);
    }
}
