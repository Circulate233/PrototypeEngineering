package com.circulation.prototype_eng.structure.tool.effect;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/** Geometry is a set of unit cells. The receiver emits their boundary vertices, omitting internal faces. */
public sealed interface ConstructionEffectPayload {
    int MAX_BYTES = 16 * 1024 - 1; // Forge also writes the one-byte message discriminator.
    int WHITE_TRANSITION_TICKS = 3;
    int REGION_FADE_TICKS = 8;
    int FINAL_WIRE_TICKS = 20;
    int MIN_INITIAL_WIRE_TICKS = 6;
    int MAX_INITIAL_WIRE_TICKS = 28;
    int LAYER_HEIGHT = 8;
    int LAYERS_PER_SECTION = 32 / LAYER_HEIGHT;
    int REGION_COUNT = LAYERS_PER_SECTION * 2;
    int CELL_COUNT = 32 * 32 * 32;
    int CHUNK_CELL_COUNT = CELL_COUNT / 4;
    int HEADER_BYTES = 1 + Integer.BYTES + Long.BYTES;
    int AREA_BYTES = 2 * Long.BYTES;

    int dimension();

    long taskId();

    static int wireTicks(int cells) {
        int log = Integer.SIZE - 1 - Integer.numberOfLeadingZeros(Math.max(1, cells));
        return Math.min(MAX_INITIAL_WIRE_TICKS, MIN_INITIAL_WIRE_TICKS + 2 * log);
    }

    static int region(int cell, boolean fluid) {
        return (cell >>> 8) / LAYER_HEIGHT + (fluid ? LAYERS_PER_SECTION : 0);
    }

    record RegionTime(int region, long activatedTick, long settledTick) {
        public RegionTime {
            if (region < 0 || region >= REGION_COUNT || activatedTick < -1 || settledTick < -1
                || activatedTick < 0 && settledTick < 0 || settledTick >= 0 && settledTick < activatedTick)
                throw new IllegalArgumentException("Invalid construction region time");
        }
    }

