package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.client.preview.ProjectionInteractions;
import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.MeshKind;
import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Section;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.End;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Forget;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Geometry;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.Hud;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.FloatBuffer;

import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.BUILD_REVEAL_TICKS;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.CHANGED_FILL;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.EDGE;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.FINISH_COLOR_TICKS;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.INTRO_GHOST_TICKS;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.PENDING_FILL;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.PLAN_FILL;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.PLAN_WIRE;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.REMOVE_GHOST_TICKS;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.REMOVE_WIRE_TICKS;
import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.WHITE_HOLD_TICKS;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.INTRO_TICKS;

public final class ConstructionEffectRenderer {
    public static final ConstructionEffectRenderer INSTANCE = new ConstructionEffectRenderer();
    private static final String HUD_KEY = "structure_tool.prototype_eng.ConstructionProgress";
    private static final String PAUSED_KEY = "structure_tool.prototype_eng.ConstructionPaused";
    private static final String COMPLETE_KEY = "structure_tool.prototype_eng.ConstructionComplete";
    private static final int WHITE = 0xf4f8ff;
    private static final int GHOST = 0x8cd8fc;
    private static final int FRAME = 0xf2d573;
    private static final int GREEN = 0x65ef85;
    private static final int PROBLEM = 0xf0ae67;
    private static final int BLOCK_SETTLE_TICKS = 4;
    private static final int MAX_UPLOADS_PER_FRAME = 12;
    private static final int MAX_UPLOAD_BYTES_PER_FRAME = 1 << 20;
    private static final long FRAME_BUILD_NANOS = 1_500_000L;
    private final Long2ObjectLinkedOpenHashMap<ConstructionAnimation> tasks = new Long2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Section> visible = new ObjectArrayList<>();
    private final ConstructionMeshes meshes = new ConstructionMeshes();
    private final FloatBuffer color = BufferUtils.createFloatBuffer(4);
    private @Nullable WorldClient world;
    private @Nullable EntityPlayerSP player;
    private @Nullable SceneParticle particle;
    private @Nullable DynamicTexture gradient;
    private boolean vbo;
    private long tick;
    private long hudTask;
    private long lastParticleUpdate;

    private ConstructionEffectRenderer() {
    }

    public void receive(Minecraft mc, ConstructionEffectPayload payload) {
        if (mc.world == null || mc.world.provider.getDimension() != payload.dimension()) return;
        useWorld(mc.world, mc.player);
        switch (payload) {
            case Geometry geometry -> geometry(geometry);
            case Forget forget -> forget(forget);
            case Hud hud -> status(hud);
            case End end -> end(end);
        }
    }

    private void geometry(Geometry packet) {
        var section = packet.section();
        long key = ConstructionAnimation.key(section.x(), section.y(), section.z());
        var task = tasks.get(packet.taskId());
        if (task == null) {
            if (!packet.snapshot()) return;
            task = begin(packet, packet.anchor(), packet.dismantle(), packet.startTick());
        }
        var batch = task.sections.get(key);
        if (batch == null) {
            if (!packet.snapshot()) return;
            batch = new Section(section.x(), section.y(), section.z());
            task.sections.put(key, batch);
        }
        task.sample(packet.serverTick(), tick);
        batch.apply(packet);
        if (packet.snapshot() || !packet.excluded().isEmpty()) task.markDirty(key, packet.snapshot());
        else task.committed(key);
    }

