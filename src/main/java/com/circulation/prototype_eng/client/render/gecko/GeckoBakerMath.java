package com.circulation.prototype_eng.client.render.gecko;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.util.EnumFacing;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import java.util.ArrayDeque;

/** The original Gecko affine, pivot, Z/Y/X rotation and reflected-normal conventions. No GL calls. */
public final class GeckoBakerMath {
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

    public static final class MatrixStack {
        private final ArrayDeque<Matrix4f> models = new ArrayDeque<>();
        private final ArrayDeque<Matrix3f> normals = new ArrayDeque<>();
        private final ArrayDeque<Matrix4f> freeModels = new ArrayDeque<>();
        private final ArrayDeque<Matrix3f> freeNormals = new ArrayDeque<>();
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

        Matrix4f model() {
            return models.getFirst();
        }

        Matrix3f normal() {
            return normals.getFirst();
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

        public static MatrixStack forRequest(GeckoRenderRequest request) {
            var stack = new MatrixStack();
            stack.translate(request.position().getX() - request.origin().getX() + .5f,
                (float) (request.position().getY() - request.origin().getY() + request.binding().yOffset() + .01),
                request.position().getZ() - request.origin().getZ() + .5f);
            stack.orientation(request.orientation());
            stack.translate((float) request.localOffset().x, (float) request.localOffset().y, (float) request.localOffset().z);
            return stack;
        }
    }
}