    static void write(ByteBuf buffer, ConstructionEffectPayload payload) {
        int start = buffer.writerIndex();
        int kind = switch (payload) {
            case Geometry geometry -> geometry.snapshot ? (geometry.layout != null ? 0 : 6) : 1;
            case Visibility visibility -> visibility.visible ? 7 : 2;
            case Hud hud -> hud.layout == null ? 3 : 5;
            case End _ -> 4;
        };
        buffer.writeByte(kind).writeInt(payload.dimension()).writeLong(payload.taskId());
        switch (payload) {
            case Geometry geometry -> {
                buffer.writeLong(geometry.serverTick);
                writeSection(buffer, geometry.section, geometry.quadrant);
                var layout = geometry.layout;
                if (geometry.snapshot) {
                    if (layout != null) writeLayout(buffer, layout);
                    writeCells(buffer, geometry.cells);
                    writeCells(buffer, geometry.fluids);
                } else writeCells(buffer, geometry.excluded);
                buffer.writeByte(geometry.regions.size());
                for (var time : geometry.regions) {
                    boolean activate = time.activatedTick() >= 0;
                    boolean settle = time.settledTick() >= 0;
                    buffer.writeByte(time.region() | (activate ? 8 : 0) | (settle ? 16 : 0));
                    if (activate) buffer.writeLong(time.activatedTick());
                    if (settle) buffer.writeLong(time.settledTick());
                }
            }
            case Visibility _ -> {
            }
            case Hud hud -> {
                if (hud.layout != null) writeLayout(buffer, hud.layout);
                var status = hud.status;
                buffer.writeInt(status.missing).writeInt(status.errors).writeInt(status.unloaded).writeBoolean(status.paused);
            }
            case End end -> buffer.writeLong(end.serverTick).writeBoolean(end.cancelled).writeBoolean(end.successful);
        }
        if (buffer.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Construction geometry exceeds packet limit");
    }

    static ConstructionEffectPayload read(ByteBuf buffer) {
        int bytes = buffer.readableBytes();
        if (bytes < HEADER_BYTES || bytes > MAX_BYTES) throw new IllegalArgumentException("Invalid construction message length");
        int kind = buffer.readUnsignedByte();
        int dimension = buffer.readInt();
        long task = buffer.readLong();
        ConstructionEffectPayload result = switch (kind) {
            case 0, 1, 6 -> {
                long serverTick = buffer.readLong();
                var section = readSection(buffer);
                int quadrant = buffer.readUnsignedByte();
                boolean snapshot = kind != 1;
                var layout = kind == 0 ? readLayout(buffer) : null;
                BitSet cells = snapshot ? readCells(buffer) : new BitSet();
                BitSet fluids = snapshot ? readCells(buffer) : new BitSet();
                var excluded = snapshot ? new BitSet() : readCells(buffer);
                int count = buffer.readUnsignedByte();
                if (count > REGION_COUNT) throw new IllegalArgumentException("Invalid construction region count");
                var regions = new ArrayList<RegionTime>(count);
                for (int i = 0; i < count; i++) {
                    int flags = buffer.readUnsignedByte();
                    if ((flags & ~31) != 0) throw new IllegalArgumentException("Invalid construction region flags");
                    regions.add(new RegionTime(flags & 7, (flags & 8) == 0 ? -1 : buffer.readLong(), (flags & 16) == 0 ? -1 : buffer.readLong()));
                }
                yield new Geometry(dimension, task, serverTick, section, quadrant, snapshot, layout, cells, fluids, excluded, regions);
            }
            case 2, 7 -> new Visibility(dimension, task, kind == 7);
            case 3, 5 -> new Hud(dimension, task, kind == 5 ? readLayout(buffer) : null,
                new Status(buffer.readInt(), buffer.readInt(), buffer.readInt(), readBoolean(buffer)));
            case 4 -> new End(dimension, task, buffer.readLong(), readBoolean(buffer), readBoolean(buffer));
            default -> throw new IllegalArgumentException("Unknown construction message kind");
        };
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing construction message bytes");
        return result;
    }

    static int bytes(ConstructionEffectPayload payload) {
        return HEADER_BYTES + switch (payload) {
            case Geometry geometry -> geometryBytes(geometry);
            case Visibility _ -> 0;
            case Hud hud -> 13 + (hud.layout != null ? 18 + AREA_BYTES : 0);
            case End _ -> 10;
        };
    }

    private static int geometryBytes(Geometry geometry) {
        int bytes = 19 + (geometry.snapshot ? cellBytes(geometry.cells) + cellBytes(geometry.fluids) : cellBytes(geometry.excluded));
        if (geometry.layout != null) bytes += 18 + AREA_BYTES;
        for (var time : geometry.regions)
            bytes += 1 + (time.activatedTick() >= 0 ? Long.BYTES : 0) + (time.settledTick() >= 0 ? Long.BYTES : 0);
        return bytes;
    }

    private static Layout readLayout(ByteBuf buffer) {
        return new Layout(buffer.readLong(), readBoolean(buffer), buffer.readLong(), buffer.readUnsignedByte(),
            new ConstructionArea(BlockPos.fromLong(buffer.readLong()), BlockPos.fromLong(buffer.readLong())));
    }

    record End(int dimension, long taskId, long serverTick, boolean cancelled,
               boolean successful) implements ConstructionEffectPayload {
        public End {
            requireTask(taskId);
            if (serverTick < 0 || cancelled && successful) throw new IllegalArgumentException("Invalid construction end status");
        }
    }

    record Section(int x, int y, int z) {
        public Section {
            if (x < -937500 || x > 937499 || z < -937500 || z > 937499 || y < 0 || y > 7)
                throw new IllegalArgumentException("Invalid construction section");
        }

        public static Section at(BlockPos pos) {
            return new Section(pos.getX() >> 5, pos.getY() >> 5, pos.getZ() >> 5);
        }

        public static int quadrant(BlockPos pos) {
            return ((pos.getX() >> 4) & 1) | (((pos.getZ() >> 4) & 1) << 1);
        }

        public static int index(BlockPos pos) {
            return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 31) << 8);
        }

        public int chunkX(int quadrant) {
            return x * 2 + (quadrant & 1);
        }

