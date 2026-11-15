package com.circulation.prototype_eng.integration.jei;

import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.client.PatternRecipeTransferEvent;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.MachineControllerBlock;
import com.circulation.prototype_eng.localization.JeiText;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Names the controller result through AE2S's normal, client-side pattern transfer event.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
@SideOnly(Side.CLIENT)
public final class StructurePatternOutput {
    private StructurePatternOutput() {
    }

    @SubscribeEvent
    public static void importing(PatternRecipeTransferEvent event) {
        if (event.isCrafting() || !StructurePreviewJeiCategory.UID.equals(event.getRecipeCategoryUid())) return;
        var elements = event.getOutputSlots();
        for (int index = 0; index < elements.size(); index++) {
            var slot = elements.get(index);
            var output = slot.getStack();
            if (output == null || !(output.what() instanceof AEItemKey key)
                || !(Block.getBlockFromItem(key.getItem()) instanceof MachineControllerBlock<?>)) continue;
            var stack = key.toStack(1);
            stack.setStackDisplayName(JeiText.PatternStructureName.getLocal(stack.getDisplayName()));
            var renamed = AEItemKey.of(stack);
            if (renamed == null) continue;
            slot.getCandidates().clear();
            slot.setStack(new GenericStack(renamed, output.amount()));
        }
    }
}
