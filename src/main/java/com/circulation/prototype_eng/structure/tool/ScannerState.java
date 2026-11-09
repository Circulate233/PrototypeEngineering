package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.RelativeSide;

import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.item.OrientationToolOrientations;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/** Persistent selection and edit buffers from PM's range structure selector. */
public final class ScannerState {
    public static final String ROOT = "pm_scanner";
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,10}");

    private ScannerState() {
    }

    public enum Field {
        NAME("langName"), ID("structureId"), EXPANDED("expanded"), QUANTITY("expandedQuantity"),
        SPACING("expandedSpacing"), SUBSTRUCTURE("substructure"), NBT("includeTileNbt"), MIRROR("allowMirror"),
        X("originEditX"), Y("originEditY"), Z("originEditZ"), PREVIEW_MIRROR("previewMirror");
        public final String key;

        Field(String key) {
            this.key = key;
        }

        public boolean toggle() {
            return this == EXPANDED || this == SUBSTRUCTURE || this == NBT || this == MIRROR || this == PREVIEW_MIRROR;
        }
    }

    public static NBTTagCompound data(ItemStack stack) {
        var root = stack.getTagCompound();
        if (root == null) {
            root = new NBTTagCompound();
            stack.setTagCompound(root);
        }
        if (!root.hasKey(ROOT, 10)) root.setTag(ROOT, new NBTTagCompound());
        var data = root.getCompoundTag(ROOT);
        defaults(data, stack.getDisplayName());
        return data;
    }

    public static void defaults(NBTTagCompound data, String name) {
        if (data.getString(Field.ID.key).isBlank()) data.setString(Field.ID.key, sanitize(name));
        if (!data.hasKey(Field.QUANTITY.key, 3)) data.setInteger(Field.QUANTITY.key, 1);
        if (!data.hasKey("previewFacing", 3)) data.setInteger("previewFacing", EnumFacing.NORTH.getIndex());
        if (!data.hasKey("exportOriginAuto", 1)) data.setBoolean("exportOriginAuto", true);
        if (!data.hasKey("previewAuto", 1)) data.setBoolean("previewAuto", true);
    }

    public static String sanitize(String name) {
        var result = new StringBuilder();
        for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_' || ch == '-') result.append(ch);
            else if (Character.isWhitespace(ch)) result.append('_');
        }
        int start = 0, end = result.length();
        while (start < end && (result.charAt(start) == '_' || result.charAt(start) == '-')) start++;
        while (end > start && (result.charAt(end - 1) == '_' || result.charAt(end - 1) == '-')) end--;
        return start == end ? "structure" : result.substring(start, end);
    }

    public static boolean edit(NBTTagCompound data, Field field, String value) {
        if (value == null || value.length() > 256) return false;
        if (field == Field.NAME || field == Field.ID)
            data.setString(field.key, field == Field.ID ? sanitize(value) : value);
        else if (field.toggle()) {
            if (!value.equals("true") && !value.equals("false")) return false;
            data.setBoolean(field.key, Boolean.parseBoolean(value));
        } else {
            if (!INTEGER.matcher(value).matches()) return false;
            long number = Long.parseLong(value);
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return false;
            int minimum = field == Field.QUANTITY ? 1 : field == Field.SPACING ? 0 : Integer.MIN_VALUE;
            data.setInteger(field.key, Math.max(minimum, (int) number));
        }
        return true;
    }

    public static @Nullable BlockPos position(NBTTagCompound data, String key) {
        if (!data.hasKey(key, 10)) return null;
        var pos = data.getCompoundTag(key);
        return new BlockPos(pos.getInteger("x"), pos.getInteger("y"), pos.getInteger("z"));
    }

    public static void position(NBTTagCompound data, String key, BlockPos pos) {
        var value = new NBTTagCompound();
        value.setInteger("x", pos.getX());
        value.setInteger("y", pos.getY());
        value.setInteger("z", pos.getZ());
        data.setTag(key, value);
    }

    public static void origin(NBTTagCompound data, BlockPos pos) {
        position(data, "exportOrigin", pos);
        data.setInteger(Field.X.key, pos.getX());
        data.setInteger(Field.Y.key, pos.getY());
        data.setInteger(Field.Z.key, pos.getZ());
    }

    public static @Nullable BlockPos origin(NBTTagCompound data) {
        var value = position(data, "exportOrigin");
        return value != null ? value : position(data, "origin");
    }

    /** Returns true when the click starts a fresh selection. */
    public static boolean select(NBTTagCompound data, BlockPos pos, int dimension, boolean restart, String name) {
        boolean fresh = restart || position(data, "origin") == null || data.getInteger("dimension") != dimension;
        if (fresh) {
            clear(data);
            position(data, "origin", pos);
            data.setInteger("dimension", dimension);
            data.setInteger(Field.X.key, pos.getX());
            data.setInteger(Field.Y.key, pos.getY());
            data.setInteger(Field.Z.key, pos.getZ());
            data.setString(Field.ID.key, sanitize(name));
        } else position(data, "corner", pos);
        data.removeTag("exportOrigin");
        data.setBoolean("exportOriginAuto", true);
        resetOrientation(data);
        return fresh;
    }

    public static void clear(NBTTagCompound data) {
        data.removeTag("origin");
        data.removeTag("corner");
        data.removeTag("exportOrigin");
    }

    public static void reset(NBTTagCompound data, String name) {
        for (String key : List.copyOf(data.getKeySet())) data.removeTag(key);
        defaults(data, name);
    }

    public static void resetOrientation(NBTTagCompound data) {
        data.setBoolean("previewAuto", true);
        data.setInteger("previewFacing", EnumFacing.NORTH.getIndex());
        data.setInteger("previewRot", 0);
        data.setBoolean(Field.PREVIEW_MIRROR.key, false);
    }

    public static BlockOrientation orientation(NBTTagCompound data) {
        int facing = data.getInteger("previewFacing");
        var front = facing >= 0 && facing < 6 ? EnumFacing.VALUES[facing] : EnumFacing.NORTH;
        var result = OrientationToolOrientations.canonical(front);
        for (int i = 0; i < Math.clamp(data.getInteger("previewRot"), 0, 3); i++)
            result = result.rotateClockwiseAround(front);
        return result;
    }

    public static void orientation(NBTTagCompound data, BlockOrientation orientation) {
        var canonical = OrientationToolOrientations.canonical(orientation.getSide(RelativeSide.FRONT));
        int twist = 0;
        while (twist < 3 && !canonical.equals(orientation)) {
            canonical = canonical.rotateClockwiseAround(orientation.getSide(RelativeSide.FRONT));
            twist++;
        }
        data.setInteger("previewFacing", orientation.getSide(RelativeSide.FRONT).getIndex());
        data.setInteger("previewRot", twist);
    }

    public static StructureTransform worldToTemplate(NBTTagCompound data) {
        return StructureTransform.of(orientation(data)).inverse();
    }

    public static @Nullable Box box(NBTTagCompound data) {
        var a = position(data, "origin");
        var b = position(data, "corner");
        return a == null || b == null ? null : new Box(a, b);
    }

    public record Box(BlockPos min, BlockPos max) {
        public Box {
            var a = min;
            var b = max;
            min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        public long sizeX() {
            return (long) max.getX() - min.getX() + 1;
        }

        public long sizeY() {
            return (long) max.getY() - min.getY() + 1;
        }

        public long sizeZ() {
            return (long) max.getZ() - min.getZ() + 1;
        }

        /** PM previews all cells up to 32768, then only the complete outer surface. */
        public Iterator<BlockPos> previewCells() {
            if (!exceeds(32768)) return previewPositions(32768).iterator();
            return new BoundaryIterator(min, max);
        }

        private static final class BoundaryIterator implements Iterator<BlockPos> {
            private final BlockPos min, max;
                int face;
                long u, v, lowV, highU, highV;
            private BoundaryIterator(BlockPos min, BlockPos max) {
                this.min = min;
                this.max = max;
                plane();
            }

                private void plane() {
                    while (face < 6) {
                        if (face < 2) {
                            u = min.getZ(); highU = max.getZ(); lowV = min.getX(); highV = max.getX();
                        } else {
                            u = (long) min.getY() + 1; highU = (long) max.getY() - 1;
                            lowV = face < 4 ? min.getX() : (long) min.getZ() + 1;
                            highV = face < 4 ? max.getX() : (long) max.getZ() - 1;
                        }
                        v = lowV;
                        boolean duplicate = face == 1 && min.getY() == max.getY()
                            || face == 3 && min.getZ() == max.getZ() || face == 5 && min.getX() == max.getX();
                        if (!duplicate && u <= highU && v <= highV) return;
                        face++;
                    }
                }

                @Override public boolean hasNext() { return face < 6; }

                @Override public BlockPos next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    var result = switch (face) {
                        case 0, 1 -> new BlockPos(v, face == 0 ? min.getY() : max.getY(), u);
                        case 2, 3 -> new BlockPos(v, u, face == 2 ? min.getZ() : max.getZ());
                        default -> new BlockPos(face == 4 ? min.getX() : max.getX(), u, v);
                    };
                    if (v < highV) v++;
                    else if (u < highU) { u++; v = lowV; }
                    else { face++; plane(); }
                    return result;
                }
        }

        /** Includes both box edges; work is bounded even for selections spanning the int coordinate range. */
        public List<BlockPos> previewPositions(int budget) {
            if (budget < 8) throw new IllegalArgumentException("Preview budget must include box corners");
            long low = 1, high = Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
            while (low < high) {
                long mid = low + (high - low) / 2;
                if (sampleCount(mid) > budget) low = mid + 1;
                else high = mid;
            }
            long nx = samples(sizeX(), low), ny = samples(sizeY(), low), nz = samples(sizeZ(), low);
            var result = new ObjectArrayList<BlockPos>();
            for (long x = 0; x < nx; x++)
                for (long y = 0; y < ny; y++)
                    for (long z = 0; z < nz; z++)
                        result.add(new BlockPos(Math.min(max.getX(), min.getX() + x * low), Math.min(max.getY(), min.getY() + y * low), Math.min(max.getZ(), min.getZ() + z * low)));
            return List.copyOf(result);
        }

        private double sampleCount(long stride) {
            return (double) samples(sizeX(), stride) * samples(sizeY(), stride) * samples(sizeZ(), stride);
        }

        private static long samples(long size, long stride) {
            return (size - 1 + stride - 1) / stride + 1;
        }

        public boolean exceeds(long limit) {
            return sizeX() > limit / sizeY() || sizeX() * sizeY() > limit / sizeZ();
        }
    }
}
