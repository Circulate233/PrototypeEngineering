package com.circulation.prototype_eng.client.gui;

import ae2.api.util.AEColor;
import ae2.client.Point;
import ae2.client.gui.ICompositeWidget;
import ae2.client.gui.Tooltip;
import com.circulation.prototype_eng.localization.IsolationGroupText;
import com.circulation.prototype_eng.localization.MEAssemblyText;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.awt.Rectangle;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared palette behavior with separate physical-hatch and ME source-artwork frames.
 */
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

    private static void enableFrameBlend() {
        // Match AE2Button: transparent pixels in the PM frames must preserve the panel below.
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
    }

    private void drawSwatch(int x, int y, AEColor color) {
        int size = style.swatchSize;
        Gui.drawRect(x, y, x + size, y + size, 0xff000000 | color.mediumVariant);
        if (color == AEColor.TRANSPARENT) {
            Gui.drawRect(x, y, x + style.darkWidth, y + size, 0xff000000 | color.blackVariant);
            Gui.drawRect(x + style.lightOffset, y, x + size, y + size, 0xff000000 | color.whiteVariant);
        }
        GlStateManager.color(1, 1, 1, 1);
        // Gui.drawRect disables blending; keep AE's blended widget state for the next frame.
        GlStateManager.enableBlend();
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
        return hovered == null ? null : new Tooltip(MEAssemblyText.ColorChannel.text(IsolationGroupText.of(hovered).text()));
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
        drawOverlay(position.x(), position.y(), hovered, false);
    }

    @Override
    public void drawAbsoluteLayer(Rectangle bounds, Point mouse) {
        if (!expanded) return;
        boolean hovered = colorAt(mouse.x() - bounds.x, mouse.y() - bounds.y) == AEColor.TRANSPARENT;
        drawOverlay(bounds.x + position.x(), bounds.y + position.y(), hovered, true);
    }

    private void drawOverlay(int x, int y, boolean hovered, boolean palette) {
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableLighting();
        try {
            if (palette) {
                enableFrameBlend();
                GuiAtlas.draw(GuiAtlas.texture("color_palette"), x, y, 0, 0, 56, 57, 56, 57);
            }
            drawButton(x, palette ? y + 57 : y, hovered, palette ? AEColor.TRANSPARENT : selected.get());
        } finally {
            if (depth) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            GlStateManager.depthMask(depthMask);
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    private void drawButton(int x, int y, boolean hovered, AEColor color) {
        boolean pressed = pressedButton >= 0 && hovered;
        enableFrameBlend();
        if (style == Style.ME) HatchGuiSprites.meColorButton(x, y, hovered, pressed);
        else HatchGuiSprites.hatchColorButton(x, y, hovered, pressed);
        // The original physical-hatch face moves down one/two pixels; the ME inset stays fixed.
        int faceOffset = style == Style.HATCH ? pressed ? 2 : hovered ? 1 : 0 : 0;
        drawSwatch(x + style.swatchOffset, y + style.swatchOffset + faceOffset, color);
    }

    public enum Style {
        HATCH(14, 14, 2, 9, 3, 6),
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
