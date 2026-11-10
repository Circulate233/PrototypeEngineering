package com.circulation.prototype_eng.client.render.gecko;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import java.util.Set;
import java.util.function.Function;

/** CPU-only hierarchy baker. Read-only SoA and cube-sized packed writes serve all three output paths. */
public class GeckoModelBaker {
    private static final int WHITE = GeckoBakerMath.packColor(1, 1, 1, 1);
    private final GeckoCubeQuadVertexCache cubes = new GeckoCubeQuadVertexCache();
    private final Object2ObjectLinkedOpenHashMap<Estimate, int[]> estimates = new Object2ObjectLinkedOpenHashMap<>();

    private static boolean skipSubtree(BakeMode mode, boolean dynamic, boolean potential) {
        return mode == BakeMode.PERMANENT_STATIC_ONLY && potential || mode == BakeMode.TEMP_STATIC_ONLY && dynamic;
    }

    private static boolean emit(BakeMode mode, boolean dynamic, boolean potential) {
        return switch (mode) {
            case ALL, PERMANENT_STATIC_ONLY -> true;
            case TEMP_STATIC_ONLY -> potential && !dynamic;
            case ANIMATED_ONLY -> dynamic;
        };
    }

    private static int route(String name, int inherited) {
        int pass = inherited;
        if (name.startsWith("emissive") || name.startsWith("bloom")) pass |= 1;
        if (name.startsWith("transparent") || name.startsWith("emissive_transparent") || name.startsWith("bloom_transparent"))
            pass |= 2;
        return pass;
    }

    public static MachineRenderPass pass(int route) {
        return switch (route) {
            case 0 -> MachineRenderPass.DEFAULT;
            case 1 -> MachineRenderPass.BLOOM;
            case 2 -> MachineRenderPass.TRANSPARENT;
            case 3 -> MachineRenderPass.BLOOM_TRANSPARENT;
            default -> throw new IllegalArgumentException("Invalid routed pass");
        };
    }

    public int[] estimateRoutedFilteredVertexCounts(GeoModel model, Set<String> active, BakeMode mode, Set<String> potential) {
        var key = new Estimate(model, Set.copyOf(active), mode, Set.copyOf(potential));
        var hit = estimates.getAndMoveToLast(key);
        if (hit != null) return hit.clone();
        int[] out = new int[4];
        for (var bone : model.topLevelBones) estimate(bone, active, potential, mode, false, false, 0, out);
        if (estimates.size() == 64) estimates.removeFirst();
        estimates.put(key, out);
        return out.clone();
    }

    private void estimate(GeoBone bone, Set<String> active, Set<String> potential, BakeMode mode,
                          boolean parentDynamic, boolean parentPotential, int inheritedPass, int[] out) {
        boolean dynamic = parentDynamic || active.contains(bone.name);
        boolean possible = parentPotential || potential.contains(bone.name);
        if (skipSubtree(mode, dynamic, possible)) return;
        int pass = route(bone.name, inheritedPass);
        if (emit(mode, dynamic, possible) && !bone.isHidden) {
            for (var cube : bone.childCubes) {
                int count = cubes.get(cube).vertexCount();
                out[pass] = Math.addExact(out[pass], count);
            }
        }
        if (!bone.childBonesAreHiddenToo()) for (var child : bone.childBones)
            estimate(child, active, potential, mode, dynamic, possible, pass, out);
    }

    public void bakeRoutedFiltered(GeoModel model, GeckoBakerMath.MatrixStack stack,
                                   Function<MachineRenderPass, BufferBuilder> outputs,
                                   Set<String> active, BakeMode mode, Set<String> potential) {
        bakeRoutedFilteredPacked(model, stack, pass -> {
            var builder = outputs.apply(pass);
            return (data, offset, length) -> {
                if (offset != 0 || length != data.length)
                    throw new IllegalArgumentException("Builder needs a whole packed batch");
                builder.addVertexData(data);
            };
        }, active, mode, potential);
    }

    public void bakeRoutedFilteredPacked(GeoModel model, GeckoBakerMath.MatrixStack stack,
                                         Function<MachineRenderPass, PackedVertexDataWriter> outputs,
                                         Set<String> active, BakeMode mode, Set<String> potential) {
        var scratch = new Scratch();
        for (var root : model.topLevelBones)
            bake(root, stack, outputs, active, potential, mode, false, false, 0, scratch);
    }

