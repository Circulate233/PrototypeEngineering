package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.api.stacks.AmountFormat;
import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.widgets.ITooltip;
import ae2.client.gui.widgets.Scrollbar;
import ae2.container.slot.AppEngSlot;
import com.circulation.prototype_eng.container.MePatternAssemblyContainer;
import com.circulation.prototype_eng.localization.MeAssemblyText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Original source layout: 36 patterns, native page controls and three independent generic-resource caches. */
public final class MePatternAssemblyGui extends AEBaseGui<MePatternAssemblyContainer> {
    private static final ResourceLocation BACKGROUND = GuiAtlas.texture("gui_bus/gui_pattern_bus");
    private static final ResourceLocation SLOTS = GuiAtlas.texture("gui_bus/gui_pattern_bus_slot");
    private static final ResourceLocation MODES = GuiAtlas.texture("io_mode");
    private final ResourceColorSelector colorSelector;
    private final Scrollbar[] scrollbars = new Scrollbar[3];

    public MePatternAssemblyGui(MePatternAssemblyContainer container, InventoryPlayer player) {
        super(container, player);
        xSize = 342;
        ySize = 196;
        colorSelector = new ResourceColorSelector(() -> container.color, container::setColor);
        colorSelector.setPosition(new Point(1, 43));
        widgets.add("patternColor", colorSelector);
        for (int kind = 0; kind < 3; kind++) {
            int textureTop = kind == 0 ? 143 : kind == 1 ? 130 : 156;
            var scrollbar = new Scrollbar(Scrollbar.Style.sheet(SLOTS, SLOTS, 0, textureTop, 0, textureTop, 7, 12, 256, 256));
            scrollbar.setCaptureMouseWheel(false);
            scrollbar.setPosition(new Point(kind == 2 ? 327 : 86, MePatternAssemblyContainer.top(kind) + 1));
            scrollbar.setHeight(MePatternAssemblyContainer.height(kind) - 2);
            scrollbars[kind] = scrollbar;
            widgets.add("patternScroll" + kind, scrollbar);
        }
        button("patternReturn", 0, 1, 5, 17, 18, MODES, () -> 114, MeAssemblyText.ReturnInputs::text, false);
        button("patternMode", 1, 1, 24, 17, 18, MODES, () -> 19 + container.snapshot.mode() * 19,
            () -> modeText().text(), false);
        button("patternPrevious", 2, 100, 98, 11, 12, SLOTS, () -> 0, MeAssemblyText.PreviousPage::text, true);
        button("patternNext", 3, 250, 98, 11, 12, SLOTS, () -> 117, MeAssemblyText.NextPage::text, true);
        int[] x = {115, 130, 145, 160, 188, 203, 218, 233};
        int[] sprites = {91, 65, 39, 13, 26, 52, 78, 104};
        int[] factors = {8, 5, 3, 2, 2, 3, 5, 8};
        for (int i = 0; i < factors.length; i++) {
            int index = i;
            button("patternScale" + i, 10 + i, x[i], 98, 13, 12, SLOTS, () -> sprites[index],
                () -> (index < 4 ? MeAssemblyText.DividePatterns : MeAssemblyText.MultiplyPatterns).text(factors[index]), false);
        }
    }

    private void button(String name, int id, int x, int y, int width, int height, ResourceLocation atlas,
                        IntSupplier spriteY, Supplier<ITextComponent> tooltip, boolean pageButton) {
        widgets.add(name, new SourceButton(id, width, height, atlas, spriteY, tooltip, pageButton), new Point(x, y));
    }

    private MeAssemblyText modeText() {
        return switch (container.snapshot.mode()) {
            case 1 -> MeAssemblyText.ModeBlocking;
            case 2 -> MeAssemblyText.ModeRecipeBlocking;
            case 3 -> MeAssemblyText.ModePatternIsolation;
            case 4 -> MeAssemblyText.ModeCraftingLock;
            default -> MeAssemblyText.ModeDefault;
        };
    }

