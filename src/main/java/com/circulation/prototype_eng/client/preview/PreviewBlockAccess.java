package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.ByteOrder;

/** Owned representative state neighborhood, never a World or a matcher. Preparation resumes per cell. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class PreviewBlockAccess implements IBlockAccess {
    private final StructurePreviewView view;
    private final Object token;
    private final Object2ObjectOpenHashMap<BlockPos, IBlockState> states = new Object2ObjectOpenHashMap<>();
    private final BufferBuilder renderScratch = new BufferBuilder(1024);
    private int cursor;
    @Nullable
    private IBlockState controllerOverride;

    public PreviewBlockAccess(StructurePreviewView view) {
        this.view = view;
        token = view.token();
    }

    /** PM's GUI uses Forge's real block renderer, including extended state, tint, face lighting and CTM. */
    int[] renderLayer(IBlockState state, BlockPos pos) {
        renderScratch.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        try {
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlock(state, pos, this, renderScratch);
        } finally {
            renderScratch.finishDrawing();
        }
        int[] data = new int[renderScratch.getVertexCount() * 7];
        renderScratch.getByteBuffer().duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(data);
        renderScratch.reset();
        return data;
    }

    public boolean current() {
        return token == view.token() && view.model() != null;
    }

    public boolean ready() {
        var model = view.model();
        return token == view.token() && model != null && cursor == model.cells().size();
    }

    public int advance(int visits) {
        if (visits < 0) throw new IllegalArgumentException("Negative state visits");
        int used = 0;
        var model = view.model();
        if (model == null || token != view.token()) return used;
        while (cursor < model.cells().size() && used < visits) {
            var cell = model.cells().get(cursor++);
            used++;
            var state = PreviewBlockStates.first(model, cell);
            if (token != view.token() || model != view.model()) return used;
            if (state != null) states.put(cell.position(), state);
        }
        return used;
    }

    /** Independent registered controller state for 3D neighbors; never a preview cell or tile. */
    public void controllerState(@Nullable IBlockState state) {
        if (!current()) return;
        controllerOverride = state;
    }

    public boolean visible(BlockPos pos) {
        var cell = view.at(pos);
        return cell != null && view.visible(cell);
    }

    public @Nullable IBlockState represented(BlockPos pos) {
        return pos.equals(BlockPos.ORIGIN) && controllerOverride != null ? controllerOverride : states.get(pos);
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        var state = represented(pos);
        return state == null ? Blocks.AIR.getDefaultState() : state;
    }

    @Override
    public @Nullable TileEntity getTileEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return 0x00f000f0;
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return Biomes.PLAINS;
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        return 0;
    }

    @Override
    public WorldType getWorldType() {
        return WorldType.DEFAULT;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return getBlockState(pos).getMaterial() == Material.AIR;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean fallback) {
        return getBlockState(pos).isSideSolid(this, pos, side);
    }
}
