package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.network.ComputerInputWork;
import com.circulation.prototype_eng.api.network.resource.ComputerInputRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
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
    public List<ComputerDetailsPacket.Recipe> recipes = List.of();
    public byte[] recipeStates = new byte[0];
    public double[] recipeProgress = new double[0];
    private boolean definitionsRead, captureQueued;
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
            List<ComputerDetailsPacket.Component> componentUpdate = null;
            List<ComputerDetailsPacket.Recipe> recipeUpdate = null;
            if (installed != inventory && (installed == null || !MachineComputerComponents.sameComposition(installed, inventory))) {
                var rows = new ObjectArrayList<ComputerDetailsPacket.Component>(inventory.components().size());
                installedCount = 0;
                for (var item : inventory.components()) {
                    var definition = item.definition();
                    installedCount = Math.addExact(installedCount, item.count());
                    rows.add(new ComputerDetailsPacket.Component(item.item(), item.count(), definition.concurrencyPerItem(),
                        definition.energyPerTick(), definition.resourcesPerItem(), definition.requiredResources()));
                }
                components = componentUpdate = rows;
            }
            installed = inventory;
            if (!definitionsRead) {
                var rows = new ObjectArrayList<ComputerDetailsPacket.Recipe>();
                for (var recipe : computer.supplyRecipes())
                    rows.add(new ComputerDetailsPacket.Recipe(recipe.id(), recipe.unitInputs(), recipe.unitResources(), -1, List.of(), List.of()));
                for (var definition : Objects.requireNonNull(computer.type()).computerInputRecipes()) {
                    var start = new ObjectArrayList<GenericStack>();
                    var ticks = new ObjectArrayList<GenericStack>();
                    var end = new ObjectArrayList<GenericStack>();
                    for (var requirement : definition.inputDefinition().orderedRequirements()) {
                        if (requirement.payload() instanceof ResourceRequirement material) {
                            start.addAll(material.inputs());
                            ticks.addAll(material.inputsPerTick());
                        } else if (requirement.type() == ComputerInputRecipe.END_INPUT
                            && ((RecipeRequirement<?>) requirement.payload()).payload() instanceof ResourceRequirement material) {
                            end.addAll(material.inputs());
                        }
                    }
                    rows.add(new ComputerDetailsPacket.Recipe(definition.recipe().id(), start, Reference2DoubleMaps.emptyMap(), definition.recipe().durationTicks(), ticks, end));
                }
                recipes = recipeUpdate = rows;
                recipeStates = new byte[rows.size()];
                recipeProgress = new double[rows.size()];
                definitionsRead = true;
            }
            ObjectArrayList<ComputerDetailsPacket.RecipeState> changes = null;
            var states = computer.recipeStates();
            var inputWork = computer.components().get(ComputerInputWork.TYPE);
            for (int index = 0; index < recipes.size(); index++) {
                byte state = ComputerController.RECIPE_WAITING;
                double progress = 0;
                if (index < states.length) state = active ? states[index] : ComputerController.RECIPE_WAITING;
                else {
                    var process = Objects.requireNonNull(inputWork).process(index - states.length);
                    if (process != null) {
                        progress = process.state().progress();
                        if (active) state = switch (process.state().result()) {
                            case ProcessResult.Success ignored -> ComputerController.RECIPE_ACTIVE;
                            case ProcessResult.Blocked ignored -> ComputerController.RECIPE_INPUT;
                            case ProcessResult.Failure ignored -> ComputerController.RECIPE_FAILED;
                        };
                    }
                }
                if (state == recipeStates[index] && progress == recipeProgress[index]) continue;
                recipeStates[index] = state;
                recipeProgress[index] = progress;
                if (changes == null) changes = new ObjectArrayList<>();
                changes.add(new ComputerDetailsPacket.RecipeState(index, state, progress));
            }
            if (componentUpdate != null || recipeUpdate != null || changes != null)
                details = new ComputerDetailsPacket(componentUpdate, recipeUpdate, changes == null ? List.of() : changes);
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
        var states = new ObjectArrayList<ComputerDetailsPacket.RecipeState>(recipeStates.length);
        for (int index = 0; index < recipeStates.length; index++)
            states.add(new ComputerDetailsPacket.RecipeState(index, recipeStates[index], recipeProgress[index]));
        details = new ComputerDetailsPacket(components, recipes, states);
        demandUpdate = demands.fullPacket();
        outputUpdate = output.fullPacket();
        super.addListener(listener);
    }

    @Override
    public void onServerDataSync(ShortSet updatedFields) {
        if (updatedFields.contains((short) 8)) {
            if (details.components() != null) components = details.components();
            if (details.recipes() != null) {
                recipes = details.recipes();
                recipeStates = new byte[recipes.size()];
                recipeProgress = new double[recipes.size()];
            }
            for (var changed : details.changed()) {
                recipeStates[changed.index()] = changed.state();
                recipeProgress[changed.index()] = changed.progress();
            }
        }
        if (updatedFields.contains((short) 9)) demandView.receive(demandUpdate);
        if (updatedFields.contains((short) 10)) outputView.receive(outputUpdate);
        super.onServerDataSync(updatedFields);
    }
}