    @Override
    protected void actionPerformed(@NotNull GuiButton button) throws IOException {
        if (button.id == 0) container.action(2, 0);
        else if (button.id == 1) container.action(1, 0);
        else if (button.id == 2) container.action(0, container.snapshot.page() - 1);
        else if (button.id == 3) container.action(0, container.snapshot.page() + 1);
        else if (button.id >= 10 && button.id <= 17) {
            int[] factors = {8, 5, 3, 2, 2, 3, 5, 8};
            int index = button.id - 10;
            container.action(index < 4 ? 4 : 3, factors[index]);
        } else super.actionPerformed(button);
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        for (int kind = 0; kind < 3; kind++) scrollbars[kind].setRange(0,
            Math.max(0, MePatternAssemblyContainer.rowCount(container.snapshot.side(kind).total())
                - MePatternAssemblyContainer.height(kind) / 18), 1);
        container.requestView(scrollbars[0].getCurrentScroll(), scrollbars[1].getCurrentScroll(), scrollbars[2].getCurrentScroll());
        for (var slot : container.inventorySlots) {
            if (!container.isResourceSlot(slot.slotNumber) || !(slot instanceof AppEngSlot resource)) continue;
            int kind = container.kind(slot.slotNumber);
            int cell = container.cell(slot.slotNumber);
            int top = MePatternAssemblyContainer.top(kind);
            int height = MePatternAssemblyContainer.height(kind);
            int y = top + cell / 3 * 18 + 1;
            boolean present = cell < container.snapshot.side(kind).entries().size();
            resource.setSlotEnabled(present);
            resource.setActive(present && y < top + height);
            slot.xPos = MePatternAssemblyContainer.left(kind) + cell % 3 * 18 + 1;
            slot.yPos = y;
        }
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        GuiAtlas.draw(BACKGROUND, x, y, 0, 0, xSize, ySize, 384, 256);
        for (var slot : container.inventorySlots) {
            if (!container.isResourceSlot(slot.slotNumber) || !slot.isEnabled()) continue;
            clip(container.kind(slot.slotNumber));
            try {
                GuiAtlas.state(x + slot.xPos - 1, y + slot.yPos - 1, 73, 0, 18, 18);
            } finally {
                GL11.glPopAttrib();
            }
        }
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        GuiLabel.draw(fontRenderer, Objects.requireNonNull(container.getGuiTitle()).getFormattedText(), 27, 9, 142, 9, 0x404040, false);
        GuiAtlas.draw(SLOTS, 192, 15, 188, 0, 68, 8, 256, 256);
        int pages = container.snapshot.pages();
        int enabledWidth = Math.min(68, 11 + Math.max(0, Math.min(20, pages) - 1) * 3);
        if (enabledWidth < 68) GuiAtlas.draw(SLOTS, 192 + enabledWidth, 15, 188 + enabledWidth, 9, 68 - enabledWidth, 8, 256, 256);
        int offset = pages <= 20 ? container.snapshot.page() * 3
            : (int) ((long) container.snapshot.page() * 57 / (pages - 1));
        GuiAtlas.draw(SLOTS, 192 + offset, 15, 176, 0, 11, 8, 256, 256);
    }

    private void clip(int kind) {
        int scale = new ScaledResolution(mc).getScaleFactor();
        int left = MePatternAssemblyContainer.left(kind), top = MePatternAssemblyContainer.top(kind), height = MePatternAssemblyContainer.height(kind);
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((guiLeft + left) * scale, mc.displayHeight - (guiTop + top + height) * scale, 54 * scale, height * scale);
    }

    @Override
    protected void drawSlot(@NotNull Slot slot) {
        if (!container.isResourceSlot(slot.slotNumber)) {
            super.drawSlot(slot);
            return;
        }
        clip(container.kind(slot.slotNumber));
        try {
            super.drawSlot(slot);
        } finally {
            GL11.glPopAttrib();
        }
    }

    @Override
    protected boolean isHovering(Slot slot, int mouseX, int mouseY) {
        return (!container.isResourceSlot(slot.slotNumber) || inside(slot, mouseX, mouseY)) && super.isHovering(slot, mouseX, mouseY);
    }

    private boolean inside(Slot slot, int x, int y) {
        int kind = container.kind(slot.slotNumber);
        int left = MePatternAssemblyContainer.left(kind), top = MePatternAssemblyContainer.top(kind);
        return x >= guiLeft + left && x < guiLeft + left + 54 && y >= guiTop + top
            && y < guiTop + top + MePatternAssemblyContainer.height(kind);
    }

    @Override
    protected boolean isPointInRegion(int left, int top, int regionWidth, int regionHeight, int x, int y) {
        if (regionWidth == 16 && regionHeight == 16) {
            for (var slot : container.inventorySlots) {
                if (container.isResourceSlot(slot.slotNumber) && slot.xPos == left && slot.yPos == top
                    && !inside(slot, x, y)) return false;
            }
        }
        return super.isPointInRegion(left, top, regionWidth, regionHeight, x, y);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        container.completeFrame();
    }

