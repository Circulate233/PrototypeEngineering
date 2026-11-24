package com.circulation.prototype_eng.structure.tool.effect;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Arrays;

import static com.circulation.prototype_eng.util.BlockPos2ObjectMap.toLong;
import static com.circulation.prototype_eng.util.BlockPos2ObjectMap.unpackX;
import static com.circulation.prototype_eng.util.BlockPos2ObjectMap.unpackY;
import static com.circulation.prototype_eng.util.BlockPos2ObjectMap.unpackZ;

/** Groups nearby face-connected construction positions into shared animation stages. */
public final class ConstructionChunkPlan {
    public static final int TARGET_BLOCKS = 500;
    public static final int MAX_CHUNKS = 32;

    private final int[] ids;
    private final int size;

    private ConstructionChunkPlan(int[] ids, int size) {
        this.ids = ids;
        this.size = size;
    }

    public int size() {
        return size;
    }

    public int chunkAt(int index) {
        return ids[index] - 1;
    }

    static final class Builder {
        private final LongArrayList positions;
        private final int count;
        private final Long2IntOpenHashMap indexByPosition;
        private final int[] queuedFor;
        private final int[] ids;
        private final int[] queue;
        private final int[] sizes = new int[MAX_CHUNKS];
        private int indexed;
        private int assigned;
        private int nextSeed;
        private int chunks;
        private int capacity;
        private int head;
        private int tail;
        private int visit;
        private int adjacent;
        private int supportFloor;
        private int mergeInto;
        private int mergeCursor;
        private boolean growing;
        private Mode mode = Mode.CONNECTED;

        Builder(LongArrayList positions) {
            this.positions = positions;
            count = positions.size();
            indexByPosition = new Long2IntOpenHashMap(count);
            indexByPosition.defaultReturnValue(-1);
            ids = new int[count];
            queuedFor = new int[count];
            queue = new int[count];
        }

        boolean step() {
            if (indexed < count) {
                if (indexByPosition.putIfAbsent(positions.getLong(indexed), indexed) != -1)
                    throw new IllegalArgumentException("Duplicate construction position");
                indexed++;
                return false;
            }
            if (mergeInto != 0) {
                ids[queue[mergeCursor++]] = mergeInto;
                if (mergeCursor == head) mergeInto = 0;
                return false;
            }
            if (!growing) {
                if (assigned == count) return true;
                if (chunks == MAX_CHUNKS) {
                    Arrays.fill(ids, 0);
                    Arrays.fill(sizes, 0);
                    assigned = nextSeed = chunks = 0;
                    mode = mode == Mode.CONNECTED ? Mode.MERGE_ADJACENT : Mode.FILL;
                }
                capacity = Math.max(TARGET_BLOCKS, Math.ceilDiv(count - assigned, MAX_CHUNKS - chunks));
                head = tail = 0;
                adjacent = supportFloor = 0;
                visit++;
                growing = true;
                enqueue(nextUnassigned());
            }
            if (head == capacity || head == tail && (mode != Mode.FILL || assigned == count)) {
                finishGroup();
                return false;
            }
            if (head == tail) enqueue(nextUnassigned());
            assignNext();
            return false;
        }

        ConstructionChunkPlan result() {
            if (assigned != count) throw new IllegalStateException("Construction chunk plan is not complete");
            return new ConstructionChunkPlan(ids, chunks);
        }

        private void assignNext() {
            int index = queue[head++];
            ids[index] = chunks + 1;
            assigned++;
            if (head == capacity) return;

            long packed = positions.getLong(index);
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            enqueue(indexByPosition.get(toLong(x + 1, y, z)));
            enqueue(indexByPosition.get(toLong(x - 1, y, z)));
            enqueue(indexByPosition.get(toLong(x, y, z + 1)));
            enqueue(indexByPosition.get(toLong(x, y, z - 1)));
            enqueue(indexByPosition.get(toLong(x, y + 1, z)));
            int support = indexByPosition.get(toLong(x, y - 1, z));
            enqueue(support);
            if (mode == Mode.MERGE_ADJACENT && support >= 0 && ids[support] <= chunks)
                supportFloor = Math.max(supportFloor, ids[support]);
        }

        private void enqueue(int index) {
            if (index < 0) return;
            int group = ids[index];
            if (group != 0) {
                if (mode == Mode.MERGE_ADJACENT && group <= chunks) adjacent |= 1 << (group - 1);
                return;
            }
            if (queuedFor[index] == visit) return;
            long pos = positions.getLong(index);
            int support = indexByPosition.get(toLong(unpackX(pos), unpackY(pos) - 1, unpackZ(pos)));
            if (support >= 0 && ids[support] == 0) return;
            queue[tail++] = index;
            queuedFor[index] = visit;
        }

        private void finishGroup() {
            growing = false;
            if (mode == Mode.MERGE_ADJACENT && head < capacity) {
                int mergeCapacity = capacity + capacity / 3;
                for (int candidates = adjacent; candidates != 0; candidates &= candidates - 1) {
                    int group = Integer.numberOfTrailingZeros(candidates) + 1;
                    int size = sizes[group - 1];
                    if (group >= supportFloor && size + head <= mergeCapacity && (mergeInto == 0 || size < sizes[mergeInto - 1]))
                        mergeInto = group;
                }
                if (mergeInto != 0) {
                    sizes[mergeInto - 1] += head;
                    mergeCursor = 0;
                    return;
                }
            }
            sizes[chunks++] = head;
        }

        private int nextUnassigned() {
            while (ids[nextSeed] != 0) nextSeed++;
            return nextSeed++;
        }

        private enum Mode {CONNECTED, MERGE_ADJACENT, FILL}
    }
}
