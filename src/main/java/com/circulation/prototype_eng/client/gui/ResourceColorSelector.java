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

/** Shared palette behavior with separate physical-hatch and ME source-artwork frames. */
public final class ResourceColorSelector implements ICompositeWidget {
    private static final AEColor[] COLORS = {AEColor.RED, AEColor.BROWN, AEColor.ORANGE, AEColor.YELLOW,
        AEColor.LIME, AEColor.GREEN, AEColor.CYAN, AEColor.LIGHT_BLUE, AEColor.BLUE, AEColor.PURPLE,
        AEColor.MAGENTA, AEColor.PINK, AEColor.WHITE, AEColor.LIGHT_GRAY, AEColor.GRAY, AEColor.BLACK};
    private final Supplier<AEColor> selected;
    private final Consumer<AEColor> choose;
    private final Style style;
    private Point position;
    private boolean expanded;
    private int pressedButton = -1;
    private boolean pressedExpanded;
    private @Nullable AEColor pressedColor;

    public ResourceColorSelector(Supplier<AEColor> selected, Consumer<AEColor> choose, Style style) {
        this.selected = Objects.requireNonNull(selected);
        this.choose = Objects.requireNonNull(choose);
        this.style = Objects.requireNonNull(style);
        position = new Point(style == Style.ME ? 1 : 4, 13);
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
        return new Rectangle(position.x(), position.y(), expanded ? 56 : style.width,
            expanded ? 57 + style.height : style.height);
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
        int swatchX = x - position.x() - style.swatchOffset;
        int swatchY = y - position.y() - 57 - style.swatchOffset;
        if (swatchX >= 0 && swatchX < style.swatchSize && swatchY >= 0 && swatchY < style.swatchSize)
            return AEColor.TRANSPARENT;
        int relativeX = x - position.x() - 4, relativeY = y - position.y() - 4;
        if (relativeX < 0 || relativeY < 0 || relativeX >= 52 || relativeY >= 52
            || relativeX % 13 >= 9 || relativeY % 13 >= 9) return null;
        return COLORS[relativeY / 13 * 4 + relativeX / 13];
    }

    @Override
    public void drawForegroundLayer(Rectangle bounds, Point mouse) {
        if (expanded) return;
        boolean hovered = getBounds().contains(mouse.x(), mouse.y());
        drawButton(position.x(), position.y(), hovered);
        drawSwatch(position.x() + style.swatchOffset, position.y() + style.swatchOffset, selected.get());
    }

    @Override
    public void drawAbsoluteLayer(Rectangle bounds, Point mouse) {
        if (!expanded) return;
        GuiAtlas.draw(GuiAtlas.texture("color_palette"), bounds.x + position.x(), bounds.y + position.y(),
            0, 0, 56, 57, 56, 57);
        int x = bounds.x + position.x(), y = bounds.y + position.y() + 57;
        boolean hovered = colorAt(mouse.x(), mouse.y()) == AEColor.TRANSPARENT;
        drawButton(x, y, hovered);
        drawSwatch(x + style.swatchOffset, y + style.swatchOffset, AEColor.TRANSPARENT);
    }

    private void drawSwatch(int x, int y, AEColor color) {
        int size = style.swatchSize;
        Gui.drawRect(x, y, x + size, y + size, 0xff000000 | color.mediumVariant);
        if (color == AEColor.TRANSPARENT) {
            Gui.drawRect(x, y, x + style.darkWidth, y + size, 0xff000000 | color.blackVariant);
            Gui.drawRect(x + style.lightOffset, y, x + size, y + size, 0xff000000 | color.whiteVariant);
        }
    }

    private void drawButton(int x, int y, boolean hovered) {
        boolean pressed = pressedButton >= 0 && hovered;
        if (style == Style.ME) HatchGuiSprites.meColorButton(x, y, hovered, pressed);
        else HatchGuiSprites.hatchColorButton(x, y, hovered, pressed);
    }

    public enum Style {
        HATCH(14, 14, 3, 8, 2, 5),
        ME(17, 18, 4, 9, 3, 6);

        public static final Style[] VALUES = values();
        private final int width, height, swatchOffset, swatchSize, darkWidth, lightOffset;

        Style(int width, int height, int swatchOffset, int swatchSize, int darkWidth, int lightOffset) {
            this.width = width;
            this.height = height;
            this.swatchOffset = swatchOffset;
            this.swatchSize = swatchSize;
            this.darkWidth = darkWidth;
            this.lightOffset = lightOffset;
        }
    }
}
