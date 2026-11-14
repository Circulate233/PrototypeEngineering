package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentInventory;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IContainerListener;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;

public final class ContainerComputer extends AEBaseContainer {
    private final MachineControllerHost host;
    private final NetworkCenterDisplay demands = new NetworkCenterDisplay(), output = new NetworkCenterDisplay();
    public final NetworkCenterResourceView demandView = new NetworkCenterResourceView(), outputView = new NetworkCenterResourceView();
    private final Runnable displayRead = this::capture;
    private final ToDoubleFunction<NetworkResourceType> available = this::available;
    private final ReferenceOpenHashSet<StorageContext> energySources = new ReferenceOpenHashSet<>();
    private NetworkComputerComponentInventory installed;
    public List<ComputerDetailsPacket.Component> components = List.of();
    private boolean captureQueued;
    @GuiSync(0)
    public @Nullable NetworkWorkState networkState;
    @GuiSync(1)
    public ComputerController.SupplyState supplyState = ComputerController.SupplyState.WAITING;
    @GuiSync(2)
    public long concurrency;
    @GuiSync(3)
    public long concurrencyLimit;
    @GuiSync(4)
    public long energyPaid;
    @GuiSync(5)
    public long energyRequired;
    @GuiSync(6)
    public long energyAvailable;
    @GuiSync(7)
    public long installedCount;
    @GuiSync(8)
    public ComputerDetailsPacket details = ComputerDetailsPacket.EMPTY;
    @GuiSync(9)
    public NetworkCenterResourcePacket demandUpdate = NetworkCenterResourcePacket.EMPTY;
    @GuiSync(10)
    public NetworkCenterResourcePacket outputUpdate = NetworkCenterResourcePacket.EMPTY;
    @GuiSync(11)
    public boolean formed;

    public ContainerComputer(InventoryPlayer player, MachineControllerHost host) {
        super(player, host.blockEntity());
        this.host = host;
        setGuiTitle(host.type().displayName().text());
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && !captureQueued && canInteractWith(getPlayer())) {
            captureQueued = true;
            var machine = host.machine();
            var device = machine == null ? null : machine.networkDevice();
            if (device == null || !device.readDisplayAtEndTick(displayRead)) host.readGuiAtBoundary(displayRead);
        }
        super.broadcastChanges();
    }

    private double available(NetworkResourceType resource) {
        return supplyState == ComputerController.SupplyState.WAITING ? 0
            : ((ComputerController) Objects.requireNonNull(host.machine())).availableForComponents(resource);
    }

    private void capture() {
        try {
            if (getPlayer().openContainer != this || !canInteractWith(getPlayer())) return;
            if (!(host.machine() instanceof ComputerController computer)) return;
            formed = host.formed();
            var device = computer.networkDevice();
            networkState = device == null ? null : device.state();
            boolean active = networkState == NetworkWorkState.ACTIVE_DEVICE;
            supplyState = active ? computer.supplyState() : ComputerController.SupplyState.WAITING;
            concurrency = active ? computer.providedConcurrency() : 0;
            concurrencyLimit = computer.concurrencyLimit();
            var inventory = computer.installedInventory();
            energyRequired = computer.energyRequired();
            energyPaid = active ? computer.energyPaid() : 0;
            energyAvailable = 0;
            energySources.clear();
            for (var input : MachineHatches.get(computer).energyInputs()) {
                var context = input.context();
                if (!energySources.add(context)) continue;
                context.lock();
                try {
                    energyAvailable = Math.addExact(energyAvailable, input.energySnapshot().availableEnergy());
                } finally {
                    context.unlock();
                }
            }
            if (installed != inventory && (installed == null || !MachineComputerComponents.sameComposition(installed, inventory))) {
                var rows = new ObjectArrayList<ComputerDetailsPacket.Component>(inventory.components().size());
                installedCount = 0;
                for (var item : inventory.components()) {
                    var definition = item.definition();
                    installedCount = Math.addExact(installedCount, item.count());
                    rows.add(new ComputerDetailsPacket.Component(item.item(), item.count(), definition.concurrencyPerItem(),
                        definition.energyPerTick(), definition.resourcesPerItem(), definition.requiredResources()));
                }
                components = rows;
                details = new ComputerDetailsPacket(rows);
            }
            installed = inventory;
            var demand = demands.capture(active ? computer.componentDemand() : inventory.requiredResources(), available);
            if (demand != null) demandUpdate = demand;
            var produced = output.capture(active ? computer.providedResources() : Reference2DoubleMaps.emptyMap(), _ -> 0);
            if (produced != null) outputUpdate = produced;
            super.broadcastChanges();
        } finally {
            captureQueued = false;
        }
    }

    @Override
    public void addListener(IContainerListener listener) {
        details = new ComputerDetailsPacket(components);
        demandUpdate = demands.fullPacket();
        outputUpdate = output.fullPacket();
        super.addListener(listener);
    }

    @Override
    public void onServerDataSync(ShortSet updatedFields) {
        if (updatedFields.contains((short) 8)) components = details.components();
        if (updatedFields.contains((short) 9)) demandView.receive(demandUpdate);
        if (updatedFields.contains((short) 10)) outputView.receive(outputUpdate);
        super.onServerDataSync(updatedFields);
    }
}
