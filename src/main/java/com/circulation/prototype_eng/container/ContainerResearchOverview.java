package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import com.circulation.prototype_eng.research.ServerResearch;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * No player slots or built-in opening item; callers choose their own access entry.
 */
public final class ContainerResearchOverview extends AEBaseContainer {
    private final @Nullable MachineControllerHost database;
    private final ResearchOverviewDisplay display;
    private final boolean editor;
    private @Nullable ICommandSender editorAuthority;
    private boolean captureQueued;
    private boolean actionPending;
    private @Nullable ServerResearch.Overview captured;
    public final ResearchOverviewClientView view = new ResearchOverviewClientView();
    @GuiSync(0)
    public ResearchOverviewPacket update = ResearchOverviewPacket.EMPTY;
    @GuiSync(1)
    public String actionResult = "";

    public ContainerResearchOverview(InventoryPlayer player) {
        this(player, null, false);
    }

    public ContainerResearchOverview(InventoryPlayer player, MachineControllerHost database) {
        this(player, database, false);
    }

    public ContainerResearchOverview(InventoryPlayer player, boolean editor) {
        this(player, null, editor);
    }

    private ContainerResearchOverview(InventoryPlayer player, @Nullable MachineControllerHost database, boolean editor) {
        super(player, database == null ? null : database.blockEntity());
        this.database = database;
        this.editor = editor;
        editorAuthority = editor ? player.player : null;
        display = isClientSide() ? null : new ResearchOverviewDisplay(ResearchRegistry.INSTANCE);
        registerClientAction("overview", Operation.class, 1024, this::operate);
    }

    public boolean databaseMode() {
        return database != null;
    }

    public boolean editorMode() {
        return editor;
    }

    /**
     * Binds a live administrator to this exact server-side editing session, without changing the player's roles.
     */
    public void authorizeEditor(ICommandSender authority) {
        var server = getPlayer().getServer();
        if (isServerSide() && editor && getPlayer().openContainer == this && server != null
            && authority.getServer() == server && authority.canUseCommand(2, "pe_research_edit"))
            editorAuthority = authority;
    }

    public boolean editorAuthorized() {
        if (!editor || isClientSide() || editorAuthority == null || getPlayer().openContainer != this) return false;
        var server = getPlayer().getServer();
        return server != null && editorAuthority.getServer() == server && editorAuthority.canUseCommand(2, "pe_research_edit")
            && (!(editorAuthority instanceof EntityPlayerMP player) || server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player);
    }

    public @Nullable MachineControllerHost database() {
        return database;
    }

