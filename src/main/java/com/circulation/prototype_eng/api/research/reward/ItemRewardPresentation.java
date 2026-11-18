package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

public record ItemRewardPresentation(GenericStack stack) implements RewardPresentation {
    public static final ResourceLocation TYPE = new ResourceLocation(Tags.MOD_ID, "reward_item");
    private static final int SLOT_SIZE = 36;

    public ItemRewardPresentation {
        if (stack.amount() <= 0) throw new IllegalArgumentException("Reward display amount must be positive");
    }

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    @Override
    public double width(RewardRenderContext context, double available) {
        return Math.min(SLOT_SIZE, available);
    }

    @Override
    public double height(RewardRenderContext context, double width) {
        return Math.min(SLOT_SIZE, width);
    }

    @Override
    public void render(RewardRenderContext context, double x, double y, double width) {
        context.item(stack, x, y, Math.min(SLOT_SIZE, width));
    }
}
