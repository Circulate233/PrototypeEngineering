package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.PrototypeEngineering;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayDeque;

final class ConstructionLightingBatch {
    private final Reference2ObjectLinkedOpenHashMap<Chunk, Long2LongLinkedOpenHashMap> lighting = new Reference2ObjectLinkedOpenHashMap<>();
    private final Reference2ObjectLinkedOpenHashMap<Chunk, Long2ObjectLinkedOpenHashMap<Notification>> notifications = new Reference2ObjectLinkedOpenHashMap<>();
    private final ArrayDeque<Long2LongLinkedOpenHashMap> freeLights = new ArrayDeque<>();
    private final ArrayDeque<Long2ObjectLinkedOpenHashMap<Notification>> freeNotifications = new ArrayDeque<>();

    static long lightProperties(IBlockState state, World world, BlockPos pos) {
        return (long) state.getLightOpacity(world, pos) << 32 | Integer.toUnsignedLong(state.getLightValue(world, pos));
    }

    private static int opacity(long properties) {
        return (int) (properties >> 32);
    }

    void light(Chunk chunk, BlockPos pos, long before, long after) {
        if (before == after) return;
        var checks = lighting.get(chunk);
        if (checks == null) {
            checks = freeLights.pollLast();
            if (checks == null) checks = new Long2LongLinkedOpenHashMap();
            lighting.put(chunk, checks);
        }
        checks.putIfAbsent(pos.toLong(), before);
    }

    void notify(Chunk chunk, BlockPos pos, IBlockState before, IBlockState after) {
        var updates = notifications.get(chunk);
        if (updates == null) {
            updates = freeNotifications.pollLast();
            if (updates == null) updates = new Long2ObjectLinkedOpenHashMap<>();
            notifications.put(chunk, updates);
        }
        long position = pos.toLong();
        var existing = updates.get(position);
        if (existing == null) updates.put(position, new Notification(pos.toImmutable(), before, after));
        else if (existing.before == after) {
            updates.remove(position);
            if (updates.isEmpty()) {
                notifications.remove(chunk);
                freeNotifications.addLast(updates);
            }
        } else existing.after = after;
    }

    boolean hasPending() {
        return !lighting.isEmpty() || hasNotifications();
    }

    boolean hasNotifications() {
        return !notifications.isEmpty();
    }

    void flushNotifications() {
        while (!notifications.isEmpty()) {
            var chunk = notifications.firstKey();
            var updates = notifications.removeFirst();
            if (chunk.isLoaded()) {
                var world = chunk.getWorld();
                for (var update : updates.values()) {
                    try {
                        if (update.after.equals(chunk.getBlockState(update.pos))) {
                            world.markAndNotifyBlock(update.pos, chunk, update.before, update.after, Constants.BlockFlags.DEFAULT);
                        }
                    } catch (RuntimeException failure) {
                        PrototypeEngineering.LOGGER.error("Construction notification failed at {} in dimension {}", update.pos, world.provider.getDimension(), failure);
                    }
                }
            }
            updates.clear();
            freeNotifications.addLast(updates);
        }
    }

    void flushLighting() {
        while (!lighting.isEmpty()) {
            var chunk = lighting.firstKey();
            var checks = lighting.removeFirst();
            if (chunk.isLoaded()) {
                var positions = checks.long2LongEntrySet().fastIterator();
                while (positions.hasNext()) {
                    var entry = positions.next();
                    settleLight(chunk, BlockPos.fromLong(entry.getLongKey()), entry.getLongValue());
                }
            }
            checks.clear();
            freeLights.addLast(checks);
        }
    }

    private static void settleLight(Chunk chunk, BlockPos pos, long before) {
        var world = chunk.getWorld();
        try {
            long after = lightProperties(chunk.getBlockState(pos), world, pos);
            if (before == after) return;
            int opacity = opacity(after);
            // Vanilla only propagates a light change when the source differs from its computed brightness.
            if (opacity(before) != opacity && world.provider.hasSkyLight()) {
                int fixed = chunk.canSeeSky(pos) ? 15 : opacity >= 15 ? 0 : -1;
                if (fixed < 0 || fixed != chunk.getLightFor(EnumSkyBlock.SKY, pos)) world.checkLightFor(EnumSkyBlock.SKY, pos);
            }
            if (opacity >= 15 && (int) after == 0 && chunk.getLightFor(EnumSkyBlock.BLOCK, pos) == 0) return;
            world.checkLightFor(EnumSkyBlock.BLOCK, pos);
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Construction lighting failed at {} in dimension {}", pos, world.provider.getDimension(), failure);
        }
    }

    void clear(World world) {
        lighting.keySet().removeIf(chunk -> chunk.getWorld() == world);
        notifications.keySet().removeIf(chunk -> chunk.getWorld() == world);
    }

    void clear() {
        lighting.clear();
        notifications.clear();
        freeLights.clear();
        freeNotifications.clear();
    }

    private static final class Notification {
        final BlockPos pos;
        final IBlockState before;
        IBlockState after;

        Notification(BlockPos pos, IBlockState before, IBlockState after) {
            this.pos = pos;
            this.before = before;
            this.after = after;
        }
    }
}
