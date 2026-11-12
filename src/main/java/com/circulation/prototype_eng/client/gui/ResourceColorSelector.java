package com.circulation.prototype_eng.client.gui;

import ae2.api.util.AEColor;
import ae2.client.Point;
import ae2.client.gui.ICompositeWidget;
import ae2.client.gui.Tooltip;
import com.circulation.prototype_eng.localization.MeAssemblyText;
import net.minecraft.client.gui.Gui;
import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared source-artwork palette for physical resource hatch color groups. */
public final class ResourceColorSelector implements ICompositeWidget {
    private static final AEColor[] COLORS = {AEColor.RED, AEColor.BROWN, AEColor.ORANGE, AEColor.YELLOW,
        AEColor.LIME, AEColor.GREEN, AEColor.CYAN, AEColor.LIGHT_BLUE, AEColor.BLUE, AEColor.PURPLE,
        AEColor.MAGENTA, AEColor.PINK, AEColor.WHITE, AEColor.LIGHT_GRAY, AEColor.GRAY, AEColor.BLACK};
    private final Supplier<AEColor> selected;
    private final Consumer<AEColor> choose;
    private Point position = new Point(4, 13);
    private boolean expanded;
    private int pressedButton = -1;
    private boolean pressedExpanded;
    private @Nullable AEColor pressedColor;

    public ResourceColorSelector(Supplier<AEColor> selected, Consumer<AEColor> choose) {
        this.selected = Objects.requireNonNull(selected);
        this.choose = Objects.requireNonNull(choose);
    }

    public boolean expanded() {
        return expanded;
    }

    public void collapse() {
        expanded = false;
        pressedButton = -1;
        pressedColor = null;
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
        return new Rectangle(position.x(), position.y(), expanded ? 56 : 14, expanded ? 71 : 14);
    }

    @Override
    public boolean wantsAllMouseDownEvents() {
        return expanded;
    }

    @Override
    public boolean onMouseDown(Point mouse, int button) {
        if (expanded) {
            pressedButton = button;
            pressedExpanded = true;
            pressedColor = colorAt(mouse.x(), mouse.y());
            return true;
        }
        if ((button == 0 || button == 1) && getBounds().contains(mouse.x(), mouse.y())) {
            pressedButton = button;
            pressedExpanded = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return true;
    }

    @Override
    public boolean onMouseUp(Point mouse, int button) {
        if (pressedButton < 0) return false;
        int original = pressedButton;
        pressedButton = -1;
        if (button != original) {
            pressedColor = null;
            return true;
        }
        if (pressedExpanded) {
            var hovered = colorAt(mouse.x(), mouse.y());
            if (button == 0 && pressedColor != null && pressedColor == hovered) choose.accept(pressedColor);
            expanded = false;
        } else if (getBounds().contains(mouse.x(), mouse.y())) {
            expanded = true;
        }
        pressedColor = null;
        return true;
    }

    @Override
    public boolean onKeyTyped(char typedChar, int keyCode) {
        if (expanded && keyCode == 1) {
            collapse();
            return true;
        }
        return false;
    }

    @Override
    public boolean blocksTooltips(int mouseX, int mouseY) {
        return expanded || getBounds().contains(mouseX, mouseY);
    }

    @Override
    public boolean blocksMouseInteraction(int mouseX, int mouseY) {
        return expanded || getBounds().contains(mouseX, mouseY);
    }

    @Override
    public boolean blocksHeiTargets() {
        return expanded;
    }

    @Override
    public @Nullable Tooltip getTooltip(int mouseX, int mouseY) {
        if (!getBounds().contains(mouseX, mouseY)) return null;
        AEColor hovered = expanded ? colorAt(mouseX, mouseY) : selected.get();
        return hovered == null ? null : new Tooltip(MeAssemblyText.ColorChannel.text(hovered.text()));
    }

    private @Nullable AEColor colorAt(int x, int y) {
        int relativeX = x - position.x() - 4, relativeY = y - position.y() - 4;
        if (relativeX >= -1 && relativeX < 7 && relativeY >= 56 && relativeY < 64) return AEColor.TRANSPARENT;
        if (relativeX < 0 || relativeY < 0 || relativeX >= 52 || relativeY >= 52
            || relativeX % 13 >= 9 || relativeY % 13 >= 9) return null;
        return COLORS[relativeY / 13 * 4 + relativeX / 13];
    }

    @Override
    public void drawForegroundLayer(Rectangle bounds, Point mouse) {
        if (expanded) return;
        boolean hovered = getBounds().contains(mouse.x(), mouse.y());
        HatchGuiSprites.colorButton(position.x(), position.y(), hovered, pressedButton >= 0 && hovered);
        drawSwatch(position.x() + 3, position.y() + 3, selected.get());
    }

    @Override
    public void drawAbsoluteLayer(Rectangle bounds, Point mouse) {
        if (!expanded) return;
        GuiAtlas.draw(GuiAtlas.texture("color_palette"), bounds.x + position.x(), bounds.y + position.y(),
            0, 0, 56, 57, 56, 57);
        int x = bounds.x + position.x(), y = bounds.y + position.y() + 57;
        boolean hovered = colorAt(mouse.x(), mouse.y()) == AEColor.TRANSPARENT;
        HatchGuiSprites.colorButton(x, y, hovered, pressedButton >= 0 && hovered);
        drawSwatch(x + 3, y + 3, AEColor.TRANSPARENT);
    }

    private static void drawSwatch(int x, int y, AEColor color) {
        Gui.drawRect(x, y, x + 8, y + 8, 0xff000000 | color.mediumVariant);
        if (color == AEColor.TRANSPARENT) {
            Gui.drawRect(x, y, x + 2, y + 8, 0xff000000 | color.blackVariant);
            Gui.drawRect(x + 5, y, x + 8, y + 8, 0xff000000 | color.whiteVariant);
        }
    }
}
