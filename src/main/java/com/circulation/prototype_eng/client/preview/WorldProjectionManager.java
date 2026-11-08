package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * PM outline/ghost-box and native textured block-model projection; display-only, client-owner presentation.
 */
public final class WorldProjectionManager implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final StructurePreviews previews;
    private final StructureProjectionState state;
    private final PreviewBlockModels blockModels;
    private final AutoCloseable catalogSubscription;
    private final AutoCloseable closeSubscription;
    private boolean closed;
    private final BufferBuilder buffer = new BufferBuilder(1 << 16);
    private final WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
    private final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(4);

    public WorldProjectionManager(StructurePreviews previews, ClientStructureCatalog catalog) {
        this.previews = previews;
        state = new StructureProjectionState(previews);
        blockModels = new PreviewBlockModels(previews);
        catalogSubscription = catalog.onInvalidated(blockModels::invalidate);
        closeSubscription = previews.onClosed(this::close);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Projection renderer requires its client owner thread");
    }

    public void invalidateResources() {
        requireOwner();
        if (!closed) blockModels.invalidate();
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            state.clear();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            MinecraftForge.EVENT_BUS.unregister(this);
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            blockModels.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            catalogSubscription.close();
        } catch (Exception caught) {
            failure = PreviewBlockModelCache.merge(failure, new IllegalStateException(caught));
        } catch (Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            closeSubscription.close();
        } catch (Exception caught) {
            failure = PreviewBlockModelCache.merge(failure, new IllegalStateException(caught));
        } catch (Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        PreviewBlockModelCache.rethrow(failure);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (previews.closed()) {
            close();
            return;
        }
        var world = Minecraft.getMinecraft().world;
        try {
            state.tick(world, (cell, position) -> observe(world, cell, position));
        } catch (RuntimeException failure) {
            reportFailure(failure);
        }
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        if (previews.closed()) return;
        var minecraft = Minecraft.getMinecraft();
        var camera = minecraft.getRenderViewEntity();
        if (minecraft.world == null || camera == null) return;
        var intent = previews.projection();
        if (intent == null) return;
        var originalRound = state.roundToken();
        try {
            var samples = state.frame(minecraft.world);
            if (samples.isEmpty()) return;
            double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.getPartialTicks();
            double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.getPartialTicks();
            double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.getPartialTicks();
            if (intent.options().style() == StructureProjectionOptions.Style.BLOCK_MODEL) {
                var request = previews.request(intent.id(), intent.settings());
                var model = request == null ? null : request.model();
                if (model != null && previews.current(model)) {
                    var unknown = blockModels.render(model, samples, intent, x, y, z);
                    if (!unknown.isEmpty() && !previews.closed() && previews.current(model) && previews.projection() == intent) {
                        var options = intent.options();
                        draw(unknown, new StructureProjectionOptions(StructureProjectionOptions.Style.BOX_OUTLINE,
                            options.hideDisplayedMatches(), options.layer(), options.expansionStepsPerTick(), options.scanChecksPerTick(),
                            options.renderVisitsPerFrame(), options.maxDistance()), x, y, z);
                    }
                }
                return;
            }
            draw(samples, intent.options(), x, y, z);
        } catch (RuntimeException failure) {
            state.cancelFailedRound(intent, originalRound);
            reportFailure(failure);
        }
    }

    private void reportFailure(RuntimeException failure) {
        PrototypeEngineering.LOGGER.error("Cannot present structure projection", failure);
    }

    @SuppressWarnings("deprecation") // Published 1.12 block options encode canonical registry metadata.
    private StructureProjectionState.Status observe(World world, StructurePreviewModel.Cell cell, BlockPos position) {
        if (!world.isBlockLoaded(position, false)) return StructureProjectionState.Status.UNLOADED;
        var display = cell.display();
        if (display == null || display.tileNbt() != null) return StructureProjectionState.Status.UNKNOWN;
        var intent = previews.projection();
        if (intent == null) return StructureProjectionState.Status.UNKNOWN;
        var request = previews.request(intent.id(), intent.settings());
        var model = request == null ? null : request.model();
        if (model == null) return StructureProjectionState.Status.UNKNOWN;
        var rotation = StructureTransform.between(model.generation().nodes().get(model.generation().entries().get(model.structureId()).node()).orientation(),
            model.settings().orientation());
        var actual = world.getBlockState(position);
        boolean unresolved = false;
        for (var option : display.options()) {
            if (!ForgeRegistries.BLOCKS.containsKey(option.blockId())) {
                unresolved = true;
                continue;
            }
            var block = ForgeRegistries.BLOCKS.getValue(option.blockId());
            if (block == null || !option.nbt().isEmpty()) {
                unresolved = true;
                continue;
            }
            if (actual == rotateState(block.getStateFromMeta(option.meta()), rotation))
                return StructureProjectionState.Status.DISPLAY_MATCH;
        }
        if (unresolved) return StructureProjectionState.Status.UNKNOWN;
        return actual.getBlock().isAir(actual, world, position)
            ? StructureProjectionState.Status.MISSING : StructureProjectionState.Status.DIFFERENT;
    }

    /**
     * Same facing/axis rotation as existing StructurePredicates; no predicate runs on the client.
     */
    @SuppressWarnings("unchecked")
    private static IBlockState rotateState(IBlockState state, StructureTransform rotation) {
        var result = state;
        for (var property : state.getPropertyKeys()) {
            if (property.getValueClass() == EnumFacing.class) {
                var facing = (IProperty<EnumFacing>) property;
                var transformed = rotation.apply(state.getValue(facing));
                if (facing.getAllowedValues().contains(transformed)) result = result.withProperty(facing, transformed);
            } else if (property.getValueClass() == EnumFacing.Axis.class) {
                var axis = (IProperty<EnumFacing.Axis>) property;
                var representative = switch (state.getValue(axis)) {
                    case X -> EnumFacing.EAST;
                    case Y -> EnumFacing.UP;
                    case Z -> EnumFacing.NORTH;
                };
                var transformed = rotation.apply(representative).getAxis();
                if (axis.getAllowedValues().contains(transformed)) result = result.withProperty(axis, transformed);
            }
        }
        return result;
    }

    private void draw(List<StructureProjectionState.Sample> samples, StructureProjectionOptions options,
                      double cameraX, double cameraY, double cameraZ) {
        var before = RenderState.capture(colorBuffer);
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.glLineWidth(2.0f);
            GlStateManager.depthMask(false);
            if (options.style() != StructureProjectionOptions.Style.BOX_OUTLINE)
                batch(samples, options, cameraX, cameraY, cameraZ, false);
            if (options.style() != StructureProjectionOptions.Style.TRANSLUCENT_BOX)
                batch(samples, options, cameraX, cameraY, cameraZ, true);
        } finally {
            before.restore();
        }
    }

    private void batch(List<StructureProjectionState.Sample> samples, StructureProjectionOptions options,
                       double cameraX, double cameraY, double cameraZ, boolean outline) {
        boolean building = false;
        try {
            buffer.begin(outline ? GL11.GL_LINES : GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            building = true;
            for (var sample : samples) {
                var position = sample.position();
                double x = position.getX() - cameraX, y = position.getY() - cameraY, z = position.getZ() - cameraZ;
                double dx = x + 0.5, dy = y + 0.5, dz = z + 0.5;
                if (dx * dx + dy * dy + dz * dz > options.maxDistance() * options.maxDistance()) continue;
                var color = color(sample, outline, options.style());
                if (outline) outline(x, y, z, color);
                else ghost(x, y, z, color);
            }
            buffer.finishDrawing();
            building = false;
            uploader.draw(buffer);
        } finally {
            if (building) buffer.finishDrawing();
            buffer.reset();
        }
    }

    private static Color color(StructureProjectionState.Sample sample, boolean outline, StructureProjectionOptions.Style style) {
        if (outline && style == StructureProjectionOptions.Style.BOX_AND_OUTLINE)
            return sample.status() == StructureProjectionState.Status.UNLOADED
                ? new Color(1.0f, 0.85f, 0.2f, 0.35f) : new Color(1.0f, 1.0f, 1.0f, 0.28f);
        if (!outline && sample.status() != StructureProjectionState.Status.UNLOADED)
            return sample.cell().display() == null ? new Color(0.78f, 0.78f, 0.78f, 0.22f) : new Color(0.55f, 0.85f, 1.0f, 0.32f);
        return switch (sample.status()) {
            case DISPLAY_MATCH -> new Color(0.2f, 1.0f, 0.2f, 0.2f);
            case MISSING, DIFFERENT -> new Color(1.0f, 0.2f, 0.2f, 0.55f);
            case UNLOADED -> new Color(1.0f, 0.85f, 0.2f, 0.25f);
            case UNKNOWN -> new Color(0.7f, 0.7f, 0.7f, 0.25f);
        };
    }

    // PM putGhostAabb: six faces, slightly shrunk to avoid fighting with real blocks.
    private void ghost(double x, double y, double z, Color c) {
        double a = x + 0.002, b = y + 0.002, d = z + 0.002;
        double e = x + 0.998, f = y + 0.998, g = z + 0.998;
        quad(a, b, d, e, b, d, e, b, g, a, b, g, c);
        quad(a, f, d, a, f, g, e, f, g, e, f, d, c);
        quad(a, b, d, a, f, d, e, f, d, e, b, d, c);
        quad(a, b, g, e, b, g, e, f, g, a, f, g, c);
        quad(a, b, d, a, b, g, a, f, g, a, f, d, c);
        quad(e, b, d, e, f, d, e, f, g, e, b, g, c);
    }

    // PM putOutlinedAabb: twelve edges in one GL_LINES batch.
    private void outline(double x, double y, double z, Color c) {
        double a = x - 0.002, b = y - 0.002, d = z - 0.002;
        double e = x + 1.002, f = y + 1.002, g = z + 1.002;
        line(a, b, d, e, b, d, c);
        line(e, b, d, e, b, g, c);
        line(e, b, g, a, b, g, c);
        line(a, b, g, a, b, d, c);
        line(a, f, d, e, f, d, c);
        line(e, f, d, e, f, g, c);
        line(e, f, g, a, f, g, c);
        line(a, f, g, a, f, d, c);
        line(a, b, d, a, f, d, c);
        line(e, b, d, e, f, d, c);
        line(e, b, g, e, f, g, c);
        line(a, b, g, a, f, g, c);
    }

    private void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                      double x3, double y3, double z3, double x4, double y4, double z4, Color color) {
        vertex(x1, y1, z1, color);
        vertex(x2, y2, z2, color);
        vertex(x3, y3, z3, color);
        vertex(x4, y4, z4, color);
    }

    private void line(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
        vertex(x1, y1, z1, color);
        vertex(x2, y2, z2, color);
    }

    private void vertex(double x, double y, double z, Color color) {
        buffer.pos(x, y, z).color(color.r, color.g, color.b, color.a).endVertex();
    }

    private record Color(float r, float g, float b, float a) {
    }

    /**
     * Restore every changed GL field through GlStateManager so its caches agree with native state.
     */
    private record RenderState(boolean texture, boolean lighting, boolean cull, boolean blend, boolean depthMask,
                               int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, float lineWidth,
                               float r, float g, float b, float a) {
        static RenderState capture(FloatBuffer colors) {
            colors.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colors);
            return new RenderState(GL11.glIsEnabled(GL11.GL_TEXTURE_2D), GL11.glIsEnabled(GL11.GL_LIGHTING),
                GL11.glIsEnabled(GL11.GL_CULL_FACE), GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GL11.glGetFloat(GL11.GL_LINE_WIDTH), colors.get(0), colors.get(1), colors.get(2), colors.get(3));
        }

        void restore() {
            if (texture) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            if (cull) GlStateManager.enableCull();
            else GlStateManager.disableCull();
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            GlStateManager.depthMask(depthMask);
            GlStateManager.glLineWidth(lineWidth);
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GlStateManager.color(r, g, b, a);
        }
    }
}