    protected void bake(GeoBone bone, GeckoBakerMath.MatrixStack stack,
                        Function<MachineRenderPass, PackedVertexDataWriter> outputs, Set<String> active,
                        Set<String> potential, BakeMode mode, boolean parentDynamic, boolean parentPotential,
                        int inheritedPass, Scratch scratch) {
        boolean dynamic = parentDynamic || active.contains(bone.name);
        boolean possible = parentPotential || potential.contains(bone.name);
        if (skipSubtree(mode, dynamic, possible)) return;
        int routed = route(bone.name, inheritedPass);
        stack.push();
        try {
            stack.translate(bone);
            stack.moveToPivot(bone);
            stack.rotate(bone);
            stack.scale(bone);
            stack.moveBackFromPivot(bone);
            if (emit(mode, dynamic, possible) && !bone.isHidden && !bone.childCubes.isEmpty()) {
                var writer = outputs.apply(pass(routed));
                for (var cube : bone.childCubes) writeCube(cube, stack, writer, scratch);
            }
            // A non-potential parent still carries the transform to a potentially animated descendant.
            if (!bone.childBonesAreHiddenToo()) for (var child : bone.childBones)
                bake(child, stack, outputs, active, potential, mode, dynamic, possible, routed, scratch);
        } finally {
            stack.pop();
        }
    }

    private void writeCube(GeoCube cube, GeckoBakerMath.MatrixStack stack, PackedVertexDataWriter writer, Scratch scratch) {
        var cached = cubes.get(cube);
        int count = cached.vertexCount();
        if (count == 0) return;
        scratch.model.mul(stack.model(), cached.model());
        scratch.normal.mul(stack.normal(), cached.normal());
        int[] packed = scratch.packed(Math.multiplyExact(count, 7));
        GeckoBakerMath.packPositions(cached, scratch.model, 0, count, packed, 0);
        var n = scratch.normal;
        int vertex = 0;
        for (var quad : cube.quads)
            if (quad != null) {
                var normal = quad.normal;
                float nx = n.m00 * normal.getX() + n.m01 * normal.getY() + n.m02 * normal.getZ();
                float ny = n.m10 * normal.getX() + n.m11 * normal.getY() + n.m12 * normal.getZ();
                float nz = n.m20 * normal.getX() + n.m21 * normal.getY() + n.m22 * normal.getZ();
                if ((cube.size.y == 0 || cube.size.z == 0) && nx < 0) nx = -nx;
                if ((cube.size.x == 0 || cube.size.z == 0) && ny < 0) ny = -ny;
                if ((cube.size.x == 0 || cube.size.y == 0) && nz < 0) nz = -nz;
                int packedNormal = GeckoBakerMath.packNormal(nx, ny, nz);
                for (int v = 0; v < 4; v++) {
                    int j = vertex * 7;
                    packed[j + 3] = cached.uv()[vertex * 2];
                    packed[j + 4] = cached.uv()[vertex * 2 + 1];
                    packed[j + 5] = WHITE;
                    packed[j + 6] = packedNormal;
                    vertex++;
                }
            }
        writer.write(packed, 0, packed.length);
    }

    public enum BakeMode {ALL, PERMANENT_STATIC_ONLY, TEMP_STATIC_ONLY, ANIMATED_ONLY}

    @FunctionalInterface
    public interface PackedVertexDataWriter {
        /** Consume synchronously without retaining the borrowed array; a batch contains whole quads. */
        void write(int[] data, int offset, int length);
    }

    private record Estimate(GeoModel model, Set<String> active, BakeMode mode, Set<String> potential) {
    }

    protected static final class Scratch {
        final Matrix4f model = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        private int @Nullable [] primary;
        private @Nullable Int2ObjectOpenHashMap<int[]> otherSizes;

        int[] packed(int intCount) {
            var data = primary;
            if (data == null) {
                data = new int[intCount];
                primary = data;
                return data;
            }
            if (data.length == intCount) return data;
            // Only genuinely mixed counts need a table; each exact size remains private to this bake.
            var sizes = otherSizes;
            if (sizes == null) {
                sizes = new Int2ObjectOpenHashMap<>();
                otherSizes = sizes;
            }
            return sizes.computeIfAbsent(intCount, int[]::new);
        }
    }
}
