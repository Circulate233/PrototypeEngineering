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
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final IntArrayList boundaryCells = new IntArrayList();
    private int boundaryCursor;
    private final FloatBuffer colors = BufferUtils.createFloatBuffer(4);
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16), projected = BufferUtils.createFloatBuffer(3);
    private final IntBuffer nativeViewport = BufferUtils.createIntBuffer(4);
    private final IntBuffer oldScissor = BufferUtils.createIntBuffer(4);
    private final WorldVertexBufferUploader controllerUploader = new WorldVertexBufferUploader();
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
        access = null;
        token = null;
        boundaryCells.clear();
        boundaryCursor = 0;
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
            invalidate();
            world = mc.world;
            token = view.token();
        }
        var bounds = view.sceneBounds();
        if (model == null || bounds == null) return;
        var round = view.token();
        prepareController();
        if (access == null) access = new PreviewBlockAccess(view);
        access.advance(2048);
        camera.prepare(bounds);
        prepareBoundaries();
        var controllerState = controller == null || !view.threeDimensional ? null : controller.previewState(view.settings().orientation(), view.formedPreview());
        if (closed || view.token() != round || view.model() != model || mc.world != world) return;
        access.controllerState(controllerState);
        tiles.prepare(view, access, mc.world, 512);
        camera.frame(System.currentTimeMillis(), view.autoRotate);
        var before = PreviewBlockModels.State.capture(colors);
        var oldViewport = new int[4];
        nativeViewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, nativeViewport);
        nativeViewport.get(oldViewport);
        var scale = new ScaledResolution(mc).getScaleFactor();
        int[] rect = screenViewport.scissor(scale, mc.displayHeight);
        int oldMatrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int oldDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int[] clipped = rect.clone();
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            oldScissor.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, oldScissor);
            int right = Math.min(rect[0] + rect[2], oldScissor.get(0) + oldScissor.get(2));
            int top = Math.min(rect[1] + rect[3], oldScissor.get(1) + oldScissor.get(3));
            clipped[0] = Math.max(rect[0], oldScissor.get(0));
            clipped[1] = Math.max(rect[1], oldScissor.get(1));
            clipped[2] = Math.max(0, right - clipped[0]);
            clipped[3] = Math.max(0, top - clipped[1]);
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(clipped[0], clipped[1], clipped[2], clipped[3]);
            GL11.glViewport(rect[0], rect[1], rect[2], rect[3]);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.loadIdentity();
                float far = (float) Math.clamp(camera.distance(bounds) * 30, 2000, Float.MAX_VALUE);
                GLU.gluPerspective(45, rect[2] / (float) rect[3], 0.1f, far);
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
                GlStateManager.pushMatrix();
                try (var frame = machines.request((System.nanoTime() - animationStartNanos) / 50_000_000.0)) {
                    GlStateManager.loadIdentity();
                    var center = camera.center(bounds);
                    GlStateManager.translate(0, 0, -camera.distance(bounds));
                    GlStateManager.rotate((float) camera.pitch(), 1, 0, 0);
                    GlStateManager.rotate((float) camera.yaw(), 0, 1, 0);
                    GlStateManager.translate(-center.x, -center.y, -center.z);
                    var ray = Objects.requireNonNull(camera.ray(viewport, viewport.x() + viewport.width() / 2, viewport.y() + viewport.height() / 2, bounds));
                    if (view.threeDimensional) {
                        if (controllerState != null) drawController(controllerState);
                        meshes.renderScene(view, access, ray.origin().x, ray.origin().y, ray.origin().z);
                        tiles.draw(view, partialTicks, 512);
                    }
                    if (closed || view.token() != round || view.model() != model || mc.world != world) return;
                    if (view.threeDimensional) frame.draw(ray.origin());
                    GlStateManager.disableTexture2D();
                    GlStateManager.disableLighting();
                    if (!highlighted.isEmpty()) {
                        GlStateManager.disableDepth();
                        for (var position : highlighted) RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(position).grow(.002), .18f, .42f, 1, 1);
                        GlStateManager.enableDepth();
                    }
                    var picked = view.picked();
                    if (!view.wireframe && picked != null && view.visible(picked)) {
                        GlStateManager.disableDepth();
                        var p = picked.position();
                        RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(p).grow(0.002), 0.18f, 0.42f, 1, 1);
                        GlStateManager.enableDepth();
                    }
                    if (view.wireframe) drawWireframe(controller != null);
                } finally {
                    GlStateManager.popMatrix();
                }
            } finally {
                GlStateManager.matrixMode(GL11.GL_PROJECTION);
                GlStateManager.popMatrix();
            }
        } finally {
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            GL11.glPopAttrib();
            GlStateManager.matrixMode(oldMatrix);
            GlStateManager.depthFunc(oldDepthFunc);
            before.restore();
        }
    }

    /** Resolve the actual host transform (including JEI recipe-origin translation) before changing GL matrices. */
    public void drawAtCurrentOrigin(int x, int y, int width, int height, float partialTicks) {
        var mc = Minecraft.getMinecraft();
        modelview.clear();
        projection.clear();
        nativeViewport.clear();
        projected.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, nativeViewport);
        if (!GLU.gluProject(x, y, 0, modelview, projection, nativeViewport, projected)) return;
        int scale = new ScaledResolution(mc).getScaleFactor();
        double screenX = projected.get(0) / scale, screenY = (mc.displayHeight - projected.get(1)) / scale;
        draw(new StructurePreviewCamera.Viewport(screenX, screenY, width, height), partialTicks);
    }

    private void prepareBoundaries() {
        var model = view.model();
        if (model == null) return;
        for (int visited = 0; visited < 2048 && boundaryCursor < model.cells().size(); visited++) {
            int index = boundaryCursor++;
            var pos = model.cells().get(index).position();
            for (var facing : EnumFacing.values()) {
                if (view.at(pos.offset(facing)) == null) { boundaryCells.add(index); break; }
            }
        }
    }

    private void drawWireframe(boolean independentController) {
        var model = Objects.requireNonNull(view.model());
        var bounds = Objects.requireNonNull(view.sceneBounds());
        var tessellator = Tessellator.getInstance();
        var buffer = tessellator.getBuffer();
        var min = bounds.min();
        var max = bounds.max();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        buffer.pos(min.getX(), min.getY(), min.getZ()).color(255, 80, 80, 120).endVertex();
        buffer.pos(max.getX() + 1.0, min.getY(), min.getZ()).color(255, 80, 80, 120).endVertex();
        buffer.pos(min.getX(), min.getY(), min.getZ()).color(80, 255, 80, 120).endVertex();
        buffer.pos(min.getX(), max.getY() + 1.0, min.getZ()).color(80, 255, 80, 120).endVertex();
        buffer.pos(min.getX(), min.getY(), min.getZ()).color(80, 80, 255, 120).endVertex();
        buffer.pos(min.getX(), min.getY(), max.getZ() + 1.0).color(80, 80, 255, 120).endVertex();
        tessellator.draw();
        var picked = view.picked();
        for (int index : boundaryCells) {
            var cell = model.cells().get(index);
            if (!view.threeDimensional && cell.position().getY() != view.layer) continue;
            if (independentController && cell.position().equals(BlockPos.ORIGIN)) continue;
            boolean selected = picked != null && picked.position().equals(cell.position());
            int color = selected ? 0xff2e6bff : wireColor(view.observation(index), cell.display() == null ? 0 : cell.display().key().hashCode());
            if (picked != null && !selected) color = 0x55000000 | color & 0xffffff;
            drawWireBox(cell.position(), color);
        }
        if (independentController && (view.threeDimensional || view.layer == 0)) {
            boolean selected = picked == view.controllerCell();
            int color = selected ? 0xff2e6bff : wireColor(null, Objects.requireNonNull(controller.getRegistryName()).toString().hashCode());
            if (picked != null && !selected) color = 0x55000000 | color & 0xffffff;
            drawWireBox(BlockPos.ORIGIN, color);
        }
    }

    private static int wireColor(@Nullable StructureProjectionState.Status status, int hash) {
        if (status != null) switch (status) {
            case DISPLAY_MATCH -> { return 0xcc55ff55; }
            case MISSING -> { return 0xccff5555; }
            case DIFFERENT -> { return 0xccffaa55; }
            case UNLOADED -> { return 0xcc55ffff; }
            case UNKNOWN -> { }
        }
        return 0xaa000000 | (120 + (hash & 63)) << 16 | (120 + ((hash >> 6) & 63)) << 8 | 120 + ((hash >> 12) & 63);
    }

    private static void drawWireBox(BlockPos pos, int color) {
        RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(pos), (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
    }

    @SuppressWarnings("unused") // Optional custom-host overlay; PM's default preview does not enable it.
    public void compass(int x, int y) {
        var font = Minecraft.getMinecraft().fontRenderer;
        Gui.drawRect(x, y, x + 36, y + 36, 0x66000000);
        var right = camera.right();
        var up = camera.up();
        var texts = new StructurePreviewText[]{StructurePreviewText.North, StructurePreviewText.East, StructurePreviewText.South, StructurePreviewText.West};
        int[] dx = {0, 1, 0, -1}, dz = {-1, 0, 1, 0};
        for (int i = 0; i < 4; i++) {
            var text = texts[i].getLocal();
            int px = x + 18 + (int) ((dx[i] * right.x + dz[i] * right.z) * 12);
            int py = y + 18 - (int) ((dx[i] * up.x + dz[i] * up.z) * 12);
            font.drawString(text, px - font.getStringWidth(text) / 2, py - 4, i == 0 ? 0xff5555 : 0xffffff);
        }
    }

    private Set<BlockPos> highlighted = Set.of();
    public void highlighted(Set<BlockPos> positions) { highlighted = Set.copyOf(positions); }
    public boolean press(double x, double y, int button) { return camera.press(viewport, x, y, button); }
    public boolean drag(double x, double y) { var bounds = view.sceneBounds(); return bounds != null && camera.drag(x, y, bounds, viewport); }
    public boolean release(double x, double y, int button) { return camera.release(button) && view.choose(camera.pick(view, viewport, x, y)); }

    /** Finish a read-only scanner drag without choosing a block. */
    public void releaseWithoutPicking(int button) { camera.release(button); }
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
                var id = Objects.requireNonNull(block.getRegistryName());
                view.controllerDisplay(new StructureDisplay("state:" + id + ":0",
                    List.of(new StructureDisplay.BlockOption(id, 0, Map.of())), null));
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
            var previousLayer = MinecraftForgeClient.getRenderLayer();
            try {
                for (var layer : BlockRenderLayer.values()) {
                    if (!state.getBlock().canRenderInLayer(state, layer)) continue;
                    ForgeHooksClient.setRenderLayer(layer);
                    if (layer == BlockRenderLayer.TRANSLUCENT) { GlStateManager.enableBlend(); GlStateManager.depthMask(false); }
                    else { GlStateManager.disableBlend(); GlStateManager.depthMask(true); }
                    var buffer = Tessellator.getInstance().getBuffer();
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    try { buffer.addVertexData(access.renderLayer(state, BlockPos.ORIGIN)); }
                    finally { buffer.finishDrawing(); }
                    controllerUploader.draw(buffer);
                }
            } finally { ForgeHooksClient.setRenderLayer(previousLayer); }
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
