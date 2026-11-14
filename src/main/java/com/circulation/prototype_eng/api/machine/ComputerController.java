package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkComputer;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkSupplyTransaction;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentInventory;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.ComputerInputWork;
import com.circulation.prototype_eng.api.network.NetworkDeviceEvent;
import com.circulation.prototype_eng.api.network.NetworkDeviceEvents;
import com.circulation.prototype_eng.api.network.NetworkResourceState;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * 配方与组件定义在本加载周期内保持不变。网络所属线程准备并提交材料、组件费用与供给。
 */
public class ComputerController extends MachineInstance implements NetworkComputer {
    public static final ComponentType<MachineInstance, MachineComputerComponents> COMPONENTS = MachineComputerComponents.component(
        new ResourceLocation(Tags.MOD_ID, "computer_components"), NetworkComputerComponentRegistry.INSTANCE);
    public static final ComponentType<MachineInstance, MachineNetworkDevice> DEVICE = MachineNetworkDevice.capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "computer"), (owner, identity) -> ((ComputerController) owner).networkCapture(identity));

    private final long concurrencyLimit;
    private final Reference2DoubleMap<NetworkResourceType> limits;
    private final List<ComputerRecipe> recipes;
    private final ControllerEnergy componentEnergy = new ControllerEnergy();
    private long suppliedTick = Long.MIN_VALUE;
    private long suppliedComposition;
    private Reference2DoubleMap<NetworkResourceType> supplied = Reference2DoubleMaps.emptyMap();
    private long suppliedConcurrency;
    private Reference2DoubleMap<NetworkResourceType> componentOffers = Reference2DoubleMaps.emptyMap();
    private Reference2DoubleMap<NetworkResourceType> componentDemand = Reference2DoubleMaps.emptyMap();
    private SupplyState supplyState = SupplyState.WAITING;
    private long energyPaid;
    private long energyRequired;

    public enum SupplyState {WAITING, ACTIVE, RESOURCE_SHORTAGE, ENERGY_SHORTAGE}


    public ComputerController(MachineType type) {
        this(type, Long.MAX_VALUE, Reference2DoubleMaps.emptyMap(), List.of());
    }

    public ComputerController(MachineType type, long concurrencyLimit, Reference2DoubleMap<NetworkResourceType> limits, List<ComputerRecipe> recipes) {
        super(type);
        if (concurrencyLimit < 0) throw new IllegalArgumentException("Negative computer concurrency limit");
        NetworkResourceModifier.validateResources(limits);
        this.concurrencyLimit = concurrencyLimit;
        this.limits = limits;
        this.recipes = recipes;
    }

    private static Reference2DoubleMap<NetworkResourceType> external(Reference2DoubleMap<NetworkResourceType> values) {
        if (values.isEmpty()) return Reference2DoubleMaps.emptyMap();
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        for (var entry : values.reference2DoubleEntrySet())
            if (!entry.getKey().internal() && entry.getDoubleValue() != 0)
                result.put(entry.getKey(), entry.getDoubleValue());
        return result;
    }

    private static void rollback(List<ResourceReservation> tokens, List<? extends ReversibleTransaction> transactions) {
        ResourceTransactions.rollback(tokens, () -> {
            for (int i = transactions.size() - 1; i >= 0; i--) transactions.get(i).rollback();
        });
    }

    /**
     * External hosts may bind a component reader backed by their own registry.
     */
    protected MachineComputerComponents computerComponents() {
        return Objects.requireNonNull(components().get(COMPONENTS));
    }

    public List<NetworkComputerComponentInventory.Installed> installedComponents() {
        return computerComponents().installed().components();
    }

    public NetworkComputerComponentInventory installedInventory() {
        return computerComponents().installed();
    }

    public long concurrencyLimit() {
        return concurrencyLimit;
    }

    public SupplyState supplyState() {
        return supplyState;
    }

    public long energyPaid() {
        return energyPaid;
    }

    public double availableForComponents(NetworkResourceType resource) {
        return componentOffers.getDouble(resource);
    }

    @Override
    public long providedConcurrency() {
        return suppliedConcurrency;
    }

    @Override
    public Reference2DoubleMap<NetworkResourceType> providedResources() {
        return supplied;
    }

    @Override
    public boolean requiresTick() {
        return !Objects.requireNonNull(type()).computerInputRecipes().isEmpty();
    }

    @Override
    public ExecutionMode executionMode() {
        return requiresTick() ? ExecutionMode.MAIN_THREAD : super.executionMode();
    }

    public long energyRequired() {
        return energyRequired;
    }

    public Reference2DoubleMap<NetworkResourceType> componentDemand() {
        return componentDemand;
    }

    @Override
    public boolean requiresNetwork() {
        return true;
    }

    public NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity) {
        var reader = computerComponents();
        reader.installed();
        long composition = reader.compositionVersion();
        if (suppliedComposition != reader.compositionVersion()) {
            var inputWork = components().get(ComputerInputWork.TYPE);
            if (inputWork != null) inputWork.invalidateEffects();
            supplied = Reference2DoubleMaps.emptyMap();
            suppliedConcurrency = 0;
            supplyState = SupplyState.WAITING;
            componentOffers = Reference2DoubleMaps.emptyMap();
            componentDemand = Reference2DoubleMaps.emptyMap();
        }
        return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.COMPUTER, 0,
            Objects.requireNonNull(lifecycle()).formed(), identity.loadOrder(), suppliedConcurrency, supplied, NetworkResourceModifier.EMPTY, 0, 0),
            () -> reader.compositionCurrent(composition), null, this::prepareSupply);
    }

    /**
     * 只能在所属线程边界调用。必须完成或回滚当前事务后才可准备下一次供给。
     */
    public NetworkSupplyTransaction prepareSupply(long tick) {
        var reader = computerComponents();
        var installed = reader.installed();
        if (suppliedTick == tick)
            return new Prepared(tick, reader.compositionVersion(), supplied, suppliedConcurrency, List.of(), List.of(),
                componentOffers, componentDemand, supplyState, energyPaid, energyRequired);
        var transactions = new ObjectArrayList<ReversibleTransaction>();
        var tokens = new ObjectArrayList<ResourceReservation>();
        try {
            var inputWork = components().get(ComputerInputWork.TYPE);
            var values = inputWork != null && inputWork.hasEffects(tick) ? inputWork.effects().copy()
                : new NetworkResourceState(installed.resourcesPerTick(), installed.requiredResources(), installed.concurrencyPerTick());
            var device = networkDevice();
            if (device != null && device.networkData() != null) {
                NetworkDeviceEvents.fire(new NetworkDeviceEvent(this, device.networkData(), values));
                device.networkValues(values);
            }
            limit(values.resources);
            var available = new Reference2DoubleOpenHashMap<>(values.resources);
            var needed = values.required;
            for (var recipe : recipes) {
                long requested = recipe.unitsFor(needed, available);
                if (requested == 0) continue;
                var conversion = recipe.prepare(this, requested);
                if (conversion == null) continue;
                tokens.addAll(conversion.reservations());
                long units = conversion.units();
                for (var entry : recipe.unitResources().reference2DoubleEntrySet()) {
                    double amount = NetworkResourceModifier.product(entry.getDoubleValue(), units);
                    NetworkResourceModifier.sum(available.getDouble(entry.getKey()), amount);
                    available.addTo(entry.getKey(), amount);
                }
            }
            boolean powered = true;
            SupplyState state = SupplyState.ACTIVE;
            for (var demand : needed.reference2DoubleEntrySet()) {
                if (available.getDouble(demand.getKey()) < demand.getDoubleValue()) {
                    powered = false;
                    state = SupplyState.RESOURCE_SHORTAGE;
                    break;
                }
            }
            long energy = networkEnergy(installed.energyPerTick());
            if (powered) {
                var payment = componentEnergy.reserve(this, tick, installed.energyPerTick());
                powered = payment != null;
                if (!powered) state = SupplyState.ENERGY_SHORTAGE;
                if (powered && payment != ControllerEnergy.Payment.FREE) {
                    transactions.add(payment);
                    tokens.addAll(payment.reservations());
                }
            }
            if (powered) {
                for (var demand : needed.reference2DoubleEntrySet())
                    values.resources.addTo(demand.getKey(), -Math.min(demand.getDoubleValue(), values.resources.getDouble(demand.getKey())));
            }
            return new Prepared(tick, reader.compositionVersion(), powered ? external(values.resources) : Reference2DoubleMaps.emptyMap(),
                powered ? Math.min(concurrencyLimit, values.concurrency) : 0, transactions, tokens,
                available, needed, state, powered ? energy : 0, energy);
        } catch (RuntimeException | Error failure) {
            try {
                rollback(tokens, transactions);
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void limit(Reference2DoubleOpenHashMap<NetworkResourceType> values) {
        for (var entry : values.reference2DoubleEntrySet()) {
            double maximum = limits.getOrDefault(entry.getKey(), Double.MAX_VALUE);
            entry.setValue(Math.min(entry.getDoubleValue(), maximum));
        }
    }

    private final class Prepared implements NetworkSupplyTransaction {
        private final long tick;
        private final long composition;
        private final Reference2DoubleMap<NetworkResourceType> result;
        private final long concurrency;
        private final Reference2DoubleMap<NetworkResourceType> offers, demand;
        private final SupplyState state;
        private final long paid, requiredEnergy;
        private final List<? extends ReversibleTransaction> transactions;
        private final List<ResourceReservation> reservations;
        private final long oldTick = suppliedTick;
        private final long oldComposition = suppliedComposition;
        private final Reference2DoubleMap<NetworkResourceType> oldSupply = supplied;
        private final long oldConcurrency = suppliedConcurrency;
        private final Reference2DoubleMap<NetworkResourceType> oldOffers = componentOffers, oldDemand = componentDemand;
        private final SupplyState oldState = supplyState;
        private final long oldPaid = energyPaid, oldRequiredEnergy = energyRequired;
        private Resolution resolution = Resolution.REVERSIBLE;

        private Prepared(long tick, long composition, Reference2DoubleMap<NetworkResourceType> result, long concurrency, List<? extends ReversibleTransaction> transactions,
                         List<ResourceReservation> reservations, Reference2DoubleMap<NetworkResourceType> offers,
                         Reference2DoubleMap<NetworkResourceType> demand, SupplyState state, long paid, long requiredEnergy) {
            this.tick = tick;
            this.composition = composition;
            this.result = result;
            this.concurrency = concurrency;
            this.transactions = transactions;
            this.reservations = reservations;
            this.offers = offers;
            this.demand = demand;
            this.state = state;
            this.paid = paid;
            this.requiredEnergy = requiredEnergy;
        }

        @Override
        public Reference2DoubleMap<NetworkResourceType> resources() {
            return result;
        }

        @Override
        public long concurrency() {
            return concurrency;
        }

        @Override
        public List<ResourceReservation> reservations() {
            return reservations;
        }

        @Override
        public boolean prepareCommit() {
            for (var transaction : transactions) if (!transaction.prepareCommit()) return false;
            return true;
        }

        @Override
        public boolean validateCommit() {
            for (var transaction : transactions) if (!transaction.validateCommit()) return false;
            return true;
        }

        @Override
        public void commit() {
            for (var transaction : transactions) transaction.commit();
            suppliedTick = tick;
            suppliedComposition = composition;
            supplied = result;
            suppliedConcurrency = concurrency;
            componentOffers = offers;
            componentDemand = demand;
            supplyState = state;
            energyPaid = paid;
            energyRequired = requiredEnergy;
        }

        @Override
        public void complete() {
            for (var transaction : transactions) transaction.complete();
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            for (int i = transactions.size() - 1; i >= 0; i--) transactions.get(i).rollback();
            suppliedTick = oldTick;
            suppliedComposition = oldComposition;
            supplied = oldSupply;
            suppliedConcurrency = oldConcurrency;
            componentOffers = oldOffers;
            componentDemand = oldDemand;
            supplyState = oldState;
            energyPaid = oldPaid;
            energyRequired = oldRequiredEnergy;
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }
}