    @Override
    protected void renderHoveredToolTip(int mouseX, int mouseY) {
        if (colorSelector.blocksTooltips(mouseX - guiLeft, mouseY - guiTop)) return;
        if (isPointInRegion(192, 15, 68, 8, mouseX, mouseY)) {
            drawTooltipWithHeader(mouseX, mouseY, List.of(MeAssemblyText.PatternPage.text(container.snapshot.page() + 1, container.snapshot.pages())));
            return;
        }
        var slot = findSlot(mouseX, mouseY);
        if (slot == null || !container.isResourceSlot(slot.slotNumber) || !(slot instanceof AppEngSlot resource)) {
            super.renderHoveredToolTip(mouseX, mouseY);
            return;
        }
        int kind = container.kind(slot.slotNumber);
        var lines = new ObjectArrayList<ITextComponent>();
        var key = resource.getGenericDisplayKey();
        if (key != null) {
            if (AEKeyRendering.get(key.getType()) == null) lines.add(key.getDisplayName());
            else lines.addAll(AEKeyRendering.getTooltip(key));
            lines.add(MeAssemblyText.StoredStock.text(key.formatAmount(resource.getGenericDisplayAmount(), AmountFormat.FULL)));
        }
        lines.add((kind == 0 ? MeAssemblyText.InputBuffer : kind == 1 ? MeAssemblyText.CatalystBuffer : MeAssemblyText.OutputBuffer).text());
        lines.add((kind == 1 ? MeAssemblyText.CatalystUsage : MeAssemblyText.ReadOnlyBuffer).text());
        if (key == null) drawTooltipWithHeader(mouseX, mouseY, lines);
        else drawKeyTooltipWithImages(mouseX, mouseY, key, lines);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (colorSelector.expanded()) {
            colorSelector.onMouseDown(new Point(mouseX - guiLeft, mouseY - guiTop), button);
            return;
        }
        if (button == 0 && isPointInRegion(192, 15, 68, 8, mouseX, mouseY)) {
            int relative = Math.clamp(mouseX - guiLeft - 192, 0, 67);
            int pages = container.snapshot.pages();
            if (pages > 20) container.action(0, (int) ((long) relative * pages / 68));
            else if (relative < 11 + (pages - 1) * 3)
                container.action(0, Math.min(pages - 1, Math.max(0, relative - 5) / 3));
            return;
        }
        var slot = findSlot(mouseX, mouseY);
        if (slot != null && (container.isPatternSlot(slot.slotNumber) || container.isResourceSlot(slot.slotNumber))) {
            if (button <= 1) container.click(slot.slotNumber, button, isShiftKeyDown() ? ClickType.QUICK_MOVE : ClickType.PICKUP);
            else if (button == 2 && container.isPatternSlot(slot.slotNumber)) container.click(slot.slotNumber, button, ClickType.CLONE);
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void handleMouseClick(@Nullable Slot slot, int index, int button, @NotNull ClickType type) {
        if (container.isPatternSlot(index) || container.isResourceSlot(index) || type == ClickType.QUICK_MOVE)
            container.click(index, button, type);
        else super.handleMouseClick(slot, index, button, type);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long elapsed) {
        var slot = findSlot(x, y);
        if (slot == null || !container.isPatternSlot(slot.slotNumber) && !container.isResourceSlot(slot.slotNumber))
            super.mouseClickMove(x, y, button, elapsed);
    }

    @Override
    public boolean handleAeMouseWheelInput() {
        if (colorSelector.expanded()) return true;
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return false;
        int x = Mouse.getEventX() * width / mc.displayWidth, y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        for (int kind = 0; kind < 3; kind++) {
            if (isPointInRegion(MePatternAssemblyContainer.left(kind), MePatternAssemblyContainer.top(kind), 68,
                MePatternAssemblyContainer.height(kind), x, y)) {
                scrollbars[kind].onMouseWheel(new Point(x - guiLeft, y - guiTop), delta > 0 ? 1 : -1);
                return true;
            }
        }
        return super.handleAeMouseWheelInput();
    }

    private static final class SourceButton extends GuiButton implements ITooltip {
        private final ResourceLocation atlas;
        private final IntSupplier spriteY;
        private final Supplier<ITextComponent> tooltip;
        private final boolean pageButton;
        private boolean pressed;

        private SourceButton(int id, int width, int height, ResourceLocation atlas, IntSupplier spriteY,
                             Supplier<ITextComponent> tooltip, boolean pageButton) {
            super(id, 0, 0, width, height, "");
            this.atlas = atlas;
            this.spriteY = spriteY;
            this.tooltip = tooltip;
            this.pageButton = pageButton;
        }

        @Override
        public void drawButton(@NotNull Minecraft minecraft, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            int step = atlas.equals(MODES) ? 18 : pageButton ? 12 : 14;
            int u = (pressed ? 2 : hovered ? 1 : 0) * step;
            GuiAtlas.draw(atlas, x, y, u, spriteY.getAsInt(), width, height, 256, 256);
        }

        @Override
        public boolean mousePressed(@NotNull Minecraft minecraft, int mouseX, int mouseY) {
            pressed = super.mousePressed(minecraft, mouseX, mouseY);
            return pressed;
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            pressed = false;
            super.mouseReleased(mouseX, mouseY);
        }

        @Override
        public @NotNull List<ITextComponent> getTooltipMessage() {
            return List.of(tooltip.get());
        }

        @Override
        public Rectangle getTooltipArea() {
            return new Rectangle(x, y, width, height);
        }

        @Override
        public boolean isTooltipAreaVisible() {
            return visible;
        }
    }
}