    private boolean current() {
        return getPlayer().openContainer == this && canInteractWith(getPlayer());
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return super.canInteractWith(player) && (!editor || isClientSide() || player == getPlayer() && editorAuthorized());
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && current()) {
            captureQueued = true;
            ServerResearch.requestOverview((EntityPlayerMP) getPlayer(), database, editor, this::current, next -> {
                captureQueued = false;
                if (next == null || !current()) return;
                captured = next;
                var packet = display.capture(next.states(), next.queue(), next.databases(), next.current(), next.environment());
                if (packet != null) update = packet;
                super.broadcastChanges();
            });
        }
        super.broadcastChanges();
    }

    @Override
    public void addListener(IContainerListener listener) {
        if (isServerSide()) update = display.fullPacket();
        super.addListener(listener);
    }

    @Override
    public void onServerDataSync(ShortSet fields) {
        if (fields.contains((short) 0)) view.receive(update);
        super.onServerDataSync(fields);
    }

    public void start(ResourceLocation id, int target) {
        send("start", id, target, null, 0, 0, false);
    }

    public void manage(long entryId, ResearchStationView.Action action) {
        send("queue", null, 0, null, entryId, action.ordinal(), false);
    }

    public void reward(ResourceLocation id, int level, ResourceLocation group) {
        send("reward", id, level, group, 0, 0, false);
    }

    public void forget(ResourceLocation id, int forgottenLevel, int expectedBefore) {
        send("forget", id, forgottenLevel, null, 0, expectedBefore, false);
    }

    public void manual(long entryId, boolean enabled) {
        send("manual", null, 0, null, entryId, 0, enabled);
    }

    public void download(ResourceLocation id, int level) {
        send("download", id, level, null, 0, 0, false);
    }

    public void complete(ResourceLocation id, int target) {
        send("complete", id, target, null, 0, 0, false);
    }

    public void choose(ResourceLocation id) {
        send("choose", id, 0, null, 0, 0, false);
    }

    public void returnToDatabase() {
        send("return", null, 0, null, 0, 0, false);
    }

    private void send(String kind, @Nullable ResourceLocation id, int level, @Nullable ResourceLocation group, long entry, int value, boolean enabled) {
        sendClientAction("overview", new Operation(kind, id, level, group, entry, value, enabled));
    }

    private void operate(Operation action) {
        if (action == null || action.kind() == null || !current() || actionPending || captured == null) return;
        if (action.kind().equals("return")) {
            if (database != null) ResearchGuiAccess.returnToDatabase((EntityPlayerMP) getPlayer(), database, null);
            return;
        }
        if (action.kind().equals("choose")) {
            if (database == null || action.id() == null) return;
            var state = state(action.id());
            if (state != null && state.selectable())
                ResearchGuiAccess.returnToDatabase((EntityPlayerMP) getPlayer(), database, action.id());
            return;
        }
        if (database != null || !captured.environment().visible()) return;
        var actor = (EntityPlayerMP) getPlayer();
        String expectedTeam = captured.environment().teamId();
        actionPending = true;
        actionResult = "";
        Runnable finalized = () -> actionPending = false;
        switch (action.kind()) {
            case "start" -> {
                var state = state(action.id());
                if (state == null || !state.canStart() || state.startableTarget() != action.level()) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestStart(actor, action.id(), action.level(), expectedTeam, this::current,
                    result -> actionResult = result.status().name(), finalized);
            }
            case "queue" -> {
                if (!captured.environment().canManage() || action.value() < 0 || action.value() >= ResearchStationView.Action.values().length) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestQueueAction(actor, expectedTeam, action.entryId(), ResearchStationView.Action.values()[action.value()], this::current,
                    accepted -> actionResult = accepted ? "" : "STALE_CONTEXT", finalized);
            }
            case "reward" -> {
                if (action.id() == null || action.group() == null) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestReward(actor, action.id(), action.level(), action.group(), this::current,
                    result -> actionResult = result.status().name(), finalized);
            }
            case "forget" -> {
                var state = state(action.id());
                if (state == null || !state.canForget()) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestForget(actor, action.id(), action.level(), expectedTeam, action.value(), this::current,
                    result -> actionResult = result.name(), finalized);
            }
            case "manual" -> {
                ResearchOverviewPacket.Queue row = null;
                for (int index = 0; index < captured.queue().size(); index++)
                    if (captured.queue().get(index).entryId() == action.entryId()) row = captured.queue().get(index);
                if (row == null) {
                    finalized.run();
                    return;
                }
                if (action.enabled())
                    ServerResearch.requestTakeover(actor, row.id(), row.targetLevel(), expectedTeam, row.entryId(), this::current,
                        result -> actionResult = result.name(), finalized);
                else ServerResearch.requestPauseManual(actor, expectedTeam, row.entryId(), this::current,
                    result -> actionResult = result.name(), finalized);
            }
            case "download" -> {
                var state = state(action.id());
                if (state == null || !state.canDownload() || captured.environment().activeDatabase() == null) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestDataCenterDownload(actor, expectedTeam, captured.environment().activeDatabase(), action.id(), action.level(), this::current,
                    result -> {
                        actionResult = result.storageResult() == null ? result.status().name() : result.storageResult().name();
                        finalized.run();
                    });
            }
            case "complete" -> {
                if (!editor || !captured.environment().editable() || action.id() == null) {
                    finalized.run();
                    return;
                }
                var definition = ResearchRegistry.INSTANCE.require(action.id());
                if (definition.specialOnly()) {
                    ServerResearch.requestEditorSpecial(actor, expectedTeam, captured.environment().activeDatabase(), action.id(), this::current,
                        result -> actionResult = result.name(), finalized);
                } else
                    ServerResearch.requestEditorCompletion(actor, expectedTeam, action.id(), action.level(), this::current,
                        result -> actionResult = result.name(), finalized);
            }
            default -> finalized.run();
        }
    }

    private @Nullable ResearchOverviewPacket.State state(@Nullable ResourceLocation id) {
        if (captured == null) return null;
        for (int index = 0; index < captured.states().size(); index++)
            if (captured.states().get(index).id().equals(id)) return captured.states().get(index);
        return null;
    }

    private record Operation(String kind, @Nullable ResourceLocation id, int level, @Nullable ResourceLocation group,
                             long entryId, int value, boolean enabled) {
    }
}
