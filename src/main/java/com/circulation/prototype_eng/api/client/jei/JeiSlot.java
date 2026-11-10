package com.circulation.prototype_eng.api.client.jei;

import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Full panel bounds and native ingredient inset, with caller supplied skin hooks. */
public record JeiSlot(int x, int y, int width, int height, int inset, Skin skin) {
    public static final Skin PLAIN = new Skin() {
    };

    public JeiSlot {
        Objects.requireNonNull(skin);
        if (width <= 0 || height <= 0 || inset < 0 || inset * 2L >= Math.min(width, height)) {
            throw new IllegalArgumentException("Invalid JEI slot bounds");
        }
    }

    public interface Skin {
        default void background(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource resource) {
        }

        default void foreground(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource resource) {
        }
    }
}
