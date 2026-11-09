package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;
import net.minecraft.client.gui.GuiButton;
import ae2.client.gui.widgets.ITooltip;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.util.text.ITextComponent;
import java.awt.Rectangle;
import java.util.List;

import java.util.function.BooleanSupplier;

/** PM states.png automatic-input/output buttons, including their disabled and hover sprites. */
final class HatchToggleButton extends GuiButton implements ITooltip {
    private final boolean input;
    private final BooleanSupplier selected;

    HatchToggleButton(int id, int x, int y, boolean input, BooleanSupplier selected, boolean enabled) {
        super(id, x, y, 14, 14, "");
        this.input = input;
        this.selected = selected;
        this.enabled = enabled;
    }

    @Override public @NotNull List<ITextComponent> getTooltipMessage() {
        return List.of((input ? GuiText.AutoInput : GuiText.AutoOutput).text());
    }
    @Override public Rectangle getTooltipArea() { return new Rectangle(x, y, width, height); }
    @Override public boolean isTooltipAreaVisible() { return visible; }

    @Override
    public void drawButton(@NotNull Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
        if (!visible) return;
        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        int u = !enabled ? 227 : selected.getAsBoolean() ? 213 : hovered ? 199 : 185;
        GuiAtlas.state(x, y, u, input ? 18 : 1, 14, 14);
    }
}
