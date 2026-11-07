package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.api.client.gui.ResearchIconRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.container.ResearchContainer;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Entry;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Selection;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Tab;
import com.circulation.prototype_eng.localization.ResearchGuiText;
import com.circulation.prototype_eng.research.content.ResearchContentLoader;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;

/** Standard Java screen: AE2 supplies the container, background, actions and scrolling widget. */
public final class ResearchGui extends AEBaseGui<ResearchContainer> {
    private final ResearchContentLoader contentLoader = new ResearchContentLoader(Loader.instance().getConfigDir().toPath());
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ResearchContentLoadResult> contents = new Object2ObjectLinkedOpenHashMap<>();
    private final Scrollbar descriptionScroll = new Scrollbar();
    private final GuiButton[] tabs = new GuiButton[3];
    private final GuiButton[] rows = new GuiButton[ResearchGuiSnapshot.MAX_ROWS];
    private final GuiButton[] choices = new GuiButton[ResearchGuiSnapshot.MAX_DETAILS];
    private final Selection[] rowSelections = new Selection[ResearchGuiSnapshot.MAX_ROWS];
    private final Selection[] choiceSelections = new Selection[ResearchGuiSnapshot.MAX_DETAILS];
    private final ResourceLocation[] choiceGroups = new ResourceLocation[ResearchGuiSnapshot.MAX_DETAILS];
    private final ObjectArrayList<String> descriptionLines = new ObjectArrayList<>();
    private GuiButton previous;
    private GuiButton next;
    private GuiButton description;
    private GuiButton details;
    private GuiButton detailPrevious;
    private GuiButton detailNext;
    private GuiButton primary;
    private GuiButton secondary;
    private String language = "";
    @Nullable private Selection displayed;
    @Nullable private Entry actionEntry;
    private Tab actionTab = Tab.RESEARCH;
    @Nullable private ResourceLocation selectedGroup;
    @Nullable private ResourceLocation iconTexture;
    private ItemStack iconItem = ItemStack.EMPTY;
    private boolean showingDescription = true;
    private int right;
    private int columnWidth;
    private int descriptionRows;

    public ResearchGui(ResearchContainer container, InventoryPlayer player) {
        super(container, player);
        descriptionScroll.setCaptureMouseWheel(false);
    }

    @Override public void initGui() {
        xSize = Math.min(440, width - 8);
        ySize = Math.min(286, height - 8);
        super.initGui();
        columnWidth = (xSize - 24) / 2;
        right = columnWidth + 16;
        for (int i = 0; i < tabs.length; i++) tabs[i] = button(i, 8 + i * (xSize - 16) / 3, 24, (xSize - 16) / 3, 18, tabText(Tab.values()[i]).getLocal());
        for (int i = 0; i < rows.length; i++) rows[i] = button(100 + i, 8, 58 + i * 20, columnWidth, 18, "");
        previous = button(10, 8, ySize - 34, 40, 18, ResearchGuiText.Previous.getLocal());
        next = button(11, columnWidth - 32, ySize - 34, 40, 18, ResearchGuiText.Next.getLocal());
        description = button(12, right, 84, columnWidth / 2, 18, ResearchGuiText.Description.getLocal());
        details = button(13, right + columnWidth / 2, 84, columnWidth - columnWidth / 2, 18, ResearchGuiText.Details.getLocal());
        detailPrevious = button(14, right, ySize - 54, 40, 16, ResearchGuiText.Previous.getLocal());
        detailNext = button(15, right + columnWidth - 40, ySize - 54, 40, 16, ResearchGuiText.Next.getLocal());
        primary = button(16, right, ySize - 34, columnWidth / 2, 18, "");
        secondary = button(17, right + columnWidth / 2, ySize - 34, columnWidth - columnWidth / 2, 18, "");
        for (int i = 0; i < choices.length; i++) choices[i] = button(200 + i, right, 106 + i * 11, columnWidth, 11, "");
        descriptionRows = Math.max(1, (ySize - 54 - 106) / 10);
        descriptionScroll.setPosition(new Point(right + columnWidth - 12, 106));
        descriptionScroll.setHeight(descriptionRows * 10);
        displayed = null;
        updateControls();
    }

    private GuiButton button(int id, int x, int y, int width, int height, String label) {
        var button = new GuiButton(id, guiLeft + x, guiTop + y, width, height, label);
        buttonList.add(button);
        return button;
    }

