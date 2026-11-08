package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** PM's 45 degree orbit, exponential smoothing and world-space panning, without native GL state. */
public final class StructurePreviewCamera {
    public record Viewport(double x, double y, double width, double height) {
        public Viewport { if (width <= 0 || height <= 0) throw new IllegalArgumentException("Empty viewport"); }
        public boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
        public int[] scissor(int scale, int displayHeight) {
            int left = (int) Math.floor(x * scale), right = (int) Math.ceil((x + width) * scale);
            int top = (int) Math.floor(y * scale), bottom = (int) Math.ceil((y + height) * scale);
            return new int[] {left, displayHeight - bottom, right - left, bottom - top};
        }
    }
    public record Ray(Vec3d origin, Vec3d direction) {}
    private double yaw = -45, pitch = 25, zoom = 1;
    private double yawTarget = yaw, pitchTarget = pitch, zoomTarget = zoom;
    private Vec3d pan = Vec3d.ZERO, panTarget = Vec3d.ZERO;
    private long lastFrame = -1;
    private int dragButton = -1;
    private double startX, startY, lastX, lastY;
    private boolean dragged;

    public void reset() {
        yaw = yawTarget = -45; pitch = pitchTarget = 25; zoom = zoomTarget = 1;
        pan = panTarget = Vec3d.ZERO; lastFrame = -1; dragButton = -1;
    }
    public double yaw() { return yaw; }
    public double pitch() { return pitch; }
    public double distance(StructureBounds bounds) {
        double size = Math.max((double) bounds.max().getX() - bounds.min().getX() + 1,
            Math.max((double) bounds.max().getY() - bounds.min().getY() + 1, (double) bounds.max().getZ() - bounds.min().getZ() + 1));
        return Math.max(1, size) * 2.6 * zoom;
    }
    public Vec3d center(StructureBounds bounds) {
        return new Vec3d(((double) bounds.min().getX() + bounds.max().getX() + 1) / 2,
            ((double) bounds.min().getY() + bounds.max().getY() + 1) / 2,
            ((double) bounds.min().getZ() + bounds.max().getZ() + 1) / 2).add(pan);
    }
    public void frame(long now) {
        frame(now, false);
    }
    public void frame(long now, boolean autoRotate) {
        double dt = lastFrame < 0 ? 0 : Math.clamp((now - lastFrame) / 1000.0, 0, 0.25);
        lastFrame = now;
        if (autoRotate && dragButton < 0) yawTarget += 40 * dt;
        double alpha = 1 - Math.exp(-dt / (dragButton < 0 ? 0.12 : 0.06));
        yaw += (yawTarget - yaw) * alpha; pitch += (pitchTarget - pitch) * alpha; zoom += (zoomTarget - zoom) * alpha;
        pan = pan.add(panTarget.subtract(pan).scale(alpha));
    }
    public boolean press(Viewport viewport, double x, double y, int button) {
        if (!viewport.contains(x, y) || button < 0 || button > 2) return false;
        dragButton = button; startX = lastX = x; startY = lastY = y; dragged = false; return true;
    }
    public boolean drag(double x, double y, StructureBounds bounds, Viewport viewport) {
        if (dragButton < 0) return false;
        double dx = x - lastX, dy = y - lastY;
        lastX = x; lastY = y;
        dragged |= Math.hypot(x - startX, y - startY) > 3;
        if (dragButton == 0) { yawTarget += dx * 0.6; pitchTarget = Math.clamp(pitchTarget + dy * 0.6, -89, 89); }
        else {
            double unit = 2 * distance(bounds) * Math.tan(Math.toRadians(22.5)) / viewport.height;
            panTarget = panTarget.add(right().scale(-dx * unit)).add(up().scale(dy * unit));
        }
        return true;
    }
    public boolean release(int button) {
        if (dragButton != button) return false;
        dragButton = -1; return button == 0 && !dragged;
    }
    public boolean wheel(Viewport viewport, double x, double y, int delta) {
        if (delta == 0 || !viewport.contains(x, y)) return false;
        zoomTarget = Math.clamp(zoomTarget * Math.pow(1.12, -Integer.signum(delta)), 0.12, 12); return true;
    }
    public Vec3d right() { double y = Math.toRadians(yaw); return new Vec3d(Math.cos(y), 0, Math.sin(y)); }
    public Vec3d up() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3d(Math.sin(y) * Math.sin(p), Math.cos(p), -Math.cos(y) * Math.sin(p));
    }
    private Vec3d back() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3d(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }
    public @Nullable Ray ray(Viewport viewport, double x, double y, StructureBounds bounds) {
        if (!viewport.contains(x, y)) return null;
        double nx = 2 * (x - viewport.x) / viewport.width - 1, ny = 1 - 2 * (y - viewport.y) / viewport.height;
        double tangent = Math.tan(Math.toRadians(22.5));
        var backward = back();
        var direction = backward.scale(-1).add(right().scale(nx * tangent * viewport.width / viewport.height)).add(up().scale(ny * tangent)).normalize();
        return new Ray(center(bounds).add(backward.scale(distance(bounds))), direction);
    }
    public @Nullable StructurePreviewView.Click pick(StructurePreviewView view, Viewport viewport, double x, double y) {
        var model = view.model();
        if (model == null) return null;
        var ray = ray(viewport, x, y, model.bounds());
        if (ray == null) return null;
        double best = Double.POSITIVE_INFINITY;
        StructurePreviewModel.Cell hit = null;
        for (var cell : model.cells()) {
            if (!view.visible(cell)) continue;
            var pos = cell.position();
            double distance = intersect(ray, pos.getX(), pos.getY(), pos.getZ());
            if (distance < best) { best = distance; hit = cell; }
        }
        return hit == null ? null : new StructurePreviewView.Click(view.token(), hit);
    }
    static double intersect(Ray ray, int x, int y, int z) {
        double near = 0, far = Double.POSITIVE_INFINITY;
        double[] origins = {ray.origin.x, ray.origin.y, ray.origin.z};
        double[] directions = {ray.direction.x, ray.direction.y, ray.direction.z};
        int[] lower = {x, y, z};
        for (int axis = 0; axis < 3; axis++) {
            double d = directions[axis], o = origins[axis], min = lower[axis], max = min + 1;
            if (d == 0) { if (o < min || o > max) return Double.POSITIVE_INFINITY; continue; }
            double a = (min - o) / d, b = (max - o) / d;
            double axisNear = Math.min(a, b), axisFar = Math.max(a, b);
            near = Math.max(near, axisNear); far = Math.min(far, axisFar);
            if (near > far) return Double.POSITIVE_INFINITY;
        }
        return near;
    }
}
