package com.circulation.prototype_eng.api.research.reward;

import net.minecraft.util.ResourceLocation;

/** The display portion of a reward group. Register its type and codec on both physical sides. */
public interface RewardPresentation {
    ResourceLocation type();

    /** Preferred width when composed with other children, in the interval (0, available]. */
    default double width(RewardRenderContext context, double available) {
        return available;
    }

    /** Measures the height on the client for the given width. Must not draw or change state. */
    double height(RewardRenderContext context, double width);

    /** Draws within the bounds measured for this width. */
    void render(RewardRenderContext context, double x, double y, double width);
}
