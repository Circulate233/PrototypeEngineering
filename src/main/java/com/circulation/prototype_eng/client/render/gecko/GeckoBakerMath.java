package com.circulation.prototype_eng.client.render.gecko;

import ae2.api.orientation.BlockOrientation;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import net.minecraft.util.EnumFacing;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Objects;

/** The original Gecko affine, pivot, Z/Y/X rotation and reflected-normal conventions. No GL calls. */
public final class GeckoBakerMath {
    private static final boolean FORCE_SCALAR = Boolean.getBoolean("prototype_eng.gecko.forceScalarTransform");
    private static final boolean VECTOR_RESOLVED = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
    // Keep the current scalar default until real Gecko batch/writer measurements select the crossover.
    private static final int VECTOR_MIN_VERTICES = Math.max(4,
        Integer.getInteger("prototype_eng.gecko.vectorMinVertices", Integer.MAX_VALUE));

    private GeckoBakerMath() {
    }

    public static int packColor(float red, float green, float blue, float alpha) {
        return color(red) | color(green) << 8 | color(blue) << 16 | color(alpha) << 24;
    }

    private static int color(float value) {
        return (int) (Math.clamp(value, 0f, 1f) * 255 + .5f);
    }

    public static int packNormal(float x, float y, float z) {
        return normalByte(x) | normalByte(y) << 8 | normalByte(z) << 16;
    }

    private static int normalByte(float value) {
        float scaled = Math.clamp(value, -1f, 1f) * 127;
        return ((int) (scaled + (scaled >= 0 ? .5f : -.5f))) & 255;
    }

    /** Read-only cache inputs; writes only position bits into the existing seven-int vertex layout. */
    static void packPositions(GeckoCubeQuadVertexCache.CubeData cube, Matrix4f matrix,
                              int vertexOffset, int count, int[] out, int outIntOffset) {
        Objects.checkFromIndexSize(vertexOffset, count, cube.vertexCount());
        Objects.checkFromIndexSize(outIntOffset, Math.multiplyExact(count, 7), out.length);
        if (out == cube.uv()) throw new IllegalArgumentException("Packed output aliases cached UV data");
        if (!FORCE_SCALAR && VECTOR_RESOLVED && count >= VECTOR_MIN_VERTICES)
            packPositionsVector(cube, matrix, vertexOffset, count, out, outIntOffset);
        else packPositionsScalar(cube, matrix, vertexOffset, count, out, outIntOffset);
    }

    /** Concrete scalar callee of the production selection; caller has already validated its ranges. */
    static void packPositionsScalar(GeckoCubeQuadVertexCache.CubeData cube, Matrix4f m,
                                    int offset, int count, int[] out, int outIntOffset) {
        int end = offset + count, o = outIntOffset;
        for (int i = offset; i < end; i++, o += 7) {
            float x = cube.xs()[i], y = cube.ys()[i], z = cube.zs()[i];
            out[o] = Float.floatToRawIntBits(m.m00 * x + m.m01 * y + m.m02 * z + m.m03);
            out[o + 1] = Float.floatToRawIntBits(m.m10 * x + m.m11 * y + m.m12 * z + m.m13);
            out[o + 2] = Float.floatToRawIntBits(m.m20 * x + m.m21 * y + m.m22 * z + m.m23);
        }
    }

    /** Production callee entered only after the module-resolution gate; no Vector type escapes it. */
    static void packPositionsVector(GeckoCubeQuadVertexCache.CubeData cube, Matrix4f matrix,
                                    int offset, int count, int[] out, int outIntOffset) {
        VectorPositions.pack(cube, matrix, offset, count, out, outIntOffset);
    }

    /** Lazy initialization keeps unresolved-module scalar launches free of Vector linkage. */
    private static final class VectorPositions {
        private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
        private static final int[] STRIDE_INDICES = strideIndices();

        private static int[] strideIndices() {
            int[] indices = new int[SPECIES.length()];
            for (int i = 0; i < indices.length; i++) indices[i] = i * 7;
            return indices;
        }

