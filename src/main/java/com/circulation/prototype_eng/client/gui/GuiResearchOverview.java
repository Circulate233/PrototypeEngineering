package com.circulation.prototype_eng.client.gui;

import ae2.api.client.AEKeyRendering;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.api.stacks.GenericStack;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.Icon;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.client.research.ClientResearchContents;
import com.circulation.prototype_eng.container.ContainerResearchOverview;
import com.circulation.prototype_eng.container.ResearchOverviewPacket;
import com.circulation.prototype_eng.container.ResearchOverviewPacket.*;
import com.circulation.prototype_eng.localization.ResearchOverviewGuiText;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.circulation.prototype_eng.client.gui.DeviceGuiDraw.*;
import static com.circulation.prototype_eng.localization.ResearchOverviewGuiText.*;

/**
 * Full-screen AE research canvas. Geometry uses the approved HTML CSS pixels in a 1536 by 864 reference viewport.
 * Page coordinates are GUI pixels; displayed server projections are borrowed until their next update.
 */
@SideOnly(Side.CLIENT)
@SuppressWarnings("DataFlowIssue") // Page and detail row kinds retain entries from the received definition projection.
public class GuiResearchOverview extends AEBaseGui<ContainerResearchOverview> {
    private static final double S = 1.7, COL = 92 * S, ROW = 40 * S;
    private static final int NODE = 44, WIDE = 84, EXP_W = 128, EXP_H = 52, LEFT = 320, SIDE = 352;
    private static final int BASE = 0xcbccd4, LOCKED = 0x8f94a9, MARK = 0xd8a43a;
    private static final Slice FRAME = new Slice(new ResourceLocation("ae2", "textures/guis/background.png"), 256, 256, 4, 7);
    private static final Slice BUTTON = new Slice(new ResourceLocation("ae2", "textures/guis/button.png"), 200, 20, 2, 4);
    private static final Slice BUTTON_HOVER = new Slice(new ResourceLocation("ae2", "textures/guis/button_highlighted.png"), 200, 20, 2, 4);
    private static final ResourceLocation SQUARE = Icon.SMALL_SQUARE_BUTTON_BACKGROUND.texture();
    private static final ResourceLocation SQUARE_HOVER = Icon.SMALL_SQUARE_BUTTON_BACKGROUND_HOVER.texture();
    private final ObjectArrayList<PageView> pages = new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<String, PageView> pagesById = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, Display> displays = new Object2ObjectOpenHashMap<>();
    private final DerivedBaseline derivedBaseline = new DerivedBaseline();
    private final ObjectArrayList<Hit> hits = new ObjectArrayList<>();
    private final ObjectArrayList<String> hoverText = new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<String, ResourceLocation> choices = new Object2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<ResourceLocation> forgetLevels = new Object2IntOpenHashMap<>();
    private final GuiClip clip = new GuiClip();
    private final ResearchNodeIconRenderer nodeIcons = new ResearchNodeIconRenderer();
    private List<Entry> knownEntries;
    private List<Database> knownDatabases;
    private @Nullable Entry knownSelected;
    private @Nullable ResearchContent knownDetailContent;
    private @Nullable PageView page;
    private @Nullable ResourceLocation selected;
    private @Nullable ResourceLocation forgetting;
    private final ObjectArrayList<DetailRow> detail = new ObjectArrayList<>();
    private final ObjectArrayList<String> footerInfo = new ObjectArrayList<>();
    private final ObjectArrayList<FooterLine> footerLines = new ObjectArrayList<>();
    private final ObjectArrayList<String> pickerRules = new ObjectArrayList<>();
    private int footerInfoHeight;
    private final ObjectArrayList<Capacity> capacities = new ObjectArrayList<>();
    private double panX = 340, panY = 120, zoom = 1, screenW, screenH, pixelScale, renderScale, leftTop = 62, leftBottom;
    private static final int DETAIL_CONTENT_WIDTH = 306;
    private double detailScroll, detailHeight, bodyTop, bodyBottom, footerTop;
    private double pressX, pressY, lastX, lastY, nodeStartX, nodeStartY, dragX, dragY;
    private long pressedAt, toastUntil;
    private @Nullable ResourceLocation pressedNode, dragging;
    private @Nullable Hit pressedHit;
    private boolean moved, held, canvasDrag, scrollbarDrag, detailDirty = true;
    private double scrollbarGrab;
    private double mouseX, mouseY;
    private String toast = "";
    private String knownActionResult = "";
    private long contentRevision = -1;

    public GuiResearchOverview(ContainerResearchOverview container, InventoryPlayer player) {
        super(container, player);
    }

    @Override
    public void initGui() {
        xSize = width;
        ySize = height;
        super.initGui();
        guiLeft = guiTop = 0;
        pixelScale = Math.min(mc.displayWidth / 1536D, mc.displayHeight / 864D);
        renderScale = pixelScale / new ScaledResolution(mc).getScaleFactor();
        screenW = mc.displayWidth / pixelScale;
        screenH = mc.displayHeight / pixelScale;
        knownEntries = null;
        knownDatabases = null;
        pickerRules.clear();
        if (container.databaseMode())
            pickerRules.addAll(fontRenderer.listFormattedStringToWidth(tr(PickerRule), (int) (278 * 9 / 11D)));
        detailDirty = true;
    }

    @Override
    public void onGuiClosed() {
        nodeIcons.delete();
        super.onGuiClosed();
    }

    private static String tr(ResearchOverviewGuiText key, Object... args) {
        return key.getLocal(args);
    }

    private int target(Entry entry) {
        return (int) Math.min(entry.maxLevel(), (long) entry.teamLevel() + 1);
    }