    @Override public void updateScreen() {
        super.updateScreen();
        descriptionScroll.tick();
    }

    private void updateControls() {
        var locale = mc.getLanguageManager().getCurrentLanguage().getLanguageCode();
        if (!language.equals(locale)) { language = locale; contents.clear(); displayed = null; }
        var view = container.snapshot;
        var entry = selected();
        if (!Objects.equals(displayed, view.selected())) {
            displayed = view.selected();
            selectedGroup = entry == null ? null : entry.selectedGroup();
            rebuildDescription(entry);
        }
        if (entry != null && entry.selectedGroup() != null) selectedGroup = entry.selectedGroup();
        else if (selectedGroup != null && view.details().stream().noneMatch(detail -> detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE
                && detail.id().equals(selectedGroup))) selectedGroup = null;
        for (int i = 0; i < tabs.length; i++) tabs[i].enabled = view.tab().ordinal() != i;
        for (int i = 0; i < rows.length; i++) {
            var button = rows[i];
            button.visible = i < view.rows().size();
            rowSelections[i] = button.visible ? view.rows().get(i).selection() : null;
            if (!button.visible) continue;
            var row = view.rows().get(i);
            button.displayString = fit(title(row.id()) + (row.targetLevel() > 0 ? " · " + row.targetLevel() : ""), columnWidth - 8);
            button.enabled = !Objects.equals(view.selected(), selection(row));
        }
        previous.enabled = view.offset() > 0;
        next.enabled = view.offset() + view.rows().size() < view.total();
        description.enabled = !showingDescription && entry != null;
        details.enabled = showingDescription && entry != null;
        detailPrevious.visible = detailNext.visible = !showingDescription && entry != null;
        detailPrevious.enabled = view.detailOffset() > 0;
        detailNext.enabled = view.detailOffset() + view.details().size() < view.detailTotal();
        primary.visible = entry != null;
        secondary.visible = entry != null && view.tab() == Tab.RESEARCH;
        actionEntry = entry;
        actionTab = view.tab();
        if (entry != null) {
            var kind = primaryKind(view.tab());
            primary.displayString = (kind == ResearchContainer.Kind.START ? ResearchGuiText.Start
                : kind == ResearchContainer.Kind.TAKEOVER ? ResearchGuiText.Takeover
                : entry.selectedGroup() == null ? ResearchGuiText.Claim : ResearchGuiText.ContinueClaim).getLocal();
            primary.enabled = !container.actionQueued && ResearchContainer.allowed(view, entry, kind)
                && (kind != ResearchContainer.Kind.CLAIM || selectedGroup != null);
            secondary.displayString = (entry.blacklisted() ? ResearchGuiText.Unblacklist : ResearchGuiText.Blacklist).getLocal();
            secondary.enabled = !container.actionQueued && ResearchContainer.allowed(view, entry, ResearchContainer.Kind.BLACKLIST);
        }
        for (int i = 0; i < choices.length; i++) {
            var button = choices[i];
            button.visible = !showingDescription && entry != null && i < view.details().size()
                && view.details().get(i).kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE;
            choiceSelections[i] = button.visible ? view.selected() : null;
            choiceGroups[i] = button.visible ? view.details().get(i).id() : null;
            if (!button.visible) continue;
            var group = view.details().get(i).id();
            button.displayString = fit(groupTitle(entry, group), columnWidth - 8);
            button.enabled = !container.actionQueued && entry.selectedGroup() == null && !group.equals(selectedGroup);
        }
    }

    private void rebuildDescription(@Nullable Entry entry) {
        descriptionLines.clear();
        descriptionScroll.setCurrentScroll(0);
        iconItem = ItemStack.EMPTY;
        iconTexture = null;
        if (entry != null) {
            var content = content(entry.id()).content();
            if (content.isEmpty()) descriptionLines.add(ResearchGuiText.MissingContent.getLocal(entry.id()));
            else {
                var icon = content.orElseThrow().icon();
                if (icon.kind() == ResearchContent.Kind.ITEM) {
                    if (!Item.REGISTRY.containsKey(icon.id())) throw new IllegalArgumentException("Unknown research content item " + icon.id());
                    iconItem = new ItemStack(Objects.requireNonNull(Item.REGISTRY.getObject(icon.id())), 1, icon.meta());
                } else iconTexture = ResearchIconRegistry.require(icon.id());
                if (!content.orElseThrow().summary().isEmpty()) descriptionLines.addAll(fontRenderer.listFormattedStringToWidth(content.orElseThrow().summary(), columnWidth - 18));
                for (var line : content.orElseThrow().description()) descriptionLines.addAll(fontRenderer.listFormattedStringToWidth(line, columnWidth - 18));
            }
        }
        descriptionScroll.setRange(0, Math.max(0, descriptionLines.size() - descriptionRows), 1);
    }