        public int chunkZ(int quadrant) {
            return z * 2 + (quadrant >>> 1);
        }
    }

    private static void writeLayout(ByteBuf buffer, Layout layout) {
        buffer.writeLong(layout.anchor).writeBoolean(layout.dismantle).writeLong(layout.startTick).writeByte(layout.wireTicks);
        buffer.writeLong(layout.area.min().toLong()).writeLong(layout.area.max().toLong());
    }

    record Layout(long anchor, boolean dismantle, long startTick, int wireTicks, ConstructionArea area) {
        public Layout {
            Objects.requireNonNull(area);
            if (startTick < 0 || wireTicks < MIN_INITIAL_WIRE_TICKS || wireTicks > MAX_INITIAL_WIRE_TICKS)
                throw new IllegalArgumentException("Invalid construction layout");
        }
    }

    record Geometry(int dimension, long taskId, long serverTick, Section section, int quadrant, boolean snapshot,
                    @Nullable Layout layout, BitSet cells, BitSet fluids,
                    BitSet excluded, List<RegionTime> regions) implements ConstructionEffectPayload {
        public Geometry {
            requireTask(taskId);
            if (serverTick < 0 || quadrant < 0 || quadrant > 3
                || cells.length() > CHUNK_CELL_COUNT || fluids.length() > CHUNK_CELL_COUNT || excluded.length() > CHUNK_CELL_COUNT)
                throw new IllegalArgumentException("Invalid construction geometry bounds");
            if (snapshot) {
                var unplanned = (BitSet) fluids.clone();
                unplanned.andNot(cells);
                if (layout != null && (serverTick < layout.startTick || cells.isEmpty()) || !unplanned.isEmpty() || !excluded.isEmpty())
                    throw new IllegalArgumentException("Invalid construction geometry snapshot");
            } else if (layout != null || !cells.isEmpty() || !fluids.isEmpty() || regions.isEmpty() && excluded.isEmpty())
                throw new IllegalArgumentException("Invalid construction geometry delta");
            int seen = 0;
            for (var time : regions) {
                int mask = 1 << time.region();
                if ((seen & mask) != 0 || time.activatedTick() > serverTick || time.settledTick() > serverTick
                    || layout != null && (time.activatedTick() >= 0 && (!layout.dismantle || time.activatedTick() < layout.startTick)
                    || time.settledTick() >= 0 && time.settledTick() < layout.startTick))
                    throw new IllegalArgumentException("Conflicting construction region time");
                seen |= mask;
            }
            cells = (BitSet) cells.clone();
            fluids = (BitSet) fluids.clone();
            excluded = (BitSet) excluded.clone();
            regions = List.copyOf(regions);
        }

        public int sectionIndex(int index) {
            return sectionIndex(quadrant, index);
        }

        public static int sectionIndex(int quadrant, int index) {
            int x = (index & 15) | ((quadrant & 1) << 4);
            int y = index >>> 8;
            int z = ((index >>> 4) & 15) | ((quadrant & 2) << 3);
            return x | (z << 5) | (y << 10);
        }
    }

    record Visibility(int dimension, long taskId, boolean visible) implements ConstructionEffectPayload {
        public Visibility {
            requireTask(taskId);
        }
    }

    private static void requireTask(long taskId) {
        if (taskId <= 0) throw new IllegalArgumentException("Invalid construction task id");
    }

    private static boolean readBoolean(ByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid construction message flag");
        return value != 0;
    }

    record Status(int missing, int errors, int unloaded, boolean paused) {
        public Status {
            if (missing < 0 || errors < 0 || unloaded < 0)
                throw new IllegalArgumentException("Invalid construction status");
        }
    }

    record Hud(int dimension, long taskId, @Nullable Layout layout,
               Status status) implements ConstructionEffectPayload {
        public Hud {
            requireTask(taskId);
            Objects.requireNonNull(status);
        }
    }

    private static Section readSection(ByteBuf buffer) {
        return new Section(buffer.readInt(), buffer.readUnsignedByte(), buffer.readInt());
    }

    private static void writeSection(ByteBuf buffer, Section section, int quadrant) {
        buffer.writeInt(section.x).writeByte(section.y).writeInt(section.z).writeByte(quadrant);
    }

    private static int cellBytes(BitSet cells) {
        return 2 + Math.min(cells.cardinality() * Short.BYTES, CHUNK_CELL_COUNT / Byte.SIZE);
    }

    private static void writeCells(ByteBuf buffer, BitSet cells) {
        int count = cells.cardinality();
        int bitmapBytes = CHUNK_CELL_COUNT / Byte.SIZE;
        if (count * Short.BYTES <= bitmapBytes) {
            buffer.writeShort(count);
            for (int index = cells.nextSetBit(0); index >= 0; index = cells.nextSetBit(index + 1)) buffer.writeShort(index);
        } else {
            buffer.writeShort(0xffff);
            byte[] values = cells.toByteArray();
            buffer.writeBytes(values).writeZero(bitmapBytes - values.length);
        }
    }

    private static BitSet readCells(ByteBuf buffer) {
        int count = buffer.readUnsignedShort();
        int bitmapBytes = CHUNK_CELL_COUNT / Byte.SIZE;
        if (count == 0xffff) {
            byte[] values = new byte[bitmapBytes];
            buffer.readBytes(values);
            var cells = BitSet.valueOf(values);
            if (cells.cardinality() * Short.BYTES <= bitmapBytes) throw new IllegalArgumentException("Noncanonical construction geometry bitmap");
            return cells;
        }
        if (count * Short.BYTES > bitmapBytes || buffer.readableBytes() < count * Short.BYTES)
            throw new IllegalArgumentException("Invalid construction geometry cell count");
        var cells = new BitSet();
        for (int i = 0, previous = -1; i < count; i++) {
            int index = buffer.readUnsignedShort();
            if (index <= previous || index >= CHUNK_CELL_COUNT) throw new IllegalArgumentException("Invalid construction geometry cell index");
            previous = index;
            cells.set(index);
        }
        return cells;
    }
}
