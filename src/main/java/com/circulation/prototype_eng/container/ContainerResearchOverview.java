package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import com.circulation.prototype_eng.research.ServerResearch;
import com.circulation.prototype_eng.network.InitNetwork;
import com.circulation.prototype_eng.network.ResearchPayload;
import com.circulation.prototype_eng.network.clientbound.ResearchViewPacket;
import com.circulation.prototype_eng.research.content.ResearchViewCodec;
import net.minecraft.network.PacketBuffer;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;

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
    private final ResearchPayload.Assembly incoming = new ResearchPayload.Assembly();
    private @Nullable ResourceLocation detailId;
    private long detailRequest;
    private long contextSequence;
    private boolean detailSent;
    private long sentLayoutRevision = -1;
    private long editRequest;
    public final ResearchOverviewClientView view = new ResearchOverviewClientView();
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
        registerClientAction("detail", DetailRequest.class, 1024, this::selectDetail);
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
            var actor = (EntityPlayerMP) getPlayer();
            var requested = detailId;
            long token = detailRequest;
            var previousTeam = captured == null ? null : captured.environment().teamId();
            ServerResearch.requestOverview(actor, database, editor, requested, previousTeam, this::current, next -> {
                captureQueued = false;
                if (next == null || !current()) return;
                var previous = captured;
                boolean contextChanged = previous == null || !next.visible().equals(previous.visible())
                    || !next.environment().teamId().equals(previous.environment().teamId());
                boolean catalogChanged = previous == null || next.catalogRevision() != previous.catalogRevision();
                if (contextChanged) contextSequence++;
                captured = next;
                var packet = display.capture(next.states(), next.queue(), next.databases(), next.current(), next.environment());
                if (packet != null) sendOverview(actor, packet);
                if (next.environment().editable() && next.catalogRevision() != sentLayoutRevision) {
                    var layout = ServerResearch.editorLayout(actor);
                    InitNetwork.sendResearchView(actor, ResearchViewPacket.EDITOR, windowId, buffer -> {
                        var data = new PacketBuffer(buffer);
                        data.writeVarLong(next.catalogRevision());
                        ResearchViewCodec.writeLayout(data, layout);
                    });
                    sentLayoutRevision = next.catalogRevision();
                }
                if (token == detailRequest && requested != null && (!detailSent || contextChanged || catalogChanged)) sendDetail(actor, requested, token, next);
                super.broadcastChanges();
            });
        }
        super.broadcastChanges();
    }

    private void sendOverview(EntityPlayerMP actor, ResearchOverviewPacket packet) {
        InitNetwork.sendResearchView(actor, ResearchViewPacket.OVERVIEW, windowId, buffer -> {
            var data = new PacketBuffer(buffer);
            data.writeVarLong(contextSequence);
            packet.writeToPacket(data);
        });
    }

    private void selectDetail(DetailRequest request) {
        if (request == null || request.token() <= detailRequest || !current()) return;
        detailRequest = request.token();
        detailId = request.id();
        detailSent = false;
    }

    private void sendDetail(EntityPlayerMP actor, ResourceLocation id, long token, ServerResearch.Overview next) {
        try {
            writeDetail(actor, id, token, next.catalogRevision(), ServerResearch.overviewDetail(actor, id, next));
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot encode research detail for {}", id, failure);
            writeDetail(actor, id, token, next.catalogRevision(), null);
        }
        detailSent = true;
    }

    private void writeDetail(EntityPlayerMP actor, ResourceLocation id, long token, long revision, @Nullable ResearchOverviewDetail detail) {
        InitNetwork.sendResearchView(actor, ResearchViewPacket.DETAIL, windowId, buffer -> {
            var data = new PacketBuffer(buffer);
            data.writeVarLong(contextSequence);
            data.writeResourceLocation(id);
            data.writeVarLong(token);
            data.writeVarLong(revision);
            data.writeBoolean(detail != null);
            if (detail != null) detail.write(data);
        });
    }

    public void receive(ResearchViewPacket packet) {
        if (!isClientSide() || packet.window() != windowId) return;
        incoming.accept(packet.part(), buffer -> {
            var data = new PacketBuffer(buffer);
            switch (packet.kind()) {
                case ResearchViewPacket.OVERVIEW -> {
                    long sequence = data.readVarLong();
                    var update = new ResearchOverviewPacket(data);
                    requireEnd(data);
                    view.receive(sequence, update);
                }
                case ResearchViewPacket.EDITOR -> {
                    if (!editor) throw new IllegalStateException("Editor layout outside editing session");
                    long revision = data.readVarLong();
                    var layout = ResearchViewCodec.readLayout(data);
                    requireEnd(data);
                    view.receiveLayout(revision, layout);
                }
                case ResearchViewPacket.DETAIL -> {
                    long sequence = data.readVarLong();
                    var id = data.readResourceLocation();
                    long token = data.readVarLong();
                    long revision = data.readVarLong();
                    ResearchOverviewDetail detail;
                    try {
                        detail = data.readBoolean() ? ResearchOverviewDetail.read(data) : null;
                        requireEnd(data);
                        if (detail != null && !id.equals(detail.definition().id())) throw new IllegalArgumentException("Research detail identity mismatch");
                    } catch (RuntimeException failure) {
                        view.receiveDetail(sequence, id, token, revision, null);
                        throw failure;
                    }
                    view.receiveDetail(sequence, id, token, revision, detail);
                }
                case ResearchViewPacket.EDIT_RESULT -> {
                    if (!editor) throw new IllegalStateException("Research edit result outside editing session");
                    long token = data.readVarLong(), revision = data.readVarLong();
                    var status = ResearchOverviewClientView.EditStatus.values()[data.readUnsignedByte()];
                    requireEnd(data);
                    if (token == editRequest) view.receiveEditResult(new ResearchOverviewClientView.EditResult(token, revision, status));
                }
                default -> throw new IllegalArgumentException("Invalid overview message kind: " + packet.kind());
            }
        });
    }

    public void requestDetail(ResourceLocation id) {
        changeDetail(id);
    }

    public void clearDetail() {
        changeDetail(null);
    }

    private void changeDetail(@Nullable ResourceLocation id) {
        long token = ++detailRequest;
        view.expectDetail(id, token);
        sendClientAction("detail", new DetailRequest(id, token));
    }

    public long createPage(String id, @Nullable String title) {
        return sendEdit(0, data -> {
            data.writeString(id);
            ResearchViewCodec.writeNullableText(data, title);
        });
    }

    public long addResearch(String page, ResourceLocation id, int x, int y, boolean manual,
                            Map<String, ResearchPage.Position> automaticPositions) {
        return sendEdit(1, data -> {
            data.writeString(page);
            data.writeResourceLocation(id);
            data.writeInt(x);
            data.writeInt(y);
            data.writeBoolean(manual);
            ResearchViewCodec.writePositions(data, automaticPositions);
        });
    }

    public long savePage(String id, ResearchPage page) {
        return sendEdit(2, data -> {
            data.writeString(id);
            data.writeBoolean(page.manual());
            ResearchViewCodec.writePositions(data, page.positions());
        });
    }

    public long saveContent(ResourceLocation id, String json) {
        return sendEdit(3, data -> {
            data.writeResourceLocation(id);
            ResearchPayload.writeText(data, json);
        });
    }

    private long sendEdit(int kind, Consumer<PacketBuffer> encoder) {
        long token = ++editRequest;
        InitNetwork.editResearch(windowId, buffer -> {
            var data = new PacketBuffer(buffer);
            data.writeVarLong(token);
            data.writeVarLong(view.layoutRevision());
            data.writeByte(kind);
            encoder.accept(data);
        });
        return token;
    }

    public void edit(ResearchPayload.Part part) {
        if (!editorAuthorized()) return;
        var actor = (EntityPlayerMP) getPlayer();
        try {
            incoming.accept(part, bytes -> applyEdit(actor, new PacketBuffer(bytes)));
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.warn("Cannot receive research edit from {}", actor.getName(), failure);
        }
    }

    private void applyEdit(EntityPlayerMP actor, PacketBuffer data) {
        long token = data.readVarLong();
        if (token <= editRequest) {
            data.skipBytes(data.readableBytes());
            return;
        }
        editRequest = token;
        long revision;
        try {
            revision = executeEdit(actor, data);
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.warn("Cannot edit research content for {}", actor.getName(), failure);
            sendEditResult(actor, token, -1, ResearchOverviewClientView.EditStatus.ERROR);
            return;
        }
        sendEditResult(actor, token, revision, revision >= 0 ? ResearchOverviewClientView.EditStatus.SAVED : ResearchOverviewClientView.EditStatus.STALE);
    }

    private void sendEditResult(EntityPlayerMP actor, long token, long revision, ResearchOverviewClientView.EditStatus status) {
        InitNetwork.sendResearchView(actor, ResearchViewPacket.EDIT_RESULT, windowId, buffer -> {
            var data = new PacketBuffer(buffer);
            data.writeVarLong(token);
            data.writeVarLong(revision);
            data.writeByte(status.ordinal());
        });
    }

    private long executeEdit(EntityPlayerMP actor, PacketBuffer data) {
        long revision = data.readVarLong();
        int kind = data.readUnsignedByte();
        return switch (kind) {
            case 0 -> {
                var id = data.readString(128);
                var title = ResearchViewCodec.readNullableText(data);
                requireEnd(data);
                yield ServerResearch.editCatalog(actor, revision, catalog -> catalog.createPage(id, title));
            }
            case 1 -> {
                var page = data.readString(128);
                var id = data.readResourceLocation();
                int x = data.readInt(), y = data.readInt();
                boolean manual = data.readBoolean();
                var automaticPositions = ResearchViewCodec.readPositions(data);
                requireEnd(data);
                yield ServerResearch.editCatalog(actor, revision, catalog -> catalog.addResearch(page, id, x, y, manual, automaticPositions));
            }
            case 2 -> {
                var id = data.readString(128);
                boolean manual = data.readBoolean();
                var positions = ResearchViewCodec.readPositions(data);
                requireEnd(data);
                yield ServerResearch.editCatalog(actor, revision, catalog -> catalog.savePage(id, manual, positions));
            }
            case 3 -> {
                var id = data.readResourceLocation();
                var json = ResearchPayload.readText(data);
                requireEnd(data);
                yield ServerResearch.editCatalog(actor, revision, catalog -> catalog.saveData(id, json));
            }
            default -> throw new IllegalArgumentException("Invalid research edit kind: " + kind);
        };
    }

    private static void requireEnd(PacketBuffer data) {
        if (data.isReadable()) throw new IllegalArgumentException("Trailing bytes in overview message");
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
                if (!captured.environment().canManage() || queued(action.entryId()) == null
                    || action.value() < 0 || action.value() >= ResearchStationView.Action.values().length) {
                    finalized.run();
                    return;
                }
                ServerResearch.requestQueueAction(actor, expectedTeam, action.entryId(), ResearchStationView.Action.values()[action.value()], this::current,
                    accepted -> actionResult = accepted ? "" : "STALE_CONTEXT", finalized);
            }
            case "reward" -> {
                if (action.id() == null || action.group() == null || state(action.id()) == null) {
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
                var row = queued(action.entryId());
                if (row == null || row.position() != 0) {
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

    private @Nullable ResearchOverviewPacket.Queue queued(long entryId) {
        if (captured == null) return null;
        var queue = captured.queue();
        for (int index = 0; index < queue.size(); index++) if (queue.get(index).entryId() == entryId) return queue.get(index);
        return null;
    }

    private record Operation(String kind, @Nullable ResourceLocation id, int level, @Nullable ResourceLocation group,
                             long entryId, int value, boolean enabled) {
    }

    private record DetailRequest(@Nullable ResourceLocation id, long token) {
    }
}