    private ResearchContentLoadResult content(ResourceLocation id) {
        var result = contents.get(id);
        if (result == null) {
            result = contentLoader.load(id, language);
            if (contents.size() == 128) contents.removeFirst();
            contents.put(id, result);
        }
        return result;
    }

    private String title(ResourceLocation id) { return content(id).content().map(ResearchContent::title).orElseGet(id::toString); }
    private String fit(String text, int width) { return fontRenderer.trimStringToWidth(text, Math.max(1, width)); }
    private static Selection selection(Entry entry) { return new Selection(entry.id(), entry.targetLevel(), entry.entryId()); }
    @Nullable private Entry selected() {
        for (var entry : container.snapshot.rows()) if (selection(entry).equals(container.snapshot.selected())) return entry;
        return null;
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        var view = container.snapshot;
        // Minecraft applies queued GuiSync before input and updates screens afterwards. A button
        // still represents the last displayed identity, never the same index in a newer page.
        if (!button.visible || !button.enabled) return;
        if (button.id < 3) {
            showingDescription = button.id == 0;
            container.requestView(Tab.values()[button.id], 0, null, 0);
        } else if (button.id >= 100 && button.id < 100 + rows.length) {
            var target = rowSelections[button.id - 100];
            if (target != null && actionTab == view.tab() && view.rows().stream().anyMatch(entry -> entry.selection().equals(target))) {
                container.requestView(view.tab(), view.offset(), target, 0);
            }
        } else if (button.id >= 200 && button.id < 200 + choices.length) {
            int index = button.id - 200;
            var group = choiceGroups[index];
            if (group != null && Objects.equals(choiceSelections[index], view.selected()) && view.tab() == Tab.REWARDS
                    && view.details().stream().anyMatch(detail -> detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE && detail.id().equals(group))) {
                selectedGroup = group;
            }
        } else {
            switch (button.id) {
                case 10 -> container.requestView(view.tab(), Math.max(0, view.offset() - ResearchGuiSnapshot.MAX_ROWS), null, 0);
                case 11 -> container.requestView(view.tab(), view.offset() + view.rows().size(), null, 0);
                case 12 -> showingDescription = true;
                case 13 -> showingDescription = false;
                case 14 -> container.requestView(view.tab(), view.offset(), view.selected(), Math.max(0, view.detailOffset() - ResearchGuiSnapshot.MAX_DETAILS));
                case 15 -> container.requestView(view.tab(), view.offset(), view.selected(), view.detailOffset() + view.details().size());
                case 16 -> { var entry = selected(); if (entry != null && actionEntry != null && actionTab == view.tab() && entry.selection().equals(actionEntry.selection())) container.requestAction(primaryKind(view.tab()), actionEntry, selectedGroup); }
                case 17 -> { var entry = selected(); if (entry != null && actionEntry != null && actionTab == view.tab() && entry.selection().equals(actionEntry.selection())) container.requestAction(ResearchContainer.Kind.BLACKLIST, actionEntry, null); }
                default -> super.actionPerformed(button);
            }
        }
    }

    private static ResearchContainer.Kind primaryKind(Tab tab) {
        return switch (tab) { case RESEARCH -> ResearchContainer.Kind.START; case QUEUE -> ResearchContainer.Kind.TAKEOVER; case REWARDS -> ResearchContainer.Kind.CLAIM; };
    }
    private static ResearchGuiText tabText(Tab tab) {
        return switch (tab) { case RESEARCH -> ResearchGuiText.Research; case QUEUE -> ResearchGuiText.Queue; case REWARDS -> ResearchGuiText.Rewards; };
    }

