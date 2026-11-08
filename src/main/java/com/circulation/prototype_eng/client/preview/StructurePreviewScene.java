package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.localization.StructurePreviewText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import com.circulation.prototype_eng.block.MachineControllerBlock;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import net.minecraft.client.renderer.texture.TextureMap;

/** Public concrete preview control for AEBaseGui and independent custom RecipePoolJeiPage composition. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewScene implements AutoCloseable {
    private final StructurePreviewView view;
    private final StructurePreviewCamera camera = new StructurePreviewCamera();
    private final PreviewBlockModels meshes;
    private final PreviewMachineModels machines;
    private final long animationStartNanos = System.nanoTime();
    private final AutoCloseable invalidationSubscription, closeSubscription;
    private final PreviewTileEntities<TileEntity> tiles = new PreviewTileEntities<>(new NativeTiles());
    private PreviewBlockAccess access;
    private Object token, world;
    private boolean closed;
    private MachineControllerBlock<?> controller;
    private int controllerCursor;
    private boolean controllerResolved;
    private final FloatBuffer colors = BufferUtils.createFloatBuffer(4);
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16), projected = BufferUtils.createFloatBuffer(3);
    private final IntBuffer nativeViewport = BufferUtils.createIntBuffer(4);
    private StructurePreviewCamera.Viewport viewport = new StructurePreviewCamera.Viewport(0, 0, 1, 1);

    public StructurePreviewScene(StructurePreviewView view) {
        this.view = view;
        controllerResolved = view.machine() == null;
        meshes = new PreviewBlockModels(view.previews());
        machines = new PreviewMachineModels(view);
        invalidationSubscription = view.previews().onInvalidated(this::invalidate);
        closeSubscription = view.previews().onClosed(this::close);
        PreviewGuiResources.add(this);
    }
    public StructurePreviewView view() { return view; }
    public StructurePreviewCamera camera() { return camera; }
    public StructurePreviewCamera.Viewport viewport() { return viewport; }
    public void invalidate() {
        if (closed) return;
        view.invalidateMaterials();
        access = null; token = null;
        Throwable failure = null;
        try {
            machines.invalidate();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            tiles.clear();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            meshes.invalidate();
    } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        PreviewBlockModelCache.rethrow(failure);
    }
    public void draw(StructurePreviewCamera.Viewport screenViewport, float partialTicks) {
        if (closed) return;
        viewport = screenViewport;
        var mc = Minecraft.getMinecraft();
        view.advance(2048);
        view.scan(mc.world, (cell, pos) -> PreviewDisplayObservation.observe(mc.world, view.model(), cell, pos), 2048);
        var model = view.model();
        if (world != mc.world || token != view.token()) {
            invalidate(); world = mc.world; token = view.token();
        }
        var bounds = view.sceneBounds();
        if (model == null || bounds == null) return;
        var round = view.token();
        prepareController();
        if (access == null) access = new PreviewBlockAccess(view);
        access.advance(2048);
        var controllerState = controller == null || !view.threeDimensional ? null : controller.previewState(view.settings().orientation(), view.formedPreview());
        if (closed || view.token() != round || view.model() != model || mc.world != world) return;
        access.controllerState(controllerState);
        tiles.prepare(view, access, mc.world, 512);
        camera.frame(System.currentTimeMillis(), view.autoRotate);
        var before = PreviewBlockModels.State.capture(colors);
        var oldViewport = new int[4];
        nativeViewport.clear(); GL11.glGetInteger(GL11.GL_VIEWPORT, nativeViewport); nativeViewport.get(oldViewport);
        var scale = new ScaledResolution(mc).getScaleFactor();
        int[] rect = screenViewport.scissor(scale, mc.displayHeight);
        int oldMatrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST); GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
            GL11.glViewport(rect[0], rect[1], rect[2], rect[3]);
            GlStateManager.depthMask(true); GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.matrixMode(GL11.GL_PROJECTION); GlStateManager.pushMatrix();
            try {
                GlStateManager.loadIdentity();
                float far = (float) Math.clamp(camera.distance(bounds) * 30, 2000, Float.MAX_VALUE);
                GLU.gluPerspective(45, rect[2] / (float) rect[3], 0.1f, far);
                GlStateManager.matrixMode(GL11.GL_MODELVIEW); GlStateManager.pushMatrix();
                try (var frame = machines.request((System.nanoTime() - animationStartNanos) / 50_000_000.0)) {
                    GlStateManager.loadIdentity();
                    var center = camera.center(bounds);
                    GlStateManager.translate(0, 0, -camera.distance(bounds));
                    GlStateManager.rotate((float) camera.pitch(), 1, 0, 0); GlStateManager.rotate((float) camera.yaw(), 0, 1, 0);
                    GlStateManager.translate(-center.x, -center.y, -center.z);
                    var ray = Objects.requireNonNull(camera.ray(viewport, viewport.x() + viewport.width() / 2, viewport.y() + viewport.height() / 2, bounds));
                    meshes.renderScene(view, access, ray.origin().x, ray.origin().y, ray.origin().z);
                    tiles.draw(view, partialTicks, 512);
                    if (controllerState != null) drawController(controllerState);
                    if (closed || view.token() != round || view.model() != model || mc.world != world) return;
                    frame.draw(ray.origin());
                    GlStateManager.disableTexture2D(); GlStateManager.disableLighting(); GlStateManager.disableDepth();
                    var picked = view.picked();
                    if (picked != null && view.visible(picked)) {
                        var p = picked.position();
                        RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(p).grow(0.002), 0.18f, 0.42f, 1, 1);
                    }
                    // Unknown representatives retain a visible box rather than silently disappearing.
                    for (var cell : view.overlaySamples(512)) if (view.wireframe || view.selected() != null && view.selected().equals(cell.display()) || access.represented(cell.position()) == null)
                        RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(cell.position()), 0.55f, 0.65f, 0.8f, 0.7f);
                } finally { GlStateManager.popMatrix(); }
            } finally { GlStateManager.matrixMode(GL11.GL_PROJECTION); GlStateManager.popMatrix(); }
        } finally {
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            GL11.glPopAttrib(); GlStateManager.matrixMode(oldMatrix); before.restore();
        }
    }
    /** Resolve the actual host transform (including JEI recipe-origin translation) before changing GL matrices. */
    public void drawAtCurrentOrigin(int x, int y, int width, int height, float partialTicks) {
        var mc = Minecraft.getMinecraft();
        modelview.clear(); projection.clear(); nativeViewport.clear(); projected.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview); GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection); GL11.glGetInteger(GL11.GL_VIEWPORT, nativeViewport);
        if (!GLU.gluProject(x, y, 0, modelview, projection, nativeViewport, projected)) return;
        int scale = new ScaledResolution(mc).getScaleFactor();
        double screenX = projected.get(0) / scale, screenY = (mc.displayHeight - projected.get(1)) / scale;
        draw(new StructurePreviewCamera.Viewport(screenX, screenY, width, height), partialTicks);
        compass(x + 4, y + 4);
    }
    public void compass(int x, int y) {
        var font = Minecraft.getMinecraft().fontRenderer;
        Gui.drawRect(x, y, x + 36, y + 36, 0x66000000);
        var right = camera.right(); var up = camera.up();
        var texts = new StructurePreviewText[] {StructurePreviewText.North, StructurePreviewText.East, StructurePreviewText.South, StructurePreviewText.West};
        int[] dx = {0, 1, 0, -1}, dz = {-1, 0, 1, 0};
        for (int i = 0; i < 4; i++) {
            var text = texts[i].getLocal();
            int px = x + 18 + (int) ((dx[i] * right.x + dz[i] * right.z) * 12);
            int py = y + 18 - (int) ((dx[i] * up.x + dz[i] * up.z) * 12);
            font.drawString(text, px - font.getStringWidth(text) / 2, py - 4, i == 0 ? 0xff5555 : 0xffffff);
        }
    }
    public boolean press(double x, double y, int button) { return camera.press(viewport, x, y, button); }
    public boolean drag(double x, double y) { var bounds = view.sceneBounds(); return bounds != null && camera.drag(x, y, bounds, viewport); }
    public boolean release(double x, double y, int button) { return camera.release(button) && view.choose(camera.pick(view, viewport, x, y)); }
    public boolean wheel(double x, double y, int delta) { return camera.wheel(viewport, x, y, delta); }
    @Override public void close() {
        if (closed) return;
        closed = true; PreviewGuiResources.remove(this);
        Throwable failure = null;
        try {
            machines.close();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            tiles.close(); } catch (RuntimeException | Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
        try { meshes.close(); } catch (RuntimeException | Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
        for (var subscription : new AutoCloseable[] {invalidationSubscription, closeSubscription}) try { subscription.close(); }
        catch (Exception caught) { failure = PreviewBlockModelCache.merge(failure, new IllegalStateException("Cannot detach viewer subscription", caught)); }
        catch (Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
        PreviewBlockModelCache.rethrow(failure);
    }

    private void prepareController() {
        if (controllerResolved || !view.threeDimensional) return;
        var declarations = MachineControllerBlocks.all();
        for (int spent = 0; spent < 512 && controllerCursor < declarations.size(); spent++) {
            var block = declarations.get(controllerCursor++);
            if (block.type() == view.machine()) {
                controller = block;
                controllerResolved = true;
                return;
            }
        }
        if (controllerCursor == declarations.size()) controllerResolved = true;
    }

    private void drawController(IBlockState state) {
        var before = PreviewBlockModels.State.capture(colors);
        float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            GlStateManager.disableLighting();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlockBrightness(state, 1);
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GlStateManager.popMatrix();
            before.restore();
        }
    }

    private final class NativeTiles implements PreviewTileEntities.Owner<TileEntity> {
        @Override public @Nullable TileEntity create(Object currentWorld, IBlockState state, BlockPos pos, @Nullable NBTTagCompound ownTag) {
            var world = (World) currentWorld;
            if (!state.getBlock().hasTileEntity(state)) return null;
            var tile = state.getBlock().createTileEntity(world, state);
            if (tile == null) return null;
            try {
                tile.setWorld(world); tile.setPos(pos.toImmutable());
                if (ownTag != null) tile.handleUpdateTag(ownTag.copy());
                tile.setWorld(world); tile.setPos(pos.toImmutable());
                return tile;
            } catch (RuntimeException | Error failure) {
                Throwable combined = failure;
                try { tile.invalidate(); } catch (RuntimeException | Error cleanup) { combined = PreviewBlockModelCache.merge(combined, cleanup); }
                PreviewBlockModelCache.rethrow(combined);
                return null;
            }
        }
        @Override public void release(TileEntity tile) { tile.invalidate(); }
        @Override public void draw(TileEntity tile, BlockPos position, float partialTicks) {
            var before = PreviewBlockModels.State.capture(colors);
            float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.enableTexture2D(); GlStateManager.enableDepth(); GlStateManager.disableCull();
                GlStateManager.disableLighting(); OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
                TileEntityRendererDispatcher.instance.render(tile, position.getX(), position.getY(), position.getZ(), partialTicks, -1, 1);
            } finally {
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
                GlStateManager.popMatrix(); before.restore();
            }
        }
    }
}
