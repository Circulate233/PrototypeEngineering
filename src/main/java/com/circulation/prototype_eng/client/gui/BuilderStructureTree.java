package com.circulation.prototype_eng.client.gui;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.container.StructureToolContainer;
import com.circulation.prototype_eng.localization.StructureTitle;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.StructureBuildPlan;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** PM's clipped tree: original row slices, connections, material chooser and slice sliders. */
final class BuilderStructureTree {
    private final StructureToolContainer container;
    private final Consumer<Set<BlockPos>> highlight;
    private final Map<String, Boolean> expanded = new Object2ObjectLinkedOpenHashMap<>();
    private final Map<String, Integer> slices = new Object2ObjectLinkedOpenHashMap<>();
    private final List<Hit> hits = new ObjectArrayList<>();
    private final GuiClip clip = new GuiClip();
    private List<StructureBuildPlan.Cell> cells = List.of();
    private StructureCatalogView catalog;
    private String key = "", selected, pressed;
    private Row root;
    private float panX, panY, scale = 1;
    private String viewRoot, initializedRoot, highlightedPath;
    private StructureDisplay highlightedDisplay;
    private float mouseX, mouseY;
    private int dragButton = -1, lastX, lastY;
    private Hit slider;

    BuilderStructureTree(StructureToolContainer container, Consumer<Set<BlockPos>> highlight) {
        this.container = container;
        this.highlight = highlight;
    }

    private static String offset(BlockPos pos) {
        return StructureToolText.BuildInstrumentOffset.getLocal(pos.getX(), pos.getY(), pos.getZ());
    }

    private static ItemStack stack(StructureDisplay.BlockOption option) {
        return option == null || !Block.REGISTRY.containsKey(option.blockId()) ? ItemStack.EMPTY : new ItemStack(Block.REGISTRY.getObject(option.blockId()), 1, option.meta());
    }

    private static String name(StructureDisplay.BlockOption option) {
        var stack = stack(option);
        return stack.isEmpty() ? option.blockId().toString() : stack.getDisplayName();
    }

