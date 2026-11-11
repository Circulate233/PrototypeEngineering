package com.circulation.prototype_eng.client.gui;

import ae2.client.Point;
import ae2.client.gui.AEBaseGui;
import ae2.client.gui.style.BackgroundGenerator;
import ae2.client.gui.widgets.Scrollbar;
import com.circulation.prototype_eng.api.client.gui.ResearchIconRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.container.DataCenterGuiSnapshot;
import com.circulation.prototype_eng.container.ResearchContainer;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Entry;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Selection;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Tab;
import com.circulation.prototype_eng.localization.ResearchGuiText;
import com.circulation.prototype_eng.research.content.ResearchContentLoader;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard Java screen: AE2 supplies the container, background, actions and scrolling widget.
 */
public final class ResearchGui extends AEBaseGui<ResearchContainer> {
    private final ResearchContentLoader contentLoader = new ResearchContentLoader(Loader.instance().getConfigDir().toPath());
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ResearchContentLoadResult> contents = new Object2ObjectLinkedOpenHashMap<>();
    private final Scrollbar descriptionScroll = new Scrollbar();
    private final GuiButton[] tabs = new GuiButton[4];
    private final GuiButton[] rows = new GuiButton[ResearchGuiSnapshot.MAX_ROWS];
    private final GuiButton[] choices = new GuiButton[ResearchGuiSnapshot.MAX_DETAILS];
    private final Selection[] rowSelections = new Selection[ResearchGuiSnapshot.MAX_ROWS];
    private final Selection[] choiceSelections = new Selection[ResearchGuiSnapshot.MAX_DETAILS];
    private final ResourceLocation[] choiceGroups = new ResourceLocation[ResearchGuiSnapshot.MAX_DETAILS];
    private final DataCenterGuiSnapshot.Selection[] databaseSelections = new DataCenterGuiSnapshot.Selection[DataCenterGuiSnapshot.MAX_ROWS];
    private final ObjectArrayList<String> descriptionLines = new ObjectArrayList<>();
    private GuiButton previous;
    private GuiButton next;
    private GuiButton description;
    private GuiButton details;
    private GuiButton detailPrevious;
    private GuiButton detailNext;
    private GuiButton primary;
    private GuiButton secondary;
    private GuiButton itemDownload;
    private GuiButton operations;
    private GuiButton forget;
    private GuiButton adminKind;
    private GuiButton adminSet;
    private GuiTextField forgottenLevel;
    private GuiTextField adminTarget;
    private GuiTextField adminLevel;
    private boolean showingOperations;
    private boolean teamLevelTarget;
    @Nullable
    private UUID itemDatabase;
    private int itemMaterialSlot;
    private String language = "";
    @Nullable
    private Selection displayed;
    @Nullable
    private Entry actionEntry;
    @Nullable
    private DataCenterGuiSnapshot.Entry databaseActionEntry;
    private DataCenterGuiSnapshot drawnDatabase = DataCenterGuiSnapshot.EMPTY;
    private boolean drawnDatabaseMode;
    private Tab actionTab = Tab.RESEARCH;
    private String actionTeam = "";
    @Nullable
    private ResourceLocation selectedGroup;
    @Nullable
    private ResourceLocation iconTexture;
    private ItemStack iconItem = ItemStack.EMPTY;
    private boolean showingDescription = true;
    private int right;
    private int columnWidth;
    private int descriptionRows;

    public ResearchGui(ResearchContainer container, InventoryPlayer player) {
        super(container, player);
        descriptionScroll.setCaptureMouseWheel(false);
    }

    private static Selection selection(Entry entry) {
        return new Selection(entry.id(), entry.targetLevel(), entry.entryId());
    }

    private static boolean hasRewardChoice(ResearchGuiSnapshot view, ResourceLocation group) {
        for (var detail : view.details())
            if (detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE && detail.id().equals(group)) return true;
        return false;
    }

    private static boolean hasSelection(ResearchGuiSnapshot view, Selection target) {
        for (var entry : view.rows()) if (entry.selection().equals(target)) return true;
        return false;
    }

    private static ResearchContainer.Kind primaryKind(Tab tab) {
        return switch (tab) {
            case RESEARCH -> ResearchContainer.Kind.START;
            case QUEUE -> ResearchContainer.Kind.TAKEOVER;
            case REWARDS -> ResearchContainer.Kind.CLAIM;
        };
    }

