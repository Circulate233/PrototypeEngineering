package com.circulation.prototype_eng.client.preview;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.block.MachineControllerBlock;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import com.circulation.prototype_eng.client.render.GuiItemLighting;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import com.circulation.prototype_eng.structure.StructureMaterials;
import com.circulation.prototype_eng.structure.StructurePickWorld;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import com.circulation.prototype_eng.util.ExternalEnumValues;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.fluids.Fluid;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Public concrete preview control for AEBaseGui and independent custom RecipePoolJeiPage composition.
 */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewScene implements AutoCloseable {
    private static final StructurePreviewText[] COMPASS_LABELS = {StructurePreviewText.North, StructurePreviewText.East,
        StructurePreviewText.South, StructurePreviewText.West};
    private static final int[] COMPASS_X = {0, 1, 0, -1}, COMPASS_Z = {-1, 0, 1, 0};
    private final StructurePreviewView view;
    private final StructurePreviewCamera camera = new StructurePreviewCamera();
    private final PreviewBlockModels meshes;
    private final PreviewMachineModels machines;
    private final long animationStartNanos = System.nanoTime();
    private final AutoCloseable invalidationSubscription, closeSubscription;
    private final PreviewTileEntities<TileEntity> tiles = new PreviewTileEntities<>(new NativeTiles());
    private final IntArrayList boundaryCells = new IntArrayList();
    private final Int2ObjectOpenHashMap<IntArrayList> layerCells = new Int2ObjectOpenHashMap<>();
    private final PreviewRayIndex rayIndex = new PreviewRayIndex();
    private final FloatBuffer colors = BufferUtils.createFloatBuffer(4);
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16), projection = BufferUtils.createFloatBuffer(16), projected = BufferUtils.createFloatBuffer(3);
    private final IntBuffer nativeViewport = BufferUtils.createIntBuffer(4);
    private final IntBuffer oldScissor = BufferUtils.createIntBuffer(4);
    private final int[] screenScissor = new int[4];
    private final WorldVertexBufferUploader controllerUploader = new WorldVertexBufferUploader();
    private final Map<ItemChoice, ItemStack> itemChoices = new Object2ObjectOpenHashMap<>();
    private final Map<StructureDisplay, BlockPos> representativePositions = new Object2ObjectOpenHashMap<>();
    private PreviewBlockAccess access;
    private StructurePickWorld pickWorld;
    private boolean materialsPrepared;
    private @Nullable List<GenericStack> materialStacks;
    private double pointerX, pointerY;
    private boolean pointerEnabled;
    private StructurePreviewView.Click hovered;
    private Object token, world;
    private boolean closed;
    private MachineControllerBlock<?> controller;
    private int controllerCursor;
    private boolean controllerResolved;
    private int boundaryCursor;
    private StructurePreviewCamera.Viewport viewport = new StructurePreviewCamera.Viewport(0, 0, 1, 1);
    private Set<BlockPos> highlighted = Set.of();

    public StructurePreviewScene(StructurePreviewView view) {
        this.view = view;
        controllerResolved = view.machine() == null;
        meshes = new PreviewBlockModels(view.previews());
        machines = new PreviewMachineModels(view);
        invalidationSubscription = view.previews().onInvalidated(this::invalidate);
        closeSubscription = view.previews().onClosed(this::close);
        PreviewGuiResources.add(this);
    }

    private static int wireColor(@Nullable StructureProjectionState.Status status, int hash) {
        if (status != null) switch (status) {
            case DISPLAY_MATCH -> {
                return 0xcc55ff55;
            }
            case MISSING -> {
                return 0xccff5555;
            }
            case DIFFERENT -> {
                return 0xccffaa55;
            }
            case UNLOADED -> {
                return 0xcc55ffff;
            }
            case UNKNOWN -> {
            }
        }
        return 0xaa000000 | (120 + (hash & 63)) << 16 | (120 + ((hash >> 6) & 63)) << 8 | 120 + ((hash >> 12) & 63);
    }

    private static void drawWireBox(BlockPos pos, int color) {
        RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(pos), (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
    }

    public StructurePreviewView view() {
        return view;
    }

    public StructurePreviewCamera camera() {
        return camera;
    }

    public StructurePreviewCamera.Viewport viewport() {
        return viewport;
    }

    public void invalidate() {
        if (closed) return;
        view.invalidateMaterials();
        access = null;
        var oldPicks = pickWorld;
        pickWorld = null;
        itemChoices.clear();
        representativePositions.clear();
        materialsPrepared = false;
        materialStacks = null;
        hovered = null;
        token = null;
        boundaryCells.clear();
        layerCells.clear();
        rayIndex.clear();
        boundaryCursor = 0;
        Throwable failure = null;
        try {
            if (oldPicks != null) oldPicks.close();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            machines.invalidate();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
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

    @SuppressWarnings("DataFlowIssue") // The viewport center lies inside the positive-size viewport accepted above.
    public void draw(StructurePreviewCamera.Viewport screenViewport, float partialTicks) {
        if (closed) return;
        viewport = screenViewport;
        var mc = Minecraft.getMinecraft();
        view.advance(2048);
        view.scan(mc.world, (model, cell, pos) -> PreviewDisplayObservation.observe(mc.world, model, cell, pos), 2048);
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
        var mainState = !view.threeDimensional && view.layer != 0 ? null : controller == null ? view.externalMainBlock()
            : controller.previewState(view.settings().orientation(), view.formingModelsVisible());
        if (closed || view.token() != round || view.model() != model || mc.world != world) return;
        access.controllerState(mainState);
        if (pickWorld == null) pickWorld = new StructurePickWorld(mc.world, access::getBlockState, position -> {
            var cell = view.at(position);
            return cell == null || cell.display() == null ? null : cell.display().tileNbt();
        });
        tiles.prepare(view, access, mc.world, 512);
        camera.frame(System.currentTimeMillis(), view.autoRotate);
        hovered = pointerEnabled ? pick(viewport.x() + pointerX, viewport.y() + pointerY) : null;
        var before = PreviewBlockModels.State.capture(colors);
        int lighting = GuiItemLighting.capture();
        nativeViewport.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, nativeViewport);
        int viewportX = nativeViewport.get(0), viewportY = nativeViewport.get(1);
        int viewportWidth = nativeViewport.get(2), viewportHeight = nativeViewport.get(3);
        var scale = new ScaledResolution(mc).getScaleFactor();
        int[] rect = screenScissor;
        screenViewport.scissor(scale, mc.displayHeight, rect);
        int oldMatrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int oldDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int clippedX = rect[0], clippedY = rect[1], clippedWidth = rect[2], clippedHeight = rect[3];
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            oldScissor.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, oldScissor);
            int right = Math.min(rect[0] + rect[2], oldScissor.get(0) + oldScissor.get(2));
            int top = Math.min(rect[1] + rect[3], oldScissor.get(1) + oldScissor.get(3));
            clippedX = Math.max(rect[0], oldScissor.get(0));
            clippedY = Math.max(rect[1], oldScissor.get(1));
            clippedWidth = Math.max(0, right - clippedX);
            clippedHeight = Math.max(0, top - clippedY);
        }
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(clippedX, clippedY, clippedWidth, clippedHeight);
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
                    var ray = camera.ray(viewport, viewport.x() + viewport.width() / 2, viewport.y() + viewport.height() / 2, bounds);
                    if (mainState != null) drawMainBlock(mainState);
                    meshes.renderScene(view, access, ray.origin().x, ray.origin().y, ray.origin().z);
                    tiles.draw(view, partialTicks, 512);
                    if (closed || view.token() != round || view.model() != model || mc.world != world) return;
                    if (view.threeDimensional) frame.draw(ray.origin());
                    GlStateManager.disableTexture2D();
                    GlStateManager.disableLighting();
                    if (!highlighted.isEmpty()) {
                        GlStateManager.disableDepth();
                        for (var position : highlighted)
                            if (view.threeDimensional || position.getY() == view.layer)
                                RenderGlobal.drawSelectionBoundingBox(new AxisAlignedBB(position).grow(.002), .18f, .42f, 1, 1);
                        GlStateManager.enableDepth();
                    }
                    if (view.wireframe) drawWireframe(model, bounds, mainState);
                    float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
                    GlStateManager.disableDepth();
                    GlStateManager.depthMask(false);
                    try {
                        var picked = view.picked();
                        if (picked != null && view.visible(picked)) {
                            GL11.glLineWidth(3.5f);
                            RenderGlobal.drawSelectionBoundingBox(selectionBox(picked).grow(.004), .15f, .55f, 1, 1);
                        }
                        if (hovered != null && hovered.token() == view.token()) {
                            GL11.glLineWidth(2.5f);
                            RenderGlobal.drawSelectionBoundingBox(selectionBox(hovered.cell()).grow(.009), 1, 1, .25f, 1);
                        }
                    } finally {
                        GL11.glLineWidth(lineWidth);
                        GlStateManager.depthMask(true);
                        GlStateManager.enableDepth();
                    }
                } finally {
                    GlStateManager.popMatrix();
                }
            } finally {
                GlStateManager.matrixMode(GL11.GL_PROJECTION);
                GlStateManager.popMatrix();
            }
        } finally {
            try {
                if (pickWorld != null) pickWorld.close();
            } finally {
                GL11.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
                GL11.glPopAttrib();
                GlStateManager.matrixMode(oldMatrix);
                GlStateManager.depthFunc(oldDepthFunc);
                before.restore();
                GuiItemLighting.restore(lighting);
            }
        }
    }

    /**
     * Resolve the actual host transform (including JEI recipe-origin translation) before changing GL matrices.
     */
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
            rayIndex.add(model.cells().get(index));
            var pos = model.cells().get(index).position();
            layerCells.computeIfAbsent(pos.getY(), _ -> new IntArrayList()).add(index);
            var display = model.cells().get(index).display();
            if (display != null) representativePositions.putIfAbsent(display, pos);
            for (var facing : EnumFacing.VALUES) {
                if (view.at(pos.offset(facing)) == null) {
                    boundaryCells.add(index);
                    break;
                }
            }
        }
    }

    private void drawWireframe(StructurePreviewModel model, StructureBounds bounds, @Nullable IBlockState mainState) {
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
        var indices = view.threeDimensional ? boundaryCells : layerCells.get(view.layer);
        if (indices != null) for (int index1 = 0; index1 < indices.size(); index1++) {
            int index = indices.getInt(index1);
            var cell = model.cells().get(index);
            if (mainState != null && cell.position().equals(BlockPos.ORIGIN)) continue;
            boolean selected = picked != null && picked.position().equals(cell.position());
            int color = selected ? 0xff2e6bff : wireColor(view.observation(index), cell.display() == null ? 0 : cell.display().key().hashCode());
            if (picked != null && !selected) color = 0x55000000 | color & 0xffffff;
            drawWireBox(cell.position(), color);
        }
        if (mainState != null) {
            boolean selected = picked == view.controllerCell();
            int color = selected ? 0xff2e6bff : wireColor(null, Objects.requireNonNull(mainState.getBlock().getRegistryName()).hashCode());
            if (picked != null && !selected) color = 0x55000000 | color & 0xffffff;
            drawWireBox(BlockPos.ORIGIN, color);
        }
    }

    public void compass(int x, int y) {
        var font = Minecraft.getMinecraft().fontRenderer;
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GlStateManager.disableDepth();
        try {
            Gui.drawRect(x, y, x + 34, y + 34, 0x66000000);
            var right = camera.right();
            var up = camera.up();
            for (int i = 0; i < 4; i++) {
                var text = COMPASS_LABELS[i].getLocal();
                float px = x + 17 + (float) ((COMPASS_X[i] * right.x + COMPASS_Z[i] * right.z) * 11);
                float py = y + 17 - (float) ((COMPASS_X[i] * up.x + COMPASS_Z[i] * up.z) * 11);
                font.drawStringWithShadow(text, px - font.getStringWidth(text) / 2f, py - font.FONT_HEIGHT / 2f, i == 0 ? 0xffff5555 : 0xffececec);
            }
            font.drawStringWithShadow("+", x + 17 - font.getStringWidth("+") / 2f, y + 17 - font.FONT_HEIGHT / 2f, 0xffaaaaaa);
        } finally {
            if (depth) GlStateManager.enableDepth();
        }
    }

    /**
     * Positions are borrowed read-only until replaced by the next selection.
     */
    public void highlighted(Set<BlockPos> positions) {
        highlighted = positions;
    }

    public boolean press(double x, double y, int button) {
        return camera.press(viewport, x, y, button);
    }

    public boolean drag(double x, double y) {
        var bounds = view.sceneBounds();
        return bounds != null && camera.drag(x, y, bounds, viewport);
    }

    public boolean release(double x, double y, int button) {
        if (!camera.release(button)) return false;
        if (!viewport.contains(x, y)) return false;
        var hit = pick(x, y);
        return hit == null ? view.clearPicked(view.token()) : view.choose(hit);
    }

    public void pointer(double x, double y, boolean enabled) {
        pointerX = x;
        pointerY = y;
        pointerEnabled = enabled;
    }

    private @Nullable StructurePreviewView.Click pick(double x, double y) {
        var bounds = view.sceneBounds();
        var model = view.model();
        if (bounds == null || model == null || boundaryCursor != model.cells().size() || pickWorld == null || access == null || !access.ready())
            return null;
        var ray = camera.ray(viewport, x, y, bounds);
        if (ray == null) return null;
        var end = ray.origin().add(ray.direction().scale(Math.max(2000, camera.distance(bounds) * 30)));
        return rayIndex.pick(view, ray, cell -> {
            var pos = cell.position();
            double box = StructurePreviewCamera.intersect(ray, pos.getX(), pos.getY(), pos.getZ());
            if (!Double.isFinite(box)) return box;
            var state = access.represented(pos);
            if (state == null) return box;
            if (state.getMaterial() == Material.AIR) return Double.POSITIVE_INFINITY;
            if (StructureMaterials.fluid(state) != null) return box;
            var hit = state.collisionRayTrace(pickWorld, pos, ray.origin(), end);
            return hit == null ? Double.POSITIVE_INFINITY : ray.origin().distanceTo(hit.hitVec);
        });
    }

    private AxisAlignedBB selectionBox(StructurePreviewModel.Cell cell) {
        var state = access == null ? null : access.represented(cell.position());
        return state == null || pickWorld == null || StructureMaterials.fluid(state) != null ? new AxisAlignedBB(cell.position())
            : state.getSelectedBoundingBox(pickWorld, cell.position());
    }

    /**
     * The actual creative middle-pick item, cached for this generation and borrowed read-only.
     */
    public ItemStack item(StructureDisplay display, int option) {
        var mc = Minecraft.getMinecraft();
        var model = view.model();
        if (model == null || pickWorld == null || access == null || !access.ready() || mc.player == null)
            return ItemStack.EMPTY;
        var picked = view.picked();
        boolean pickedDisplay = picked != null && display.equals(picked.display());
        var position = pickedDisplay ? picked.position() : representativePositions.get(display);
        if (position == null) return ItemStack.EMPTY;
        var key = new ItemChoice(display, option, position);
        var cached = itemChoices.get(key);
        if (cached != null) return cached;
        var state = pickedDisplay && picked == view.controllerCell() ? view.externalMainBlock() : null;
        if (state == null) {
            var root = model.generation().entries().get(model.structureId());
            var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
            state = PreviewBlockStates.resolve(display.options().get(option), rotation);
        }
        var result = state == null ? ItemStack.EMPTY : pickWorld.pick(state, position, mc.player, display.tileNbt());
        // Some native pick hooks become ready later; an empty first observation is not a stable icon.
        if (!result.isEmpty()) itemChoices.put(key, result);
        return result;
    }

    /**
     * Complete selected construction resources; the layer, scan and visibility filters are presentation only.
     */
    public @Nullable List<GenericStack> materials() {
        var mc = Minecraft.getMinecraft();
        var model = view.model();
        var currentAccess = access;
        var expected = token;
        if (closed || model == null || mc.player == null || mc.world == null || world != mc.world
            || expected != view.token() || currentAccess == null || !currentAccess.ready()) return null;
        if (materialsPrepared) return materialStacks;
        materialsPrepared = true;
        var quantities = new Object2LongLinkedOpenHashMap<MaterialChoice>();
        var positions = new Object2ObjectOpenHashMap<MaterialChoice, BlockPos>();
        var elements = model.cells();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var cell = elements.get(index2);
            if (cell.position().equals(BlockPos.ORIGIN)) continue;
            var display = cell.display();
            if (display == null || !display.complete()) return null;
            int option = model.settings().option(cell);
            if (model.settings().explicitOption(cell) < 0) {
                var represented = currentAccess.represented(cell.position());
                option = -1;
                for (int index = 0; index < display.options().size(); index++) {
                    var candidate = PreviewBlockStates.selected(model, display, index);
                    if (candidate != null && candidate.equals(represented)) {
                        option = index;
                        break;
                    }
                }
                if (option < 0) return null;
            }
            var choice = new MaterialChoice(display, option);
            quantities.addTo(choice, 1);
            positions.putIfAbsent(choice, cell.position());
        }
        var root = model.generation().entries().get(model.structureId());
        var rotation = StructureTransform.between(model.generation().nodes().get(root.node()).orientation(), model.settings().orientation());
        AEKey2LongMap totals = new AEKeyLinkedOpenHashMap(6);
        // The native pick context contains every layer, even while the GUI renders only one of them.
        try (var picks = new StructurePickWorld(mc.world, position -> {
            var state = currentAccess.represented(position);
            return state == null ? Blocks.AIR.getDefaultState() : state;
        }, position -> {
            var cell = view.at(position);
            return cell == null || cell.display() == null ? null : cell.display().tileNbt();
        })) {
            for (var entry : quantities.object2LongEntrySet()) {
                var choice = entry.getKey();
                var state = PreviewBlockStates.resolve(choice.display.options().get(choice.option), rotation);
                if (state == null) return null;
                if (state.getMaterial() == Material.AIR) continue;
                var fluid = StructureMaterials.fluid(state);
                AEKey key;
                long amount = entry.getLongValue();
                if (fluid != null) {
                    key = AEFluidKey.of(fluid);
                    amount = Math.multiplyExact(amount, Fluid.BUCKET_VOLUME);
                } else {
                    var stack = picks.pick(state, positions.get(choice), mc.player, choice.display.tileNbt());
                    key = AEItemKey.of(stack);
                    if (key == null) return null;
                }
                if (totals.getLong(key) > Long.MAX_VALUE - amount)
                    throw new ArithmeticException("Structure material amount overflow");
                totals.addTo(key, amount);
            }
        }
        if (closed || view.model() != model || view.token() != expected || access != currentAccess || world != mc.world)
            return null;
        var result = new ObjectArrayList<GenericStack>(totals.size());
        totals.forEachEntry((key, amount) -> result.add(new GenericStack(key, amount)));
        materialStacks = result;
        return materialStacks;
    }

    /**
     * A transfer snapshot is revoked by choices, catalog/world changes, resource reload or panel closure.
     */
    public boolean materialsCurrent(List<GenericStack> expected) {
        return !closed && expected == materialStacks && token == view.token() && view.model() != null
            && world == Minecraft.getMinecraft().world;
    }

    /**
     * Finish a read-only scanner drag without choosing a block.
     */
    public void releaseWithoutPicking(int button) {
        camera.release(button);
    }

    public boolean wheel(double x, double y, int delta) {
        return camera.wheel(viewport, x, y, delta);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        PreviewGuiResources.remove(this);
        Throwable failure = null;
        try {
            if (pickWorld != null) pickWorld.close();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            machines.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            tiles.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            meshes.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        for (var subscription : new AutoCloseable[]{invalidationSubscription, closeSubscription})
            try {
                subscription.close();
            } catch (Exception caught) {
                failure = PreviewBlockModelCache.merge(failure, new IllegalStateException("Cannot detach viewer subscription", caught));
            } catch (Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
        PreviewBlockModelCache.rethrow(failure);
    }

    private void prepareController() {
        if (controllerResolved) return;
        var declarations = MachineControllerBlocks.all();
        for (int spent = 0; spent < 512 && controllerCursor < declarations.size(); spent++) {
            var block = declarations.get(controllerCursor++);
            if (block.type() == view.machine()) {
                controller = block;
                controllerResolved = true;
                var id = block.getRegistryName();
                view.controllerDisplay(new StructureDisplay("state:" + id + ":0",
                    List.of(new StructureDisplay.BlockOption(id, 0, Map.of())), null));
                return;
            }
        }
        if (controllerCursor == declarations.size()) controllerResolved = true;
    }

    private void drawMainBlock(IBlockState state) {
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
                for (var layer : ExternalEnumValues.BLOCK_RENDER_LAYERS) {
                    if (!state.getBlock().canRenderInLayer(state, layer)) continue;
                    ForgeHooksClient.setRenderLayer(layer);
                    if (layer == BlockRenderLayer.TRANSLUCENT) {
                        GlStateManager.enableBlend();
                        GlStateManager.depthMask(false);
                    } else {
                        GlStateManager.disableBlend();
                        GlStateManager.depthMask(true);
                    }
                    var buffer = Tessellator.getInstance().getBuffer();
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                    try {
                        buffer.addVertexData(access.renderLayer(state, BlockPos.ORIGIN));
                    } finally {
                        buffer.finishDrawing();
                    }
                    controllerUploader.draw(buffer);
                }
            } finally {
                ForgeHooksClient.setRenderLayer(previousLayer);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GlStateManager.popMatrix();
            before.restore();
        }
    }

    private record ItemChoice(StructureDisplay display, int option, BlockPos position) {
    }

    private record MaterialChoice(StructureDisplay display, int option) {
    }

    private final class NativeTiles implements PreviewTileEntities.Owner<TileEntity> {
        @Override
        public @Nullable TileEntity create(Object currentWorld, IBlockState state, BlockPos pos, @Nullable NBTTagCompound ownTag) {
            var world = (World) currentWorld;
            if (!state.getBlock().hasTileEntity(state)) return null;
            var tile = state.getBlock().createTileEntity(world, state);
            if (tile == null) return null;
            try {
                tile.setWorld(world);
                tile.setPos(pos.toImmutable());
                if (ownTag != null) tile.handleUpdateTag(ownTag);
                return tile;
            } catch (RuntimeException | Error failure) {
                Throwable combined = failure;
                try {
                    tile.invalidate();
                } catch (RuntimeException | Error cleanup) {
                    combined = PreviewBlockModelCache.merge(combined, cleanup);
                }
                PreviewBlockModelCache.rethrow(combined);
                return null;
            }
        }

        @Override
        public void release(TileEntity tile) {
            tile.invalidate();
        }

        @Override
        public void draw(TileEntity tile, BlockPos position, float partialTicks) {
            var before = PreviewBlockModels.State.capture(colors);
            float lightX = OpenGlHelper.lastBrightnessX, lightY = OpenGlHelper.lastBrightnessY;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.enableTexture2D();
                GlStateManager.enableDepth();
                GlStateManager.disableCull();
                GlStateManager.disableLighting();
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
                TileEntityRendererDispatcher.instance.render(tile, position.getX(), position.getY(), position.getZ(), partialTicks, -1, 1);
            } finally {
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
                GlStateManager.popMatrix();
                before.restore();
            }
        }
    }
}