    private static void texturedState() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1, 1, 1, 1);
    }

    private static void border(int x, int y, int width, int color) {
        Gui.drawRect(x, y, x + width, y + 1, color);
        Gui.drawRect(x, y + 13, x + width, y + 14, color);
        Gui.drawRect(x, y, x + 1, y + 14, color);
        Gui.drawRect(x + width - 1, y, x + width, y + 14, color);
    }

    private boolean open(Row row) {
        return expanded.getOrDefault(row.id, row.kind == 0);
    }

    private void refresh(StructureCatalogView current, ResourceLocation id, BlockOrientation orientation) {
        var tag = container.snapshot();
        String next = id + tag.getCompoundTag("MaterialSelections").toString() + slices + orientation;
        if (current == catalog && next.equals(key)) return;
        catalog = current;
        key = next;
        root = null;
        viewRoot = String.valueOf(id);
        if (current == null || id == null || !current.entries().containsKey(id)) return;
        var plan = StructureBuildPlan.expand(current, id, orientation, slices);
        cells = plan.cells();
        var rows = new Object2ObjectLinkedOpenHashMap<String, Row>();
        for (var instance : plan.nodes()) {
            var node = current.nodes().get(instance.index());
            var entry = current.entries().get(node.id());
            String label = entry == null || entry.translationKey() == null ? node.id().toString() : new StructureTitle(entry.translationKey()).getLocal();
            if (node.kind() == StructurePreviewProvider.Kind.SLICE)
                label += StructureToolText.BuildInstrumentSliceSuffix.getLocal(node.minCount(), node.maxCount(), offset(node.sliceOffset()));
            if (!instance.path().equals("0"))
                label = StructureToolText.BuildInstrumentSubstructureLabel.getLocal(label, offset(node.offset()));
            var row = new Row(instance.path(), label, 0, null, null, instance.path(), 0, 0, 0);
            rows.put(instance.path(), row);
            if (root == null) root = row;
            else {
                var parent = rows.get(instance.path().substring(0, instance.path().lastIndexOf('/')));
                if (parent != null) parent.children.add(row);
            }
            if (node.kind() == StructurePreviewProvider.Kind.SLICE)
                row.children.add(new Row(instance.path() + "/slice", StructureToolText.BuildInstrumentSliceSlider.getLocal(instance.count(), node.maxCount()), 3, null, null, instance.path(), node.minCount(), node.maxCount(), instance.count()));
            var counts = new Object2LongLinkedOpenHashMap<StructureDisplay>();
            for (var cell : plan.cells())
                if (cell.display() != null && (cell.path().equals(instance.path()) || cell.path().startsWith(instance.path() + "/")))
                    counts.addTo(cell.display(), 1);
            if (counts.isEmpty()) continue;
            var materials = new Row(instance.path() + "/materials", StructureToolText.BuildInstrumentMaterialsTitleWithCount.getLocal(Math.min(counts.size(), 24)), 1, null, null, instance.path(), 0, 0, 0);
            row.children.add(materials);
            int index = 0;
            var materialsByCount = new ObjectArrayList<>(counts.object2LongEntrySet());
            materialsByCount.sort((a, b) -> Long.compare(b.getLongValue(), a.getLongValue()));
            for (var count : materialsByCount) {
                if (index == 24) break;
                var display = count.getKey();
                var choice = BuilderState.choice(tag, display);
                boolean alternatives = display.options().size() > 1;
                var material = new Row(materials.id + "/" + index++, (alternatives ? StructureToolText.BuildInstrumentMaterialAnyofEntry : StructureToolText.BuildInstrumentMaterialEntry).getLocal(name(choice), count.getLongValue()), 2, display, null, instance.path(), 0, 0, 0);
                materials.children.add(material);
                if (alternatives) for (int i = 0; i < display.options().size(); i++) {
                    var option = display.options().get(i);
                    material.children.add(new Row(material.id + "/" + i, name(option), 2, display, option, instance.path(), 0, 0, 0));
                }
            }
        }
        updateHighlight();
    }

    private void updateHighlight() {
        if (highlightedPath == null) return;
        var positions = new ObjectOpenHashSet<BlockPos>();
        for (var cell : cells)
            if ((cell.path().equals(highlightedPath) || cell.path().startsWith(highlightedPath + "/"))
                && (highlightedDisplay == null || highlightedDisplay.equals(cell.display())))
                positions.add(cell.position());
        highlight.accept(positions);
    }

    private void clampView() {
        if (hits.isEmpty()) return;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (var hit : hits) {
            minX = Math.min(minX, hit.x() - 4);
            minY = Math.min(minY, hit.y() - 2);
            maxX = Math.max(maxX, hit.x() + hit.width() + 4);
            maxY = Math.max(maxY, hit.y() + 16);
        }
        if (!Objects.equals(initializedRoot, viewRoot)) {
            initializedRoot = viewRoot;
            panX = 2 - minX * scale;
            panY = 21 - minY * scale;
        }
        float width = (maxX - minX) * scale, height = (maxY - minY) * scale;
        float minPanX = width + 8 <= 185 ? (185 - width) / 2 - minX * scale : 181 - maxX * scale;
        float maxPanX = width + 8 <= 185 ? minPanX : 4 - minX * scale;
        float minPanY = height + 25 <= 192 ? 21 - minY * scale : 188 - maxY * scale;
        float maxPanY = height + 25 <= 192 ? minPanY : 21 - minY * scale;
        panX = Math.clamp(panX, minPanX, maxPanX);
        panY = Math.clamp(panY, minPanY, maxPanY);
    }

    void draw(StructureCatalogView current, ResourceLocation id, BlockOrientation orientation, int mx, int my) {
        refresh(current, id, orientation);
        hits.clear();
        mouseX = (mx - panX) / scale;
        mouseY = (my - panY) / scale;
        texturedState();
        if (root == null) {
            clip.draw(0, 0, 185, 192, () -> Minecraft.getMinecraft().fontRenderer.drawStringWithShadow(StructureToolText.NoStructureData.getLocal(), 4, 23, 0xff9e9e9e));
            return;
        }
        clip.draw(0, 0, 185, 192, () -> {
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(panX, panY, 0);
                GlStateManager.scale(scale, scale, 1);
                var pending = new ObjectArrayList<Visit>();
                pending.add(new Visit(root, 0));
                int y = 19;
                while (!pending.isEmpty()) {
                    var visit = pending.removeLast();
                    var row = visit.row();
                    int x = visit.depth() * 12;
                    drawRow(row, x, y);
                    if (x > 0) {
                        Gui.drawRect(x - 1, y + 6, x, y + 8, 0xfff2f2f2);
                        Gui.drawRect(x - 1, y + 8, x, y + 9, 0xff696d88);
                    }
                    y += 16;
                    if (open(row)) for (int i = row.children.size() - 1; i >= 0; i--)
                        pending.add(new Visit(row.children.get(i), visit.depth() + 1));
                }
                // Draw continuous parent rails with the same two-pixel bright line and one-pixel shadow.
                for (var hit : hits)
                    if (open(hit.row()) && !hit.row().children.isEmpty()) {
                        int last = hit.y() + 14;
                        for (var child : hits)
                            if (hit.row().children.contains(child.row())) {
                                last = child.y() + 7;
                                Gui.drawRect(hit.x() + 5, last - 1, child.x() - 1, last + 1, 0xfff2f2f2);
                                Gui.drawRect(hit.x() + 5, last + 1, child.x() - 1, last + 2, 0xff696d88);
                            }
                        Gui.drawRect(hit.x() + 5, hit.y() + 14, hit.x() + 7, last, 0xfff2f2f2);
                        Gui.drawRect(hit.x() + 7, hit.y() + 14, hit.x() + 8, last, 0xff696d88);
                        for (var child : hits)
                            if (hit.row().children.contains(child.row()))
                                Gui.drawRect(hit.x() + 5, child.y() + 6, hit.x() + 8, child.y() + 8, 0xfff2f2f2);
                    }
            } finally {
                GlStateManager.popMatrix();
            }
        });
        clampView();
        texturedState();
    }

    private void drawRow(Row row, int x, int y) {
        var mc = Minecraft.getMinecraft();
        boolean toggle = !row.children.isEmpty();
        boolean action = row.option != null;
        boolean chosen = action && BuilderState.optionKey(row.option).equals(BuilderState.optionKey(BuilderState.choice(container.snapshot(), row.display)));
        String actionLabel = (chosen ? StructureToolText.BuildInstrumentMaterialOptionSelected : StructureToolText.BuildInstrumentMaterialOptionChoose).getLocal();
        int actionW = action ? Math.max(24, mc.fontRenderer.getStringWidth(actionLabel) + 8) : 0;
        int width = Math.max(31, 14 + (row.display == null ? 0 : 14) + mc.fontRenderer.getStringWidth(row.label) + 20 + (toggle ? 17 : 0) + (row.kind == 3 ? 72 : 0) + (action ? actionW + 4 : 0));
        var hit = new Hit(row, x, y, width, actionW);
        hits.add(hit);
        if (row.id.equals(selected)) border(x, y, width, 0xff2e6bff);
        if (row.kind == 0)
            StructureToolDraw.horizontal("gui_build_instrument/tree_diagram", x, y, width, 14, 32, 13, 14, 16, 16);
        else {
            Gui.drawRect(x, y, x + width, y + 14, row.kind == 1 ? 0x1effffff : row.kind == 2 ? 0x12000000 : 0x16000000);
            if (row.kind == 1) border(x, y, width, open(row) ? 0x99a6abc6 : 0x66999db5);
        }
        texturedState();
        if (toggle) {
            boolean hover = mouseX >= x + width - 15 && mouseY >= y + 1 && mouseX < x + width - 4 && mouseY < y + 13;
            StructureToolDraw.image("gui_build_instrument/button/" + (open(row) ? "withdraw" : "expand") + "_" + (row.id.equals(pressed) ? "pressed" : hover ? "selected" : "default"), x + width - 15, y + 1, 11, 12);
        }
        if (row.display != null) {
            var icon = stack(row.option == null ? BuilderState.choice(container.snapshot(), row.display) : row.option);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(x + 2, y + 1, 0);
                GlStateManager.scale(.75f, .75f, 1);
                RenderHelper.enableGUIStandardItemLighting();
                mc.getRenderItem().renderItemAndEffectIntoGUI(icon, 0, 0);
                RenderHelper.disableStandardItemLighting();
            } finally {
                GlStateManager.popMatrix();
            }
        }
        GlStateManager.enableTexture2D();
        mc.fontRenderer.drawStringWithShadow(row.label, x + (row.display == null ? 14 : 16), y + 4, row.kind == 2 ? 0xffe6e6e6 : 0xffffffff);
        if (action) {
            int ax = x + width - 4 - actionW;
            boolean hover = mouseX >= ax && mouseX < ax + actionW && mouseY >= y + 2 && mouseY < y + 12;
            Gui.drawRect(ax, y + 2, ax + actionW, y + 12, chosen ? 0x22000000 : hover ? 0x55376dff : 0x442e6bff);
            Gui.drawRect(ax, y + 2, ax + actionW, y + 3, 0x66ffffff);
            mc.fontRenderer.drawStringWithShadow(actionLabel, ax + (actionW - mc.fontRenderer.getStringWidth(actionLabel)) / 2f, y + 3, chosen ? 0xaaaaaa : 0xffffff);
        }
        if (row.kind == 3) {
            int sx = x + width - 76;
            StructureToolDraw.horizontal("gui_states/slider/s/normal/lr_base_expand", sx, y + 2, 72, 10, 14, 5, 6, 8, 6);
            int handle = sx + 1 + (int) (63 * Math.clamp((row.value - row.min) / (double) Math.max(1L, (long) row.max - row.min), 0, 1));
            StructureToolDraw.image("gui_states/slider/s/lr_" + (slider != null && slider.row().id.equals(row.id) ? "pressed" : mouseX >= handle && mouseX <= handle + 7 && mouseY >= y + 3 && mouseY <= y + 11 ? "selected" : "default"), handle, y + 3, 7, 8);
        }
    }

    boolean press(int x, int y, int button) {
        if (x < 0 || y < 0 || x >= 185 || y >= 192) return false;
        if (button == 1 || button == 0 && y < 19) {
            dragButton = button;
            lastX = x;
            lastY = y;
            return true;
        }
        if (button != 0) return false;
        float tx = (x - panX) / scale, ty = (y - panY) / scale;
        for (var hit : hits)
            if (hit.contains(tx, ty)) {
                var row = hit.row();
                if (hit.toggle(tx, ty)) pressed = row.id;
                else if (row.kind == 3 && tx >= hit.x() + hit.width() - 76) {
                    slider = hit;
                    adjustSlider(tx);
                } else if (row.option != null && hit.action(tx, ty)
                    && !BuilderState.optionKey(row.option).equals(BuilderState.optionKey(BuilderState.choice(container.snapshot(), row.display))))
                    container.action("material", row.display.key(), BuilderState.optionKey(row.option));
                else {
                    selected = row.id;
                    if (row != root && (row.kind == 0 || row.kind == 2)) {
                        highlightedPath = row.path;
                        highlightedDisplay = row.display;
                        updateHighlight();
                    }
                }
                return true;
            }
        if (selected == null) return false;
        selected = null;
        highlightedPath = null;
        highlightedDisplay = null;
        highlight.accept(Set.of());
        return true;
    }

    void drag(int x, int y, int button) {
        if (dragButton == button) {
            panX += x - lastX;
            panY += y - lastY;
            lastX = x;
            lastY = y;
            clampView();
        }
        if (slider != null) adjustSlider((x - panX) / scale);
    }

    private void adjustSlider(float tx) {
        var row = slider.row();
        double fraction = Math.clamp((tx - slider.x() - slider.width() + 75) / 63d, 0, 1);
        slices.put(row.path, Math.toIntExact(row.min + Math.round(((long) row.max - row.min) * fraction)));
    }

    void release(int x, int y, int button) {
        if (button == 0 && pressed != null) for (var hit : hits)
            if (hit.row().id.equals(pressed) && hit.toggle((x - panX) / scale, (y - panY) / scale))
                expanded.put(pressed, !open(hit.row()));
        pressed = null;
        slider = null;
        if (dragButton == button) dragButton = -1;
    }

    boolean wheel(int x, int y, int delta) {
        if (x < 0 || y < 0 || x >= 185 || y >= 192) return false;
        int direction = Integer.signum(delta);
        if (GuiScreen.isCtrlKeyDown()) scale = Math.clamp(scale + direction * .1f, .5f, 2f);
        else if (GuiScreen.isShiftKeyDown()) panX += direction * 12;
        else panY += direction * 12;
        clampView();
        return true;
    }

    private static final class Row {
        final String id, label;
        final int kind;
        final StructureDisplay display;
        final StructureDisplay.BlockOption option;
        final String path;
        final int min, max, value;
        final List<Row> children = new ObjectArrayList<>();

        Row(String id, String label, int kind, StructureDisplay display, StructureDisplay.BlockOption option, String path, int min, int max, int value) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.display = display;
            this.option = option;
            this.path = path;
            this.min = min;
            this.max = max;
            this.value = value;
        }
    }

    private record Hit(Row row, int x, int y, int width, int actionWidth) {
        boolean contains(float px, float py) {
            return px >= x && py >= y && px <= x + width && py <= y + 14;
        }

        boolean toggle(float px, float py) {
            return !row.children.isEmpty() && px >= x + width - 15 && px <= x + width - 4 && py >= y + 1 && py <= y + 13;
        }

        boolean action(float px, float py) {
            return actionWidth > 0 && px >= x + width - 4 - actionWidth && px <= x + width - 4 && py >= y + 2 && py <= y + 12;
        }
    }

    private record Visit(Row row, int depth) {
    }

}
