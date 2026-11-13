package com.circulation.prototype_eng.structure;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Biomes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.fluids.Fluid;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.List;
import java.util.Objects;

/** Detached state neighborhood for the native middle-pick hook; never edits the live world. */
public final class StructurePickWorld extends World implements AutoCloseable {
    private final Function<BlockPos, IBlockState> states;
    private final Function<BlockPos, @Nullable String> stateNbt;
    private final Object2ObjectOpenHashMap<BlockPos, TileEntity> tiles = new Object2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    private @Nullable BlockPos pickedPosition;
    private @Nullable IBlockState pickedState;
    private @Nullable String pickedNbt;

    public StructurePickWorld(World source, Function<BlockPos, IBlockState> states) {
        this(source, states, _ -> null);
    }

    public StructurePickWorld(World source, Function<BlockPos, IBlockState> states, Function<BlockPos, @Nullable String> stateNbt) {
        super(source.getSaveHandler(), new WorldInfo(source.getWorldInfo()), source.provider, source.profiler, source.isRemote);
        this.states = states;
        this.stateNbt = stateNbt;
        chunkProvider = createChunkProvider();
    }

    public ItemStack pick(IBlockState state, BlockPos position, EntityPlayer player, @Nullable String tileNbt) {
        var fluid = StructureMaterials.fluid(state);
        if (fluid != null) {
            var key = AEFluidKey.of(fluid);
            return key == null ? ItemStack.EMPTY : GenericStack.wrapInItemStack(key, Fluid.BUCKET_VOLUME);
        }
        if (state.getMaterial().isLiquid()) return ItemStack.EMPTY;
        pickedPosition = position.toImmutable();
        pickedState = state;
        pickedNbt = tileNbt;
        try {
            var hit = new RayTraceResult(new Vec3d(position).add(.5, .5, .5), EnumFacing.UP, position);
            var stack = state.getBlock().getPickBlock(state, hit, this, position, player).copy();
            if (!stack.isEmpty()) stack.setCount(1);
            return stack;
        } finally {
            try { close(); }
            finally {
                pickedPosition = null;
                pickedState = null;
                pickedNbt = null;
            }
        }
    }

    @Override public @NotNull IBlockState getBlockState(@NotNull BlockPos pos) {
        return pos.equals(pickedPosition) ? Objects.requireNonNull(pickedState) : states.apply(pos);
    }

    @Override public @Nullable TileEntity getTileEntity(@NotNull BlockPos pos) {
        var old = tiles.get(pos);
        if (old != null) return old;
        var state = getBlockState(pos);
        if (!state.getBlock().hasTileEntity(state)) return null;
        var tile = state.getBlock().createTileEntity(this, state);
        if (tile == null) return null;
        tiles.put(pos.toImmutable(), tile);
        tile.setWorld(this);
        tile.setPos(pos.toImmutable());
        var nbt = pos.equals(pickedPosition) ? pickedNbt : stateNbt.apply(pos);
        if (nbt != null) {
            try { tile.handleUpdateTag(JsonToNBT.getTagFromJson(nbt)); }
            catch (NBTException invalid) { throw new IllegalArgumentException("Invalid structure pick NBT", invalid); }
            tile.setWorld(this);
            tile.setPos(pos.toImmutable());
        }
        return tile;
    }

    @Override public void setTileEntity(@NotNull BlockPos pos, @Nullable TileEntity tile) {
        if (tile != null) tiles.put(pos.toImmutable(), tile);
    }
    @Override public void removeTileEntity(@NotNull BlockPos pos) {
        var tile = tiles.remove(pos);
        if (tile != null) tile.invalidate();
    }
    @Override public boolean setBlockState(@NotNull BlockPos pos, @NotNull IBlockState state, int flags) { return false; }
    @Override public boolean spawnEntity(@NotNull Entity entity) { return false; }
    @Override public void markChunkDirty(@NotNull BlockPos pos, @NotNull TileEntity tile) { }
    @Override public int getCombinedLight(@NotNull BlockPos pos, int light) { return 0x00f000f0; }
    @Override public @NotNull Biome getBiome(@NotNull BlockPos pos) { return Biomes.PLAINS; }
    @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return true; }
    @Override protected @NotNull IChunkProvider createChunkProvider() {
        return new PickChunks(this);
    }
    private static long key(int x, int z) { return (long) x << 32 ^ z & 0xffffffffL; }

    private record PickChunks(StructurePickWorld owner) implements IChunkProvider {
        @Override public @Nullable Chunk getLoadedChunk(int x, int z) { return owner.chunks.get(key(x, z)); }
        @Override public @NotNull Chunk provideChunk(int x, int z) {
            return owner.chunks.computeIfAbsent(key(x, z), _ -> new PickChunk(owner, x, z));
        }
        @Override public boolean tick() { return false; }
        @Override public @NotNull String makeString() { return "structure-pick"; }
        @Override public boolean isChunkGeneratedAt(int x, int z) { return false; }
    }

    private static final class PickChunk extends Chunk {
        private final StructurePickWorld owner;
        PickChunk(StructurePickWorld owner, int x, int z) { super(owner, x, z); this.owner = owner; }
        @Override public @NotNull IBlockState getBlockState(@NotNull BlockPos pos) { return owner.getBlockState(pos); }
        @Override public @Nullable TileEntity getTileEntity(@NotNull BlockPos pos, @NotNull EnumCreateEntityType type) {
            return owner.getTileEntity(pos);
        }
    }

    @Override public void close() {
        var owned = List.copyOf(tiles.values());
        tiles.clear();
        chunks.clear();
        Throwable failure = null;
        for (var tile : owned) {
            try { tile.invalidate(); }
            catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
