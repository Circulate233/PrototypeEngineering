package com.circulation.prototype_eng.client.render;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.proxy.ClientProxy;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Public typed native TESR mechanism; a concrete controller supplies its actual client view channel. */
@SuppressWarnings("resource")
// Native registered renderers borrow the proxy's managers; the proxy owns their world/resource lifetime.
public abstract class MachineControllerRenderer<T extends AEBaseTile & MachineControllerHost> extends TileEntitySpecialRenderer<T> {
    // Finite native extents avoid the clipping helper's zero-times-infinity NaN corner rejection.
    public static final AxisAlignedBB CONSERVATIVE_BOUNDS = new AxisAlignedBB(-1E100, -1E100, -1E100, 1E100, 1E100, 1E100);
    private final Class<T> tileClass;
    private @Nullable Frustum frustum;

    protected MachineControllerRenderer(Class<T> tileClass) {
        this.tileClass = Objects.requireNonNull(tileClass);
    }

    public static @Nullable AxisAlignedBB ownedBounds(TileEntity tile) {
        var registered = TileEntityRendererDispatcher.instance.getRenderer(tile.getClass());
        return registered instanceof MachineControllerRenderer<?> renderer ? bounds(renderer, tile) : null;
    }

    private static <T extends AEBaseTile & MachineControllerHost> AxisAlignedBB bounds(MachineControllerRenderer<T> renderer, TileEntity tile) {
        var bounds = renderer.bounds(renderer.tileClass.cast(tile));
        return bounds == TileEntity.INFINITE_EXTENT_AABB ? CONSERVATIVE_BOUNDS : bounds;
    }

    public static AxisAlignedBB nativeBounds(TileEntity tile) {
        var bounds = ownedBounds(tile);
        return bounds == null ? tile.getRenderBoundingBox() : bounds;
    }

    public static double nativeMaximumDistanceSquared(TileEntity tile) {
        var registered = TileEntityRendererDispatcher.instance.getRenderer(tile.getClass());
        return registered instanceof MachineControllerRenderer<?> renderer ? maximumDistanceSquared(renderer, tile) : tile.getMaxRenderDistanceSquared();
    }

    private static <T extends AEBaseTile & MachineControllerHost> double maximumDistanceSquared(MachineControllerRenderer<T> renderer, TileEntity tile) {
        return renderer.maximumDistanceSquared(renderer.tileClass.cast(tile));
    }

    public final Class<T> tileClass() {
        return tileClass;
    }

    public final void register() {
        ClientRegistry.bindTileEntitySpecialRenderer(tileClass, this);
    }

    protected abstract ClientMachineRenderSnapshots.@Nullable View view(T tile);

    protected ClientMachineRenderSnapshots snapshots() {
        return client().renderSnapshots();
    }

    protected MachineModelRenderState models() {
        return client().modelRenderState();
    }

    protected ClientProxy client() {
        return (ClientProxy) PrototypeEngineering.proxy;
    }

    /** Owner-thread native culling hook; the class-token redirects also dispatch it for independent host tiles. */
    public AxisAlignedBB bounds(T tile) {
        var view = view(tile);
        return view != null && snapshots().current(view) ? models().bounds(tile, view) : new AxisAlignedBB(tile.getPos());
    }

    public double maximumDistanceSquared(T tile) {
        var bounds = bounds(tile);
        if (bounds == CONSERVATIVE_BOUNDS || bounds == TileEntity.INFINITE_EXTENT_AABB) return Double.MAX_VALUE;
        double x = Math.max(Math.abs(bounds.minX - tile.getPos().getX()), Math.abs(bounds.maxX - tile.getPos().getX()));
        double y = Math.max(Math.abs(bounds.minY - tile.getPos().getY()), Math.abs(bounds.maxY - tile.getPos().getY()));
        double z = Math.max(Math.abs(bounds.minZ - tile.getPos().getZ()), Math.abs(bounds.maxZ - tile.getPos().getZ()));
        double distance = 64 + Math.sqrt(x * x + y * y + z * z);
        return distance * distance;
    }

    @Override
    public void render(@NotNull T tile, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        var view = view(tile);
        if (view == null || !snapshots().current(view)) {
            models().withdraw(tile);
            return;
        }
        if (!models().global(tile, view)) {
            if (frustum == null) frustum = new Frustum();
            frustum.setPosition(TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY, TileEntityRendererDispatcher.staticPlayerZ);
            if (!frustum.isBoundingBoxInFrustum(models().bounds(tile, view))) return;
        }
        models().submit(tile, view, () -> snapshots().current(view), partialTicks);
    }

    @Override
    public boolean isGlobalRenderer(@NotNull T tile) {
        // RenderChunk calls this on its worker. Owner-only view/culling runs in render, never here.
        return true;
    }
}
