package com.circulation.prototype_eng.api.research.reward;

import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

public record TextRewardPresentation(String text) implements RewardPresentation {
    public static final ResourceLocation TYPE = new ResourceLocation(Tags.MOD_ID, "reward_text");

    public TextRewardPresentation {
        Objects.requireNonNull(text);
    }

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    @Override
    public double height(RewardRenderContext context, double width) {
        return 16;
    }

    @Override
    public void render(RewardRenderContext context, double x, double y, double width) {
        context.text(text, x, y, width);
    }
}
