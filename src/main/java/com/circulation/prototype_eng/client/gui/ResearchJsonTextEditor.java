package com.circulation.prototype_eng.client.gui;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;

/**
 * Local multiline content editor. The caller owns saving, validation and its surrounding dialog.
 */
final class ResearchJsonTextEditor {
    private static final double SCALE = 4D / 3;
    private static final int LINE = 12;
    private final GuiClip clip = new GuiClip();
    private final ObjectArrayList<String> lines = new ObjectArrayList<>();
    private final IntArrayList starts = new IntArrayList();
    private final ObjectArrayList<Snapshot> undo = new ObjectArrayList<>();
    private String text = "";
    private int cursor, anchor, firstLine, horizontal, visibleLines = 1, textWidth = 1;
    private double x, y, width, height;
    private int gutter;
    private boolean dragging, scrolling;

    ResearchJsonTextEditor(String text) {
        setText(text);
    }

    String text() {
        return text;
    }

    void setText(String value) {
        text = value.replace("\r\n", "\n").replace('\r', '\n');
        cursor = anchor = firstLine = horizontal = 0;
        undo.clear();
        rebuild();
    }

    void draw(FontRenderer font, double left, double top, double w, double h) {
        x = left;
        y = top;
        width = w;
        height = h;
        gutter = font.getStringWidth(Integer.toString(lines.size())) + 10;
        visibleLines = Math.max(1, (int) ((h / SCALE - 8) / LINE));
        textWidth = Math.max(1, (int) (w / SCALE) - gutter - 14);
        firstLine = Math.clamp(firstLine, 0, Math.max(0, lines.size() - visibleLines));
        GlStateManager.pushMatrix();
        GlStateManager.translate(left, top, 0);
        GlStateManager.scale(SCALE, SCALE, 1);
        int localW = (int) (w / SCALE), localH = (int) (h / SCALE);
        Gui.drawRect(0, 0, localW, localH, 0xff696d88);
        Gui.drawRect(1, 1, localW - 1, localH - 1, 0xfff2f2f2);
        Gui.drawRect(1, 1, gutter - 3, localH - 1, 0xffcbccd4);
        clip.draw(gutter, 3, textWidth, localH - 6, () -> {
            int low = Math.min(cursor, anchor), high = Math.max(cursor, anchor);
            for (int row = firstLine; row < Math.min(lines.size(), firstLine + visibleLines); row++) {
                int drawY = 4 + (row - firstLine) * LINE, start = starts.getInt(row);
                String line = lines.get(row);
                int selectedStart = Math.clamp(low - start, 0, line.length());
                int selectedEnd = Math.clamp(high - start, 0, line.length());
                if (selectedStart < selectedEnd || low <= start + line.length() && high > start + line.length()) {
                    int from = font.getStringWidth(line.substring(0, selectedStart));
                    int to = font.getStringWidth(line.substring(0, selectedEnd));
                    if (high > start + line.length()) to += 4;
                    Gui.drawRect(gutter + from - horizontal, drawY - 1, gutter + to - horizontal, drawY + 10, 0xffadb0c4);
                }
                font.drawString(line, gutter - horizontal, drawY, 0x404040);
                if (cursor >= start && cursor <= start + line.length() && (System.currentTimeMillis() / 500 & 1) == 0) {
                    int caret = gutter + font.getStringWidth(line.substring(0, cursor - start)) - horizontal;
                    Gui.drawRect(caret, drawY - 1, caret + 1, drawY + 9, 0xff413f54);
                }
            }
        });
        for (int row = firstLine; row < Math.min(lines.size(), firstLine + visibleLines); row++) {
            String number = Integer.toString(row + 1);
            font.drawString(number, gutter - 7 - font.getStringWidth(number), 4 + (row - firstLine) * LINE, 0x5a5d72);
        }
        if (lines.size() > visibleLines) {
            int track = localH - 6, thumb = Math.max(8, track * visibleLines / lines.size());
            int thumbY = 3 + (track - thumb) * firstLine / (lines.size() - visibleLines);
            Gui.drawRect(localW - 7, 3, localW - 3, localH - 3, 0xffcbccd4);
            Gui.drawRect(localW - 7, thumbY, localW - 3, thumbY + thumb, 0xff878fa5);
        }
        GlStateManager.popMatrix();
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    void click(FontRenderer font, double mx, double my) {
        if (!contains(mx, my)) return;
        scrolling = mx >= x + width - 12 && lines.size() > visibleLines;
        dragging = !scrolling;
        if (scrolling) scrollTo(my);
        else {
            cursor = pointer(font, mx, my);
            if (!GuiScreen.isShiftKeyDown()) anchor = cursor;
        }
    }

    void drag(FontRenderer font, double mx, double my) {
        if (scrolling) scrollTo(my);
        else if (dragging) {
            if (my < y) firstLine = Math.max(0, firstLine - 1);
            else if (my >= y + height)
                firstLine = Math.clamp(firstLine + 1L, 0, Math.max(0, lines.size() - visibleLines));
            cursor = pointer(font, mx, my);
        }
    }

    void release() {
        dragging = scrolling = false;
    }

    void scroll(int delta) {
        firstLine = Math.clamp(firstLine - delta * 3L, 0, Math.max(0, lines.size() - visibleLines));
    }

    private void scrollTo(double my) {
        double fraction = Math.clamp((my - y) / height, 0, 1);
        firstLine = (int) Math.round(fraction * Math.max(0, lines.size() - visibleLines));
    }

    private int pointer(FontRenderer font, double mx, double my) {
        int row = Math.clamp(firstLine + (int) Math.floor(((my - y) / SCALE - 4) / LINE), 0, lines.size() - 1);
        String line = lines.get(row);
        double target = (mx - x) / SCALE - gutter + horizontal;
        int column = 0, offset = 0;
        while (column < line.length()) {
            int next = font.getStringWidth(line.substring(0, column + 1));
            if (target < (offset + next) / 2D) break;
            offset = next;
            column++;
        }
        return starts.getInt(row) + column;
    }

    void keyTyped(FontRenderer font, char character, int code) {
        boolean control = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        if (GuiScreen.isKeyComboCtrlA(code)) {
            anchor = 0;
            cursor = text.length();
        } else if (GuiScreen.isKeyComboCtrlC(code)) {
            GuiScreen.setClipboardString(selection());
        } else if (GuiScreen.isKeyComboCtrlX(code)) {
            GuiScreen.setClipboardString(selection());
            replace("");
        } else if (GuiScreen.isKeyComboCtrlV(code)) {
            replace(GuiScreen.getClipboardString().replace("\r\n", "\n").replace('\r', '\n'));
        } else if (control && code == Keyboard.KEY_Z) {
            if (!undo.isEmpty()) {
                var previous = undo.removeLast();
                text = previous.text;
                cursor = previous.cursor;
                anchor = previous.anchor;
                rebuild();
            }
        } else switch (code) {
            case Keyboard.KEY_BACK -> {
                if (cursor == anchor && cursor > 0) anchor = control ? previousWord(cursor) : cursor - 1;
                replace("");
            }
            case Keyboard.KEY_DELETE -> {
                if (cursor == anchor && cursor < text.length()) anchor = control ? nextWord(cursor) : cursor + 1;
                replace("");
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> replace("\n");
            case Keyboard.KEY_TAB -> replace("  ");
            case Keyboard.KEY_LEFT -> move(control ? previousWord(cursor) : Math.max(0, cursor - 1), shift);
            case Keyboard.KEY_RIGHT -> move(control ? nextWord(cursor) : Math.min(text.length(), cursor + 1), shift);
            case Keyboard.KEY_HOME -> move(control ? 0 : starts.getInt(row()), shift);
            case Keyboard.KEY_END ->
                move(control ? text.length() : starts.getInt(row()) + lines.get(row()).length(), shift);
            case Keyboard.KEY_UP -> vertical(-1, shift);
            case Keyboard.KEY_DOWN -> vertical(1, shift);
            case Keyboard.KEY_PRIOR -> vertical(-visibleLines, shift);
            case Keyboard.KEY_NEXT -> vertical(visibleLines, shift);
            default -> {
                if (!control && character >= 32 && character != 127) replace(Character.toString(character));
            }
        }
        showCursor(font);
    }

    private String selection() {
        return text.substring(Math.min(cursor, anchor), Math.max(cursor, anchor));
    }

    private void replace(String value) {
        if (cursor == anchor && value.isEmpty()) return;
        undo.add(new Snapshot(text, cursor, anchor));
        if (undo.size() > 32) undo.removeFirst();
        int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
        text = text.substring(0, start) + value + text.substring(end);
        cursor = anchor = start + value.length();
        rebuild();
    }

    private void move(int next, boolean shift) {
        cursor = next;
        if (!shift) anchor = cursor;
    }

    private void vertical(int delta, boolean shift) {
        int row = row(), column = cursor - starts.getInt(row);
        int next = Math.clamp(row + delta, 0, lines.size() - 1);
        move(starts.getInt(next) + Math.min(column, lines.get(next).length()), shift);
    }

    private int previousWord(int at) {
        while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) at--;
        while (at > 0 && !Character.isWhitespace(text.charAt(at - 1))) at--;
        return at;
    }

    private int nextWord(int at) {
        while (at < text.length() && !Character.isWhitespace(text.charAt(at))) at++;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        return at;
    }

    private int row() {
        int row = 0;
        while (row + 1 < starts.size() && starts.getInt(row + 1) <= cursor) row++;
        return row;
    }

    private void showCursor(FontRenderer font) {
        int row = row();
        if (row < firstLine) firstLine = row;
        if (row >= firstLine + visibleLines) firstLine = row - visibleLines + 1;
        int left = font.getStringWidth(lines.get(row).substring(0, cursor - starts.getInt(row)));
        if (left < horizontal) horizontal = left;
        if (left >= horizontal + textWidth) horizontal = left - textWidth + 1;
    }

    private void rebuild() {
        lines.clear();
        starts.clear();
        int start = 0;
        for (int index = 0; index <= text.length(); index++) {
            if (index == text.length() || text.charAt(index) == '\n') {
                starts.add(start);
                lines.add(text.substring(start, index));
                start = index + 1;
            }
        }
    }

    private record Snapshot(String text, int cursor, int anchor) {
    }
}
