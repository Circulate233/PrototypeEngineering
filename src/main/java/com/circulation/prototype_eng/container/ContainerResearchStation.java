package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.research.ServerResearch;
import com.feed_the_beast.ftblib.lib.data.Universe;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IContainerListener;


/**
 * Queue actions recheck team membership and permission on the server.
 */
public final class ContainerResearchStation extends AEBaseContainer {
    private static final String MANAGE = "research_queue";
    private final MachineControllerHost host;
    private final ResearchStationDisplay display = new ResearchStationDisplay(ResearchRegistry.INSTANCE);
    public final ResearchStationClientView view = new ResearchStationClientView();
    private final Runnable displayRead = this::capture;
    private boolean captureQueued, actionPending;
    @GuiSync(0)
    public ResearchStationPacket update = ResearchStationPacket.EMPTY;
    @GuiSync(1)
    public long elapsedTicks;
    @GuiSync(2)
    public int workingStations;
    @GuiSync(3)
    public boolean canManage;
    private String teamId = "";
    @GuiSync(4)
    public String teamName = "";
    @GuiSync(5)
    public boolean visible;
    @GuiSync(6)
    public boolean manual;

    public ContainerResearchStation(InventoryPlayer player, MachineControllerHost host) {
        super(player, host.blockEntity());
        this.host = host;
        setGuiTitle(host.type().displayName().text());
        registerClientAction(MANAGE, QueueAction.class, 128, action -> {
            if (action == null || action.action == null || action.entryId < 1 || actionPending || !canManage || !current())
                return;
            actionPending = true;
            String expectedTeam = teamId;
            ServerResearch.requestQueueAction((EntityPlayerMP) getPlayer(), expectedTeam, action.entryId, action.action,
                () -> current() && expectedTeam.equals(ServerResearch.stationTeam((EntityPlayerMP) getPlayer(), host)),
                accepted -> {
                    if (!accepted && current()) capture();
                }, () -> actionPending = false);
        });
    }

    private boolean current() {
        return getPlayer().openContainer == this && canInteractWith(getPlayer());
    }

    public void manage(long entryId, ResearchStationView.Action action) {
        sendClientAction(MANAGE, new QueueAction(entryId, action));
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && current()) {
            captureQueued = true;
            var machine = host.machine();
            var device = machine == null ? null : machine.networkDevice();
            if (device == null || !device.readDisplayAtEndTick(displayRead)) host.readGuiAtBoundary(displayRead);
        }
        super.broadcastChanges();
    }

    private void capture() {
        try {
            if (!current()) return;
            var captured = ServerResearch.stationView((EntityPlayerMP) getPlayer(), host);
            if (captured == null) return;
            teamId = captured.teamId();
            teamName = teamId.isEmpty() ? "" : Universe.get().getTeam((short) Integer.parseInt(teamId, 16)).getTitle().getUnformattedText();
            visible = captured.visible();
            canManage = captured.canManage() && !actionPending;
            elapsedTicks = captured.progress() == null ? 0 : captured.progress().elapsedTicks();
            workingStations = captured.workingStations();
            manual = captured.progress() != null && ResearchRegistry.INSTANCE.require(captured.progress().target().researchId())
                                                                             .researchTierForLevel(captured.progress().target().level()) == 0;
            var packet = display.capture(captured);
            if (packet != null) update = packet;
            super.broadcastChanges();
        } finally {
            captureQueued = false;
        }
    }

    @Override
    public void addListener(IContainerListener listener) {
        update = display.fullPacket();
        super.addListener(listener);
    }

    @Override
    public void onServerDataSync(ShortSet fields) {
        if (fields.contains((short) 0)) view.receive(update);
        super.onServerDataSync(fields);
    }

    private record QueueAction(long entryId, ResearchStationView.Action action) {
    }
}
