package com.circulation.prototype_eng.client;

import ae2.api.orientation.RelativeSide;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.item.ControllerOrientationTool;
import com.circulation.prototype_eng.item.OrientationToolOrientations;
import com.circulation.prototype_eng.item.OrientationToolOrientations.Part;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Face-local 20/60/20 grid; its hit regions are the same ones used by the server tool. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class OrientationToolGridRenderer {
    private static final BufferBuilder BUFFER = new BufferBuilder(4096);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();

    private OrientationToolGridRenderer() { }

    @SubscribeEvent
    public static void highlight(DrawBlockHighlightEvent event) {
        var mc = Minecraft.getMinecraft();
        var player = event.getPlayer();
        var hit = event.getTarget();
        var camera = mc.getRenderViewEntity();
        if (mc.world == null || camera == null || mc.currentScreen != null || mc.gameSettings.hideGUI
            || !ControllerOrientationTool.rotating(player.getHeldItemMainhand()) && !ControllerOrientationTool.rotating(player.getHeldItemOffhand())
            || hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK || hit.sideHit == null || hit.hitVec == null) return;
        var pos = hit.getBlockPos();
        if (!mc.world.isBlockLoaded(pos, false) || !(mc.world.getTileEntity(pos) instanceof MachineControllerHost controller)) return;
        var face = hit.sideHit;
        var part = OrientationToolOrientations.part(face, (float) (hit.hitVec.x - pos.getX()),
            (float) (hit.hitVec.y - pos.getY()), (float) (hit.hitVec.z - pos.getZ()));
        double partial = event.getPartialTicks();
        var origin = new Vec3d(pos.getX() - (camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partial) + .002 * face.getXOffset(),
            pos.getY() - (camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partial) + .002 * face.getYOffset(),
            pos.getZ() - (camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partial) + .002 * face.getZOffset());
        draw(face, part, controller.orientation().getSide(RelativeSide.FRONT) == face, origin);
    }

    private static void draw(EnumFacing face, Part selected, boolean front, Vec3d origin) {
        double edge = OrientationToolOrientations.EDGE, far = 1 - edge;
        boolean building = false;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2);
            BUFFER.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            building = true;
            quad(face, origin, 0, 0, edge, edge, .15f, .15f, .15f, .22f);
            quad(face, origin, far, 0, 1, edge, .15f, .15f, .15f, .22f);
            quad(face, origin, 0, far, edge, 1, .15f, .15f, .15f, .22f);
            quad(face, origin, far, far, 1, 1, .15f, .15f, .15f, .22f);
            if (selected != Part.CORNER) {
                double left = selected == Part.LEFT ? 0 : selected == Part.RIGHT ? far : edge;
                double right = selected == Part.LEFT ? edge : selected == Part.RIGHT ? 1 : far;
                double bottom = selected == Part.BOTTOM ? 0 : selected == Part.TOP ? far : edge;
                double top = selected == Part.BOTTOM ? edge : selected == Part.TOP ? 1 : far;
                quad(face, origin, left, bottom, right, top, 1, .75f, .2f, .28f);
            }
            BUFFER.finishDrawing();
            building = false;
            UPLOADER.draw(BUFFER);
            BUFFER.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            building = true;
            line(face, origin, 0, 0, 1, 0);
            line(face, origin, 1, 0, 1, 1);
            line(face, origin, 1, 1, 0, 1);
            line(face, origin, 0, 1, 0, 0);
            line(face, origin, edge, 0, edge, 1);
            line(face, origin, far, 0, far, 1);
            line(face, origin, 0, edge, 1, edge);
            line(face, origin, 0, far, 1, far);
            // A turning arrow on the front; a crosshair on other faces denotes setting the front here.
            if (front) {
                line(face, origin, .43, .42, .58, .42);
                line(face, origin, .58, .42, .58, .58);
                line(face, origin, .58, .58, .43, .58);
                line(face, origin, .43, .58, .48, .63);
                line(face, origin, .43, .58, .48, .53);
            } else {
                line(face, origin, .43, .5, .57, .5);
                line(face, origin, .5, .43, .5, .57);
            }
            BUFFER.finishDrawing();
            building = false;
            UPLOADER.draw(BUFFER);
        } finally {
            if (building) BUFFER.finishDrawing();
            BUFFER.reset();
            GL11.glPopAttrib();
        }
    }

    private static void vertex(EnumFacing face, Vec3d origin, double u, double v, float r, float g, float b, float a) {
        var point = OrientationToolOrientations.point(face, u, v).add(origin);
        BUFFER.pos(point.x, point.y, point.z).color(r, g, b, a).endVertex();
    }

    private static void line(EnumFacing face, Vec3d origin, double u, double v, double toU, double toV) {
        vertex(face, origin, u, v, .25f, .85f, 1, .9f);
        vertex(face, origin, toU, toV, .25f, .85f, 1, .9f);
    }

    private static void quad(EnumFacing face, Vec3d origin, double left, double bottom, double right, double top,
                             float r, float g, float b, float a) {
        vertex(face, origin, left, bottom, r, g, b, a);
        vertex(face, origin, right, bottom, r, g, b, a);
        vertex(face, origin, right, top, r, g, b, a);
        vertex(face, origin, left, top, r, g, b, a);
    }
}