    private int queueIndex(ResourceLocation id) {
        var queue = container.view.queue();
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(id)) return i;
        return -1;
    }

    private double fraction(Entry entry) {
        var progress = entry.progress();
        return progress == null || progress.targetLevel() != target(entry) || entry.teamLevel() >= entry.maxLevel()
            ? 0 : Math.clamp((double) progress.elapsed() / entry.stage(progress.targetLevel()).duration(), 0, 1);
    }

    private boolean showsProgress(Entry entry) {
        return !container.databaseMode() && (queueIndex(entry.id()) == 0 || fraction(entry) > 0);
    }

    private boolean missing(Entry entry) {
        var requirements = entry.stage(target(entry)).prerequisites();
        for (int i = 0; i < requirements.size(); i++) {
            var required = requirements.get(i);
            var other = container.view.entry(required.id());
            if (other == null || other.teamLevel() < required.level()) return true;
        }
        return false;
    }

    private boolean blocked(Entry entry) {
        var requirements = entry.stage(target(entry)).mutex();
        for (int i = 0; i < requirements.size(); i++) {
            var required = requirements.get(i);
            var other = container.view.entry(required.id());
            if (other != null && other.teamLevel() >= required.level()) return true;
        }
        return false;
    }

    private Status status(Entry entry) {
        var display = derived(entry);
        if (display.status != null) return display.status;
        Status result;
        if (entry.specialOnly()) result = Status.SPECIAL;
        else if (container.databaseMode()) result = entry.selectable() ? Status.PICKABLE : Status.LOCKED;
        else if (entry.teamLevel() >= entry.maxLevel()) result = Status.DONE;
        else if (missing(entry)) result = Status.LOCKED;
        else if (blocked(entry)) result = Status.BLOCKED;
        else {
            int index = queueIndex(entry.id());
            boolean partial = entry.teamLevel() > 0;
            if (index == 0) result = Status.RESEARCHING;
            else if (index > 0) result = partial ? Status.QUEUED_PARTIAL : Status.QUEUED;
            else if (deviceShort(entry)) result = partial ? Status.DEVICE_PARTIAL : Status.DEVICE;
            else if (fraction(entry) > 0) result = partial ? Status.SAVED_PARTIAL : Status.SAVED;
            else result = partial ? Status.CONTINUE : Status.AVAILABLE;
        }
        display.status = result;
        return result;
    }

    private String statusText(Entry entry) {
        var display = derived(entry);
        if (display.statusText == null) {
            var key = status(entry).key;
            display.statusText = key == Researching || key == Saved ? tr(key, percentage(fraction(entry)))
                : key == Queued ? tr(key, queueIndex(entry.id()) + 1) : tr(key);
        }
        return display.statusText;
    }

    private String maxText(Entry entry) {
        return entry.maxLevel() == Integer.MAX_VALUE ? "∞" : Integer.toString(entry.maxLevel());
    }

    private Display display(ResourceLocation id) {
        var content = ClientResearchContents.get(id);
        var found = displays.get(id);
        if (found == null || found.content != content) {
            found = new Display(content);
            displays.put(id, found);
        }
        return found;
    }

    private Display derived(Entry entry) {
        var view = container.view;
        var baseline = derivedBaseline;
        var active = view.activeDatabase();
        if (baseline.entries != view.entries() || baseline.queue != view.queue() || baseline.databases != view.databases()
            || baseline.center != view.centerLevel() || baseline.station != view.stationLevel()
            || !Objects.equals(baseline.active, active) || baseline.manage != view.canManage()
            || baseline.pending != view.savePending() || baseline.databaseTier != view.databaseLevel() || baseline.page != page) {
            baseline.entries = view.entries();
            baseline.queue = view.queue();
            baseline.databases = view.databases();
            baseline.center = view.centerLevel();
            baseline.station = view.stationLevel();
            baseline.active = active;
            baseline.manage = view.canManage();
            baseline.pending = view.savePending();
            baseline.databaseTier = view.databaseLevel();
            baseline.page = page;
            baseline.generation++;
        }
        var display = display(entry.id());
        if (display.entry != entry || display.generation != baseline.generation) {
            display.entry = entry;
            display.generation = baseline.generation;
            display.status = null;
            display.statusText = display.downloadReason = display.forgetReason = display.pickerReason = null;
            display.deviceReasons = display.startReasons = null;
        }
        return display;
    }

    private boolean deviceShort(Entry entry) {
        int tier = entry.stage(target(entry)).tier();
        if (entry.specialOnly() || entry.teamLevel() >= entry.maxLevel() || tier == 0) return false;
        var database = activeDatabase();
        return container.view.centerLevel() == 0 || database == null || database.tier() < tier
            || container.view.stationLevel() < tier;
    }

    private void updateView() {
        long revision = ClientResearchContents.revision();
        if (contentRevision != revision) {
            contentRevision = revision;
            knownEntries = null;
            knownDatabases = null;
            pickerRules.clear();
            if (container.databaseMode())
                pickerRules.addAll(fontRenderer.listFormattedStringToWidth(tr(PickerRule), (int) (278 * 9 / 11D)));
        }
        if (!container.actionResult.equals(knownActionResult)) {
            knownActionResult = container.actionResult;
            var reason = switch (knownActionResult) {
                case "", "STARTED", "ALREADY_PRESENT", "RESUMED", "ADVANCED", "COMPLETED", "CANCELLED", "PAUSED",
                     "WAITING_FOR_TURN",
                     "NO_MATERIALS", "CLAIMED", "ALREADY_CLAIMED", "APPLIED", "UPDATED", "UNCHANGED" -> null;
                case "PERMISSION_DENIED" -> Denied;
                case "SOURCE_RESERVED" -> Reserved;
                case "BUSY", "QUEUE_BUSY", "RETRY_LATER", "SAVE_PENDING", "RECOVERY_PENDING", "COMPLETION_PENDING" ->
                    SavePending;
                case "QUEUE_FULL" -> QueueFull;
                case "DUPLICATE_TARGET" -> AlreadyQueued;
                case "NO_ACTIVE_CENTER" -> NoCenter;
                case "NO_ACTIVE_DATA_CENTER" -> NoDatabase;
                case "CAPACITY_FULL" -> CapacityFull;
                case "MAX_LEVEL_REACHED" -> MaxLevel;
                default -> ActionFailed;
            };
            if (reason != null) showToast(tr(reason));
        }
        var entries = container.view.entries();
        if (knownEntries != entries) {
            boolean definitionsChanged = knownEntries == null || knownEntries.size() != entries.size();
            if (!definitionsChanged) for (int i = 0; i < entries.size(); i++) {
                if (knownEntries.get(i).definition() != entries.get(i).definition()) {
                    definitionsChanged = true;
                    break;
                }
            }
            knownEntries = entries;
            if (definitionsChanged) rebuildPages(entries);
            detailDirty = true;
        }
        var databases = container.view.databases();
        if (knownDatabases != databases) {
            knownDatabases = databases;
            var stored = new Int2LongOpenHashMap();
            var counts = new Int2LongOpenHashMap();
            for (int i = 0; i < databases.size(); i++) {
                var database = databases.get(i);
                stored.addTo(database.tier(), database.stored().size());
                counts.addTo(database.tier(), database.capacity());
            }
            capacities.clear();
            var tiers = counts.keySet().toIntArray();
            Arrays.sort(tiers);
            for (int tier : tiers)
                capacities.add(new Capacity("Lv" + tier, GuiNumbers.grouped(stored.get(tier)) + " / " + GuiNumbers.grouped(counts.get(tier))));
            detailDirty = true;
        }
        var entry = selected == null ? null : container.view.entry(selected);
        if (entry != knownSelected) {
            knownSelected = entry;
            detailDirty = true;
        }
        if (entry != null && display(entry.id()).content != knownDetailContent) {
            knownDetailContent = display(entry.id()).content;
            detailDirty = true;
        }
        if (entry != null) {
            long generation = derivedBaseline.generation;
            derived(entry);
            if (generation != derivedBaseline.generation) detailDirty = true;
        }
        if (entry == null) selected = forgetting = null;
        if (detailDirty) {
            rebuildDetail(entry);
            detailDirty = false;
        }
        if (held && Mouse.isButtonDown(0) && !moved && pressedNode != null && container.editorMode() && MinecraftTime() - pressedAt >= 300) {
            dragging = pressedNode;
            dragX = nodeStartX;
            dragY = nodeStartY;
        }
    }

    private static long MinecraftTime() {
        return Minecraft.getSystemTime();
    }

    private void rebuildPages(List<Entry> entries) {
        String previous = page == null ? null : page.id;
        pages.clear();
        pagesById.clear();
        var byNamespace = new Object2ObjectLinkedOpenHashMap<String, ObjectArrayList<ResourceLocation>>();
        for (int i = 0; i < entries.size(); i++) {
            var id = entries.get(i).id();
            byNamespace.computeIfAbsent(id.getNamespace(), _ -> new ObjectArrayList<>()).add(id);
        }
        for (var group : byNamespace.object2ObjectEntrySet()) {
            var config = ClientResearchContents.page(group.getKey());
            var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
            if (config.manual()) {
                positions.putAll(config.positions());
                int below = 0, lost = 0;
                for (var position : positions.values()) below = Math.max(below, position.y() + 80);
                for (int i = 0; i < group.getValue().size(); i++) {
                    var path = group.getValue().get(i).getPath();
                    if (!positions.containsKey(path)) {
                        positions.put(path, new ResearchPage.Position(lost % 6 * 92, below + lost / 6 * 40));
                        lost++;
                    }
                }
            } else autoLayout(group.getValue(), positions);
            var nameKey = "gui.prototype_eng.research_page." + group.getKey();
            LocalizationEnum label = () -> nameKey;
            var view = new PageView(group.getKey(), label.getLocal(), group.getValue(), new ResearchPage(config.order(), config.manual(), positions));
            pages.add(view);
            pagesById.put(view.id, view);
        }
        pages.sort(Comparator.comparingInt((PageView p) -> p.layout.order()).thenComparing(p -> p.id));
        page = previous == null ? null : pagesById.get(previous);
        if (page == null && !pages.isEmpty()) page = pages.getFirst();
    }

    private int depth(ResourceLocation id, String namespace, Object2IntOpenHashMap<ResourceLocation> depths) {
        if (depths.containsKey(id)) return depths.getInt(id);
        depths.put(id, 0);
        int result = 0;
        var prerequisites = container.view.entry(id).stage(target(container.view.entry(id))).prerequisites();
        for (int i = 0; i < prerequisites.size(); i++) {
            var required = prerequisites.get(i).id();
            if (required.getNamespace().equals(namespace) && container.view.entry(required) != null)
                result = Math.max(result, depth(required, namespace, depths) + 1);
        }
        depths.put(id, result);
        return result;
    }

    private void autoLayout(List<ResourceLocation> ids, Object2ObjectOpenHashMap<String, ResearchPage.Position> positions) {
        var linked = new ObjectOpenHashSet<ResourceLocation>();
        var depths = new Object2IntOpenHashMap<ResourceLocation>();
        String namespace = ids.getFirst().getNamespace();
        for (int i = 0; i < ids.size(); i++) {
            var entry = container.view.entry(ids.get(i));
            var requirements = entry.stage(target(entry)).prerequisites();
            for (int j = 0; j < requirements.size(); j++)
                if (requirements.get(j).id().getNamespace().equals(namespace)) {
                    linked.add(entry.id());
                    linked.add(requirements.get(j).id());
                }
        }
        int maxColumn = 0;
        for (int i = 0; i < ids.size(); i++)
            if (linked.contains(ids.get(i))) maxColumn = Math.max(maxColumn, depth(ids.get(i), namespace, depths));
        int maxRow = -2;
        for (int column = 0; column <= maxColumn; column++) {
            var candidates = new ObjectArrayList<ResourceLocation>();
            for (int i = 0; i < ids.size(); i++)
                if (linked.contains(ids.get(i)) && depths.getInt(ids.get(i)) == column) candidates.add(ids.get(i));
            candidates.sort(Comparator.comparingInt(id -> preferredRow(container.view.entry(id), positions)));
            var used = new IntOpenHashSet();
            for (int i = 0; i < candidates.size(); i++) {
                int row = preferredRow(container.view.entry(candidates.get(i)), positions);
                if (row == Integer.MAX_VALUE) row = 0;
                while (used.contains(row)) row++;
                used.add(row);
                maxRow = Math.max(maxRow, row);
                positions.put(candidates.get(i).getPath(), new ResearchPage.Position(column * 92, row * 40));
            }
        }
        int lone = 0;
        for (int i = 0; i < ids.size(); i++)
            if (!linked.contains(ids.get(i))) {
                positions.put(ids.get(i).getPath(), new ResearchPage.Position(lone % 6 * 92, (maxRow + 2 + lone / 6) * 40));
                lone++;
            }
    }

    private int preferredRow(Entry entry, Object2ObjectOpenHashMap<String, ResearchPage.Position> positions) {
        int result = Integer.MAX_VALUE;
        var required = entry.stage(target(entry)).prerequisites();
        for (int i = 0; i < required.size(); i++) {
            var position = positions.get(required.get(i).id().getPath());
            if (position != null) result = Math.min(result, position.y() / 40);
        }
        return result;
    }

    @Override
    public void drawScreen(int actualMouseX, int actualMouseY, float partialTicks) {
        mouseX = Mouse.getX() / pixelScale;
        mouseY = (mc.displayHeight - Mouse.getY() - 1) / pixelScale;
        updateView();
        if (Mouse.isButtonDown(0)) updateDrag();
        hits.clear();
        hoverText.clear();
        GlStateManager.pushMatrix();
        GlStateManager.scale(renderScale, renderScale, 1);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        rect(0, 0, (int) Math.ceil(screenW), (int) Math.ceil(screenH), PANEL);
        drawCanvas();
        drawPages();
        drawLeft();
        drawBanner();
        drawTopRight();
        drawEscape();
        drawDetails();
        if (MinecraftTime() < toastUntil) {
            double w = textWidth(toast, 13) + 28;
            box((screenW - w) / 2, screenH - 65, w, 26, BASE, LINE);
            label(toast, (screenW - w) / 2 + 14, screenH - 61, w - 28, 13, TEXT);
        }
        if (!hoverText.isEmpty()) {
            GlStateManager.pushMatrix();
            GlStateManager.scale(4D / 3, 4D / 3, 1);
            GuiUtils.drawHoveringText(hoverText, (int) (mouseX * 0.75), (int) (mouseY * 0.75),
                (int) (screenW * 0.75), (int) (screenH * 0.75), -1, fontRenderer);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableDepth();
    }

    private void drawCanvas() {
        double gridX = panX - (COL - NODE) / 2 * zoom, gridY = panY - (ROW - NODE) / 2 * zoom;
        int gridColor = 0x9196aa;
        for (double x = gridX % (COL * zoom); x < screenW; x += COL * zoom) fill(x, 0, 1, screenH, gridColor);
        for (double y = gridY % (ROW * zoom); y < screenH; y += ROW * zoom) fill(0, y, screenW, 1, gridColor);
        if (page == null) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(panX, panY, 0);
        GlStateManager.scale(zoom, zoom, 1);
        for (int i = 0; i < page.ids.size(); i++) drawLinks(container.view.entry(page.ids.get(i)));
        nodeIcons.beginBatch();
        try {
            for (int i = 0; i < page.ids.size(); i++)
                if (!page.ids.get(i).equals(selected)) drawNode(container.view.entry(page.ids.get(i)), false);
            if (selected != null && selected.getNamespace().equals(page.id))
                drawNode(container.view.entry(selected), true);
        } finally {
            nodeIcons.endBatch();
        }
        GlStateManager.popMatrix();
    }

    private ResearchPage.Position position(ResourceLocation id) {
        return pagesById.get(id.getNamespace()).layout.positions().get(id.getPath());
    }

    private double nodeX(ResourceLocation id) {
        return id.equals(dragging) ? dragX : position(id).x() * S;
    }

    private double nodeY(ResourceLocation id) {
        return id.equals(dragging) ? dragY : position(id).y() * S;
    }

    private void drawLinks(Entry entry) {
        double tx = nodeX(entry.id()), ty = nodeY(entry.id()), cy = ty + NODE / 2D;
        var required = entry.stage(target(entry)).prerequisites();
        for (int i = 0; i < required.size(); i++) {
            var requirement = required.get(i);
            var source = container.view.entry(requirement.id());
            int color = source != null && source.teamLevel() >= requirement.level() ? DARK : 0xc4c7d3;
            if (!requirement.id().getNamespace().equals(page.id)) {
                for (int k = 0; k < 24; k += 7) fill(tx - 24 + k, cy - 1, 4, 2, color);
                ring(tx - 28, cy, 4, 2, color, 0, 1);
                continue;
            }
            if (source == null) continue;
            double sx = nodeX(source.id()), sy = nodeY(source.id()), edge = sx + (showsProgress(source) ? WIDE : NODE), bend = tx - 14;
            if (edge + 8 <= bend) {
                line(edge, sy + NODE / 2D, bend, sy + NODE / 2D, color);
                line(bend, sy + NODE / 2D, bend, cy, color);
            } else {
                double routeY = sy + NODE + 6 <= ty ? (sy + NODE + ty) / 2 : ty + NODE + 6 <= sy ? (ty + NODE + sy) / 2 : Math.max(sy, ty) + NODE + 10;
                line(edge, sy + NODE / 2D, edge + 8, sy + NODE / 2D, color);
                line(edge + 8, sy + NODE / 2D, edge + 8, routeY, color);
                line(edge + 8, routeY, bend, routeY, color);
                line(bend, routeY, bend, cy, color);
            }
            line(bend, cy, tx, cy, color);
        }
    }

    private void drawNode(Entry entry, boolean expanded) {
        double x = nodeX(entry.id()), y = nodeY(entry.id()) - (expanded ? 4 : 0);
        double w = expanded ? EXP_W : showsProgress(entry) ? WIDE : NODE, h = expanded ? EXP_H : NODE;
        if (panX + (x + w) * zoom < 0 || panX + x * zoom > screenW
            || panY + (y + h) * zoom < 0 || panY + y * zoom > screenH) return;
        var state = status(entry);
        boolean hovering = inside(panX + x * zoom, panY + y * zoom, w * zoom, h * zoom) && !overChrome();
        boolean dim = state == Status.LOCKED;
        boolean drag = entry.id().equals(dragging);
        boolean brighten = hovering && !expanded && !dim && !drag;
        if (expanded || drag) {
            int shift = drag ? 4 : 3;
            alphaFill(x + shift, y + h, w, shift, DARK, 0.45F);
            alphaFill(x + w, y + shift, shift, h, DARK, 0.45F);
        }
        if (expanded || drag || state == Status.RESEARCHING)
            box(x - (drag ? 2 : 1), y - (drag ? 2 : 1), w + (drag ? 4 : 2), h + (drag ? 4 : 2), LIGHT, LIGHT);
        box(x, y, w, h, brighten ? brighter(state.background) : state.background, brighten ? brighter(state.border) : state.border, 2);
        double iconX = x + (expanded ? 10 : 8), iconY = y + (h - 28) / 2;
        var researchIcon = ClientResearchContents.icon(entry.id());
        if (researchIcon != null && (dim || blocked(entry) || brighten || drag))
            nodeIcons.draw(mc, researchIcon, (float) iconX, (float) iconY, 28, (dim ? 0.35F : blocked(entry) ? 0.6F : 1) * (drag ? 0.9F : 1),
                dim ? 1 : 0, brighten ? 1.07F : 1);
        else icon(entry.id(), iconX, iconY, 28);
        String name = display(entry.id()).content.title();
        if (expanded) {
            double infoY = y + (showsProgress(entry) ? 6.5 : 10);
            label(name, x + 45, infoY, w - 55, 13, TEXT);
            String line = container.databaseMode() ? tr(NodePickedLevel, entry.teamLevel())
                : entry.maxLevel() > 1 && (state == Status.CONTINUE || state == Status.AVAILABLE || state == Status.QUEUED || state == Status.QUEUED_PARTIAL)
                ? "Lv." + entry.teamLevel() + " / " + maxText(entry) : statusText(entry);
            label(line, x + 45, infoY + 18, w - 55, 11, SOFT);
            if (showsProgress(entry)) bar(x + 45, infoY + 35, w - 55, 4, fraction(entry), DARK);
        } else if (showsProgress(entry))
            label(percentage(fraction(entry)), x + 43, y + 14, w - 51, 13, brighten ? brighter(TEXT) : TEXT);
        if (!expanded && entry.maxLevel() > 1 && (!container.databaseMode() || entry.selectable())) {
            String badge = container.databaseMode() ? "Lv." + entry.teamLevel() : entry.maxLevel() == Integer.MAX_VALUE ? Integer.toString(entry.teamLevel()) : entry.teamLevel() + "/" + entry.maxLevel();
            aligned(badge, x + w - 2, y + h - 12, w - 4, 11, LIGHT);
        }
        if (!container.databaseMode()) {
            int queue = queueIndex(entry.id());
            if (queue >= 0) {
                fill(x + 2, y + 2, textWidth(Integer.toString(queue + 1), 10) + 6, 12, DARK);
                label(Integer.toString(queue + 1), x + 5, y + 2, 8, 10, LIGHT);
            }
            if (entry.downloadedLevel() >= entry.teamLevel() && entry.downloadedLevel() > 0)
                box(x + w - 11, y + 3, 8, 8, MARK, DARK);
            if (state.key == Locked || state.key == Blocked || state.key == Special || state.key == DeviceShort)
                box(x + 3, y + h - 11, 8, 8, state.key == Blocked ? BAD : state.key == Special ? WAIT : MUTED, DARK);
        }
        if (inside(panX + x * zoom, panY + y * zoom, w * zoom, h * zoom) && !overChrome()) {
            hoverText.clear();
            hoverText.add(name + (entry.maxLevel() > 1 ? " Lv." + entry.teamLevel() + " / " + maxText(entry) : ""));
            if (!display(entry.id()).content.summary().isEmpty()) hoverText.add(display(entry.id()).content.summary());
            hoverText.add(container.databaseMode() ? pickerReason(entry) : statusText(entry));
            if (missing(entry)) appendRequirements(hoverText, entry.stage(target(entry)).prerequisites(), true);
            hoverText.addAll(deviceReasons(entry));
            if (!entry.specialOnly() && !container.databaseMode()) hoverText.add(downloadReason(entry));
        }
    }

    private void drawPages() {
        double available = screenW / 2 - 180, x = 16, y = 0;
        for (int i = 0; i < pages.size(); i++) {
            var candidate = pages.get(i);
            if (x > 16 && x - 16 + 136 > available) {
                x = 16;
                y += 52;
            }
            boolean active = candidate == page;
            if (active) for (int k = 0; k < 42; k++) alphaFill(x, y + 5 + k, 136, 1, LIGHT, 0.55F * (1 - k / 42F));
            fill(x, y, 136, 5, active ? DARK : inside(x, y, 136, 52) ? LINE : MUTED);
            centeredLabel(candidate.name, x + 6, y + 11, 124, 32, 24, active ? DARK : SOFT);
            int completed = 0;
            for (int j = 0; j < candidate.ids.size(); j++) {
                var entry = container.view.entry(candidate.ids.get(j));
                if (container.databaseMode() ? entry.selectable() : !entry.specialOnly() && entry.teamLevel() >= entry.maxLevel())
                    completed++;
            }
            var tip = new ObjectArrayList<String>();
            tip.add(candidate.name);
            tip.add(container.databaseMode() ? tr(SelectablePageCount, completed) : tr(CompletedCount, completed, candidate.ids.size()));
            if (container.editorMode()) {
                tip.add(candidate.id);
                tip.add("gui.prototype_eng.research_page." + candidate.id);
            }
            hit(x, y, 136, 52, true, () -> switchPage(candidate), tip);
            x += 146;
        }
        leftTop = Math.max(62, y + 62);
    }

    private void switchPage(PageView next) {
        page = next;
        selected = forgetting = null;
        panX = 340;
        panY = 120;
        detailScroll = 0;
        detailDirty = true;
    }

    private void drawLeft() {
        leftBottom = leftTop;
        if (container.databaseMode()) {
            double x = 16, y = leftTop;
            double height = 85 + pickerRules.size() * 16;
            frame(x, y, 296, height);
            String name = container.view.databaseName();
            double nameWidth = Math.min(130, textWidth(name, 11));
            label(tr(PickerTitle), x + 9, y + 10, 278 - nameWidth - 8, 14, TEXT);
            aligned(name, x + 287, y + 11, nameWidth, 11, SOFT);
            fill(x + 7, y + 32, 282, 1, LINE);
            keyValue(tr(DatabaseTierLabel), Integer.toString(container.view.databaseLevel()), x + 9, y + 38, 278, 12, TEXT);
            int count = 0;
            for (int i = 0; i < knownEntries.size(); i++) if (knownEntries.get(i).selectable()) count++;
            keyValue(tr(SelectableCount), Integer.toString(count), x + 9, y + 56, 278, 12, TEXT);
            for (int i = 0; i < pickerRules.size(); i++)
                label(pickerRules.get(i), x + 9, y + 78 + i * 16, 278, 11, SOFT);
            leftBottom = y + height;
            return;
        }
        var queue = container.view.queue();
        for (int i = 0; i < 5; i++) {
            double x = 20 + i * 54, y = leftTop;
            if (i < queue.size()) {
                var row = queue.get(i);
                var entry = container.view.entry(row.id());
                ring(x + 23, y + 23, 23, 23, LINE, 0, 1);
                if (i == 0) ring(x + 23, y + 23, 23, 3, SLOT, DARK, fraction(entry));
                circle(x + 23, y + 23, 20, inside(x, y, 46, 46) ? HOVER : i == 0 ? LIGHT : SLOT);
                icon(row.id(), x + 11, y + 11, 24);
                circle(x + 45, y + 5, 8, DARK);
                centeredLabel(Integer.toString(i + 1), x + 37, y - 3, 16, 16, 11, LIGHT);
                hit(x, y, 46, 46, true, () -> focus(row.id()), List.of(display(row.id()).content.title() + " Lv." + row.targetLevel(), tr(Progress, percentage(fraction(entry)))));
            } else ring(x + 23, y + 23, 23, 2, LINE, 0, 1);
        }
        leftBottom = leftTop + 46;
        var current = container.view.current();
        if (current == null) return;
        double contentHeight = 20 + 6 + 5 + 24;
        if (!current.materials().isEmpty())
            contentHeight += (current.manual() ? 0 : 20) + current.materials().size() * 34;
        else if (!current.manual()) contentHeight += 20;
        if (!current.resources().isEmpty()) contentHeight += 20 + current.resources().size() * 58.4;
        if (current.manual()) contentHeight += 36;
        double top = leftTop + 54, height = 14 + contentHeight;
        frame(16, top, 296, height);
        clip.draw(23, (float) (top + 7), 282, (float) contentHeight, () -> drawCurrent(current, top));
        leftBottom = top + height;
    }

    private void drawCurrent(Current current, double top) {
        double x = 25, y = top + 7, w = 278;
        String name = display(current.id()).content.title();
        String title = current.manual() ? tr(Contributor, current.contributorName().isEmpty() ? tr(Nobody) : current.contributorName()) : name + " Lv." + current.targetLevel();
        String secondary = current.manual() ? current.contributor() == null ? tr(Paused) : name + " Lv." + current.targetLevel() : tr(Stations, current.workingStations());
        double secondaryWidth = Math.min(130, textWidth(secondary, 11));
        label(title, x, y + 3, w - secondaryWidth - 8, 14, TEXT);
        aligned(secondary, x + w, y + 4, secondaryWidth, 11, SOFT);
        fill(x - 2, y + 25, w + 4, 1, LINE);
        y += 31;
        double fraction = Math.clamp((double) current.elapsed() / current.duration(), 0, 1);
        keyValue(tr(current.manual() ? ResearchTime : Workload, GuiNumbers.grouped(current.elapsed()), GuiNumbers.grouped(current.duration())), percentage(fraction), x, y, w);
        bar(x, y + 18, w, 6, fraction, fraction >= 1 ? GOOD : DARK);
        y += 28;
        if (!current.materials().isEmpty()) {
            if (!current.manual()) {
                section(tr(Materials), tr(MaterialHint), x, y, w);
                y += 20;
            }
            for (int i = 0; i < current.materials().size(); i++) {
                var material = current.materials().get(i);
                long left = current.manual() ? material.total() - material.remaining() : material.available();
                long right = current.manual() ? material.total() : material.remaining();
                box(x, y + 3, 24, 24, SLOT, LIGHT);
                keyIcon(material.key(), x, y + 3, 24);
                String amounts = amount(material.key(), left) + " / " + amount(material.key(), right);
                double amountWidth = Math.min(w - 75, textWidth(amounts, 14));
                double nameWidth = Math.min(w - 130, w - 44 - amountWidth);
                label(material.key().getDisplayName().getFormattedText(), x + 36, y + 8, nameWidth, 12, SOFT);
                aligned(amounts, x + w, y + 6, amountWidth, 14, current.manual() ? material.available() < material.remaining() ? BAD : TEXT : left < right ? BAD : TEXT);
                truncatedTooltip(material.key().getDisplayName().getFormattedText(), x + 36, y + 8, nameWidth, 12);
                y += 34;
            }
        } else if (!current.manual()) {
            label(tr(NoMaterials), x, y, w, 12, SOFT);
            y += 20;
        }
        if (!current.resources().isEmpty()) {
            section(tr(NetworkResources), tr(NetworkHint), x, y, w);
            y += 20;
            for (int i = 0; i < current.resources().size(); i++) {
                var resource = current.resources().get(i);
                double progress = resource.accumulated() / resource.total();
                boolean completed = resource.accumulated() >= resource.total();
                keyValue(resource.type().displayName(), completed ? tr(Done) : resource.type().format(resource.occupied()) + " / " + resource.type().format(resource.capacity()), x, y + 3, w, 12, completed ? GOOD : TEXT);
                bar(x, y + 22, w, 3.4, progress, progress >= 1 ? GOOD : DARK);
                keyValue(resource.type().format(resource.accumulated()) + " / " + resource.type().format(resource.total()), percentage(progress), x, y + 29, w, 11, progress >= 1 ? GOOD : SOFT);
                if (i + 1 < current.resources().size()) fill(x, y + 53.4, w, 1, LINE);
                y += 58.4;
            }
        }
        if (current.manual()) {
            boolean mine = current.contributor() != null && current.contributor().equals(container.view.playerId());
            double rowY = y + 4;
            fill(x, rowY, w, 28, mine ? LINE : MUTED);
            box(x + 8, rowY + 6, 16, 16, mine ? LINE : MUTED, LIGHT, 2);
            if (mine) {
                line(x + 12, rowY + 13, x + 16, rowY + 18, LIGHT);
                line(x + 16, rowY + 18, x + 22, rowY + 8, LIGHT);
            }
            label(tr(ConsumeInventory), x + 32, rowY + 4, w - 40, 13, LIGHT);
            hit(x, rowY, w, 28, container.view.canManage(), () -> container.manual(current.entryId(), !mine), List.of(tr(mine ? PauseHint : ContributeHint)));
        }
    }

    private void drawBanner() {
        String banner = container.editorMode() ? tr(Editor) : container.databaseMode() ? tr(Picker, container.view.databaseName()) : tr(Overview, container.view.teamName());
        double w = textWidth(banner, 13) + 80, left = (screenW - w) / 2;
        polygon(new double[]{left, 0, left + w, 0, left + w - 14, 30, left + 14, 30}, container.editorMode() ? 0xddd2b6 : BASE);
        clip.draw((float) left, 0, (float) w, 30, () -> {
            for (double x = left - 30; x < left + w; x += 20)
                polygon(new double[]{x, 0, x + 10, 0, x + 40, 30, x + 30, 30}, container.editorMode() ? 0xd4c6a3 : HOVER);
        });
        polygon(new double[]{left + 18, 10, left + 25, 15, left + 18, 20}, container.editorMode() ? WAIT : DARK);
        centeredLabel(banner, left + 35, 0, w - 70, 30, 13, container.editorMode() ? WAIT : TEXT);
    }

    private void drawTopRight() {
        double right = screenW - (selected == null ? 16 : 356), y = 8;
        if (container.databaseMode()) {
            square(right - 30, y, Icon.BACK, container::returnToDatabase, List.of(tr(Back)));
            return;
        }
        if (container.editorMode() && page != null) {
            String label = tr(page.layout.manual() ? ManualLayout : Automatic);
            double buttonWidth = textWidth(tr(Export), 12) + 28, labelWidth = textWidth(label, 12) + 16;
            box(right - buttonWidth - 6 - labelWidth, y + 2, labelWidth, 20, 0xb9bccb, LINE);
            centeredLabel(label, right - buttonWidth - 6 - labelWidth, y + 2, labelWidth, 20, 12, TEXT);
            action(right - buttonWidth, y, buttonWidth, tr(Export), true, this::export, List.of("config/prototype_eng/research_content/data/" + page.id + ".json"));
            y += 30;
        }
        double first = Math.max(textWidth(tr(Center) + ":", 12), textWidth(tr(HighestStation) + ":", 12));
        double second = Math.max(textWidth("Lv" + container.view.centerLevel(), 12), textWidth("Lv" + container.view.stationLevel(), 12));
        for (int i = 0; i < capacities.size(); i++) {
            var row = capacities.get(i);
            first = Math.max(first, textWidth(row.label, 12));
            second = Math.max(second, textWidth(row.value, 12));
        }
        if (capacities.isEmpty()) {
            first = Math.max(first, textWidth(tr(None), 12));
            second = Math.max(second, textWidth("0 / 0", 12));
        }
        double w = Math.max(first + 12 + second, textWidth(tr(Databases) + ":", 12)) + 20;
        int databaseRows = Math.max(1, capacities.size());
        frame(right - w, y, w, (databaseRows + 3) * 18 + 14);
        double x = right - w + 10;
        label(tr(Databases) + ":", x, y + 7, w - 20, 12, TEXT);
        for (int i = 0; i < capacities.size(); i++) {
            var row = capacities.get(i);
            label(row.label, x, y + 25 + i * 18, first, 12, SOFT);
            aligned(row.value, right - 10, y + 25 + i * 18, second, 12, SOFT);
        }
        if (capacities.isEmpty()) {
            label(tr(None), x, y + 25, first, 12, SOFT);
            aligned("0 / 0", right - 10, y + 25, second, 12, SOFT);
        }
        label(tr(Center) + ":", x, y + 25 + databaseRows * 18, first, 12, SOFT);
        aligned("Lv" + container.view.centerLevel(), right - 10, y + 25 + databaseRows * 18, second, 12, SOFT);
        label(tr(HighestStation) + ":", x, y + 43 + databaseRows * 18, first, 12, SOFT);
        aligned("Lv" + container.view.stationLevel(), right - 10, y + 43 + databaseRows * 18, second, 12, SOFT);
    }

    private void drawEscape() {
        String text = tr(forgetting != null ? Cancel : selected != null ? Collapse : container.databaseMode() ? Back : Close);
        double right = screenW - (selected == null ? 16 : 356), w = textWidth(text, 12);
        label(text, right - w, screenH - 26, w, 12, DARK);
        box(right - w - 8 - 29, screenH - 26, 29, 16, SLOT, LINE);
        centeredLabel("ESC", right - w - 37, screenH - 26, 29, 16, 11, TEXT);
    }

    // Detail content is rebuilt only when its research projection, reward choice or confirmation changes.
    private void rebuildDetail(@Nullable Entry entry) {
        detail.clear();
        footerInfo.clear();
        footerLines.clear();
        footerInfoHeight = 0;
        detailHeight = 0;
        if (entry == null) return;
        if (forgetting != null) {
            forgetPreview(entry);
            for (int i = 0; i < footerInfo.size(); i++) {
                var lines = fontRenderer.listFormattedStringToWidth(footerInfo.get(i), (int) (318 * 9 / 11D));
                int color = i == 0 || i + 1 == footerInfo.size() ? SOFT : BAD;
                for (int j = 0; j < lines.size(); j++) {
                    int height = 16 + (j + 1 == lines.size() && i + 1 < footerInfo.size() ? 2 : 0);
                    footerLines.add(new FooterLine(lines.get(j), color, height));
                    footerInfoHeight += height;
                }
            }
        }
        var content = display(entry.id()).content;
        if (!content.summary().isEmpty()) addParagraph(content.summary(), 13, 19, TEXT);
        for (int i = 0; i < content.description().size(); i++)
            paragraph(content.description().get(i), 12, 18, SOFT, i + 1 == content.description().size() ? 6 : 2);
        if (container.databaseMode()) {
            heading(tr(DownloadInfo), "");
            info(tr(TeamLevel), tr(ResearchTierLevel, entry.teamLevel(), entry.stage(entry.teamLevel()).tier()));
            info(tr(ThisDatabaseTier), Integer.toString(container.view.databaseLevel()));
            info(tr(Delivery), deliveryText(entry));
            if (entry.processableLevel() < entry.teamLevel())
                addParagraph(tr(PickerLevelWarning, entry.processableLevel() + 1), 11, 16, SOFT);
            finishDetail();
            return;
        }
        if (showsProgress(entry))
            detail.add(new DetailRow(Kind.PROGRESS, tr(DetailProgress, target(entry)), percentage(fraction(entry)), null, fraction(entry), 24, 0));
        heading(tr(ResearchInfo), "");
        int target = target(entry);
        var stage = entry.stage(target);
        if (!entry.specialOnly())
            info(tr(TeamLevel), entry.teamLevel() == 0 ? tr(Incomplete) : tr(ResearchTierLevel, entry.teamLevel(), entry.stage(entry.teamLevel()).tier()));
        if (entry.teamLevel() < entry.maxLevel() && !entry.specialOnly())
            info(tr(TargetLevel), tr(stage.tier() == 0 ? ManualTierLevel : ResearchTierLevel, target, stage.tier()));
        if (entry.specialOnly()) info(tr(Tier), Integer.toString(stage.tier()));
        if (!entry.specialOnly() && entry.deliveryPolicy() != ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            info(tr(PersonalLevel), "Lv." + entry.personalLevel());
        info(tr(Duration), GuiNumbers.grouped(stage.duration()) + " t");
        info(tr(Delivery), deliveryText(entry));
        if (container.editorMode()) {
            info(tr(ResearchId), entry.id().toString());
            var pos = position(entry.id());
            info(tr(Coordinates), "(" + pos.x() + ", " + pos.y() + ")" + (page.layout.manual() ? "" : tr(AutomaticCoordinates)));
        }
        if (entry.teamLevel() < entry.maxLevel() && !entry.specialOnly()) {
            heading(tr(StartConditions), "Lv." + target);
            if (stage.tier() == 0) addParagraph(tr(ManualResources), 12, 20, SOFT);
            else {
                required(tr(Center), container.view.centerLevel() > 0 ? "Lv." + container.view.centerLevel() : tr(None), container.view.centerLevel() < 1);
                required(tr(Station) + " ≥ " + stage.tier(), container.view.stationLevel() == 0 ? tr(None) : "Lv." + container.view.stationLevel(), container.view.stationLevel() < stage.tier());
            }
        }
        heading(tr(Prerequisites), "");
        if (stage.prerequisites().isEmpty()) addParagraph(tr(None), 12, 20, SOFT);
        for (int i = 0; i < stage.prerequisites().size(); i++) requiredResearch(stage.prerequisites().get(i), false);
        if (!stage.mutex().isEmpty()) {
            heading(tr(Mutex), "");
            for (int i = 0; i < stage.mutex().size(); i++) requiredResearch(stage.mutex().get(i), true);
        }
        if (entry.teamLevel() < entry.maxLevel() || entry.specialOnly()) {
            heading(tr(Materials), "Lv." + target);
            if (stage.materials().isEmpty()) addParagraph(tr(NoMaterials), 12, 20, SOFT);
            else {
                var stacks = new ObjectArrayList<GenericStack>();
                for (int i = 0; i < stage.materials().size(); i++) {
                    var material = stage.materials().get(i);
                    stacks.add(new GenericStack(material.key(), material.total()));
                }
                addSlots(stacks);
            }
            heading(tr(NetworkResources), "Lv." + target);
            if (stage.resources().isEmpty())
                addParagraph(tr(stage.tier() == 0 ? ManualNetwork : NoResources), 12, 20, SOFT);
            for (int i = 0; i < stage.resources().size(); i++) {
                var resource = stage.resources().get(i);
                double total = resource.totalAt(stage.firstLevel(), target);
                detail.add(new DetailRow(Kind.RESOURCE, resource.type().displayName(), resource.type().format(total), null, 0, 19, 0));
            }
        }
        heading(tr(Rewards), "");
        addRewards(entry);
        finishDetail();
    }

    private String deliveryText(Entry entry) {
        return tr(entry.specialOnly() ? DiskOnly : entry.deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY ? ItemOnly : DatabaseAndItem);
    }

    private void addParagraph(String text, int size, int lineHeight, int color) {
        finishDetailBlock();
        paragraph(text, size, lineHeight, color, 6);
    }

    private void paragraph(String text, int size, int lineHeight, int color, int gap) {
        var lines = fontRenderer.listFormattedStringToWidth(text, (int) (DETAIL_CONTENT_WIDTH * 9D / size));
        for (int i = 0; i < lines.size(); i++)
            detail.add(new DetailRow(Kind.TEXT, lines.get(i), "", null, size, lineHeight, color));
        detail.add(new DetailRow(Kind.GAP, "", "", null, 0, gap, 0));
    }

    private void finishDetailBlock() {
        if (!detail.isEmpty() && detail.getLast().kind != Kind.GAP)
            detail.add(new DetailRow(Kind.GAP, "", "", null, 0, 6, 0));
    }

    private void heading(String label, String hint) {
        finishDetailBlock();
        detail.add(new DetailRow(Kind.HEADING, label, hint, null, 0, 27, 0));
        finishDetailBlock();
    }

    private void info(String label, String value) {
        detail.add(new DetailRow(Kind.INFO, label, value, null, 0, 19, 0));
    }

    private void finishDetail() {
        if (!detail.isEmpty() && detail.getLast().kind == Kind.GAP) detail.removeLast();
        for (int first = 0; first < detail.size(); first++) {
            var kind = detail.get(first).kind;
            if (kind != Kind.INFO && kind != Kind.RESOURCE) continue;
            int end = first;
            double labelWidth = 0;
            while (end < detail.size() && detail.get(end).kind == kind) {
                labelWidth = Math.max(labelWidth, textWidth(kind == Kind.INFO ? detail.get(end).label : detail.get(end).value, 12));
                end++;
            }
            for (int index = first; index < end; index++) {
                var row = detail.get(index);
                detail.set(index, new DetailRow(row.kind, row.label, row.value, row.items, row.fraction, row.height, row.color, labelWidth, null));
            }
            first = end - 1;
        }
    }

    private void required(String label, String value, boolean missing) {
        detail.add(new DetailRow(Kind.REQUIRED, label, value, null, missing ? 1 : 0, 20, 0));
    }

    private void requiredResearch(Required required, boolean mutex) {
        var other = container.view.entry(required.id());
        boolean met = other != null && other.teamLevel() >= required.level();
        String name = display(required.id()).content.title() + " Lv." + required.level();
        if (!required.id().getNamespace().equals(page.id))
            name += " (" + pagesById.get(required.id().getNamespace()).name + ")";
        required(name, tr(met ? Done : Incomplete), mutex == met);
    }

    private void addSlots(List<GenericStack> stacks) {
        int perRow = (DETAIL_CONTENT_WIDTH + 4) / 40;
        for (int first = 0; first < stacks.size(); first += perRow) {
            int end = Math.min(stacks.size(), first + perRow);
            detail.add(new DetailRow(Kind.SLOTS, "", "", stacks.subList(first, end), 0, end == stacks.size() ? 36 : 40, 0));
        }
    }

    private void addRewards(Entry entry) {
        var levels = new IntOpenHashSet();
        for (int i = 0; i < entry.claims().size(); i++) levels.add(entry.claims().get(i).level());
        boolean hasRewards = false;
        for (int stageIndex = 0; stageIndex < entry.stages().size(); stageIndex++) {
            var rewards = entry.stages().get(stageIndex).rewards();
            for (int rewardIndex = 0; rewardIndex < rewards.size(); rewardIndex++) {
                int level = rewards.get(rewardIndex).level();
                hasRewards |= !rewards.get(rewardIndex).groups().isEmpty();
                if (level <= entry.teamLevel() || !entry.specialOnly() && entry.teamLevel() < entry.maxLevel() && level == target(entry))
                    levels.add(level);
            }
        }
        var sorted = levels.toIntArray();
        Arrays.sort(sorted);
        boolean any = false;
        for (int level : sorted) {
            var groups = rewardGroups(entry, level);
            if (groups.isEmpty()) continue;
            any = true;
            var claim = claim(entry, level);
            boolean choiceAllowed = groups.size() > 1 && claim != null && claim.status() == RewardClaim.Status.UNCLAIMED;
            boolean claimable = claim != null && claim.status() != RewardClaim.Status.CLAIMED;
            String choiceKey = entry.id() + "/" + level;
            var selectedGroup = groups.size() == 1 ? groups.getFirst().id() : choices.get(choiceKey);
            if (claim != null && claim.selectedGroupId() != null) selectedGroup = claim.selectedGroupId();
            var cards = new ObjectArrayList<RewardCardGroup>();
            double height = 16 + 20;
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                var description = display(entry.id()).content.reward(group.id());
                var titleLines = fontRenderer.listFormattedStringToWidth(description.title(), (int) ((DETAIL_CONTENT_WIDTH - 28) * 9D / 12));
                var descriptionLines = description.description().isEmpty() ? List.<String>of()
                    : fontRenderer.listFormattedStringToWidth(description.description(), (int) ((DETAIL_CONTENT_WIDTH - 28) * 9D / 11));
                int slotRows = (group.items().size() + 6) / 7;
                int slotsHeight = slotRows == 0 ? 0 : slotRows * 40 - 4;
                double groupHeight = 10 + titleLines.size() * 20 + 4 + slotsHeight + (descriptionLines.isEmpty() ? 0 : 4 + descriptionLines.size() * 15);
                cards.add(new RewardCardGroup(group.id(), titleLines, descriptionLines, group.items(), groupHeight, choiceAllowed && group.id().equals(selectedGroup)));
                height += 5 + groupHeight;
            }
            if (claimable) height += 5 + 24;
            var card = new RewardCard(level, claim != null && claim.status() == RewardClaim.Status.CLAIMED ? Claimed : claimable ? Claimable : ClaimLater,
                cards, choiceKey, selectedGroup, choiceAllowed, claimable, height);
            detail.add(new DetailRow(Kind.REWARD, "", "", null, 0, height, 0, 0, card));
            finishDetailBlock();
        }
        if (!any) addParagraph(tr(hasRewards ? NoNextReward : NoRewards), 12, 20, SOFT);
    }

    private List<ResearchOverviewPacket.RewardGroup> rewardGroups(Entry entry, int level) {
        var rewards = entry.stage(level).rewards();
        for (int i = 0; i < rewards.size(); i++) if (rewards.get(i).level() == level) return rewards.get(i).groups();
        return List.of();
    }

    private @Nullable Claim claim(Entry entry, int level) {
        var claims = entry.claims();
        for (int i = 0; i < claims.size(); i++) if (claims.get(i).level() == level) return claims.get(i);
        return null;
    }

    private void drawDetails() {
        if (knownSelected == null) return;
        double left = screenW - 340;
        frame(left, 0, 340, screenH);
        var entry = knownSelected;
        overviewSlot(left + 11, 11, 44);
        icon(entry.id(), left + 17, 17, 32);
        truncatedTooltip(display(entry.id()).content.title(), left + 65, 11, 224, 16);
        square(left + 299, 11, Icon.CLEAR, this::collapse, List.of(tr(Collapse)));
        var status = status(entry);
        String statusLabel = container.databaseMode() ? tr(Selectable) : statusText(entry);
        Tone chipTone = container.databaseMode() ? Tone.GOOD : status.tone;
        double chipWidth = Math.min(224, chipWidth(statusLabel, false));
        String levelLabel = container.databaseMode() ? tr(CompletedLevel, entry.teamLevel())
            : entry.maxLevel() > 1 ? "Lv." + entry.teamLevel() + " / " + maxText(entry) : "";
        double levelWidth = levelLabel.isEmpty() ? 0 : Math.min(224, chipWidth(levelLabel, true));
        boolean wrapLevel = levelWidth > 0 && chipWidth + 6 + levelWidth > 224;
        label(display(entry.id()).content.title(), left + 65, 11 + ((wrapLevel ? 20 : 21) - 16) / 2D, 224, 16, TEXT);
        double chipY = wrapLevel ? 33 : 34.5;
        chip(statusLabel, left + 65, chipY, chipWidth, chipTone, false);
        if (levelWidth > 0)
            chip(levelLabel, left + 65 + (wrapLevel ? 0 : chipWidth + 6), chipY + (wrapLevel ? 26 : 0), levelWidth, null, true);
        double headerBottom = wrapLevel ? 87 : 63;
        fill(left + 11, headerBottom, 318, 1, LINE);
        bodyTop = headerBottom + 9;
        computeFooter();
        bodyBottom = footerTop - 9;
        detailHeight = 0;
        for (int i = 0; i < detail.size(); i++) detailHeight += detail.get(i).height;
        detailScroll = Math.clamp(detailScroll, 0, Math.max(0, detailHeight - (bodyBottom - bodyTop)));
        clip.draw((float) (left + 11), (float) bodyTop, DETAIL_CONTENT_WIDTH, (float) Math.max(0, bodyBottom - bodyTop), () -> {
            double y = bodyTop - detailScroll;
            for (int i = 0; i < detail.size(); i++) {
                var row = detail.get(i);
                if (y + row.height > bodyTop && y < bodyBottom) drawDetailRow(entry, row, left + 11, y);
                y += row.height;
            }
        });
        if (detailHeight > bodyBottom - bodyTop) {
            double scrollHeight = bodyBottom - bodyTop, thumb = Math.max(18, scrollHeight * scrollHeight / detailHeight);
            fill(left + 322, bodyTop, 4, scrollHeight, BASE);
            fill(left + 322, bodyTop + (scrollHeight - thumb) * detailScroll / Math.max(1, detailHeight - scrollHeight), 4, thumb, MUTED);
        }
        drawFooter(entry, left + 11, footerTop);
    }

    private void drawDetailRow(Entry entry, DetailRow row, double x, double y) {
        double w = DETAIL_CONTENT_WIDTH;
        switch (row.kind) {
            case GAP -> {
            }
            case TEXT -> label(row.label, x, y, w, row.fraction > 0 ? (int) row.fraction : 12, row.color);
            case INFO -> {
                double labelWidth = Math.min(w - 12, row.labelWidth);
                label(row.label, x, y, labelWidth, 12, SOFT);
                aligned(row.value, x + w, y, w - labelWidth - 12, 12, TEXT);
            }
            case RESOURCE -> {
                double valueWidth = Math.min(w - 10, row.labelWidth);
                label(row.label, x, y, w - valueWidth - 10, 12, TEXT);
                aligned(row.value, x + w, y, valueWidth, 12, TEXT);
            }
            case HEADING -> {
                double hintWidth = textWidth(row.value, 11);
                label(row.label, x, y + 4, w - hintWidth - 8, 13, TEXT);
                aligned(row.value, x + w, y + 6, hintWidth, 11, SOFT);
                fill(x, y + 26, w, 1, LINE);
            }
            case REQUIRED -> {
                fill(x, y + 7, 6, 6, row.fraction > 0 ? BAD : GOOD);
                double valueWidth = textWidth(row.value, 12);
                label(row.label, x + 16, y, w - 22 - valueWidth, 12, TEXT);
                aligned(row.value, x + w, y, Math.min(valueWidth, w - 22), 12, row.fraction > 0 ? BAD : SOFT);
                truncatedTooltip(row.label, x + 16, y, w - 22 - valueWidth, 12);
            }
            case PROGRESS -> {
                keyValue(row.label, row.value, x, y, w);
                bar(x, y + 18, w, 6, row.fraction, DARK);
            }
            case SLOTS -> drawSlots(row.items, x, y, w);
            case REWARD -> drawRewardCard(entry, row.reward, x, y, w);
        }
    }

    private double chipWidth(String text, boolean plain) {
        return textWidth(text, 12) + 16 + (plain ? 0 : 11);
    }

    private void chip(String text, double x, double y, double width, @Nullable Tone tone, boolean plain) {
        int background = tone == null ? 0xb9bccb : tone.background;
        int border = tone == null ? LINE : tone.border;
        int color = tone == null ? TEXT : tone.text;
        box(x, y, width, 20, background, border);
        if (!plain) fill(x + 8, y + 7, 6, 6, tone == null ? MUTED : tone.text);
        label(text, x + 8 + (plain ? 0 : 11), y + 3, width - 16 - (plain ? 0 : 11), 12, color);
    }

    private void drawRewardCard(Entry entry, RewardCard card, double x, double y, double width) {
        box(x, y, width, card.height, PANEL, LIGHT, 2);
        double innerX = x + 8, innerY = y + 8, innerWidth = width - 16;
        String stateLabel = tr(card.state);
        double stateWidth = chipWidth(stateLabel, false);
        String levelLabel = "Lv." + card.level + (card.groups.size() > 1 ? " · " + tr(ChooseOne) : "");
        label(levelLabel, innerX, innerY + 3, innerWidth - stateWidth - 8, 12, TEXT);
        chip(stateLabel, innerX + innerWidth - stateWidth, innerY, stateWidth,
            card.state == Claimed ? Tone.GOOD : card.state == Claimable ? Tone.WAIT : null, false);
        innerY += 25;
        for (int index = 0; index < card.groups.size(); index++) {
            var group = card.groups.get(index);
            boolean hovered = card.choiceAllowed && inside(innerX, innerY, innerWidth, group.height);
            box(innerX, innerY, innerWidth, group.height, group.chosen ? LIGHT : hovered ? HOVER : CARD, group.chosen ? LINE : CARD_BORDER);
            if (card.choiceAllowed)
                detailHit(innerX, innerY, innerWidth, group.height, true, () -> {
                    choices.put(card.choiceKey, group.id);
                    detailDirty = true;
                }, group.title);
            double contentY = innerY + 5;
            for (int line = 0; line < group.title.size(); line++)
                label(group.title.get(line), innerX + 6, contentY + line * 20, innerWidth - 12, 12, TEXT);
            contentY += group.title.size() * 20 + 4;
            drawSlots(group.items, innerX + 6, contentY, innerWidth - 12);
            int rows = (group.items.size() + 6) / 7;
            contentY += rows == 0 ? 0 : rows * 40 - 4;
            if (!group.description.isEmpty()) {
                contentY += 4;
                for (int line = 0; line < group.description.size(); line++)
                    label(group.description.get(line), innerX + 6, contentY + line * 15, innerWidth - 12, 11, SOFT);
            }
            innerY += group.height + 5;
        }
        if (card.claimable) {
            boolean ready = card.selected != null;
            (ready && inside(innerX, innerY, innerWidth, 24) ? BUTTON_HOVER : BUTTON).draw(innerX, innerY, innerWidth, 24);
            centeredLabel(tr(ClaimReward), innerX + 4, innerY, innerWidth - 8, 24, 12,
                ready && inside(innerX, innerY, innerWidth, 24) ? 0x517497 : LIGHT);
            if (!ready) alphaFill(innerX, innerY, innerWidth, 24, BASE, 0.5F);
            detailHit(innerX, innerY, innerWidth, 24, ready, () -> container.reward(entry.id(), card.level, card.selected),
                List.of(ready ? tr(ClaimLevelHint, card.level) : tr(ChooseRewardHint)));
        }
    }

    private void detailHit(double x, double y, double width, double height, boolean enabled, Runnable action, List<String> tooltip) {
        double top = Math.max(y, bodyTop), bottom = Math.min(y + height, bodyBottom);
        if (bottom > top) hit(x, top, width, bottom - top, enabled, action, tooltip);
    }

    private void drawSlots(List<GenericStack> stacks, double x, double y, double width) {
        int columns = Math.max(1, (int) ((width + 4) / 40));
        for (int i = 0; i < stacks.size(); i++) {
            var item = stacks.get(i);
            int slotRow = i / columns;
            double slotX = x + i % columns * 40, slotY = y + slotRow * 40;
            overviewSlot(slotX, slotY, 36);
            keyIcon(item.what(), slotX + 2, slotY + 2, 32);
            aligned(item.what().formatAmount(item.amount(), AmountFormat.SLOT), slotX + 35, slotY + 24, 35, 11, LIGHT);
            if (mouseY >= bodyTop && mouseY <= bodyBottom)
                tooltip(slotX, slotY, 36, 36, List.of(item.what().getDisplayName().getFormattedText(), amount(item.what(), item.amount())));
        }
    }

    private static void overviewSlot(double x, double y, double size) {
        fill(x, y, size, size, SLOT);
        fill(x, y, size, 2, LINE);
        fill(x, y, 2, size, LINE);
        fill(x, y + size - 2, size, 2, LIGHT);
        fill(x + size - 2, y, 2, size, LIGHT);
    }

    private void computeFooter() {
        int height;
        if (forgetting != null) {
            height = 24 + 5 + footerInfoHeight + 5 + 24 + 11;
        } else {
            int index = queueIndex(knownSelected.id());
            boolean forget = !knownSelected.specialOnly() && knownSelected.deliveryPolicy() != ResearchDownloadDeliveryPolicy.ITEM_ONLY && !container.editorMode();
            int rows = container.databaseMode() || container.editorMode() ? 1 : index > 0 || forget ? 2 : 1;
            if (index > 0 && forget) rows = 3;
            height = rows * 24 + (rows - 1) * 6 + 16 + 5 + 11;
        }
        footerTop = screenH - 7 - height;
    }

    private void drawFooter(Entry entry, double x, double y) {
        double w = 318;
        fill(x, y, w, 1, LINE);
        y += 9;
        double half = (w - 6) / 2;
        if (forgetting != null) {
            int k = forgetLevels.getInt(entry.id());
            label(tr(ForgetLabel), x, y + 4, 40, 12, TEXT);
            label(tr(ForgetFrom, k), x + 45, y + 4, w - 105, 12, TEXT);
            squareText(x + w - 54, y, "−", k > 1, () -> {
                forgetLevels.put(entry.id(), k - 1);
                detailDirty = true;
            });
            squareText(x + w - 24, y, "+", k < entry.personalLevel(), () -> {
                forgetLevels.put(entry.id(), k + 1);
                detailDirty = true;
            });
            y += 29;
            for (int i = 0; i < footerLines.size(); i++) {
                var line = footerLines.get(i);
                label(line.text, x, y, w, 11, line.color);
                y += line.height;
            }
            y += 5;
            action(x, y, half, tr(ConfirmForget), entry.canForget(), () -> {
                container.forget(entry.id(), k, entry.personalLevel());
                forgetting = null;
                detailDirty = true;
            }, List.of(tr(ConfirmForget)));
            action(x + half + 6, y, half, tr(Cancel), true, () -> {
                forgetting = null;
                detailDirty = true;
            }, List.of(tr(Cancel)));
            return;
        }
        if (container.databaseMode()) {
            action(x, y, w, tr(Pick), entry.selectable(), () -> container.choose(entry.id()), List.of(pickerReason(entry)));
            label(tr(PickerFooter), x, y + 29, w, 12, SOFT);
            return;
        }
        int index = queueIndex(entry.id());
        if (container.editorMode()) {
            var database = activeDatabase();
            boolean available = entry.specialOnly() ? database != null && database.level(entry.id()) < entry.maxLevel()
                && (database.level(entry.id()) > 0 || database.remaining() > 0) : entry.teamLevel() < entry.maxLevel();
            String reason = entry.specialOnly() ? database == null ? tr(NoDatabase) : database.level(entry.id()) >= entry.maxLevel() ? tr(MaxLevel)
                : database.level(entry.id()) == 0 && database.remaining() == 0 ? tr(CapacityFull) : tr(EditorSpecialHint)
                : entry.teamLevel() >= entry.maxLevel() ? tr(MaxLevel) : tr(EditorHint);
            action(x, y, half, tr(entry.specialOnly() ? WriteDatabase : Start), available, () -> container.complete(entry.id(), target(entry)), List.of(reason));
            action(x + half + 6, y, half, tr(Download), entry.canDownload(), () -> container.download(entry.id(), entry.teamLevel()), List.of(downloadReason(entry)));
            label(tr(entry.specialOnly() ? EditorSpecialHint : EditorHint), x, y + 29, w, 12, WAIT);
            return;
        }
        if (index > 0) {
            long entryId = container.view.queue().get(index).entryId();
            action(x, y, half, tr(Current), container.view.canManage(), () -> container.manage(entryId, ResearchStationView.Action.CURRENT), List.of(tr(CurrentHint)));
            action(x + half + 6, y, half, tr(Remove), container.view.canManage(), () -> container.manage(entryId, ResearchStationView.Action.REMOVE), List.of(tr(RemoveHint)));
            y += 30;
            action(x, y, w, tr(Download), entry.canDownload(), () -> container.download(entry.id(), entry.teamLevel()), List.of(downloadReason(entry)));
        } else {
            if (index == 0) {
                long entryId = container.view.queue().getFirst().entryId();
                action(x, y, half, tr(Remove), container.view.canManage(), () -> container.manage(entryId, ResearchStationView.Action.REMOVE), List.of(tr(RemoveHint)));
            } else
                action(x, y, half, tr(fraction(entry) > 0 ? Continue : Start), entry.canStart(), () -> container.start(entry.id(), target(entry)), startReasons(entry));
            action(x + half + 6, y, half, tr(Download), entry.canDownload(), () -> container.download(entry.id(), entry.teamLevel()), List.of(downloadReason(entry)));
        }
        boolean forget = !entry.specialOnly() && entry.deliveryPolicy() != ResearchDownloadDeliveryPolicy.ITEM_ONLY;
        y += forget ? 30 : 29;
        if (forget) {
            action(x, y, w, tr(Forget), entry.canForget(), () -> {
                forgetting = entry.id();
                forgetLevels.put(entry.id(), 1);
                detailDirty = true;
            }, List.of(forgetReason(entry)));
            y += 29;
        }
        String reason = downloadReason(entry);
        label(reason, x, y, w, 12, entry.downloadedLevel() >= entry.teamLevel() && entry.downloadedLevel() > 0 ? GOOD : entry.canDownload() ? SOFT : BAD);
        truncatedTooltip(reason, x, y, w, 12);
    }

    private void forgetPreview(Entry entry) {
        int k = forgetLevels.getInt(entry.id()), keep = Math.min(entry.personalLevel(), k - 1);
        var before = new Object2IntOpenHashMap<ResourceLocation>();
        var after = new Object2IntOpenHashMap<ResourceLocation>();
        for (int i = 0; i < knownEntries.size(); i++)
            before.put(knownEntries.get(i).id(), knownEntries.get(i).teamLevel());
        for (int i = 0; i < knownEntries.size(); i++) effectiveAfter(knownEntries.get(i), entry.id(), keep, after);
        footerInfo.add(tr(ForgetLevels, entry.personalLevel(), keep, entry.teamLevel(), after.getInt(entry.id())));
        for (int i = 0; i < knownEntries.size(); i++) {
            var other = knownEntries.get(i);
            if (!other.id().equals(entry.id()) && after.getInt(other.id()) < before.getInt(other.id()))
                footerInfo.add(tr(ForgetDependent, display(other.id()).content.title(), before.getInt(other.id()), after.getInt(other.id())));
        }
        var queue = container.view.queue();
        for (int i = 0; i < queue.size(); i++) {
            var queued = queue.get(i);
            if (queued.targetLevel() > (long) after.getInt(queued.id()) + 1) {
                footerInfo.add(tr(ForgetQueue, display(queued.id()).content.title(), queued.targetLevel()));
                continue;
            }
            var prerequisites = container.view.entry(queued.id()).stage(queued.targetLevel()).prerequisites();
            for (int j = 0; j < prerequisites.size(); j++)
                if (after.getInt(prerequisites.get(j).id()) < prerequisites.get(j).level()) {
                    footerInfo.add(tr(ForgetQueue, display(queued.id()).content.title(), queued.targetLevel()));
                    break;
                }
        }
        footerInfo.add(tr(ForgetKeep));
    }

    private int effectiveAfter(Entry entry, ResourceLocation forgotten, int keep, Object2IntOpenHashMap<ResourceLocation> levels) {
        if (levels.containsKey(entry.id())) return levels.getInt(entry.id());
        levels.put(entry.id(), 0);
        int raw = entry.specialOnly() ? 0 : entry.deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY ? entry.teamLevel()
            : Math.max(entry.otherMemberLevel(), entry.id().equals(forgotten) ? keep : entry.personalLevel());
        while (raw > 0) {
            boolean met = true;
            var requirements = entry.stage(raw).prerequisites();
            for (int i = 0; i < requirements.size(); i++) {
                var requirement = requirements.get(i);
                var other = container.view.entry(requirement.id());
                if (other == null || effectiveAfter(other, forgotten, keep, levels) < requirement.level()) {
                    met = false;
                    break;
                }
            }
            if (met) break;
            raw = entry.stage(raw).firstLevel() - 1;
        }
        levels.put(entry.id(), raw);
        return raw;
    }

    private List<String> deviceReasons(Entry entry) {
        var display = derived(entry);
        if (display.deviceReasons != null) return display.deviceReasons;
        int tier = entry.stage(target(entry)).tier();
        if (entry.specialOnly() || entry.teamLevel() >= entry.maxLevel() || tier == 0) {
            display.deviceReasons = List.of();
            return display.deviceReasons;
        }
        var reasons = new ObjectArrayList<String>(3);
        if (container.view.centerLevel() == 0) reasons.add(tr(NoCenter));
        var db = activeDatabase();
        if (db == null) reasons.add(tr(NoDatabase));
        else if (db.tier() < tier) reasons.add(tr(DatabaseLow, tier, db.tier()));
        if (container.view.stationLevel() < tier) reasons.add(tr(StationLow, tier, container.view.stationLevel()));
        display.deviceReasons = reasons.isEmpty() ? List.of() : reasons;
        return display.deviceReasons;
    }

    private List<String> startReasons(Entry entry) {
        var display = derived(entry);
        if (display.startReasons != null) return display.startReasons;
        var result = new ObjectArrayList<String>();
        if (entry.specialOnly()) result.add(tr(SpecialStart));
        if (entry.teamLevel() >= entry.maxLevel()) result.add(tr(MaxLevel));
        if (missing(entry)) appendRequirements(result, entry.stage(target(entry)).prerequisites(), true);
        if (blocked(entry)) appendRequirements(result, entry.stage(target(entry)).mutex(), false);
        result.addAll(deviceReasons(entry));
        if (!container.view.canManage()) result.add(tr(Denied));
        if (container.view.savePending()) result.add(tr(SavePending));
        if (container.view.queue().size() >= 5) result.add(tr(QueueFull));
        if (!entry.canStart() && result.isEmpty()) result.add(tr(Denied));
        if (result.isEmpty()) result.add(tr(Start));
        display.startReasons = result;
        return result;
    }

    private void appendRequirements(List<String> result, List<Required> required, boolean prerequisite) {
        for (int i = 0; i < required.size(); i++) {
            var item = required.get(i);
            var entry = container.view.entry(item.id());
            boolean met = entry != null && entry.teamLevel() >= item.level();
            if (prerequisite != met)
                result.add(tr(prerequisite ? Prerequisites : Mutex) + ": " + display(item.id()).content.title() + " Lv." + item.level()
                    + (!item.id().getNamespace().equals(page.id) ? " (" + pagesById.get(item.id().getNamespace()).name + ")" : ""));
        }
    }

    private @Nullable Database activeDatabase() {
        var id = container.view.activeDatabase();
        var databases = container.view.databases();
        for (int i = 0; i < databases.size(); i++) if (databases.get(i).id().equals(id)) return databases.get(i);
        return null;
    }

    private String downloadReason(Entry entry) {
        var display = derived(entry);
        if (display.downloadReason != null) return display.downloadReason;
        String result;
        if (entry.specialOnly()) result = tr(SpecialDownload);
        else if (entry.deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY) result = tr(ItemDownload);
        else if (entry.teamLevel() == 0) result = tr(NotCompleted);
        else if (entry.downloadedLevel() >= entry.teamLevel())
            result = tr(Downloaded, entry.downloadedTo(), entry.downloadedLevel());
        else {
            var database = activeDatabase();
            int tier = entry.stage(entry.teamLevel()).tier();
            if (database == null) result = tr(NoDatabase);
            else if (tier > database.tier()) result = tr(DatabaseLow, tier, database.tier());
            else if (database.level(entry.id()) == 0 && database.remaining() <= 0) result = tr(CapacityFull);
            else {
                result = null;
                var mutex = entry.stage(entry.teamLevel()).mutex();
                for (int i = 0; i < mutex.size(); i++) {
                    var other = container.view.entry(mutex.get(i).id());
                    if (Math.max(database.level(mutex.get(i).id()), other == null ? 0 : other.teamLevel()) >= mutex.get(i).level()) {
                        result = tr(DownloadMutex, display(mutex.get(i).id()).content.title());
                        break;
                    }
                }
                if (result == null) result = container.view.savePending() ? tr(SavePending)
                    : !entry.canDownload() ? tr(Denied) : tr(DownloadReady, database.name(), entry.teamLevel());
            }
        }
        display.downloadReason = result;
        return result;
    }

    private String forgetReason(Entry entry) {
        var display = derived(entry);
        if (display.forgetReason == null) display.forgetReason = entry.personalLevel() == 0 ? tr(PersonalMissing)
            : entry.availability().forget() != ResearchPermissionPolicy.Decision.ALLOWED ? tr(Denied)
            : container.view.savePending() ? tr(SavePending) : tr(entry.canForget() ? ForgetKeep : Reserved);
        return display.forgetReason;
    }

    private String pickerReason(Entry entry) {
        var display = derived(entry);
        if (display.pickerReason == null) display.pickerReason = entry.specialOnly() ? tr(SpecialDownload)
            : entry.teamLevel() < 1 ? tr(NotCompleted)
            : !entry.selectable() ? tr(PickerHigh, entry.stage(1).tier(), container.view.databaseLevel())
            : entry.processableLevel() < entry.teamLevel() ? tr(PickerPartial, entry.processableLevel()) : tr(Pick);
        return display.pickerReason;
    }

    private boolean overChrome() {
        if (selected != null && mouseX >= screenW - 340) return true;
        if (mouseX >= 16 && mouseX <= 312 && mouseY >= leftTop && mouseY <= leftBottom) return true;
        return mouseY < leftTop;
    }

    private @Nullable ResourceLocation nodeAt(double x, double y) {
        if (page == null || overChrome()) return null;
        for (int i = page.ids.size() - 1; i >= 0; i--) {
            var id = page.ids.get(i);
            var entry = container.view.entry(id);
            boolean expanded = id.equals(selected);
            double nx = panX + nodeX(id) * zoom, ny = panY + (nodeY(id) - (expanded ? 4 : 0)) * zoom;
            double w = expanded ? EXP_W : showsProgress(entry) ? WIDE : NODE;
            if (x >= nx && y >= ny && x < nx + w * zoom && y < ny + (expanded ? EXP_H : NODE) * zoom) return id;
        }
        return null;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (button != 0) return;
        readMouseEvent();
        pressX = lastX = mouseX;
        pressY = lastY = mouseY;
        pressedAt = MinecraftTime();
        held = true;
        moved = false;
        pressedHit = null;
        pressedNode = null;
        canvasDrag = scrollbarDrag = false;
        if (selected != null && mouseX >= screenW - 18 && mouseX < screenW - 14 && mouseY >= bodyTop && mouseY <= bodyBottom
            && detailHeight > bodyBottom - bodyTop) {
            double viewHeight = bodyBottom - bodyTop;
            double thumb = Math.max(18, viewHeight * viewHeight / detailHeight);
            double top = bodyTop + (viewHeight - thumb) * detailScroll / Math.max(1, detailHeight - viewHeight);
            scrollbarGrab = mouseY >= top && mouseY <= top + thumb ? mouseY - top : thumb / 2;
            scrollbarDrag = true;
            scrollThumb(mouseY);
            return;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            var hit = hits.get(i);
            if (hit.contains(mouseX, mouseY)) {
                pressedHit = hit;
                return;
            }
        }
        if (selected != null && mouseX >= screenW - 340) return;
        canvasDrag = !overChrome();
        pressedNode = nodeAt(mouseX, mouseY);
        if (pressedNode != null) {
            nodeStartX = nodeX(pressedNode);
            nodeStartY = nodeY(pressedNode);
        }
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long timeSinceLastClick) {
        if (button != 0) return;
        readMouseEvent();
        updateDrag();
    }

    private void readMouseEvent() {
        mouseX = Mouse.getEventX() / pixelScale;
        mouseY = (mc.displayHeight - Mouse.getEventY() - 1) / pixelScale;
    }

    private void updateDrag() {
        if (!held || pressedHit != null) return;
        if (scrollbarDrag) {
            scrollThumb(mouseY);
            return;
        }
        if (Math.hypot(mouseX - pressX, mouseY - pressY) > 3) moved = true;
        if (dragging != null) {
            dragX = nodeStartX + (mouseX - pressX) / zoom;
            dragY = nodeStartY + (mouseY - pressY) / zoom;
        } else if (moved && canvasDrag) {
            panX += mouseX - lastX;
            panY += mouseY - lastY;
        }
        lastX = mouseX;
        lastY = mouseY;
    }

    @Override
    protected void mouseReleased(int x, int y, int state) {
        if (state != 0 || !held) return;
        readMouseEvent();
        updateDrag();
        held = false;
        if (scrollbarDrag) {
            scrollbarDrag = false;
            return;
        }
        if (pressedHit != null) {
            var hit = pressedHit;
            pressedHit = null;
            if (hit.enabled && hit.contains(mouseX, mouseY)) hit.action.run();
        } else if (dragging != null) {
            var id = dragging;
            dragging = null;
            if (!page.layout.move(id.getPath(), (int) Math.round(dragX / S), (int) Math.round(dragY / S)))
                showToast(tr(Overlap));
            else {
                if (!page.layout.manual())
                    page.layout = new ResearchPage(page.layout.order(), true, page.layout.positions());
                ClientResearchContents.retainPage(page.id, page.layout);
            }
            detailDirty = true;
        } else if (!moved && pressedNode != null) {
            var entry = container.view.entry(pressedNode);
            if (container.databaseMode() ? entry.selectable() : container.editorMode() || !missing(entry)) {
                if (pressedNode.equals(selected)) collapse();
                else {
                    selected = pressedNode;
                    forgetting = null;
                    detailScroll = 0;
                    detailDirty = true;
                    reveal(selected);
                }
            }
        } else if (!moved && !overChrome()) collapse();
        pressedNode = null;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;
        double x = Mouse.getEventX() / pixelScale;
        double y = (mc.displayHeight - Mouse.getEventY() - 1) / pixelScale;
        mouseX = x;
        mouseY = y;
        if (selected != null && x >= screenW - 340 && y >= bodyTop && y <= bodyBottom) {
            detailScroll = Math.clamp(detailScroll - Math.signum(delta) * 32, 0, Math.max(0, detailHeight - (bodyBottom - bodyTop)));
        } else if (!overChrome()) {
            double next = Math.clamp(zoom * (delta > 0 ? 1.15 : 1 / 1.15), 0.5, 2);
            double worldX = (x - panX) / zoom, worldY = (y - panY) / zoom;
            panX = x - worldX * next;
            panY = y - worldY * next;
            zoom = next;
        }
    }

    @Override
    protected void keyTyped(char character, int code) throws IOException {
        if (code == Keyboard.KEY_ESCAPE) {
            if (forgetting != null) {
                forgetting = null;
                detailDirty = true;
            } else if (selected != null) collapse();
            else if (container.databaseMode()) container.returnToDatabase();
            else mc.player.closeScreen();
            return;
        }
        super.keyTyped(character, code);
    }

    private void collapse() {
        selected = forgetting = null;
        knownSelected = null;
        detailScroll = 0;
        detailDirty = true;
    }

    private void scrollThumb(double pointerY) {
        double viewHeight = bodyBottom - bodyTop, thumb = Math.max(18, viewHeight * viewHeight / detailHeight);
        detailScroll = Math.clamp((pointerY - scrollbarGrab - bodyTop) / (viewHeight - thumb), 0, 1) * (detailHeight - viewHeight);
    }

    private void focus(ResourceLocation id) {
        page = pagesById.get(id.getNamespace());
        selected = id;
        forgetting = null;
        detailScroll = 0;
        detailDirty = true;
        var position = position(id);
        panX = LEFT + (screenW - LEFT - SIDE) / 2 - (position.x() * S + EXP_W / 2D) * zoom;
        panY = screenH / 2 - (position.y() * S + NODE / 2D) * zoom;
    }

    private void reveal(ResourceLocation id) {
        double x = nodeX(id), y = nodeY(id), right = screenW - SIDE;
        double start = panX + x * zoom, end = panX + (x + EXP_W) * zoom;
        if (end > right) panX -= end - right + 16;
        else if (start < LEFT) panX += LEFT - start + 16;
        if (panY + y * zoom < leftTop) panY = leftTop + 8 - y * zoom;
        if (panY + (y + EXP_H) * zoom > screenH - 34) panY = screenH - 42 - (y + EXP_H) * zoom;
    }

    private void export() {
        try {
            ClientResearchContents.exportPage(page.id, page.layout);
            showToast(tr(Exported));
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot export research page {}", page.id, failure);
            showToast(tr(ExportFailed));
        }
    }

    private void showToast(String text) {
        toast = text;
        toastUntil = MinecraftTime() + 2400;
    }

    private static String percentage(double fraction) {
        return Math.round(Math.clamp(fraction, 0, 1) * 100) + "%";
    }

    private static int brighter(int color) {
        return Math.min(255, Math.round(((color >> 16) & 255) * 1.07F)) << 16
            | Math.min(255, Math.round(((color >> 8) & 255) * 1.07F)) << 8
            | Math.min(255, Math.round((color & 255) * 1.07F));
    }

    private static String amount(AEKey key, long amount) {
        return key.formatAmount(amount, AmountFormat.FULL);
    }

    private void icon(ResourceLocation id, double x, double y, double size) {
        var icon = ClientResearchContents.icon(id);
        if (icon == null) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(size / 16, size / 16, 1);
        icon.draw(mc, 0, 0);
        GlStateManager.popMatrix();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
    }

    private void keyIcon(AEKey key, double x, double y, double size) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(size / 16, size / 16, 1);
        AEKeyRendering.drawInGui(mc, 0, 0, key);
        GlStateManager.popMatrix();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
    }

    private double textWidth(String text, int size) {
        return fontRenderer.getStringWidth(text) * size / 9D;
    }

    private void label(String text, double x, double y, double width, int size, int color) {
        if (width <= 0) return;
        double scale = size / 9D;
        int available = (int) Math.floor(width / scale + 1e-6);
        if (fontRenderer.getStringWidth(text) * scale > width + 1e-6)
            text = fontRenderer.trimStringToWidth(text, Math.max(0, available - fontRenderer.getStringWidth("..."))) + "...";
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y + 1, 0);
        GlStateManager.scale(scale, scale, 1);
        fontRenderer.drawString(text, 0, 0, color);
        GlStateManager.popMatrix();
    }

    private void aligned(String text, double right, double y, double width, int size, int color) {
        double actual = Math.min(width, textWidth(text, size));
        label(text, right - actual, y, actual, size, color);
    }

    private void centeredLabel(String text, double x, double y, double w, double h, int size, int color) {
        double actual = Math.min(w, textWidth(text, size));
        label(text, x + (w - actual) / 2, y + (h - size) / 2, actual, size, color);
    }

    private void keyValue(String key, String value, double x, double y, double width) {
        keyValue(key, value, x, y, width, 12, SOFT);
    }

    private void keyValue(String key, String value, double x, double y, double width, int size, int valueColor) {
        double valueWidth = Math.min(width * 0.58, textWidth(value, size));
        label(key, x, y, width - valueWidth - 12, size, SOFT);
        aligned(value, x + width, y, valueWidth, size, valueColor);
    }

    private void section(String key, String hint, double x, double y, double width) {
        double hintWidth = textWidth(hint, 11);
        label(key, x, y, width - hintWidth - 8, 12, TEXT);
        aligned(hint, x + width, y + 1, hintWidth, 11, SOFT);
    }

    private boolean inside(double x, double y, double w, double h) {
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }

    private void tooltip(double x, double y, double w, double h, List<String> text) {
        if (inside(x, y, w, h) && (selected == null || x < screenW - 340 || mouseY >= bodyTop && mouseY <= bodyBottom || y >= footerTop || y < bodyTop)) {
            hoverText.clear();
            hoverText.addAll(text);
        }
    }

    private void truncatedTooltip(String text, double x, double y, double w, int size) {
        if (textWidth(text, size) > w + 1e-6)
            tooltip(x, y, w, size + 3, List.of(text));
    }

    private void hit(double x, double y, double w, double h, boolean enabled, Runnable action, List<String> tooltip) {
        hits.add(new Hit(x, y, w, h, enabled, action));
        tooltip(x, y, w, h, tooltip);
    }

    private void action(double x, double y, double w, String text, boolean enabled, Runnable callback, List<String> tooltip) {
        (enabled && inside(x, y, w, 24) ? BUTTON_HOVER : BUTTON).draw(x, y, w, 24);
        centeredLabel(text, x + 4, y, w - 8, 24, 12, enabled && inside(x, y, w, 24) ? 0x517497 : LIGHT);
        if (!enabled) alphaFill(x, y, w, 24, BASE, 0.5F);
        hit(x, y, w, 24, enabled, callback, tooltip);
    }

    private void square(double x, double y, Icon icon, Runnable callback, List<String> tooltip) {
        int size = 30;
        boolean enabled = true;
        texture(inside(x, y, size, size) && enabled ? SQUARE_HOVER : SQUARE, x, y, size, size, 0, 0, 16, 16, 16, 16);
        texture(icon.texture(), x + 3, y + 3, 24, 24, 0, 0, icon.width, icon.height, icon.width, icon.height);
        if (!enabled) alphaFill(x, y, size, size, BASE, 0.5F);
        hit(x, y, size, size, enabled, callback, tooltip);
    }

    private void squareText(double x, double y, String text, boolean enabled, Runnable callback) {
        int size = 24;
        texture(inside(x, y, size, size) && enabled ? SQUARE_HOVER : SQUARE, x, y, size, size, 0, 0, 16, 16, 16, 16);
        centeredLabel(text, x, y, size, size, 16, TEXT);
        if (!enabled) alphaFill(x, y, size, size, BASE, 0.5F);
        hit(x, y, size, size, enabled, callback, List.of(text));
    }

    private void frame(double x, double y, double w, double h) {
        fill(x, y, w, h, BASE);
        FRAME.draw(x, y, w, h);
    }

    private static void box(double x, double y, double w, double h, int background, int border) {
        box(x, y, w, h, background, border, 1);
    }

    private static void box(double x, double y, double w, double h, int background, int border, double size) {
        fill(x, y, w, h, border);
        fill(x + size, y + size, w - size * 2, h - size * 2, background);
    }

    private static void bar(double x, double y, double w, double h, double fraction, int color) {
        box(x, y, w, h, SLOT, LINE);
        fill(x + 1, y + 1, (w - 2) * Math.clamp(fraction, 0, 1), h - 2, color);
    }

    private static void line(double x1, double y1, double x2, double y2, int color) {
        fill(Math.min(x1, x2) - 1, Math.min(y1, y2) - 1, Math.abs(x2 - x1) + 2, Math.abs(y2 - y1) + 2, color);
    }

    private static void fill(double x, double y, double w, double h, int color) {
        alphaFill(x, y, w, h, color, 1);
    }

    private static void alphaFill(double x, double y, double w, double h, int color, float alpha) {
        if (w <= 0 || h <= 0) return;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(buffer, x, y + h, color, alpha);
        vertex(buffer, x + w, y + h, color, alpha);
        vertex(buffer, x + w, y, color, alpha);
        vertex(buffer, x, y, color, alpha);
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    private static void vertex(BufferBuilder buffer, double x, double y, int color, float alpha) {
        buffer.pos(x, y, 0).color((color >> 16) & 255, (color >> 8) & 255, color & 255, (int) (alpha * 255)).endVertex();
    }

    private static void polygon(double[] points, int color) {
        GlStateManager.disableTexture2D();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_POLYGON, DefaultVertexFormats.POSITION_COLOR);
        for (int i = points.length - 2; i >= 0; i -= 2) vertex(buffer, points[i], points[i + 1], color, 1);
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
    }

    private static void circle(double cx, double cy, double radius, int color) {
        ring(cx, cy, radius, radius, color, 0, 1);
    }

    private static void ring(double cx, double cy, double radius, double thickness, int color, int progressColor, double fraction) {
        GlStateManager.disableTexture2D();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < 64; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 32, b = a + Math.PI / 32, inner = radius - thickness;
            int segment = progressColor != 0 && i < fraction * 64 ? progressColor : color;
            vertex(buffer, cx + Math.cos(a) * inner, cy + Math.sin(a) * inner, segment, 1);
            vertex(buffer, cx + Math.cos(b) * inner, cy + Math.sin(b) * inner, segment, 1);
            vertex(buffer, cx + Math.cos(b) * radius, cy + Math.sin(b) * radius, segment, 1);
            vertex(buffer, cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, segment, 1);
        }
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
    }

    private static void texture(ResourceLocation resource, double x, double y, double w, double h, double u, double v, double sw, double sh, int tw, int th) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(resource);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
        var buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + h, 0).tex(u / tw, (v + sh) / th).endVertex();
        buffer.pos(x + w, y + h, 0).tex((u + sw) / tw, (v + sh) / th).endVertex();
        buffer.pos(x + w, y, 0).tex((u + sw) / tw, v / th).endVertex();
        buffer.pos(x, y, 0).tex(u / tw, v / th).endVertex();
        Tessellator.getInstance().draw();
    }

    private record Slice(ResourceLocation resource, int width, int height, int cut, int edge) {
        private void draw(double x, double y, double w, double h) {
            for (int row = 0; row < 3; row++)
                for (int column = 0; column < 3; column++) {
                    double u = column == 0 ? 0 : column == 1 ? cut : width - cut, v = row == 0 ? 0 : row == 1 ? cut : height - cut;
                    double sw = column == 1 ? width - 2 * cut : cut, sh = row == 1 ? height - 2 * cut : cut;
                    double left = x + (column == 0 ? 0 : column == 1 ? edge : w - edge), top = y + (row == 0 ? 0 : row == 1 ? edge : h - edge);
                    double dw = column == 1 ? w - 2 * edge : edge, dh = row == 1 ? h - 2 * edge : edge;
                    if (row != 1 || column != 1 || resource != FRAME.resource)
                        texture(resource, left, top, dw, dh, u, v, sw, sh, width, height);
                }
        }
    }

    private record FooterLine(String text, int color, int height) {
    }

    private static final class PageView {
        private final String id, name;
        private final List<ResourceLocation> ids;
        private ResearchPage layout;

        private PageView(String id, String name, List<ResourceLocation> ids, ResearchPage layout) {
            this.id = id;
            this.name = name;
            this.ids = ids;
            this.layout = layout;
        }
    }

    private static final class Display {
        private final ResearchContent content;
        private Entry entry;
        private long generation = -1;
        private Status status;
        private String statusText, downloadReason, forgetReason, pickerReason;
        private List<String> deviceReasons, startReasons;

        private Display(ResearchContent content) {
            this.content = content;
        }
    }

    private static final class DerivedBaseline {
        private List<Entry> entries;
        private List<Queue> queue;
        private List<Database> databases;
        private UUID active;
        private int center, station, databaseTier;
        private boolean manage, pending;
        private PageView page;
        private long generation;
    }

    private record Status(ResearchOverviewGuiText key, @Nullable Tone tone, int background, int border) {
        private static final Status SPECIAL = new Status(Special, Tone.WAIT, 0xddd2b6, WAIT);
        private static final Status PICKABLE = new Status(Selectable, Tone.GOOD, 0xc2d4c5, GOOD);
        private static final Status LOCKED = new Status(Locked, Tone.BAD, GuiResearchOverview.LOCKED, MUTED);
        private static final Status DONE = new Status(Done, Tone.GOOD, 0xc2d4c5, GOOD);
        private static final Status BLOCKED = new Status(Blocked, Tone.BAD, 0xdcc3c6, BAD);
        private static final Status RESEARCHING = new Status(Researching, Tone.GOOD, LIGHT, DARK);
        private static final Status QUEUED = new Status(Queued, Tone.WAIT, SLOT, LINE);
        private static final Status QUEUED_PARTIAL = new Status(Queued, Tone.WAIT, 0xc2d4c5, 0x8fb095);
        private static final Status DEVICE = new Status(DeviceShort, Tone.BAD, SLOT, 0xc09398);
        private static final Status DEVICE_PARTIAL = new Status(DeviceShort, Tone.BAD, 0xc2d4c5, 0xc09398);
        private static final Status SAVED = new Status(Saved, null, SLOT, LINE);
        private static final Status SAVED_PARTIAL = new Status(Saved, null, 0xc2d4c5, 0x8fb095);
        private static final Status CONTINUE = new Status(Continue, null, 0xc2d4c5, 0x8fb095);
        private static final Status AVAILABLE = new Status(Available, null, SLOT, LINE);
    }

    private record Capacity(String label, String value) {
    }

    private record Hit(double x, double y, double w, double h, boolean enabled, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    private enum Kind {TEXT, GAP, HEADING, INFO, RESOURCE, REQUIRED, SLOTS, PROGRESS, REWARD}

    private record DetailRow(Kind kind, String label, String value, @Nullable List<GenericStack> items, double fraction,
                             double height, int color, double labelWidth, @Nullable RewardCard reward) {
        private DetailRow(Kind kind, String label, String value, @Nullable List<GenericStack> items, double fraction,
                          double height, int color) {
            this(kind, label, value, items, fraction, height, color, 0, null);
        }
    }

    private record RewardCard(int level, ResearchOverviewGuiText state, List<RewardCardGroup> groups, String choiceKey,
                              @Nullable ResourceLocation selected, boolean choiceAllowed, boolean claimable,
                              double height) {
    }

    private record RewardCardGroup(ResourceLocation id, List<String> title, List<String> description,
                                   List<GenericStack> items, double height, boolean chosen) {
    }
}
