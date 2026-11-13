package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IContainerListener;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only display container. It adds no controller slots or player inventory slots.
 */
public final class ContainerNetworkCenter extends AEBaseContainer {
    private final MachineControllerHost host;
    private final NetworkCenterDisplay display = new NetworkCenterDisplay();
    public final NetworkCenterResourceView resources = new NetworkCenterResourceView();
    private boolean captureQueued;
    private final Runnable displayRead = this::captureDisplay;
    @GuiSync(0)
    public int networkLevel;
    @GuiSync(1)
    public long concurrency;
    @GuiSync(2)
    public int concurrencyUsed;
    @GuiSync(3)
    public NetworkCenterResourcePacket resourceUpdate = NetworkCenterResourcePacket.EMPTY;
    @GuiSync(4)
    public @Nullable NetworkWorkState state;

    public ContainerNetworkCenter(InventoryPlayer player, MachineControllerHost host) {
        super(player, host.blockEntity());
        this.host = host;
        setGuiTitle(host.type().displayName().text());
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && canInteractWith(getPlayer())) {
            var machine = host.machine();
            var device = machine == null ? null : machine.networkDevice();
            if (device != null) captureQueued = device.readDisplayAtEndTick(displayRead);
        }
        super.broadcastChanges();
    }

    private void captureDisplay() {
        try {
            if (getPlayer().openContainer != this || !canInteractWith(getPlayer())) return;
            var machine = host.machine();
            var device = machine == null ? null : machine.networkDevice();
            var view = device == null ? null : device.view();
            state = view == null ? null : view.state();
            var team = view == null ? null : view.team();
            networkLevel = team == null ? 0 : team.centerLevel();
            concurrency = team == null ? 0 : team.concurrencyPerTick();
            concurrencyUsed = team == null ? 0 : view.usedConcurrency();
            var update = team == null ? display.capture(Reference2DoubleMaps.emptyMap(), _ -> 0)
                : display.capture(team.resourcesPerTick(), view.occupiedResource());
            if (update != null) resourceUpdate = update;
            super.broadcastChanges();
        } finally {
            captureQueued = false;
        }
    }

    @Override
    public void addListener(IContainerListener listener) {
        resourceUpdate = display.fullPacket();
        super.addListener(listener);
    }

    @Override
    public void onServerDataSync(ShortSet updatedFields) {
        if (updatedFields.contains((short) 3)) resources.receive(resourceUpdate);
        super.onServerDataSync(updatedFields);
    }

}
