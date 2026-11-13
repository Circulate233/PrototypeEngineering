package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.ICompositeWidget;
import ae2.client.gui.Tooltip;
import com.circulation.prototype_eng.localization.GuiText;

import java.awt.Rectangle;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Original 14×14 PM automatic IO artwork; a matching release commits the toggle.
 */
final class HatchToggleButton implements ICompositeWidget {
    private final boolean input;
    private final BooleanSupplier selected;
    private final Runnable toggle;
    private final boolean enabled;
    private Point position;
    private int pressedButton = -1;

    HatchToggleButton(boolean input, BooleanSupplier selected, boolean enabled, Runnable toggle, Point position) {
        this.input = input;
        this.selected = Objects.requireNonNull(selected);
        this.enabled = enabled;
        this.toggle = Objects.requireNonNull(toggle);
        this.position = Objects.requireNonNull(position);
    }

    @Override
    public void setPosition(Point value) {
        position = value;
    }

    @Override
    public void setSize(int width, int height) {
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(position.x(), position.y(), 14, 14);
    }

    @Override
    public boolean onMouseDown(Point mouse, int button) {
        if (!getBounds().contains(mouse.x(), mouse.y())) return false;
        if (enabled && (button == 0 || button == 1)) pressedButton = button;
        return true;
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return true;
    }

    @Override
    public boolean onMouseUp(Point mouse, int button) {
        int original = pressedButton;
        pressedButton = -1;
        if (original < 0) return false;
        if (button == original && enabled && getBounds().contains(mouse.x(), mouse.y())) toggle.run();
        return true;
    }

    @Override
    public Tooltip getTooltip(int mouseX, int mouseY) {
        return new Tooltip((input ? GuiText.AutoInput : GuiText.AutoOutput).text());
    }

    @Override
    public boolean blocksMouseInteraction(int mouseX, int mouseY) {
        return getBounds().contains(mouseX, mouseY);
    }

    @Override
    public void drawForegroundLayer(Rectangle bounds, Point mouse) {
        boolean hover = getBounds().contains(mouse.x(), mouse.y());
        int u = !enabled ? 227 : pressedButton >= 0 && hover || selected.getAsBoolean() ? 213 : hover ? 199 : 185;
        GuiAtlas.state(position.x(), position.y(), u, input ? 18 : 1, 14, 14);
    }
}
