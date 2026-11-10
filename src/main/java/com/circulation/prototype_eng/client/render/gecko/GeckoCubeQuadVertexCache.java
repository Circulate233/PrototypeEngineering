package com.circulation.prototype_eng.client.render.gecko;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import software.bernie.geckolib3.geo.render.built.GeoCube;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;

/** Per-instance read-only SoA coordinates and raw UV bits retain loader/null-quad/mirror ordering. */
public final class GeckoCubeQuadVertexCache {
    private final Reference2ObjectOpenHashMap<GeoCube, CubeData> cache = new Reference2ObjectOpenHashMap<>();

    private static CubeData bake(GeoCube cube) {
        int count = 0;
        for (var quad : cube.quads)
            if (quad != null) {
                if (quad.vertices.length != 4) throw new IllegalArgumentException("GeoQuad requires four vertices");
                count++;
            }
        int vertices = Math.multiplyExact(count, 4);
        float[] xs = new float[vertices], ys = new float[vertices], zs = new float[vertices];
        int[] uv = new int[Math.multiplyExact(vertices, 2)];
        int i = 0;
        for (var quad : cube.quads)
            if (quad != null) for (var vertex : quad.vertices) {
                xs[i] = vertex.position.x;
                ys[i] = vertex.position.y;
                zs[i] = vertex.position.z;
                uv[i * 2] = Float.floatToRawIntBits(vertex.textureU);
                uv[i * 2 + 1] = Float.floatToRawIntBits(vertex.textureV);
                i++;
            }
        var stack = new GeckoBakerMath.MatrixStack();
        stack.moveToPivot(cube);
        stack.rotate(cube);
        stack.moveBackFromPivot(cube);
        return new CubeData(xs, ys, zs, uv, new Matrix4f(stack.model()), new Matrix3f(stack.normal()));
    }

    CubeData get(GeoCube cube) {
        return cache.computeIfAbsent(cube, GeckoCubeQuadVertexCache::bake);
    }

    record CubeData(float[] xs, float[] ys, float[] zs, int[] uv, Matrix4f model, Matrix3f normal) {
        int vertexCount() {
            return xs.length;
        }
    }
}