        static void pack(GeckoCubeQuadVertexCache.CubeData cube, Matrix4f m,
                         int offset, int count, int[] out, int outIntOffset) {
            var sp = SPECIES;
            int bound = sp.loopBound(count), step = sp.length();
            var m00 = FloatVector.broadcast(sp, m.m00);
            var m01 = FloatVector.broadcast(sp, m.m01);
            var m02 = FloatVector.broadcast(sp, m.m02);
            var m03 = FloatVector.broadcast(sp, m.m03);
            var m10 = FloatVector.broadcast(sp, m.m10);
            var m11 = FloatVector.broadcast(sp, m.m11);
            var m12 = FloatVector.broadcast(sp, m.m12);
            var m13 = FloatVector.broadcast(sp, m.m13);
            var m20 = FloatVector.broadcast(sp, m.m20);
            var m21 = FloatVector.broadcast(sp, m.m21);
            var m22 = FloatVector.broadcast(sp, m.m22);
            var m23 = FloatVector.broadcast(sp, m.m23);
            int i = 0;
            for (; i < bound; i += step) {
                int index = offset + i, o = outIntOffset + i * 7;
                var x = FloatVector.fromArray(sp, cube.xs(), index);
                var y = FloatVector.fromArray(sp, cube.ys(), index);
                var z = FloatVector.fromArray(sp, cube.zs(), index);
                // Loop fusion is distinct from floating FMA: preserve the original mul/add association.
                var rx = x.mul(m00).add(y.mul(m01)).add(z.mul(m02)).add(m03);
                var ry = x.mul(m10).add(y.mul(m11)).add(z.mul(m12)).add(m13);
                var rz = x.mul(m20).add(y.mul(m21)).add(z.mul(m22)).add(m23);
                rx.reinterpretAsInts().intoArray(out, o, STRIDE_INDICES, 0);
                ry.reinterpretAsInts().intoArray(out, o + 1, STRIDE_INDICES, 0);
                rz.reinterpretAsInts().intoArray(out, o + 2, STRIDE_INDICES, 0);
            }
            packPositionsScalar(cube, m, offset + i, count - i, out, outIntOffset + i * 7);
        }
    }

    public static final class MatrixStack {
        private final ObjectArrayList<Matrix4f> models = new ObjectArrayList<>();
        private final ObjectArrayList<Matrix3f> normals = new ObjectArrayList<>();
        private final ObjectArrayList<Matrix4f> freeModels = new ObjectArrayList<>();
        private final ObjectArrayList<Matrix3f> freeNormals = new ObjectArrayList<>();
        private final Matrix4f tempModel = new Matrix4f();
        private final Matrix3f tempNormal = new Matrix3f();

        public MatrixStack() {
            var model = new Matrix4f();
            model.setIdentity();
            models.push(model);
            var normal = new Matrix3f();
            normal.setIdentity();
            normals.push(normal);
        }

        public static MatrixStack forRequest(GeckoRenderRequest request) {
            var stack = new MatrixStack();
            stack.translate(request.position().getX() - request.origin().getX() + .5f,
                (float) (request.position().getY() - request.origin().getY() + request.binding().yOffset() + .01),
                request.position().getZ() - request.origin().getZ() + .5f);
            stack.orientation(request.orientation());
            stack.translate((float) request.localOffset().x, (float) request.localOffset().y, (float) request.localOffset().z);
            return stack;
        }

        Matrix4f model() {
            return models.top();
        }

        Matrix3f normal() {
            return normals.top();
        }

        public void push() {
            var m = freeModels.isEmpty() ? new Matrix4f() : freeModels.pop();
            m.set(model());
            models.push(m);
            var n = freeNormals.isEmpty() ? new Matrix3f() : freeNormals.pop();
            n.set(normal());
            normals.push(n);
        }