    private static ResearchGuiText tabText(Tab tab) {
        return switch (tab) {
            case RESEARCH -> ResearchGuiText.Research;
            case QUEUE -> ResearchGuiText.Queue;
            case REWARDS -> ResearchGuiText.Rewards;
        };
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

    private static RewardGroup group(Entry entry, ResourceLocation id) {
        for (var group : ResearchRegistry.INSTANCE.require(entry.id()).rewardGroupsForLevel(entry.targetLevel()))
            if (group.id().equals(id)) return group;
        throw new IllegalStateException("Research reward choice is missing from the client registry: " + id);
    }

    private static String groupTitle(Entry entry, ResourceLocation id) {
        var group = group(entry, id);
        return group.title() == null ? id.toString() : group.title().getLocal();
    }

    private static boolean validLevelInput(GuiTextField input, Entry entry, int minimum) {
        int value = ResearchContainer.levelInput(input.getText());
        return value >= minimum && value <= ResearchRegistry.INSTANCE.require(entry.id()).maxLevel();
    }

    @Override
    public void initGui() {
        xSize = Math.min(440, width - 8);
        ySize = Math.min(286, height - 8);
        super.initGui();
        columnWidth = (xSize - 24) / 2;
        right = columnWidth + 16;
        for (int i = 0; i < tabs.length; i++)
            tabs[i] = button(i, 8 + i * (xSize - 16) / tabs.length, 24,
                (xSize - 16) / tabs.length, 18, (i == 3 ? ResearchGuiText.Database : tabText(Tab.values()[i])).getLocal());
        for (int i = 0; i < rows.length; i++) rows[i] = button(100 + i, 8, 58 + i * 20, columnWidth, 18, "");
        previous = button(10, 8, ySize - 34, 40, 18, ResearchGuiText.Previous.getLocal());
        next = button(11, columnWidth - 32, ySize - 34, 40, 18, ResearchGuiText.Next.getLocal());
        description = button(12, right, 84, columnWidth / 2, 18, ResearchGuiText.Description.getLocal());
        details = button(13, right + columnWidth / 2, 84, columnWidth - columnWidth / 2, 18, ResearchGuiText.Details.getLocal());
        detailPrevious = button(14, right, ySize - 54, 40, 16, ResearchGuiText.Previous.getLocal());
        detailNext = button(15, right + columnWidth - 40, ySize - 54, 40, 16, ResearchGuiText.Next.getLocal());
        primary = button(16, right, ySize - 34, columnWidth / 2, 18, "");
        secondary = button(17, right + columnWidth / 2, ySize - 34, columnWidth - columnWidth / 2, 18, "");
        itemDownload = button(18, right, ySize - 54, columnWidth, 18, "");
        operations = button(19, right + columnWidth * 2 / 3, 84, columnWidth - columnWidth * 2 / 3, 18, ResearchGuiText.ResearchActions.getLocal());
        description.width = columnWidth / 3;
        details.x = guiLeft + right + columnWidth / 3;
        details.width = columnWidth / 3;
        forget = button(20, right + 70, 118, columnWidth - 70, 18, ResearchGuiText.Forget.getLocal());
        adminKind = button(21, right, 152, 64, 18, "");
        adminSet = button(22, right + 70, 184, columnWidth - 70, 18, ResearchGuiText.SetLevel.getLocal());
        forgottenLevel = field(23, right, 118, 64, 10);
        adminTarget = field(24, right + 70, 152, columnWidth - 70, 256);
        adminLevel = field(25, right, 184, 64, 10);
        for (int i = 0; i < choices.length; i++) choices[i] = button(200 + i, right, 106 + i * 11, columnWidth, 11, "");
        descriptionRows = Math.max(1, (ySize - 74 - 106) / 10);
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

    private GuiTextField field(int id, int x, int y, int width, int limit) {
        var field = new GuiTextField(id, fontRenderer, guiLeft + x, guiTop + y, width, 18);
        field.setMaxStringLength(limit);
        return field;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        descriptionScroll.tick();
        forgottenLevel.updateCursorCounter();
        adminTarget.updateCursorCounter();
        adminLevel.updateCursorCounter();
    }

    private void updateControls() {
        var locale = mc.getLanguageManager().getCurrentLanguage().getLanguageCode();
        if (!language.equals(locale)) {
            language = locale;
            contents.clear();
            displayed = null;
        }
        if (drawnDatabaseMode != container.databaseMode) {
            displayed = null;
            showingOperations = false;
        }
        drawnDatabaseMode = container.databaseMode;
        primary.width = drawnDatabaseMode ? columnWidth : columnWidth / 2;
        if (drawnDatabaseMode) {
            updateDatabaseControls();
            return;
        }
        var view = container.snapshot;
        var entry = selected();
        if (!Objects.equals(displayed, view.selected())) {
            displayed = view.selected();
            selectedGroup = entry == null ? null : entry.selectedGroup();
            forgottenLevel.setText(Integer.toString(entry == null ? 1 : Math.max(1, entry.personalLevel())));
            adminLevel.setText(Integer.toString(entry == null ? 0 : entry.personalLevel()));
            adminTarget.setText(teamLevelTarget ? container.displayedTeam : mc.player.getUniqueID().toString());
            rebuildDescription(entry == null ? null : entry.id());
        }
        if (entry != null && entry.selectedGroup() != null) selectedGroup = entry.selectedGroup();
        else if (selectedGroup != null && !hasRewardChoice(view, selectedGroup)) selectedGroup = null;
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
        description.enabled = (showingOperations || !showingDescription) && entry != null;
        details.enabled = (showingOperations || showingDescription) && entry != null;
        detailPrevious.visible = detailNext.visible = !showingOperations && !showingDescription && entry != null;
        detailPrevious.enabled = view.detailOffset() > 0;
        detailNext.enabled = view.detailOffset() + view.details().size() < view.detailTotal();
        primary.visible = !showingOperations && entry != null;
        secondary.visible = !showingOperations && entry != null && view.tab() == Tab.RESEARCH;
        actionEntry = entry;
        actionTab = view.tab();
        actionTeam = container.displayedTeam;
        itemDatabase = container.databaseSnapshot.dataCenterId();
        itemMaterialSlot = mc.player.inventory.currentItem;
        itemDownload.visible = !showingOperations && showingDescription && entry != null && view.tab() == Tab.RESEARCH;
        itemDownload.enabled = entry != null && !container.actionQueued && ResearchContainer.canItemDownload(view, container.databaseSnapshot, entry)
            && actionTeam.equals(container.databaseSnapshot.teamId()) && !mc.player.getHeldItemMainhand().isEmpty();
        itemDownload.displayString = ResearchGuiText.ItemDownload.getLocal(entry == null ? 0 : entry.sharedLevel());
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
        operations.visible = entry != null && view.tab() == Tab.RESEARCH;
        operations.enabled = !showingOperations;
        boolean panel = showingOperations && operations.visible;
        forgottenLevel.setVisible(panel);
        forgottenLevel.setEnabled(panel && !container.actionQueued && view.forgetPermission() == Decision.ALLOWED);
        forget.visible = panel;
        forget.enabled = panel && entry != null && !container.actionQueued && ResearchContainer.allowed(view, entry, ResearchContainer.Kind.FORGET)
            && validLevelInput(forgottenLevel, entry, 1);
        boolean admin = panel && view.adminPermission() == Decision.ALLOWED;
        adminKind.visible = admin;
        adminKind.enabled = !container.actionQueued;
        adminKind.displayString = (teamLevelTarget ? ResearchGuiText.AdminTeam : ResearchGuiText.AdminPlayer).getLocal();
        adminTarget.setVisible(admin);
        adminTarget.setEnabled(admin && !container.actionQueued);
        adminLevel.setVisible(admin);
        adminLevel.setEnabled(admin && !container.actionQueued);
        adminSet.visible = admin;
        adminSet.enabled = admin && entry != null && !container.actionQueued && validLevelInput(adminLevel, entry, 0)
            && (teamLevelTarget ? ResearchContainer.teamTarget(adminTarget.getText()) : ResearchContainer.playerTarget(adminTarget.getText()));
        for (int i = 0; i < choices.length; i++) {
            var button = choices[i];
            button.visible = !showingOperations && !showingDescription && entry != null && i < view.details().size()
                && view.details().get(i).kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE;
            choiceSelections[i] = button.visible ? view.selected() : null;
            choiceGroups[i] = button.visible ? view.details().get(i).id() : null;
            if (!button.visible) continue;
            var group = view.details().get(i).id();
            button.displayString = fit(groupTitle(entry, group), columnWidth - 8);
            button.enabled = !container.actionQueued && entry.selectedGroup() == null && !group.equals(selectedGroup);
        }
    }

    private void updateDatabaseControls() {
        itemDownload.visible = false;
        operations.visible = forget.visible = adminKind.visible = adminSet.visible = false;
        forgottenLevel.setVisible(false);
        adminTarget.setVisible(false);
        adminLevel.setVisible(false);
        var view = container.databaseSnapshot;
        drawnDatabase = view;
        var entry = databaseSelected();
        var identity = entry == null ? null : new Selection(entry.id(), entry.targetLevel(), 0);
        if (!Objects.equals(displayed, identity)) {
            displayed = identity;
            rebuildDescription(entry == null ? null : entry.id());
        }
        actionEntry = null;
        databaseActionEntry = entry;
        selectedGroup = null;
        for (int i = 0; i < tabs.length; i++) tabs[i].enabled = i != 3;
        for (int i = 0; i < rows.length; i++) {
            var button = rows[i];
            button.visible = i < view.rows().size();
            rowSelections[i] = null;
            databaseSelections[i] = button.visible ? view.rows().get(i).selection() : null;
            if (!button.visible) continue;
            var row = view.rows().get(i);
            button.displayString = fit(title(row.id()), columnWidth - 8);
            button.enabled = !row.selection().equals(view.selected());
        }
        previous.enabled = view.offset() > 0;
        next.enabled = view.offset() + view.rows().size() < view.total();
        description.enabled = (showingOperations || !showingDescription) && entry != null;
        details.enabled = (showingOperations || showingDescription) && entry != null;
        detailPrevious.visible = detailNext.visible = false;
        primary.visible = !showingOperations && entry != null;
        primary.displayString = ResearchGuiText.Download.getLocal();
        primary.enabled = entry != null && !container.actionQueued && view.canDownload(entry);
        secondary.visible = false;
        for (int i = 0; i < choices.length; i++) {
            choices[i].visible = false;
            choiceSelections[i] = null;
            choiceGroups[i] = null;
        }
    }

    private void rebuildDescription(@Nullable ResourceLocation researchId) {
        descriptionLines.clear();
        descriptionScroll.setCurrentScroll(0);
        iconItem = ItemStack.EMPTY;
        iconTexture = null;
        if (researchId != null) {
            var content = content(researchId).content();
            if (content.isEmpty()) descriptionLines.add(ResearchGuiText.MissingContent.getLocal(researchId));
            else {
                var icon = content.orElseThrow().icon();
                if (icon.kind() == ResearchContent.Kind.ITEM) {
                    if (!Item.REGISTRY.containsKey(icon.id()))
                        throw new IllegalArgumentException("Unknown research content item " + icon.id());
                    iconItem = new ItemStack(Objects.requireNonNull(Item.REGISTRY.getObject(icon.id())), 1, icon.meta());
                } else iconTexture = ResearchIconRegistry.require(icon.id());
                if (!content.orElseThrow().summary().isEmpty())
                    descriptionLines.addAll(fontRenderer.listFormattedStringToWidth(content.orElseThrow().summary(), columnWidth - 18));
                for (var line : content.orElseThrow().description())
                    descriptionLines.addAll(fontRenderer.listFormattedStringToWidth(line, columnWidth - 18));
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

    private String title(ResourceLocation id) {
        return content(id).content().map(ResearchContent::title).orElseGet(id::toString);
    }

    private String fit(String text, int width) {
        return fontRenderer.trimStringToWidth(text, Math.max(1, width));
    }

    @Nullable
    private Entry selected() {
        for (var entry : container.snapshot.rows())
            if (selection(entry).equals(container.snapshot.selected())) return entry;
        return null;
    }

    @Nullable
    private DataCenterGuiSnapshot.Entry databaseSelected() {
        var selection = container.databaseSnapshot.selected();
        return selection == null ? null : container.databaseSnapshot.find(selection);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        var view = container.snapshot;
        // Minecraft applies queued GuiSync before input and updates screens afterwards. A button
        // still represents the last displayed identity, never the same index in a newer page.
        if (!button.visible || !button.enabled) return;
        if (button.id < tabs.length) {
            showingOperations = false;
            showingDescription = button.id == 0 || button.id == 3;
            if (button.id == 3) container.requestDatabaseView(0, null);
            else container.requestView(Tab.values()[button.id], 0, null, 0);
        } else if (button.id >= 100 && button.id < 100 + rows.length) {
            if (drawnDatabaseMode) {
                var target = databaseSelections[button.id - 100];
                if (target != null && currentDatabaseRow(target))
                    container.requestDatabaseView(drawnDatabase.offset(), target);
                return;
            }
            var target = rowSelections[button.id - 100];
            if (!container.databaseMode && target != null && actionTab == view.tab() && hasSelection(view, target)) {
                container.requestView(view.tab(), view.offset(), target, 0);
            }
        } else if (button.id >= 200 && button.id < 200 + choices.length) {
            int index = button.id - 200;
            var group = choiceGroups[index];
            if (group != null && Objects.equals(choiceSelections[index], view.selected()) && view.tab() == Tab.REWARDS
                && hasRewardChoice(view, group)) {
                selectedGroup = group;
            }
        } else {
            if (drawnDatabaseMode && button.id != 12 && button.id != 13) {
                if (!container.databaseMode) return;
                if (button.id == 10 || button.id == 11) {
                    if (Objects.equals(drawnDatabase.dataCenterId(), container.databaseSnapshot.dataCenterId())
                        && drawnDatabase.teamId().equals(container.databaseSnapshot.teamId())) {
                        int offset = button.id == 10 ? Math.max(0, drawnDatabase.offset() - DataCenterGuiSnapshot.MAX_ROWS)
                            : drawnDatabase.offset() + drawnDatabase.rows().size();
                        container.requestDatabaseView(offset, null);
                    }
                } else if (button.id == 16 && databaseActionEntry != null && currentDatabaseRow(databaseActionEntry.selection())
                    && databaseActionEntry.selection().equals(container.databaseSnapshot.selected())) {
                    container.requestDatabaseDownload(drawnDatabase, databaseActionEntry);
                }
                return;
            }
            if (!drawnDatabaseMode && container.databaseMode) return;
            switch (button.id) {
                case 10 ->
                    container.requestView(view.tab(), Math.max(0, view.offset() - ResearchGuiSnapshot.MAX_ROWS), null, 0);
                case 11 -> container.requestView(view.tab(), view.offset() + view.rows().size(), null, 0);
                case 12 -> {
                    showingOperations = false;
                    showingDescription = true;
                }
                case 13 -> {
                    showingOperations = false;
                    showingDescription = false;
                }
                case 14 ->
                    container.requestView(view.tab(), view.offset(), view.selected(), Math.max(0, view.detailOffset() - ResearchGuiSnapshot.MAX_DETAILS));
                case 15 ->
                    container.requestView(view.tab(), view.offset(), view.selected(), view.detailOffset() + view.details().size());
                case 16 -> {
                    var entry = selected();
                    if (entry != null && actionEntry != null && actionTab == view.tab() && entry.selection().equals(actionEntry.selection()))
                        container.requestAction(primaryKind(view.tab()), actionEntry, selectedGroup, actionTeam);
                }
                case 17 -> {
                    var entry = selected();
                    if (entry != null && actionEntry != null && actionTab == view.tab() && entry.selection().equals(actionEntry.selection()))
                        container.requestAction(ResearchContainer.Kind.BLACKLIST, actionEntry, null, actionTeam);
                }
                case 19 -> showingOperations = true;
                case 20, 22 -> {
                    var entry = selected();
                    if (entry != null && actionEntry != null && view.tab() == actionTab && actionTab == Tab.RESEARCH
                        && entry.selection().equals(actionEntry.selection()) && entry.personalLevel() == actionEntry.personalLevel()) {
                        var kind = button.id == 20 ? ResearchContainer.Kind.FORGET
                            : teamLevelTarget ? ResearchContainer.Kind.ADMIN_TEAM : ResearchContainer.Kind.ADMIN_PLAYER;
                        int value = ResearchContainer.levelInput((button.id == 20 ? forgottenLevel : adminLevel).getText());
                        if (value >= (button.id == 20 ? 1 : 0) && value <= ResearchRegistry.INSTANCE.require(actionEntry.id()).maxLevel())
                            container.requestLevelAction(kind, actionEntry, actionTeam, value, button.id == 20 ? "" : adminTarget.getText());
                    }
                }
                case 21 -> {
                    teamLevelTarget = !teamLevelTarget;
                    adminTarget.setText(teamLevelTarget ? actionTeam : mc.player.getUniqueID().toString());
                }
                case 18 -> {
                    var entry = selected();
                    if (entry != null && actionEntry != null && itemDatabase != null && actionTab == view.tab()
                        && entry.selection().equals(actionEntry.selection()) && entry.sharedLevel() == actionEntry.sharedLevel()
                        && itemDatabase.equals(container.databaseSnapshot.dataCenterId())
                        && actionTeam.equals(container.databaseSnapshot.teamId())) {
                        container.requestItemDownload(actionEntry, actionTeam, itemDatabase, itemMaterialSlot);
                    }
                }
                default -> super.actionPerformed(button);
            }
        }
    }

    private boolean currentDatabaseRow(DataCenterGuiSnapshot.Selection selection) {
        return container.databaseMode && container.databaseSnapshot.contains(drawnDatabase.teamId(), drawnDatabase.dataCenterId(), selection);
    }

    @Override
    public void drawBG(int x, int y, int mouseX, int mouseY, float partialTicks) {
        BackgroundGenerator.draw(xSize, ySize, x, y);
        GlStateManager.color(1, 1, 1, 1);
    }

    @Override
    public void drawFG(int x, int y, int mouseX, int mouseY) {
        if (container.databaseMode) {
            drawDatabaseFG(mouseX, mouseY);
            return;
        }
        var view = container.snapshot;
        fontRenderer.drawString(ResearchGuiText.Title.getLocal(), 8, 8, 0x404040);
        var state = !view.initialized() ? ResearchGuiText.Loading : view.savePending() ? ResearchGuiText.Saving : ResearchGuiText.team(view.teamStatus());
        fontRenderer.drawString(fit(state.getLocal(), columnWidth), 8, 46, 0x404040);
        fontRenderer.drawString(fit(container.feedback.getLocal(), xSize - 16), 8, ySize - 12, 0x404040);
        if (view.initialized() && view.rows().isEmpty())
            fontRenderer.drawString(ResearchGuiText.NoEntries.getLocal(), 8, 62, 0x404040);
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
        if (showingOperations && view.tab() == Tab.RESEARCH) {
            fontRenderer.drawString(fit(ResearchGuiText.ForgetFrom.getLocal(), columnWidth), right, 106, 0x404040);
            if (view.adminPermission() == Decision.ALLOWED) {
                fontRenderer.drawString(fit(ResearchGuiText.AdminTarget.getLocal(), columnWidth), right, 141, 0x404040);
                fontRenderer.drawString(fit(ResearchGuiText.SetLevel.getLocal(), columnWidth), right, 173, 0x404040);
            }
        } else if (showingDescription) {
            for (int i = 0; i < descriptionRows && i + descriptionScroll.getCurrentScroll() < descriptionLines.size(); i++) {
                fontRenderer.drawString(descriptionLines.get(i + descriptionScroll.getCurrentScroll()), right, 106 + i * 10, 0x404040);
            }
            descriptionScroll.drawForegroundLayer(descriptionScroll.getBounds(), new Point(mouseX - guiLeft, mouseY - guiTop));
        } else {
            if (view.details().isEmpty())
                fontRenderer.drawString(ResearchGuiText.NoDetails.getLocal(), right, 106, 0x404040);
            for (int i = 0; i < view.details().size(); i++) {
                var detail = view.details().get(i);
                if (detail.kind() != ResearchGuiSnapshot.Kind.REWARD_CHOICE)
                    fontRenderer.drawString(fit(detailText(entry, detail), columnWidth), right, 106 + i * 11, 0x404040);
            }
            var detailPage = ResearchGuiText.Page.getLocal(view.detailTotal() == 0 ? 0 : view.detailOffset() / ResearchGuiSnapshot.MAX_DETAILS + 1,
                (view.detailTotal() + (long) ResearchGuiSnapshot.MAX_DETAILS - 1) / ResearchGuiSnapshot.MAX_DETAILS);
            fontRenderer.drawString(fit(detailPage, columnWidth - 84), right + 42, ySize - 50, 0x404040);
        }
    }

    private void drawDatabaseFG(int mouseX, int mouseY) {
        var view = container.databaseSnapshot;
        fontRenderer.drawString(ResearchGuiText.Title.getLocal(), 8, 8, 0x404040);
        var state = !view.initialized() ? ResearchGuiText.Loading : view.savePending() ? ResearchGuiText.Saving
            : view.viewPermission() != Decision.ALLOWED ? ResearchGuiText.TeamDenied
            : view.dataCenterId() == null ? ResearchGuiText.NoDatabase : ResearchGuiText.Database;
        fontRenderer.drawString(fit(state.getLocal(), columnWidth), 8, 46, 0x404040);
        fontRenderer.drawString(fit(container.feedback.getLocal(), xSize - 16), 8, ySize - 12, 0x404040);
        if (view.dataCenterId() != null)
            fontRenderer.drawString(fit(ResearchGuiText.DatabaseSummary.getLocal(view.level(),
                view.storedCount(), view.capacity()), columnWidth), right, 8, 0x404040);
        if (view.initialized() && view.rows().isEmpty())
            fontRenderer.drawString(ResearchGuiText.NoEntries.getLocal(), 8, 62, 0x404040);
        var page = ResearchGuiText.Page.getLocal(view.total() == 0 ? 0 : view.offset() / DataCenterGuiSnapshot.MAX_ROWS + 1,
            (view.total() + (long) DataCenterGuiSnapshot.MAX_ROWS - 1) / DataCenterGuiSnapshot.MAX_ROWS);
        fontRenderer.drawString(fit(page, columnWidth - 84), 50, ySize - 29, 0x404040);
        var entry = databaseSelected();
        if (entry == null) return;
        drawIcon();
        fontRenderer.drawString(fit(title(entry.id()), columnWidth - 20), right + 20, 46, 0x404040);
        fontRenderer.drawString(fit(ResearchGuiText.CloudLevel.getLocal(entry.cloudLevel()), columnWidth), right, 62, 0x404040);
        fontRenderer.drawString(fit(ResearchGuiText.DatabaseLevel.getLocal(entry.storedLevel()), columnWidth), right, 73, 0x404040);
        if (showingDescription) {
            for (int i = 0; i < descriptionRows && i + descriptionScroll.getCurrentScroll() < descriptionLines.size(); i++) {
                fontRenderer.drawString(descriptionLines.get(i + descriptionScroll.getCurrentScroll()), right, 106 + i * 10, 0x404040);
            }
            descriptionScroll.drawForegroundLayer(descriptionScroll.getBounds(), new Point(mouseX - guiLeft, mouseY - guiTop));
        } else {
            var lines = databaseTooltip(entry);
            for (int i = 2; i < lines.size(); i++)
                fontRenderer.drawString(fit(lines.get(i), columnWidth), right, 106 + (i - 2) * 11, 0x404040);
        }
    }

    private void drawIcon() {
        if (!iconItem.isEmpty()) itemRender.renderItemAndEffectIntoGUI(iconItem, right, 44);
        else if (iconTexture != null) {
            bindTexture(iconTexture);
            GlStateManager.color(1, 1, 1, 1);
            drawModalRectWithCustomSizedTexture(right, 44, 0, 0, 16, 16, 16, 16);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updateControls();
        super.drawScreen(mouseX, mouseY, partialTicks);
        forgottenLevel.drawTextBox();
        adminTarget.drawTextBox();
        adminLevel.drawTextBox();
        if (container.databaseMode) {
            var database = container.databaseSnapshot;
            if (database.dataCenterId() != null && isPointInRegion(right, 8, columnWidth, 10, mouseX, mouseY)) {
                drawHoveringText(List.of(ResearchGuiText.DatabaseTeam.getLocal(database.teamId()),
                    ResearchGuiText.DatabaseIdentity.getLocal(database.dataCenterId()),
                    ResearchGuiText.DatabaseSummary.getLocal(database.level(), database.storedCount(), database.capacity())), mouseX, mouseY);
            }
            for (int i = 0; i < database.rows().size(); i++) {
                if (isPointInRegion(8, 58 + i * 20, columnWidth, 18, mouseX, mouseY))
                    drawHoveringText(databaseTooltip(database.rows().get(i)), mouseX, mouseY);
            }
            return;
        }
        var view = container.snapshot;
        for (int i = 0; i < view.rows().size(); i++) {
            if (isPointInRegion(8, 58 + i * 20, columnWidth, 18, mouseX, mouseY))
                drawHoveringText(rowTooltip(view.rows().get(i)), mouseX, mouseY);
        }
        var entry = selected();
        if (entry != null && itemDownload.visible && isPointInRegion(right, ySize - 54, columnWidth, 18, mouseX, mouseY)) {
            var definition = ResearchRegistry.INSTANCE.require(entry.id());
            var material = mc.player.getHeldItemMainhand();
            drawHoveringText(List.of(ResearchGuiText.ItemDownload.getLocal(entry.sharedLevel()),
                ResearchGuiText.ItemDownloadMaterial.getLocal(material.isEmpty() ? ResearchGuiText.NoMaterials.getLocal() : material.getDisplayName()),
                (definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.ITEM_ONLY
                    ? ResearchGuiText.ItemDownloadTeamCost : ResearchGuiText.ItemDownloadPersonalCost).getLocal(definition.itemDownloadLevelCost())), mouseX, mouseY);
        }
        if (entry == null || showingDescription) return;
        for (int i = 0; i < view.details().size(); i++) {
            if (!isPointInRegion(right, 106 + i * 11, columnWidth, 11, mouseX, mouseY)) continue;
            var detail = view.details().get(i);
            var tooltip = new ObjectArrayList<String>();
            if (detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE) {
                var group = group(entry, detail.id());
                tooltip.add(groupTitle(entry, detail.id()));
                if (group.description() != null)
                    tooltip.addAll(fontRenderer.listFormattedStringToWidth(group.description().getLocal(), 240));
                tooltip.add(detail.id().toString());
                if (entry.selectedGroup() != null)
                    tooltip.add(ResearchGuiText.FixedChoice.getLocal(entry.selectedGroup()));
            } else tooltip.add(detailText(entry, detail));
            drawHoveringText(tooltip, mouseX, mouseY);
        }
    }

    private ObjectArrayList<String> databaseTooltip(DataCenterGuiSnapshot.Entry entry) {
        var lines = new ObjectArrayList<String>();
        lines.add(title(entry.id()));
        lines.add(entry.id().toString());
        lines.add(ResearchGuiText.CloudLevel.getLocal(entry.cloudLevel()));
        lines.add(ResearchGuiText.DatabaseLevel.getLocal(entry.storedLevel()));
        if (entry.targetLevel() > 0) {
            lines.add(ResearchGuiText.TargetLevel.getLocal(entry.targetLevel()));
            lines.add(ResearchGuiText.Tier.getLocal(entry.requiredTier()));
        } else lines.add(ResearchGuiText.DatabaseStoredOnly.getLocal());
        return lines;
    }

    private ObjectArrayList<String> rowTooltip(Entry entry) {
        var lines = new ObjectArrayList<String>();
        lines.add(title(entry.id()));
        lines.add(entry.id().toString());
        if (entry.blacklisted()) lines.add(ResearchGuiText.Blacklisted.getLocal());
        if (container.snapshot.tab() == Tab.RESEARCH && entry.targetLevel() > 0)
            lines.add(ResearchGuiText.PrerequisitesOnly.getLocal());
        if (entry.manualStatus() != null) lines.add(ResearchGuiText.session(entry.manualStatus()).getLocal());
        if (entry.pauseReason() != null) lines.add(ResearchGuiText.pause(entry.pauseReason()).getLocal());
        if (entry.contributor() != null) lines.add(ResearchGuiText.Contributor.getLocal(entry.contributor()));
        if (entry.claimStatus() != null) lines.add(ResearchGuiText.claim(entry.claimStatus()).getLocal());
        return lines;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (showingOperations && !container.databaseMode) {
            forgottenLevel.mouseClicked(mouseX, mouseY, button);
            adminTarget.mouseClicked(mouseX, mouseY, button);
            adminLevel.mouseClicked(mouseX, mouseY, button);
        }
        if (!showingOperations && showingDescription && descriptionScroll.getBounds().contains(mouseX - guiLeft, mouseY - guiTop)
            && descriptionScroll.onMouseDown(new Point(mouseX - guiLeft, mouseY - guiTop), button)) return;
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char character, int keyCode) throws IOException {
        if (showingOperations && !container.databaseMode
            && (forgottenLevel.textboxKeyTyped(character, keyCode) || adminTarget.textboxKeyTyped(character, keyCode)
            || adminLevel.textboxKeyTyped(character, keyCode))) return;
        super.keyTyped(character, keyCode);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (!showingOperations && showingDescription && descriptionScroll.onMouseDrag(new Point(mouseX - guiLeft, mouseY - guiTop), button))
            return;
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        descriptionScroll.onMouseUp(new Point(mouseX - guiLeft, mouseY - guiTop), button);
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (wheel != 0 && showingDescription && isPointInRegion(right, 106, columnWidth, descriptionRows * 10, mouseX, mouseY)) {
            descriptionScroll.setCurrentScroll(descriptionScroll.getCurrentScroll() - Integer.signum(wheel));
        }
    }
}
