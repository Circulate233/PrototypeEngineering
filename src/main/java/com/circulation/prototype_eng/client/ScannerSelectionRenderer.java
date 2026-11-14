package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.item.StructureToolItem;
import com.circulation.prototype_eng.structure.tool.ScannerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * PM scanner endpoint, selected region and small-region block outlines.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ScannerSelectionRenderer {
    private ScannerSelectionRenderer() {
    }

    @SubscribeEvent
    public static void render(RenderWorldLastEvent event) {
        var mc = Minecraft.getMinecraft();
        var player = mc.player;
        var world = mc.world;
        var view = mc.getRenderViewEntity();
        if (player == null || world == null || view == null) return;
        var stack = player.getHeldItemMainhand();
        if (!scanner(stack)) stack = player.getHeldItemOffhand();
        var tag = stack.getTagCompound();
        if (!scanner(stack) || tag == null) return;
        var data = tag.getCompoundTag(ScannerState.ROOT);
        if (data.hasKey("dimension", 3) && data.getInteger("dimension") != world.provider.getDimension()) return;
        var origin = ScannerState.position(data, "origin");
        var corner = ScannerState.position(data, "corner");
        if (origin == null && corner == null) return;
        double x = view.lastTickPosX + (view.posX - view.lastTickPosX) * event.getPartialTicks();
        double y = view.lastTickPosY + (view.posY - view.lastTickPosY) * event.getPartialTicks();
        double z = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * event.getPartialTicks();
        // Direct GL state is restored together; it does not invalidate GlStateManager's cache.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glLineWidth(2);
            if (origin != null) outline(world, origin, x, y, z, .2f, 1, .7f);
            if (corner != null) outline(world, corner, x, y, z, 1, .2f, .7f);
            var box = ScannerState.box(data);
            if (box == null) return;
            var min = box.min();
            var max = box.max();
            var bounds = new AxisAlignedBB(min.getX(), min.getY(), min.getZ(), (double) max.getX() + 1,
                (double) max.getY() + 1, (double) max.getZ() + 1);
            RenderGlobal.drawSelectionBoundingBox(bounds.offset(-x, -y, -z), .2f, 1, .2f, .35f);
            if (!box.exceeds(4096)) {
                for (var pos : BlockPos.getAllInBoxMutable(min, max)) outline(world, pos, x, y, z, .35f, .35f, .2f);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static boolean scanner(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof StructureToolItem;
    }

    private static void outline(World world, BlockPos pos, double x, double y, double z, float red, float blue, float alpha) {
        if (!world.isBlockLoaded(pos) || world.isAirBlock(pos)) return;
        var bounds = world.getBlockState(pos).getSelectedBoundingBox(world, pos);
        RenderGlobal.drawSelectionBoundingBox(bounds.grow(.002).offset(-x, -y, -z), red, .9f, blue, alpha);
    }
}
