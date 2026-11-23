package com.circulation.prototype_eng.structure.tool;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionLightingBatchTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void intermediateWritesAndRollbackUseTheFinalChunkStateAtAFullCoordinate() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        var batch = new ConstructionLightingBatch();
        var rolledBack = new BlockPos(-16, 71, 33);
        var committed = rolledBack.up();
        var external = committed.east();
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        var snapshot = ConstructionPlacement.snapshot(world, rolledBack, air);
        write(chunk, batch, rolledBack, stone);
        ConstructionPlacement.restore(world, chunk, snapshot, stone, batch);
        assertEquals(air, chunk.getBlockState(rolledBack));
        assertFalse(world.restoringBlockSnapshots);
        write(chunk, batch, committed, Blocks.STONE.getDefaultState());
        write(chunk, batch, committed, Blocks.GLOWSTONE.getDefaultState());
        write(chunk, batch, external, Blocks.STONE.getDefaultState());
        chunk.setBlockState(external, Blocks.AIR.getDefaultState());

        batch.flushLighting();

        assertEquals(List.of(new LightCheck(EnumSkyBlock.SKY, committed), new LightCheck(EnumSkyBlock.BLOCK, committed)), world.checks);
        assertFalse(batch.hasPending());
    }

    @Test
    void anEmissionChangeAtTheSameOpacityOnlyUpdatesBlockLight() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        var batch = new ConstructionLightingBatch();
        var pos = new BlockPos(-12, 71, 35);
        chunk.setBlockState(pos, Blocks.STONE.getDefaultState());
        write(chunk, batch, pos, Blocks.GLOWSTONE.getDefaultState());

        batch.flushLighting();
        assertEquals(List.of(new LightCheck(EnumSkyBlock.BLOCK, pos)), world.checks);
        assertFalse(batch.hasPending());
    }

    @Test
    void alreadySettledOpaqueBlocksSkipLightPropagation() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        chunk.skyLight = 0;
        chunk.blockLight = 0;
        var batch = new ConstructionLightingBatch();
        var pos = new BlockPos(-12, 71, 35);
        write(chunk, batch, pos, Blocks.STONE.getDefaultState());
        write(chunk, batch, pos.up(), Blocks.GLOWSTONE.getDefaultState());

        batch.flushLighting();

        assertEquals(List.of(new LightCheck(EnumSkyBlock.BLOCK, pos.up())), world.checks);
        assertFalse(batch.hasPending());
    }

    @Test
    void deferredNativeNotificationsMergeOnlyTheSameSourceAndDoNotBorrowMutablePositions() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        var batch = new ConstructionLightingBatch();
        var first = new BlockPos(-10, 65, 34);
        var second = first.east();
        var cursor = new BlockPos.MutableBlockPos(first);
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        var brick = Blocks.BRICK_BLOCK.getDefaultState();
        chunk.setBlockState(first, stone);
        ConstructionPlacement.notify(world, cursor, chunk, air, stone, batch);
        cursor.setPos(second.getX(), second.getY(), second.getZ());
        chunk.setBlockState(second, brick);
        ConstructionPlacement.notify(world, cursor, chunk, air, brick, batch);
        chunk.setBlockState(first, brick);
        ConstructionPlacement.notify(world, first, chunk, stone, brick, batch);
        var unchanged = second.up();
        chunk.setBlockState(unchanged, stone);
        ConstructionPlacement.notify(world, unchanged, chunk, air, stone, batch);
        chunk.setBlockState(unchanged, air);
        ConstructionPlacement.notify(world, unchanged, chunk, stone, air, batch);

        assertTrue(world.notifications.isEmpty());
        assertTrue(batch.hasNotifications());
        batch.flushNotifications();
        assertEquals(List.of(new Change(first, air, brick), new Change(second, air, brick)), world.notifications);
        assertFalse(batch.hasPending());
    }

    @Test
    void aNativeReplacementInvalidatesOnlyTheSupersededSourceNotice() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        var batch = new ConstructionLightingBatch();
        var first = new BlockPos(-10, 65, 34);
        var second = first.east();
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        chunk.setBlockState(first, stone);
        ConstructionPlacement.notify(world, first, chunk, air, stone, batch);
        chunk.setBlockState(second, stone);
        ConstructionPlacement.notify(world, second, chunk, air, stone, batch);
        chunk.setBlockState(first, Blocks.DIRT.getDefaultState());

        batch.flushNotifications();

        assertEquals(List.of(new Change(second, air, stone)), world.notifications);
        assertFalse(batch.hasPending());
    }

    @Test
    void settlingAnotherTickDoesNotReplayEarlierLightingOrNotifications() {
        var world = new TestWorld();
        var chunk = new TestChunk(world);
        var batch = new ConstructionLightingBatch();
        var first = new BlockPos(-12, 71, 35);
        var second = first.up();
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        write(chunk, batch, first, stone);
        ConstructionPlacement.notify(world, first, chunk, air, stone, batch);
        batch.flushLighting();
        batch.flushNotifications();
        write(chunk, batch, second, stone);
        ConstructionPlacement.notify(world, second, chunk, air, stone, batch);
        batch.flushLighting();
        batch.flushNotifications();

        assertEquals(List.of(new LightCheck(EnumSkyBlock.SKY, first), new LightCheck(EnumSkyBlock.BLOCK, first),
            new LightCheck(EnumSkyBlock.SKY, second), new LightCheck(EnumSkyBlock.BLOCK, second)), world.checks);
        assertEquals(List.of(new Change(first, air, stone), new Change(second, air, stone)), world.notifications);
        assertFalse(batch.hasPending());
    }

    @Test
    void unloadingOneWorldDiscardsOnlyItsPendingLightingAndNotifications() {
        var overworld = new TestWorld();
        var other = new TestWorld();
        var overworldChunk = new TestChunk(overworld);
        var otherChunk = new TestChunk(other);
        var batch = new ConstructionLightingBatch();
        var pos = new BlockPos(-12, 71, 35);
        var air = Blocks.AIR.getDefaultState();
        var stone = Blocks.STONE.getDefaultState();
        write(overworldChunk, batch, pos, stone);
        write(otherChunk, batch, pos, stone);
        ConstructionPlacement.notify(overworld, pos, overworldChunk, air, stone, batch);
        ConstructionPlacement.notify(other, pos, otherChunk, air, stone, batch);

        batch.clear(other);
        batch.flushLighting();
        batch.flushNotifications();

        assertEquals(List.of(new LightCheck(EnumSkyBlock.SKY, pos), new LightCheck(EnumSkyBlock.BLOCK, pos)), overworld.checks);
        assertEquals(List.of(new Change(pos, air, stone)), overworld.notifications);
        assertTrue(other.checks.isEmpty());
        assertTrue(other.notifications.isEmpty());
        assertFalse(batch.hasPending());
    }

    private static void write(TestChunk chunk, ConstructionLightingBatch batch, BlockPos pos, IBlockState after) {
        assertTrue(ConstructionPlacement.write(chunk.getWorld(), pos, chunk, chunk.getBlockState(pos), after, batch));
    }

    private record LightCheck(EnumSkyBlock type, BlockPos pos) {
    }

    private record Change(BlockPos pos, IBlockState before, IBlockState after) {
    }

    private static final class TestWorld extends World {
        final List<LightCheck> checks = new ArrayList<>();
        final List<Change> notifications = new ArrayList<>();
        private final TestChunks chunks = new TestChunks();

        TestWorld() {
            super(new SaveHandlerMP(), new WorldInfo(new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), "construction"),
                new WorldProviderSurface() {
                    @Override
                    public boolean hasSkyLight() {
                        return true;
                    }
                }, new Profiler(), false);
            chunkProvider = createChunkProvider();
        }

        @Override
        protected @NotNull IChunkProvider createChunkProvider() {
            return chunks;
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            return chunkProvider.getLoadedChunk(x, z) != null;
        }

        @Override
        public boolean checkLightFor(@NotNull EnumSkyBlock type, @NotNull BlockPos pos) {
            checks.add(new LightCheck(type, pos));
            return true;
        }

        @Override
        public void markAndNotifyBlock(@NotNull BlockPos pos, @Nullable Chunk chunk, @NotNull IBlockState before, @NotNull IBlockState after, int flags) {
            notifications.add(new Change(pos, before, after));
        }

        private static final class TestChunks implements IChunkProvider {
            private @Nullable TestChunk loaded;

            @Override
            public @Nullable Chunk getLoadedChunk(int x, int z) {
                var chunk = loaded;
                return chunk != null && x == chunk.x && z == chunk.z ? chunk : null;
            }

            @Override
            public @NotNull Chunk provideChunk(int x, int z) {
                var chunk = getLoadedChunk(x, z);
                if (chunk == null) throw new IllegalArgumentException("Unexpected chunk coordinates: " + x + ", " + z);
                return chunk;
            }

            @Override
            public boolean tick() {
                return false;
            }

            @Override
            public @NotNull String makeString() {
                return "Construction test chunk";
            }

            @Override
            public boolean isChunkGeneratedAt(int x, int z) {
                return getLoadedChunk(x, z) != null;
            }
        }
    }

    private static final class TestChunk extends Chunk {
        private final HashMap<BlockPos, IBlockState> states = new HashMap<>();
        int skyLight = 15;
        int blockLight = 1;

        TestChunk(TestWorld world) {
            super(world, -1, 2);
            world.chunks.loaded = this;
        }

        @Override
        public boolean canSeeSky(@NotNull BlockPos pos) {
            return false;
        }

        @Override
        public int getLightFor(@NotNull EnumSkyBlock type, @NotNull BlockPos pos) {
            return type == EnumSkyBlock.SKY ? skyLight : blockLight;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public @Nullable IBlockState setBlockState(@NotNull BlockPos pos, @NotNull IBlockState state) {
            var old = getBlockState(pos);
            if (old == state) return null;
            states.put(pos.toImmutable(), state);
            return old;
        }

        @Override
        public @NotNull IBlockState getBlockState(@NotNull BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }
    }
}
