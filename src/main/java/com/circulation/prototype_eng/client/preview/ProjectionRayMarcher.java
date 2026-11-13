package com.circulation.prototype_eng.client.preview;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Amanatides/Woo voxel visits, including empty cells; no world query or client matcher.
 */
public final class ProjectionRayMarcher {
    private ProjectionRayMarcher() {
    }

    /**
     * The predicate borrows a mutable relative position only for the call and must not retain it.
     */
    public static @Nullable BlockPos pick(BlockPos anchor, Vec3d start, Vec3d direction, double maxDistance,
                                          int maxVisits, Predicate<BlockPos> relativeCell) {
        if (!Double.isFinite(maxDistance) || maxDistance <= 0 || maxVisits <= 0 || !finite(start) || !finite(direction))
            return null;
        double length = direction.length();
        if (length == 0) return null;
        var ray = direction.scale(1 / length);
        int x = (int) Math.floor(start.x), y = (int) Math.floor(start.y), z = (int) Math.floor(start.z);
        int sx = Double.compare(ray.x, 0), sy = Double.compare(ray.y, 0), sz = Double.compare(ray.z, 0);
        double tx = boundary(start.x, ray.x), ty = boundary(start.y, ray.y), tz = boundary(start.z, ray.z);
        double dx = ray.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / ray.x);
        double dy = ray.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / ray.y);
        double dz = ray.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / ray.z);
        var relative = new BlockPos.MutableBlockPos();
        for (int visits = 0; visits < maxVisits; visits++) {
            long rx = (long) x - anchor.getX(), ry = (long) y - anchor.getY(), rz = (long) z - anchor.getZ();
            if (rx >= Integer.MIN_VALUE && rx <= Integer.MAX_VALUE && ry >= Integer.MIN_VALUE && ry <= Integer.MAX_VALUE && rz >= Integer.MIN_VALUE && rz <= Integer.MAX_VALUE
                && relativeCell.test(relative.setPos((int) rx, (int) ry, (int) rz))) return new BlockPos(x, y, z);
            double next = Math.min(tx, Math.min(ty, tz));
            if (next > maxDistance) return null;
            if (tx <= ty && tx <= tz) {
                if (x == (sx > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE)) return null;
                x += sx;
                tx += dx;
            } else if (ty <= tx && ty <= tz) {
                if (y == (sy > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE)) return null;
                y += sy;
                ty += dy;
            } else {
                if (z == (sz > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE)) return null;
                z += sz;
                tz += dz;
            }
        }
        return null;
    }

    private static boolean finite(Vec3d value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static double boundary(double value, double direction) {
        if (direction > 0) return (Math.floor(value) + 1 - value) / direction;
        if (direction < 0) return (value - Math.floor(value)) / -direction;
        return Double.POSITIVE_INFINITY;
    }
}