    private void forget(Forget packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) return;
        var section = packet.section();
        long key = ConstructionAnimation.key(section.x(), section.y(), section.z());
        var batch = task.sections.get(key);
        if (batch == null) return;
        batch.forget(packet.quadrant());
        if (batch.plan.isEmpty()) {
            task.sections.remove(key);
            batch.dispose();
        }
        task.markDirty(key, true);
        if (!task.owner && task.sections.isEmpty()) drop(packet.taskId());
    }

    private void status(Hud packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) task = begin(packet, packet.anchor(), packet.dismantle(), packet.startTick());
        task.owner = true;
        task.complete = packet.complete();
        task.sample(packet.serverTick(), tick);
        task.metrics(packet.missing(), packet.errors(), packet.unloaded(), packet.paused());
        hudTask = Math.max(hudTask, packet.taskId());
    }

    private ConstructionAnimation begin(ConstructionEffectPayload packet, long anchor, boolean dismantle, long startTick) {
        var task = new ConstructionAnimation(dismantle, startTick);
        tasks.put(packet.taskId(), task);
        ProjectionInteractions.INSTANCE.constructionStarted(packet.dimension(), BlockPos.fromLong(anchor));
        return task;
    }

    private void end(End packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) return;
        if (packet.cancelled()) {
            drop(packet.taskId());
            return;
        }
        task.finish(packet.serverTick(), tick, packet.successful());
    }

    private void drop(long id) {
        var removed = tasks.remove(id);
        if (removed != null) removed.dispose();
        if (hudTask == id) hudTask = 0;
    }

    private void useWorld(@Nullable WorldClient world, @Nullable EntityPlayerSP player) {
        if (world == this.world && player == this.player) return;
        clear();
        this.world = world;
        this.player = player;
    }

    private void clear() {
        for (var task : tasks.values()) task.dispose();
        tasks.clear();
        visible.clear();
        hudTask = 0;
        tick = 0;
        lastParticleUpdate = 0;
        if (particle != null) particle.setExpired();
        particle = null;
        if (gradient != null) gradient.deleteGlTexture();
        gradient = null;
        world = null;
        player = null;
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldClient unloaded)) return;
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (world == unloaded) clear();
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getMinecraft();
        useWorld(mc.world, mc.player);
        if (!mc.isGamePaused()) tick++;
        if (tasks.isEmpty() || world == null) return;
        var iterator = tasks.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var task = entry.getValue();
            if (task.expired(tick)) {
                task.dispose();
                if (hudTask == entry.getLongKey()) hudTask = 0;
                iterator.remove();
            }
        }
        if (!tasks.isEmpty() && (particle == null || tick - lastParticleUpdate > 2)) {
            if (particle != null) particle.setExpired();
            particle = new SceneParticle(world);
            lastParticleUpdate = tick;
            mc.effectRenderer.addEffect(particle);
        }
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        var mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.world != world) return;
        var task = tasks.get(hudTask);
        if (task == null) return;
        boolean success = task.complete && task.missing == 0 && task.wrong == 0 && task.unloaded == 0;
        String text;
        if (success) text = I18n.format(COMPLETE_KEY);
        else {
            if (task.hudText == null) task.hudText = I18n.format(task.paused ? PAUSED_KEY : HUD_KEY, task.missing, task.wrong, task.unloaded);
            text = task.hudText;
        }
        int width = event.getResolution().getScaledWidth();
        int height = event.getResolution().getScaledHeight();
        int textColor = success ? GREEN : task.wrong != 0 || task.unloaded != 0 || task.paused ? PROBLEM : WHITE;
        mc.fontRenderer.drawStringWithShadow(text, (width - mc.fontRenderer.getStringWidth(text)) * 0.5F, height - 53, textColor);
    }

    public void invalidateResources() {
        if (gradient != null) gradient.deleteGlTexture();
        gradient = null;
        for (var task : tasks.values()) task.hudText = null;
    }

    @SubscribeEvent
    public void debugText(RenderGameOverlayEvent.Text event) {
        if (tasks.isEmpty() || !Minecraft.getMinecraft().gameSettings.showDebugInfo) return;
        var counters = meshes.metrics();
        event.getRight().add(I18n.format("structure_tool.prototype_eng.ConstructionRenderStats", counters.draws(), counters.uploads(), counters.uploadedBytes()));
    }

    private void render(Entity camera, float partialTicks) {
        var mc = Minecraft.getMinecraft();
        if (tasks.isEmpty() || mc.world != world) return;
        mc.profiler.startSection("peConstruction");
        try {
            renderScene(camera, partialTicks);
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot render construction effects", failure);
            clear();
        } finally {
            mc.profiler.endSection();
        }
    }

    private void renderScene(Entity camera, float partialTicks) {
        boolean newVbo = OpenGlHelper.useVbo();
        if (vbo != newVbo) {
            for (var task : tasks.values()) {
                if (task.frame != null) task.frame.delete();
                task.frame = null;
                var sections = task.sections.long2ObjectEntrySet().fastIterator();
                while (sections.hasNext()) {
                    var entry = sections.next();
                    entry.getValue().dispose();
                    entry.getValue().dirty = ConstructionAnimation.ALL;
                    task.dirty.add(entry.getLongKey());
                }
            }
            vbo = newVbo;
        }
        meshes.resetCounters();
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        double now = tick + (double) partialTicks;
        var frustum = new Frustum();
        frustum.setPosition(x, y, z);
        long deadline = System.nanoTime() + FRAME_BUILD_NANOS;
        var state = ConstructionGlState.capture(color);
        ConstructionGlState.prepare();
        try {
            if (vbo) ConstructionMeshes.beginDraw();
            for (var task : tasks.values()) {
                task.refreshBounds();
                double after = task.finishAge(now);
                if (task.bounds == null || after >= ConstructionEffectPayload.FINISH_TICKS) continue;
                double age = task.age(now);
                visible.clear();
                for (var section : task.sections.values()) if (frustum.isBoundingBoxInFrustum(section.view)) visible.add(section);
                var dirty = task.dirty.iterator();
                while (dirty.hasNext() && canUpload(deadline)) {
                    long key = dirty.nextLong();
                    var section = task.sections.get(key);
                    if (!frustum.isBoundingBoxInFrustum(section.view)) continue;
                    upload(task, section, age, after, deadline);
                    if (section.dirty == 0) dirty.remove();
                }
                for (var section : visible) drawBatch(task, section, age, after, x, y, z);
                drawFrame(task, frustum, after, x, y, z, deadline);
            }
        } finally {
            if (vbo) ConstructionMeshes.endDraw();
            state.restore();
        }
    }

    private boolean canUpload(long deadline) {
        return meshes.canUpload(MAX_UPLOADS_PER_FRAME, MAX_UPLOAD_BYTES_PER_FRAME) && System.nanoTime() < deadline;
    }

    private void upload(ConstructionAnimation task, Section section, double age, double after, long deadline) {
        int variants = task.dismantle
            ? after < 0 ? PENDING_FILL | PLAN_WIRE : after < REMOVE_WIRE_TICKS ? PLAN_WIRE : PLAN_FILL
            : after >= 0 ? CHANGED_FILL : age < INTRO_TICKS ? PLAN_FILL | PLAN_WIRE : PLAN_FILL;
        if (!task.dismantle && age >= INTRO_TICKS && after < 0 && !section.changed.isEmpty()) variants |= CHANGED_FILL;
        int needed = section.dirty & variants;
        while (needed != 0 && canUpload(deadline)) {
            int kind = Integer.lowestOneBit(needed);
            switch (kind) {
                case PLAN_FILL -> section.planFill = rebuild(section.planFill, task, section, MeshKind.PLAN, false);
                case PLAN_WIRE -> section.planWire = rebuild(section.planWire, task, section, MeshKind.PLAN, true);
                case PENDING_FILL -> section.pendingFill = rebuild(section.pendingFill, task, section, MeshKind.PENDING, false);
                case CHANGED_FILL -> section.changedFill = rebuild(section.changedFill, task, section, MeshKind.CHANGED, false);
                default -> throw new IllegalStateException("Unknown construction mesh kind");
            }
            section.dirty &= ~kind;
            needed &= ~kind;
        }
    }

    private @Nullable ConstructionMeshes.Mesh rebuild(@Nullable ConstructionMeshes.Mesh previous, ConstructionAnimation task,
                                                        Section section, MeshKind kind, boolean lines) {
        var next = meshes.build(task, section, kind, lines, vbo);
        if (previous != null) previous.delete();
        return next;
    }

    private void drawBatch(ConstructionAnimation task, Section section, double age, double after, double x, double y, double z) {
        var bounds = task.bounds;
        if (bounds == null) return;
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.translate(section.x * (double) EDGE - x, section.y * (double) EDGE - y, section.z * (double) EDGE - z);
        try {
            if (task.dismantle) {
                if (after >= REMOVE_WIRE_TICKS) {
                    paint(section.planFill, GHOST, 0.28 * (1 - smooth(after - REMOVE_WIRE_TICKS, REMOVE_GHOST_TICKS)));
                } else if (after >= 0) {
                    paint(section.planWire, WHITE, 0.92 * (1 - smooth(after, REMOVE_WIRE_TICKS)));
                } else {
                    double height = interpolate(smooth(age, INTRO_TICKS), bounds.maxY() + 1.0, bounds.minY() - 1.0);
                    masked(section.pendingFill, section.y * EDGE, height, 1.6);
                    paint(section.planWire, WHITE, 0.52 * smooth(age, INTRO_TICKS));
                }
            } else if (after >= 0) {
                double fade = smooth(after - BLOCK_SETTLE_TICKS, BUILD_REVEAL_TICKS - BLOCK_SETTLE_TICKS);
                double height = interpolate(fade, bounds.minY() - 2.0, bounds.maxY() + 2.0);
                masked(section.changedFill, section.y * EDGE, height, 2.0);
            } else if (age < INTRO_GHOST_TICKS) {
                paint(section.planFill, GHOST, 0.33 * (1 - smooth(age, INTRO_GHOST_TICKS)));
                paint(section.planWire, WHITE, 0.85 * smooth(age, INTRO_GHOST_TICKS));
            } else if (age < INTRO_TICKS) {
                double fill = smooth(age - INTRO_GHOST_TICKS, INTRO_TICKS - INTRO_GHOST_TICKS - WHITE_HOLD_TICKS);
                paintSolid(section.planFill, fill);
                paint(section.planWire, WHITE, 0.90 * (1 - fill));
            } else {
                paintSolid(section.planFill, 1.0);
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawFrame(ConstructionAnimation task, Frustum frustum, double after,
                           double x, double y, double z, long deadline) {
        var bounds = task.bounds;
        if (bounds == null || !frustum.isBoxInFrustum(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) return;
        if (task.frame == null && canUpload(deadline))
            task.frame = meshes.frame(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY(), bounds.maxZ() - bounds.minZ(), vbo);
        int transition = task.dismantle ? REMOVE_WIRE_TICKS + REMOVE_GHOST_TICKS : BUILD_REVEAL_TICKS;
        int frameColor = after >= transition && task.success ? GREEN : FRAME;
        double opacity = after < ConstructionEffectPayload.FINISH_TICKS - FINISH_COLOR_TICKS ? .78
            : .78 * (1 - smooth(after - ConstructionEffectPayload.FINISH_TICKS + FINISH_COLOR_TICKS, FINISH_COLOR_TICKS));
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(bounds.minX() - 0.04 - x, bounds.minY() - 0.04 - y, bounds.minZ() - 0.04 - z);
            GlStateManager.scale(1 + .08 / (bounds.maxX() - bounds.minX()), 1 + .08 / (bounds.maxY() - bounds.minY()),
                1 + .08 / (bounds.maxZ() - bounds.minZ()));
            paint(task.frame, frameColor, opacity);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static double smooth(double age, int duration) {
        double value = Math.clamp(age / duration, 0, 1);
        return value * value * (3.0 - 2.0 * value);
    }

    private static double interpolate(double factor, double min, double max) {
        return min + (max - min) * factor;
    }

    private void paint(@Nullable ConstructionMeshes.Mesh mesh, int rgb, double opacity) {
        if (mesh == null || opacity <= 0.004) return;
        setColor(rgb, opacity);
        meshes.draw(mesh);
    }

    private void paintSolid(@Nullable ConstructionMeshes.Mesh mesh, double opacity) {
        if (mesh == null || opacity <= 0.004) return;
        if (opacity < 0.995) {
            paint(mesh, WHITE, opacity);
            return;
        }
        GlStateManager.depthMask(true);
        try {
            paint(mesh, WHITE, 1.0);
        } finally {
            GlStateManager.depthMask(false);
        }
    }

    private void masked(@Nullable ConstructionMeshes.Mesh mesh, int baseY, double height, double feather) {
        if (mesh == null || height - feather >= baseY + EDGE) return;
        if (height + feather <= baseY) {
            paintSolid(mesh, 1.0);
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(gradient());
        GlStateManager.matrixMode(GL11.GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0, 0.5 + (baseY - height) / (2 * feather), 0);
        GlStateManager.scale(1, 1 / (2 * feather), 1);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        try {
            paint(mesh, WHITE, 1.0);
            GlStateManager.depthMask(true);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.99F);
            paint(mesh, WHITE, 1.0);
        } finally {
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.depthMask(false);
            GlStateManager.matrixMode(GL11.GL_TEXTURE);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.disableTexture2D();
        }
    }

    private int gradient() {
        if (gradient == null) {
            gradient = new DynamicTexture(1, 64);
            int[] pixels = gradient.getTextureData();
            for (int i = 0; i < pixels.length; i++) {
                int alpha = (int) (smooth(i, pixels.length - 1) * 255);
                pixels[i] = alpha << 24 | 0xffffff;
            }
            gradient.updateDynamicTexture();
            gradient.setBlurMipmapDirect(true, false);
            GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        }
        return gradient.getGlTextureId();
    }

    private static void setColor(int rgb, double opacity) {
        GlStateManager.color((rgb >>> 16 & 255) / 255F, (rgb >>> 8 & 255) / 255F, (rgb & 255) / 255F, (float) opacity);
    }

    private final class SceneParticle extends Particle {
        SceneParticle(WorldClient world) {
            super(world, 0, 0, 0);
        }

        @Override
        public int getFXLayer() {
            return 3;
        }

        @Override
        public void onUpdate() {
            if (particle == this) lastParticleUpdate = tick;
        }

        @Override
        public void renderParticle(@NotNull BufferBuilder buffer, @NotNull Entity camera, float partialTicks, float rotationX, float rotationZ,
                                   float rotationYZ, float rotationXY, float rotationXZ) {
            if (particle == this) render(camera, partialTicks);
        }
    }
}
