package com.circulation.prototype_eng.structure.tool.effect;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Objects;

/** Geometry is a set of unit cells. The receiver emits their boundary vertices, omitting internal faces. */
public sealed interface ConstructionEffectPayload {
    int MAX_BYTES = 16 * 1024 - 1; // Forge also writes the one-byte message discriminator.
    int INTRO_TICKS = 24;
    int FINISH_TICKS = 68;
    int CELL_COUNT = 32 * 32 * 32;
    int CHUNK_CELL_COUNT = CELL_COUNT / 4;
    int HEADER_BYTES = 1 + Integer.BYTES + Long.BYTES;
    int AREA_BYTES = 2 * Long.BYTES;

    int dimension();

    long taskId();

    record Geometry(int dimension, long taskId, long anchor, boolean dismantle, long startTick, long serverTick,
                    Section section, int quadrant, @Nullable ConstructionArea area, BitSet cells,
                    BitSet committed, BitSet excluded) implements ConstructionEffectPayload {
        public Geometry {
            requireTask(taskId);
            if (startTick < 0 || serverTick < 0 || quadrant < 0 || quadrant > 3 || cells.length() > CHUNK_CELL_COUNT
                || committed.length() > CHUNK_CELL_COUNT || excluded.length() > CHUNK_CELL_COUNT)
                throw new IllegalArgumentException("Invalid construction geometry bounds");
            if (area != null) {
                var unplanned = (BitSet) committed.clone();
                unplanned.or(excluded);
                unplanned.andNot(cells);
                if (cells.isEmpty() || !unplanned.isEmpty()) throw new IllegalArgumentException("Invalid construction geometry snapshot");
            } else if (!cells.isEmpty() || committed.isEmpty() && excluded.isEmpty())
                throw new IllegalArgumentException("Invalid construction geometry delta");
            if (committed.intersects(excluded)) throw new IllegalArgumentException("Conflicting construction geometry delta");
            cells = (BitSet) cells.clone();
            committed = (BitSet) committed.clone();
            excluded = (BitSet) excluded.clone();
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
                buffer.writeLong(geometry.anchor).writeBoolean(geometry.dismantle).writeLong(geometry.startTick).writeLong(geometry.serverTick);
                writeSection(buffer, geometry.section, geometry.quadrant);
                if (geometry.area != null) {
                    writeArea(buffer, geometry.area);
                    writeCells(buffer, geometry.cells);
                }
                writeCells(buffer, geometry.committed);
                writeCells(buffer, geometry.excluded);
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
                var section = readSection(buffer);
                int quadrant = buffer.readUnsignedByte();
                var area = kind == 0 ? readArea(buffer) : null;
                BitSet cells = kind == 0 ? readCells(buffer) : new BitSet();
                yield new Geometry(dimension, task, anchor, dismantle, startTick, serverTick, section, quadrant, area, cells, readCells(buffer), readCells(buffer));
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
            case Geometry geometry -> 35 + (geometry.snapshot() ? AREA_BYTES + cellBytes(geometry.cells) : 0) + cellBytes(geometry.committed) + cellBytes(geometry.excluded);
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