    @Override public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override public void drawFG(int x, int y, int mouseX, int mouseY) {
        var view = container.snapshot;
        fontRenderer.drawString(ResearchGuiText.Title.getLocal(), 8, 8, 0x404040);
        var state = !view.initialized() ? ResearchGuiText.Loading : view.savePending() ? ResearchGuiText.Saving : ResearchGuiText.team(view.teamStatus());
        fontRenderer.drawString(fit(state.getLocal(), columnWidth), 8, 46, 0x404040);
        fontRenderer.drawString(fit(container.feedback.getLocal(), xSize - 16), 8, ySize - 12, 0x404040);
        if (view.initialized() && view.rows().isEmpty()) fontRenderer.drawString(ResearchGuiText.NoEntries.getLocal(), 8, 62, 0x404040);
        String page = ResearchGuiText.Page.getLocal(view.total() == 0 ? 0 : view.offset() / ResearchGuiSnapshot.MAX_ROWS + 1,
            (view.total() + (long) ResearchGuiSnapshot.MAX_ROWS - 1) / ResearchGuiSnapshot.MAX_ROWS);
        fontRenderer.drawString(fit(page, columnWidth - 84), 50, ySize - 29, 0x404040);
        var entry = selected();
        if (entry == null) return;
        drawIcon();
        fontRenderer.drawString(fit(title(entry.id()), columnWidth - 20), right + 20, 46, 0x404040);
        fontRenderer.drawString(fit(ResearchGuiText.PersonalLevel.getLocal(view.personalPermission() == Decision.ALLOWED ? entry.personalLevel() : "—")
            + " / " + ResearchGuiText.SharedLevel.getLocal(view.teamViewPermission() == Decision.ALLOWED ? entry.sharedLevel() : "—"), columnWidth), right, 62, 0x404040);
        String level = entry.claimStatus() != null ? ResearchGuiText.claim(entry.claimStatus()).getLocal()
            : entry.manualStatus() != null ? ResearchGuiText.session(entry.manualStatus()).getLocal()
            : entry.targetLevel() == 0 ? ResearchGuiText.NotStartable.getLocal()
            : ResearchGuiText.TargetLevel.getLocal(entry.targetLevel()) + " / " + ResearchGuiText.Tier.getLocal(entry.tier());
        fontRenderer.drawString(fit(level, columnWidth), right, 73, 0x404040);
        if (showingDescription) {
            for (int i = 0; i < descriptionRows && i + descriptionScroll.getCurrentScroll() < descriptionLines.size(); i++) {
                fontRenderer.drawString(descriptionLines.get(i + descriptionScroll.getCurrentScroll()), right, 106 + i * 10, 0x404040);
            }
            descriptionScroll.drawForegroundLayer(descriptionScroll.getBounds(), new Point(mouseX - guiLeft, mouseY - guiTop));
        } else {
            if (view.details().isEmpty()) fontRenderer.drawString(ResearchGuiText.NoDetails.getLocal(), right, 106, 0x404040);
            for (int i = 0; i < view.details().size(); i++) {
                var detail = view.details().get(i);
                if (detail.kind() != ResearchGuiSnapshot.Kind.REWARD_CHOICE) fontRenderer.drawString(fit(detailText(entry, detail), columnWidth), right, 106 + i * 11, 0x404040);
            }
            var detailPage = ResearchGuiText.Page.getLocal(view.detailTotal() == 0 ? 0 : view.detailOffset() / ResearchGuiSnapshot.MAX_DETAILS + 1,
                (view.detailTotal() + (long) ResearchGuiSnapshot.MAX_DETAILS - 1) / ResearchGuiSnapshot.MAX_DETAILS);
            fontRenderer.drawString(fit(detailPage, columnWidth - 84), right + 42, ySize - 50, 0x404040);
        }
    }

    private static String detailText(Entry entry, ResearchGuiSnapshot.Detail detail) {
        String name;
        if (detail.kind() == ResearchGuiSnapshot.Kind.NUMERIC) {
            name = detail.id().equals(NetworkResources.COMPUTE.id()) ? ResearchGuiText.Compute.getLocal()
                : detail.id().equals(NetworkResources.QUBIT.id()) ? ResearchGuiText.Qubit.getLocal()
                : detail.id().equals(NetworkResources.THREAD.id()) ? ResearchGuiText.Threads.getLocal() : detail.id().toString();
        } else {
            name = materialName(entry, detail);
        }
        return ResearchGuiText.ResourceProgress.getLocal(name, detail.current(), detail.required());
    }