        public void pop() {
            if (models.size() == 1) throw new IllegalStateException("Cannot pop root matrix");
            freeModels.push(models.pop());
            freeNormals.push(normals.pop());
        }

        public void translate(float x, float y, float z) {
            var m = model();
            m.m03 += m.m00 * x + m.m01 * y + m.m02 * z;
            m.m13 += m.m10 * x + m.m11 * y + m.m12 * z;
            m.m23 += m.m20 * x + m.m21 * y + m.m22 * z;
        }

        public void scale(float x, float y, float z) {
            tempModel.setIdentity();
            tempModel.m00 = x;
            tempModel.m11 = y;
            tempModel.m22 = z;
            model().mul(tempModel);
            // Preserve Gecko's sign-only normal scale, including flat and reflected cubes.
            if (x < 0 || y < 0 || z < 0) {
                tempNormal.setIdentity();
                tempNormal.m00 = x < 0 ? -1 : 1;
                tempNormal.m11 = y < 0 ? -1 : 1;
                tempNormal.m22 = z < 0 ? -1 : 1;
                normal().mul(tempNormal);
            }
        }

        public void rotateX(float radians) {
            tempModel.rotX(radians);
            tempNormal.rotX(radians);
            multiplyRotation();
        }

        public void rotateY(float radians) {
            tempModel.rotY(radians);
            tempNormal.rotY(radians);
            multiplyRotation();
        }

        public void rotateZ(float radians) {
            tempModel.rotZ(radians);
            tempNormal.rotZ(radians);
            multiplyRotation();
        }

        private void multiplyRotation() {
            model().mul(tempModel);
            normal().mul(tempNormal);
        }

        public void translate(GeoBone bone) {
            translate(-bone.getPositionX() / 16, bone.getPositionY() / 16, bone.getPositionZ() / 16);
        }

        public void moveToPivot(GeoBone bone) {
            translate(bone.rotationPointX / 16, bone.rotationPointY / 16, bone.rotationPointZ / 16);
        }

        public void moveBackFromPivot(GeoBone bone) {
            translate(-bone.rotationPointX / 16, -bone.rotationPointY / 16, -bone.rotationPointZ / 16);
        }

        public void moveToPivot(GeoCube cube) {
            translate(cube.pivot.x / 16, cube.pivot.y / 16, cube.pivot.z / 16);
        }

        public void moveBackFromPivot(GeoCube cube) {
            translate(-cube.pivot.x / 16, -cube.pivot.y / 16, -cube.pivot.z / 16);
        }

        public void rotate(GeoBone bone) {
            rotate(bone.getRotationX(), bone.getRotationY(), bone.getRotationZ());
        }

        public void rotate(GeoCube cube) {
            rotate(cube.rotation.x, cube.rotation.y, cube.rotation.z);
        }

        private void rotate(float x, float y, float z) {
            if (z != 0) rotateZ(z);
            if (y != 0) rotateY(y);
            if (x != 0) rotateX(x);
        }

        public void scale(GeoBone bone) {
            scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
        }

        public void orientation(BlockOrientation orientation) {
            var x = orientation.rotate(EnumFacing.EAST).getDirectionVec();
            var y = orientation.rotate(EnumFacing.UP).getDirectionVec();
            var z = orientation.rotate(EnumFacing.SOUTH).getDirectionVec();
            tempModel.setIdentity();
            tempModel.m00 = x.getX();
            tempModel.m10 = x.getY();
            tempModel.m20 = x.getZ();
            tempModel.m01 = y.getX();
            tempModel.m11 = y.getY();
            tempModel.m21 = y.getZ();
            tempModel.m02 = z.getX();
            tempModel.m12 = z.getY();
            tempModel.m22 = z.getZ();
            tempModel.getRotationScale(tempNormal);
            // Same center as the old reverse shortest-path X-centered / Y rotations.
            translate(0, .5f, 0);
            model().mul(tempModel);
            normal().mul(tempNormal);
            translate(0, -.5f, 0);
        }
    }
}
