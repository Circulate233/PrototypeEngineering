package com.circulation.prototype_eng.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.jetbrains.annotations.NotNull;

/** PM's borderless field keeps its full 97-pixel hit area and four-pixel horizontal text padding. */
final class HatchRateField extends GuiTextField {
    private final int left, top;
    private int selectionAnchor;
    private long lastClick;
    private int blink;
    private final FontRenderer font;
    private final GuiClip clip = new GuiClip();

    HatchRateField(int id, FontRenderer font, int left, int top) {
        super(id, font, left + 4, top, 89, 10);
        this.left = left;
        this.top = top;
        this.font = font;
        setEnableBackgroundDrawing(false);
        setTextColor(0xffffff);
        setMaxStringLength(Integer.MAX_VALUE);
    }

    @Override public boolean mouseClicked(int mouseX, int mouseY, int button) {
        boolean inside = mouseX >= left && mouseX < left + 97 && mouseY >= top && mouseY < top + 10;
        setFocused(inside);
        if (!inside) return false;
        if (button == 1) setText("");
        else {
            long now = Minecraft.getSystemTime();
            if (lastClick > 0 && now - lastClick < 300 || lastClick < 0 && now + lastClick < 300) {
                setCursorPosition(0);
                setSelectionPos(getText().length());
                lastClick = lastClick > 0 ? -now : 0;
            } else {
                setCursorPosition(cursorAt(mouseX));
                lastClick = now;
            }
            selectionAnchor = getCursorPosition();
        }
        return true;
    }

    void dragTo(int mouseX) {
        if (!isFocused()) return;
        int caret = cursorAt(mouseX);
        setCursorPosition(selectionAnchor);
        setSelectionPos(caret);
    }

    private int cursorAt(int mouseX) {
        String text = getText();
        int relative = mouseX - left - 4, offset = lineScrollOffset, px = 0;
        for (int i = offset; i < text.length(); i++) {
            int next = px + font.getCharWidth(text.charAt(i));
            if (next >= relative) return relative - px > next - relative ? i + 1 : i;
            px = next;
        }
        return text.length();
    }

    @Override public void setFocused(boolean focused) {
        if (focused != isFocused()) blink = 0;
        super.setFocused(focused);
        if (!focused) setCursorPositionZero();
    }

    @Override public void setText(@NotNull String text) {
        super.setText(text);
        if (!isFocused()) setCursorPositionZero();
    }

    @Override public void updateCursorCounter() { if (isFocused()) blink++; }

    @Override public boolean textboxKeyTyped(char character, int key) {
        if (!isFocused()) return false;
        int current = getSelectionEnd(), next;
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT) {
            boolean right = key == Keyboard.KEY_RIGHT;
            next = Math.clamp(current + (right ? 1 : -1), 0, getText().length());
            if (GuiScreen.isCtrlKeyDown()) {
                if (right && next < getText().length()) {
                    int index = current;
                    while (index < getText().length() && Character.isLetterOrDigit(getText().charAt(index))) index++;
                    next = Math.max(next, index);
                } else if (!right && next > 0) {
                    int index = next;
                    while (index >= 0 && Character.isLetterOrDigit(getText().charAt(index))) index--;
                    next = index < 0 ? 0 : index < next ? index + 1 : next;
                }
            }
        } else if (key == Keyboard.KEY_UP) next = 0;
        else if (key == Keyboard.KEY_DOWN) next = getText().length();
        else if (key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE) {
            if (cursorPosition == selectionEnd) setCursorPosition(current);
            deleteFromCursor(key == Keyboard.KEY_BACK ? -1 : 1);
            return true;
        } else {
            if (GuiScreen.isKeyComboCtrlV(key)) writeText(GuiScreen.getClipboardString().replace("§", ""));
            else if (character != 0) super.textboxKeyTyped(character, key);
            return true;
        }
        if (GuiScreen.isShiftKeyDown()) setSelectionPos(next);
        else setCursorPosition(next);
        return true;
    }

    @Override public void drawTextBox() {
        if (!getVisible()) return;
        clip.draw(left + 1, top + 1, 95, 8, () -> {
            String text = getText();
            float startX = left + 4 - font.getStringWidth(text.substring(0, lineScrollOffset));
            int y = top + (10 - font.FONT_HEIGHT) / 2;
            int low = Math.min(cursorPosition, selectionEnd), high = Math.max(cursorPosition, selectionEnd);
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            if (low != high) drawRect((int) startX + font.getStringWidth(text.substring(0, low)), y - 1,
                (int) startX + font.getStringWidth(text.substring(0, high)), y + font.FONT_HEIGHT - 1, 0xff2f72a8);
            font.drawString(text, startX, y, 0xffffff, false);
            if (isFocused() && blink / 10 % 2 == 0) {
                float caret = startX + font.getStringWidth(text.substring(0, selectionEnd)) - .8f + (text.isEmpty() ? .7f : 0);
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate(caret, y - 1, 0);
                    GlStateManager.scale(.6f, 1, 1);
                    drawRect(0, 0, 1, 9, 0xffffffff);
                } finally { GlStateManager.popMatrix(); }
            }
            GlStateManager.enableDepth();
        });
    }
}
