package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.GenericStack;

import java.util.List;

public interface RewardRenderContext {
    /** Draws a square stack slot with its amount and hover text. */
    void item(GenericStack stack, double x, double y, double size);

    default void item(GenericStack stack, double x, double y) {
        item(stack, x, y, 36);
    }

    void text(String text, double x, double y, double width);

    void tooltip(double x, double y, double width, double height, List<String> lines);
}
