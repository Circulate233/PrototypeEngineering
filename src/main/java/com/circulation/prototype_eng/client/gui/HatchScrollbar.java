package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.ICompositeWidget;
import net.minecraft.client.gui.Gui;

import java.awt.Rectangle;

/** PM's two hatch scrollbar skins, 30-pixel wheel steps and 180–360 ms cubic easing. */
final class HatchScrollbar implements ICompositeWidget {
    private final boolean thin;
    private final boolean present;
    private Rectangle grid = new Rectangle();
    private Point position = new Point(0, 0);
    private long maximum, scroll, target, start;
    private int dragOffset;
    private long animationStart, animationDuration;
    private boolean dragging;

    HatchScrollbar(boolean thin, boolean present) {
        this.thin = thin;
        this.present = present;
    }

    void configure(int left, int top, int columns, int visibleRows, int totalRows) {
        grid = new Rectangle(left, top, columns * 18, visibleRows * 18);
        setPosition(new Point(left + grid.width + (thin ? 3 : 5), top));
        maximum = present ? Math.max(0, (totalRows - (long) visibleRows) * 18) : 0;
        scroll = Math.clamp(scroll, 0, maximum);
        target = Math.clamp(target, 0, maximum);
        start = Math.clamp(start, 0, maximum);
        if (!isScrollable()) {
            dragging = false;
            animationDuration = 0;
        }
    }

    long scroll() {
        return scroll;
    }

    private int thumbHeight() {
        return thin ? 12 : 15;
    }

    private int thumbY() {
        return maximum == 0 ? 0 : (int) (scroll * (grid.height - thumbHeight()) / maximum);
    }

    void frame(Point mouse) {
        if (dragging) {
            scroll = Math.clamp((long) ((mouse.y() - position.y() - dragOffset)
                * (double) maximum / Math.max(1, grid.height - thumbHeight())), 0, maximum);
            target = scroll;
            animationDuration = 0;
        } else if (animationDuration > 0) {
            double progress = Math.clamp((System.currentTimeMillis() - animationStart) / (double) animationDuration, 0, 1);
            double remaining = 1 - progress;
            scroll = (long) (start + (target - start) * (1 - remaining * remaining * remaining));
            if (progress == 1) {
                scroll = target;
                animationDuration = 0;
            }
        }
    }

    @Override
    public boolean isVisible() {
        return present;
    }

    boolean isScrollable() {
        return present && maximum > 0;
    }

    @Override
    public void setPosition(Point value) {
        position = value;
    }

    @Override
    public void setSize(int width, int height) {
        grid.height = height;
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(position.x(), position.y(), thin ? 7 : 12, grid.height);
    }

    @Override
    public boolean wantsAllMouseWheelEvents() {
        return true;
    }

    @Override
    public boolean wantsAllMouseUpEvents() {
        return true;
    }

    @Override
    public boolean onMouseWheel(Point mouse, double delta) {
        if (!isScrollable() || !new Rectangle(grid.x, grid.y, grid.width + (thin ? 10 : 17), grid.height).contains(mouse.x(), mouse.y()))
            return false;
        start = scroll;
        target = Math.clamp(target - (long) Math.signum(delta) * 30, 0, maximum);
        animationStart = System.currentTimeMillis();
        animationDuration = Math.clamp(90L + Math.abs(target - start) * 2L, 180, 360);
        return true;
    }

    @Override
    public boolean onMouseDown(Point mouse, int button) {
        if (button != 0 || !isScrollable() || !getBounds().contains(mouse.x(), mouse.y())) return false;
        int relative = mouse.y() - position.y() - thumbY();
        dragOffset = relative >= 0 && relative <= thumbHeight() ? relative : thumbHeight() / 2;
        dragging = true;
        frame(mouse);
        return true;
    }

    @Override
    public boolean onMouseDrag(Point mouse, int button) {
        if (!dragging) return false;
        frame(mouse);
        return true;
    }

    @Override
    public boolean onMouseUp(Point mouse, int button) {
        if (button == 0) dragging = false;
        return false;
    }

    ThumbState thumbState(Point mouse) {
        if (!isScrollable()) return ThumbState.DISABLED;
        int y = position.y() + thumbY();
        boolean hover = mouse.x() >= position.x() && mouse.x() < position.x() + (thin ? 7 : 12)
            && mouse.y() >= y && mouse.y() < y + thumbHeight();
        return dragging ? ThumbState.PRESSED : hover ? ThumbState.HOVER : ThumbState.NORMAL;
    }

    enum ThumbState { DISABLED, NORMAL, HOVER, PRESSED }

    @Override
    public void drawForegroundLayer(Rectangle bounds, Point mouse) {
        if (!isVisible()) return;
        int x = position.x(), y = position.y() + thumbY(), width = thin ? 7 : 12;
        var state = thumbState(mouse);
        int sprite = switch (state) {
            case DISABLED, NORMAL -> 0;
            case HOVER -> 1;
            case PRESSED -> 2;
        };
        Gui.drawRect(x, position.y(), x + width, position.y() + grid.height, 0x3f000000);
        GuiAtlas.state(x, y, thin ? 161 + sprite * 8 : 146 + sprite * 13, thin ? 17 : 1, width, thumbHeight());
        // PM supplies three interactive sprites; retain its normal artwork and dim unavailable thumbs.
        if (state == ThumbState.DISABLED) Gui.drawRect(x, y, x + width, y + thumbHeight(), 0x66000000);
    }
}
