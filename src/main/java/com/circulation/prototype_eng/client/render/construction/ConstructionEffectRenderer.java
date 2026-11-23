package com.circulation.prototype_eng.client.render.construction;

import com.circulation.prototype_eng.client.preview.ProjectionInteractions;
import com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.Slice;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionArea;
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
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;

import static com.circulation.prototype_eng.client.render.construction.ConstructionAnimation.MIN_OPACITY;
import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.FLUID;
import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.SOLID;
import static com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload.LAYERS_PER_SECTION;

public final class ConstructionEffectRenderer {
    public static final ConstructionEffectRenderer INSTANCE = new ConstructionEffectRenderer();
    private static final String HUD_KEY = "structure_tool.prototype_eng.ConstructionProgress";
    private static final String PAUSED_KEY = "structure_tool.prototype_eng.ConstructionPaused";
    private static final String COMPLETE_KEY = "structure_tool.prototype_eng.ConstructionComplete";
    private static final int WHITE = 0xf4f8ff;
    private static final int GREEN = 0x65ef85;
    private static final int PROBLEM = 0xf0ae67;
    private static final long FRAME_BUILD_NANOS = 1_500_000L;
    private final Long2ObjectLinkedOpenHashMap<ConstructionAnimation> tasks = new Long2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Slice> visible = new ObjectArrayList<>();
    private final ConstructionMeshes meshes = new ConstructionMeshes();
    private final FloatBuffer color = BufferUtils.createFloatBuffer(4);
    private final long[] frameSamples = new long[240];
    private @Nullable WorldClient world;
    private @Nullable EntityPlayerSP player;
    private boolean vbo;
    private long tick;
    private long hudTask;
    private int frame;
    private long lastFrameStatsTick = Long.MIN_VALUE;
    private @Nullable String frameStats;

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
        var task = tasks.get(packet.taskId());
        if (task == null) {
            var area = packet.area();
            if (area == null) return;
            task = begin(packet, packet.anchor(), packet.dismantle(), packet.startTick(), area);
        }
        task.geometry(packet, tick);
    }

    private void forget(Forget packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) return;
        var section = packet.section();
        int x = section.chunkX(packet.quadrant()), y = section.y() * LAYERS_PER_SECTION, z = section.chunkZ(packet.quadrant());
        for (int i = 0; i < LAYERS_PER_SECTION; i++) task.forget(x, y + i, z);
        if (!task.owner && task.slices.isEmpty()) drop(packet.taskId());
    }

    private void status(Hud packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) task = begin(packet, packet.anchor(), packet.dismantle(), packet.startTick(), packet.area());
        if (task.complete != packet.complete()) task.hudText = null;
        task.owner = true;
        task.complete = packet.complete();
        task.sample(packet.serverTick());
        task.metrics(packet.missing(), packet.errors(), packet.unloaded(), packet.paused());
        hudTask = Math.max(hudTask, packet.taskId());
    }

    private ConstructionAnimation begin(ConstructionEffectPayload packet, long anchor, boolean dismantle, long startTick, ConstructionArea area) {
        var task = new ConstructionAnimation(dismantle, startTick, area);
        tasks.put(packet.taskId(), task);
        ProjectionInteractions.INSTANCE.constructionStarted(packet.dimension(), BlockPos.fromLong(anchor));
        return task;
    }

    private void end(End packet) {
        var task = tasks.get(packet.taskId());
        if (task == null) return;
        if (packet.cancelled()) drop(packet.taskId());
        else task.finish(packet.serverTick(), tick);
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
        meshes.cancel();
        for (var task : tasks.values()) task.dispose();
        tasks.clear();
        visible.clear();
        hudTask = 0;
        tick = 0;
        lastFrameStatsTick = Long.MIN_VALUE;
        frameStats = null;
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
        if (world == null) return;
        var iterator = tasks.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().expired(tick)) continue;
            entry.getValue().dispose();
            if (hudTask == entry.getLongKey()) hudTask = 0;
            iterator.remove();
        }
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        var mc = Minecraft.getMinecraft();
        var viewer = mc.player;
        if (mc.currentScreen != null || mc.world != world || viewer == null || viewer != player) return;
        var task = tasks.get(hudTask);
        float partial = event.getPartialTicks();
        if (task == null || !task.area.withinRenderDistance(
            interpolate(partial, viewer.lastTickPosX, viewer.posX), interpolate(partial, viewer.lastTickPosY, viewer.posY),
            interpolate(partial, viewer.lastTickPosZ, viewer.posZ))) return;
        boolean success = task.complete && task.missing == 0 && task.wrong == 0 && task.unloaded == 0;
        if (task.hudText == null) task.hudText = success ? I18n.format(COMPLETE_KEY)
            : I18n.format(task.paused ? PAUSED_KEY : HUD_KEY, task.missing, task.wrong, task.unloaded);
        int width = event.getResolution().getScaledWidth();
        int height = event.getResolution().getScaledHeight();
        int textColor = success ? GREEN : task.wrong != 0 || task.unloaded != 0 || task.paused ? PROBLEM : WHITE;
        mc.fontRenderer.drawStringWithShadow(task.hudText, (width - mc.fontRenderer.getStringWidth(task.hudText)) * 0.5F, height - 53, textColor);
    }

    public void invalidateResources() {
        frameStats = null;
        for (var task : tasks.values()) task.hudText = null;
    }

    @SubscribeEvent
    public void debugText(RenderGameOverlayEvent.Text event) {
        var mc = Minecraft.getMinecraft();
        if (tasks.isEmpty() || !mc.gameSettings.showDebugInfo) return;
        var counters = meshes.metrics();
        event.getRight().add(I18n.format("structure_tool.prototype_eng.ConstructionRenderStats", counters.draws(), counters.uploads(), counters.uploadedBytes()));
        if (frameStats == null || tick - lastFrameStatsTick >= 20) {
            int count = 0;
            for (long sample : mc.getFrameTimer().getFrames()) {
                if (sample > 0) frameSamples[count++] = sample;
            }
            if (count == 0) return;
            Arrays.sort(frameSamples, 0, count);
            frameStats = String.format(Locale.ROOT, "P95 %.2f ms, P99 %.2f ms", percentile(count, 95), percentile(count, 99));
            lastFrameStatsTick = tick;
        }
        event.getRight().add(frameStats);
    }

    private double percentile(int count, int percent) {
        return frameSamples[(count * percent + 99) / 100 - 1] / 1_000_000.0;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        var mc = Minecraft.getMinecraft();
        var camera = mc.getRenderViewEntity();
        var viewer = mc.player;
        if (tasks.isEmpty() || mc.world != world || camera == null || viewer == null || viewer != player) return;
        mc.profiler.startSection("peConstruction");
        try {
            renderScene(camera, viewer, event.getPartialTicks());
        } finally {
            visible.clear();
            mc.profiler.endSection();
        }
    }

    private void renderScene(Entity camera, EntityPlayerSP viewer, float partial) {
        meshes.beginFrame();
        frame++;
        double viewerX = interpolate(partial, viewer.lastTickPosX, viewer.posX);
        double viewerY = interpolate(partial, viewer.lastTickPosY, viewer.posY);
        double viewerZ = interpolate(partial, viewer.lastTickPosZ, viewer.posZ);
        double x = interpolate(partial, camera.lastTickPosX, camera.posX);
        double y = interpolate(partial, camera.lastTickPosY, camera.posY);
        double z = interpolate(partial, camera.lastTickPosZ, camera.posZ);
        double now = tick + (double) partial;
        Frustum frustum = null;
        boolean draw = false;
        for (var task : tasks.values()) {
            if (task.slices.isEmpty() || !task.area.withinRenderDistance(viewerX, viewerY, viewerZ)) continue;
            if (frustum == null) {
                frustum = new Frustum();
                frustum.setPosition(x, y, z);
            }
            double wire = task.wireOpacity(now);
            boolean prepareWire = task.prepareOutline(now);
            for (var slice : task.slices.values()) {
                double solid = slice.hasSolid ? task.shellOpacity(slice.solid, now) : 0;
                double fluid = slice.fluids.isEmpty() ? 0 : task.shellOpacity(slice.fluid, now);
                boolean drawSlice = Math.max(wire, Math.max(solid, fluid)) > MIN_OPACITY;
                boolean prepareSlice = prepareWire && slice.wireVersion != slice.version;
                if ((!drawSlice && !prepareSlice) || !frustum.isBoundingBoxInFrustum(slice.view)) continue;
                slice.seenFrame = frame;
                slice.prepareWire = prepareSlice;
                slice.wireOpacity = wire;
                slice.solidMesh.opacity = solid;
                slice.fluidMesh.opacity = fluid;
                visible.add(slice);
                draw |= drawSlice;
            }
        }
        if (visible.isEmpty()) {
            meshes.cancel();
            return;
        }
        boolean useVbo = OpenGlHelper.useVbo();
        if (vbo != useVbo) {
            meshes.cancel();
            for (var task : tasks.values()) task.invalidateMeshes();
            vbo = useVbo;
        }
        var state = ConstructionGlState.capture(color);
        try {
            upload(System.nanoTime() + FRAME_BUILD_NANOS);
            if (!draw) return;
            ConstructionGlState.prepare();
            if (vbo) GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            try {
                drawShells(x, y, z);
                drawWires(x, y, z);
            } finally {
                if (vbo) GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            }
        } finally {
            state.restore();
        }
    }

    private void upload(long deadline) {
        boolean readyToPrefetch = true;
        if (meshes.preparingOutline()) {
            for (int i = 0; i < visible.size(); i++) {
                var slice = visible.get(i);
                if (slice.wireOpacity > MIN_OPACITY && slice.wireVersion != slice.version
                    || slice.solidMesh.opacity > MIN_OPACITY && slice.solidMesh.version != slice.version
                    || slice.fluidMesh.opacity > MIN_OPACITY && slice.fluidMesh.version != slice.version) {
                    readyToPrefetch = false;
                    break;
                }
            }
        }
        if (!meshes.resume(frame, deadline, vbo, readyToPrefetch)) return;
        for (int i = 0; i < visible.size(); i++) {
            var slice = visible.get(i);
            if (slice.wireOpacity > MIN_OPACITY && !meshes.build(slice, (byte) 0, deadline, vbo)) return;
            if (slice.solidMesh.opacity > MIN_OPACITY && !meshes.build(slice, SOLID, deadline, vbo)) return;
            if (slice.fluidMesh.opacity > MIN_OPACITY && !meshes.build(slice, FLUID, deadline, vbo)) return;
        }
        for (int i = 0; i < visible.size(); i++) {
            var slice = visible.get(i);
            if (slice.prepareWire && !meshes.build(slice, (byte) 0, deadline, vbo)) return;
        }
    }

    private void drawShells(double x, double y, double z) {
        GlStateManager.enablePolygonOffset();
        GlStateManager.doPolygonOffset(-1.0F, -10.0F);
        for (int i = 0; i < visible.size(); i++) {
            var slice = visible.get(i);
            var solid = slice.solidMesh;
            var fluid = slice.fluidMesh;
            boolean showSolid = solid.opacity > MIN_OPACITY && solid.mesh != null && solid.version == slice.version;
            boolean showFluid = fluid.opacity > MIN_OPACITY && fluid.mesh != null && fluid.version == slice.version;
            if (!showSolid && !showFluid) continue;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(slice.view.minX - x, slice.view.minY - y, slice.view.minZ - z);
                if (showSolid) paint(solid.mesh, solid.opacity);
                if (showFluid) paint(fluid.mesh, fluid.opacity);
            } finally {
                GlStateManager.popMatrix();
            }
        }
        GlStateManager.disablePolygonOffset();
    }

    private void drawWires(double x, double y, double z) {
        for (int i = 0; i < visible.size(); i++) {
            var slice = visible.get(i);
            if (slice.wire == null || slice.wireOpacity <= MIN_OPACITY || slice.wireVersion != slice.version) continue;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(slice.view.minX - x, slice.view.minY - y, slice.view.minZ - z);
                paint(slice.wire, slice.wireOpacity);
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    private void paint(ConstructionMeshes.Mesh mesh, double opacity) {
        GlStateManager.color(244 / 255F, 248 / 255F, 1, (float) opacity);
        meshes.draw(mesh);
    }

    private static double interpolate(double factor, double min, double max) {
        return min + (max - min) * factor;
    }
}