    private static String materialName(Entry entry, ResearchGuiSnapshot.Detail detail) {
        for (var requirement : ResearchRegistry.INSTANCE.require(entry.id()).materialRequirementsForLevel(entry.targetLevel())) {
            if (requirement.id().equals(detail.id()) && requirement.payload() instanceof ResourceRequirement resources) {
                return resources.inputs().get(detail.inputIndex()).what().getDisplayName().getFormattedText();
            }
        }
        throw new IllegalStateException("Research material is missing from the client definition: " + detail.id());
    }

    private void drawIcon() {
        if (!iconItem.isEmpty()) itemRender.renderItemAndEffectIntoGUI(iconItem, right, 44);
        else if (iconTexture != null) {
            bindTexture(iconTexture);
            GlStateManager.color(1, 1, 1, 1);
            drawModalRectWithCustomSizedTexture(right, 44, 0, 0, 16, 16, 16, 16);
        }
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updateControls();
        super.drawScreen(mouseX, mouseY, partialTicks);
        var view = container.snapshot;
        for (int i = 0; i < view.rows().size(); i++) {
            if (isPointInRegion(8, 58 + i * 20, columnWidth, 18, mouseX, mouseY)) drawHoveringText(rowTooltip(view.rows().get(i)), mouseX, mouseY);
        }
        var entry = selected();
        if (entry == null || showingDescription) return;
        for (int i = 0; i < view.details().size(); i++) {
            if (!isPointInRegion(right, 106 + i * 11, columnWidth, 11, mouseX, mouseY)) continue;
            var detail = view.details().get(i);
            var tooltip = new ObjectArrayList<String>();
            if (detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE) {
                var group = group(entry, detail.id());
                tooltip.add(groupTitle(entry, detail.id()));
                if (group.description() != null) tooltip.addAll(fontRenderer.listFormattedStringToWidth(group.description().getLocal(), 240));
                tooltip.add(detail.id().toString());
                if (entry.selectedGroup() != null) tooltip.add(ResearchGuiText.FixedChoice.getLocal(entry.selectedGroup()));
            } else tooltip.add(detailText(entry, detail));
            drawHoveringText(tooltip, mouseX, mouseY);
        }
    }

    private ObjectArrayList<String> rowTooltip(Entry entry) {
        var lines = new ObjectArrayList<String>();
        lines.add(title(entry.id()));
        lines.add(entry.id().toString());
        if (entry.blacklisted()) lines.add(ResearchGuiText.Blacklisted.getLocal());
        if (container.snapshot.tab() == Tab.RESEARCH && entry.targetLevel() > 0) lines.add(ResearchGuiText.PrerequisitesOnly.getLocal());
        if (entry.manualStatus() != null) lines.add(ResearchGuiText.session(entry.manualStatus()).getLocal());
        if (entry.pauseReason() != null) lines.add(ResearchGuiText.pause(entry.pauseReason()).getLocal());
        if (entry.contributor() != null) lines.add(ResearchGuiText.Contributor.getLocal(entry.contributor()));
        if (entry.claimStatus() != null) lines.add(ResearchGuiText.claim(entry.claimStatus()).getLocal());
        return lines;
    }

    private static RewardGroup group(Entry entry, ResourceLocation id) {
        for (var group : ResearchRegistry.INSTANCE.require(entry.id()).rewardGroupsForLevel(entry.targetLevel())) if (group.id().equals(id)) return group;
        throw new IllegalStateException("Research reward choice is missing from the client registry: " + id);
    }
    private static String groupTitle(Entry entry, ResourceLocation id) {
        var group = group(entry, id);
        return group.title() == null ? id.toString() : group.title().getLocal();
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (showingDescription && descriptionScroll.getBounds().contains(mouseX - guiLeft, mouseY - guiTop)
                && descriptionScroll.onMouseDown(new Point(mouseX - guiLeft, mouseY - guiTop), button)) return;
        super.mouseClicked(mouseX, mouseY, button);
    }
    @Override protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (showingDescription && descriptionScroll.onMouseDrag(new Point(mouseX - guiLeft, mouseY - guiTop), button)) return;
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }
    @Override protected void mouseReleased(int mouseX, int mouseY, int button) {
        descriptionScroll.onMouseUp(new Point(mouseX - guiLeft, mouseY - guiTop), button);
        super.mouseReleased(mouseX, mouseY, button);
    }
    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && showingDescription && isPointInRegion(right, 106, columnWidth, descriptionRows * 10, mouseX, mouseY)) {
            descriptionScroll.setCurrentScroll(descriptionScroll.getCurrentScroll() - Integer.signum(wheel));
        }
    }
}
