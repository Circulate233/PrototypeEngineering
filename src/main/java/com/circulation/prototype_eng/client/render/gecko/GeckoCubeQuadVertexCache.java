package com.circulation.prototype_eng.client.render.gecko;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import software.bernie.geckolib3.geo.render.built.GeoCube;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;

/** Per-instance cube cache: immutable pivot matrices and primitive xyzuv retain loader UV/mirror ordering. */
public final class GeckoCubeQuadVertexCache {
    private final Reference2ObjectOpenHashMap<GeoCube, CubeData> cache = new Reference2ObjectOpenHashMap<>();

    CubeData get(GeoCube cube) {
        return cache.computeIfAbsent(cube, GeckoCubeQuadVertexCache::bake);
    }

    private static CubeData bake(GeoCube cube) {
        int count = 0;
        for (var quad : cube.quads)
            if (quad != null) {
                if (quad.vertices.length != 4) throw new IllegalArgumentException("GeoQuad requires four vertices");
                count++;
            }
        float[] data = new float[Math.multiplyExact(count, 20)];
        int i = 0;
        for (var quad : cube.quads)
            if (quad != null) for (var vertex : quad.vertices) {
                data[i++] = vertex.position.x;
                data[i++] = vertex.position.y;
                data[i++] = vertex.position.z;
                data[i++] = vertex.textureU;
                data[i++] = vertex.textureV;
            }
        var stack = new GeckoBakerMath.MatrixStack();
        stack.moveToPivot(cube);
        stack.rotate(cube);
        stack.moveBackFromPivot(cube);
        return new CubeData(data, new Matrix4f(stack.model()), new Matrix3f(stack.normal()));
    }

    record CubeData(float[] xyzuv, Matrix4f model, Matrix3f normal) {
    }
}
