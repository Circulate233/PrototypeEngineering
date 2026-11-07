package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.Tags;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/** The original disabled-mode attack advancement is granted by its impossible criterion. */
final class OrientationToolAdvancements {
    private static final ResourceLocation CHERISH_TOOL = new ResourceLocation(Tags.MOD_ID, "cherish_tool");

    private OrientationToolAdvancements() { }

    static void grantCherishTool(EntityPlayerMP player) {
        var advancement = Objects.requireNonNull(player.getServer()).getAdvancementManager().getAdvancement(CHERISH_TOOL);
        if (advancement != null && !player.getAdvancements().getProgress(advancement).isDone()) {
            player.getAdvancements().grantCriterion(advancement, "trigger");
        }
    }
}
