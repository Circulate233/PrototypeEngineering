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

    record Geometry(int dimension, long taskId, long anchor, boolean dismantle, long startTick, long serverTick, int wireTicks,
                    Section section, int quadrant, @Nullable ConstructionArea area, BitSet cells, BitSet fluids,
                    BitSet excluded, List<RegionTime> regions) implements ConstructionEffectPayload {
        public Geometry {
            requireTask(taskId);
            if (startTick < 0 || serverTick < startTick || wireTicks < MIN_INITIAL_WIRE_TICKS || wireTicks > MAX_INITIAL_WIRE_TICKS
                || quadrant < 0 || quadrant > 3
                || cells.length() > CHUNK_CELL_COUNT || fluids.length() > CHUNK_CELL_COUNT || excluded.length() > CHUNK_CELL_COUNT)
                throw new IllegalArgumentException("Invalid construction geometry bounds");
            if (area != null) {
                var unplanned = (BitSet) fluids.clone();
                unplanned.or(excluded);
                unplanned.andNot(cells);
                if (cells.isEmpty() || !unplanned.isEmpty()) throw new IllegalArgumentException("Invalid construction geometry snapshot");
            } else if (!cells.isEmpty() || !fluids.isEmpty() || regions.isEmpty() && excluded.isEmpty())
                throw new IllegalArgumentException("Invalid construction geometry delta");
            int seen = 0;
            for (var time : regions) {
                int mask = 1 << time.region();
                if ((seen & mask) != 0 || time.activatedTick() > serverTick || time.settledTick() > serverTick
                    || time.activatedTick() >= 0 && (!dismantle || time.activatedTick() < startTick)
                    || time.settledTick() >= 0 && time.settledTick() < startTick)
                    throw new IllegalArgumentException("Conflicting construction region time");
                seen |= mask;
            }
            cells = (BitSet) cells.clone();
            fluids = (BitSet) fluids.clone();
            excluded = (BitSet) excluded.clone();
            regions = List.copyOf(regions);
        }

        public boolean snapshot() {
            return area != null;
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

    record Forget(int dimension, long taskId, Section section, int quadrant) implements ConstructionEffectPayload {
        public Forget {
            requireTask(taskId);
            if (quadrant < 0 || quadrant > 3) throw new IllegalArgumentException("Invalid construction geometry quadrant");
        }
    }

    record Hud(int dimension, long taskId, long anchor, boolean dismantle, long startTick, long serverTick,
               ConstructionArea area, int missing, int errors, int unloaded, boolean paused, boolean complete) implements ConstructionEffectPayload {
        public Hud {
            requireTask(taskId);
            Objects.requireNonNull(area);
            if (startTick < 0 || serverTick < 0 || missing < 0 || errors < 0 || unloaded < 0)
                throw new IllegalArgumentException("Invalid construction status");
        }
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

    static void write(ByteBuf buffer, ConstructionEffectPayload payload) {
        int start = buffer.writerIndex();
        int kind = switch (payload) {
            case Geometry geometry -> geometry.snapshot() ? 0 : 1;
            case Forget _ -> 2;
            case Hud _ -> 3;
            case End _ -> 4;
        };
        buffer.writeByte(kind).writeInt(payload.dimension()).writeLong(payload.taskId());
        switch (payload) {
            case Geometry geometry -> {
                buffer.writeLong(geometry.anchor).writeBoolean(geometry.dismantle).writeLong(geometry.startTick).writeLong(geometry.serverTick)
                    .writeByte(geometry.wireTicks);
                writeSection(buffer, geometry.section, geometry.quadrant);
                if (geometry.area != null) {
                    writeArea(buffer, geometry.area);
                    writeCells(buffer, geometry.cells);
                    writeCells(buffer, geometry.fluids);
                }
                writeCells(buffer, geometry.excluded);
                buffer.writeByte(geometry.regions.size());
                for (var time : geometry.regions)
                    buffer.writeByte(time.region()).writeLong(time.activatedTick()).writeLong(time.settledTick());
            }
            case Forget forget -> writeSection(buffer, forget.section, forget.quadrant);
            case Hud hud -> {
                buffer.writeLong(hud.anchor).writeBoolean(hud.dismantle).writeLong(hud.startTick).writeLong(hud.serverTick);
                writeArea(buffer, hud.area);
                buffer.writeInt(hud.missing).writeInt(hud.errors).writeInt(hud.unloaded).writeBoolean(hud.paused).writeBoolean(hud.complete);
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
            case 0, 1 -> {
                long anchor = buffer.readLong();
                boolean dismantle = readBoolean(buffer);
                long startTick = buffer.readLong();
                long serverTick = buffer.readLong();
                int wireTicks = buffer.readUnsignedByte();
                var section = readSection(buffer);
                int quadrant = buffer.readUnsignedByte();
                var area = kind == 0 ? readArea(buffer) : null;
                BitSet cells = kind == 0 ? readCells(buffer) : new BitSet();
                BitSet fluids = kind == 0 ? readCells(buffer) : new BitSet();
                var excluded = readCells(buffer);
                int count = buffer.readUnsignedByte();
                if (count > REGION_COUNT) throw new IllegalArgumentException("Invalid construction region count");
                var regions = new ArrayList<RegionTime>(count);
                for (int i = 0; i < count; i++) regions.add(new RegionTime(buffer.readUnsignedByte(), buffer.readLong(), buffer.readLong()));
                yield new Geometry(dimension, task, anchor, dismantle, startTick, serverTick, wireTicks, section, quadrant, area, cells, fluids,
                    excluded, regions);
            }
            case 2 -> new Forget(dimension, task, readSection(buffer), buffer.readUnsignedByte());
            case 3 -> new Hud(dimension, task, buffer.readLong(), readBoolean(buffer), buffer.readLong(), buffer.readLong(),
                readArea(buffer), buffer.readInt(), buffer.readInt(), buffer.readInt(), readBoolean(buffer), readBoolean(buffer));
            case 4 -> new End(dimension, task, buffer.readLong(), readBoolean(buffer), readBoolean(buffer));
            default -> throw new IllegalArgumentException("Unknown construction message kind");
        };
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing construction message bytes");
        return result;
    }

    static int bytes(ConstructionEffectPayload payload) {
        return HEADER_BYTES + switch (payload) {
            case Geometry geometry -> 37 + (geometry.snapshot() ? AREA_BYTES + cellBytes(geometry.cells) + cellBytes(geometry.fluids) : 0)
                + cellBytes(geometry.excluded) + geometry.regions.size() * (1 + 2 * Long.BYTES);
            case Forget _ -> 10;
            case Hud _ -> 39 + AREA_BYTES;
            case End _ -> 10;
        };
    }

    private static void requireTask(long taskId) {
        if (taskId <= 0) throw new IllegalArgumentException("Invalid construction task id");
    }

    private static boolean readBoolean(ByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid construction message flag");
        return value != 0;
    }

    private static ConstructionArea readArea(ByteBuf buffer) {
        return new ConstructionArea(BlockPos.fromLong(buffer.readLong()), BlockPos.fromLong(buffer.readLong()));
    }

    private static void writeArea(ByteBuf buffer, ConstructionArea area) {
        buffer.writeLong(area.min().toLong()).writeLong(area.max().toLong());
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
