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
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
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
 * Composed computer runtime. The network owner prepares recurring recipes and component costs;
 * only admitted computers commit them. Definitions are borrowed immutable for this loaded generation.
 * The controller has no FE overhead and no independently scheduled recipe work slots or GUI.
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
    private Reference2DoubleMap<NetworkResourceType> recipeSupply = Reference2DoubleMaps.emptyMap();
    private long suppliedConcurrency;

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

    private static void add(Reference2DoubleOpenHashMap<NetworkResourceType> totals, Reference2DoubleMap<NetworkResourceType> values, long count) {
        for (var entry : values.reference2DoubleEntrySet()) {
            double amount = NetworkResourceModifier.product(entry.getDoubleValue(), count);
            NetworkResourceModifier.sum(amount, totals.getDouble(entry.getKey()));
            totals.addTo(entry.getKey(), amount);
        }
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
        return false;
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
            supplied = external(recipeSupply);
            suppliedConcurrency = 0;
        }
        return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.COMPUTER, 0,
            Objects.requireNonNull(lifecycle()).formed(), identity.loadOrder(), suppliedConcurrency, supplied, NetworkResourceModifier.EMPTY, 0, 0),
            () -> reader.compositionCurrent(composition), null, this::prepareSupply);
    }

    /**
     * Owner boundary only. The returned standard transaction owns all acquired recipe and FE holds.
     */
    public NetworkSupplyTransaction prepareSupply(long tick) {
        var reader = computerComponents();
        var installed = reader.installed();
        if (suppliedTick == tick)
            return new Prepared(tick, reader.compositionVersion(), recipeSupply, supplied, suppliedConcurrency, List.of(), List.of());
        var transactions = new ObjectArrayList<ReversibleTransaction>();
        var tokens = new ObjectArrayList<ResourceReservation>();
        try {
            var generated = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            for (var recipe : recipes) {
                var transaction = recipe.prepare(this, tick);
                if (transaction == null) continue;
                if (transaction.result() != ProcessResult.SUCCESS) {
                    ResourceTransactions.rollback(transaction.reservations(), transaction::rollback);
                    continue;
                }
                transactions.add(transaction);
                tokens.addAll(transaction.reservations());
                add(generated, recipe.provided(), 1);
            }
            var total = new Reference2DoubleOpenHashMap<>(generated);
            add(total, installed.resourcesPerTick(), 1);
            limit(total);
            var needed = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            long energy = 0;
            for (var component : installed.components()) {
                add(needed, component.definition().requiredResources(), component.count());
                energy = Math.addExact(energy, Math.multiplyExact(component.definition().energyPerTick(), component.count()));
            }
            boolean powered = true;
            for (var demand : needed.reference2DoubleEntrySet())
                if (total.getDouble(demand.getKey()) < demand.getDoubleValue()) {
                    powered = false;
                    break;
                }
            if (powered) {
                var payment = componentEnergy.reserve(this, tick, energy);
                powered = payment != null;
                if (powered && payment != ControllerEnergy.Payment.FREE) {
                    transactions.add(payment);
                    tokens.addAll(payment.reservations());
                }
            }
            if (powered) {
                for (var demand : needed.reference2DoubleEntrySet())
                    total.addTo(demand.getKey(), -demand.getDoubleValue());
            } else {
                total = new Reference2DoubleOpenHashMap<>(generated);
                limit(total);
            }
            limit(generated);
            return new Prepared(tick, reader.compositionVersion(), generated, external(total),
                powered ? Math.min(concurrencyLimit, installed.concurrencyPerTick()) : 0, transactions, tokens);
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
        private final Reference2DoubleMap<NetworkResourceType> recipes;
        private final Reference2DoubleMap<NetworkResourceType> result;
        private final long concurrency;
        private final List<? extends ReversibleTransaction> transactions;
        private final List<ResourceReservation> reservations;
        private final long oldTick = suppliedTick;
        private final long oldComposition = suppliedComposition;
        private final Reference2DoubleMap<NetworkResourceType> oldRecipes = recipeSupply;
        private final Reference2DoubleMap<NetworkResourceType> oldSupply = supplied;
        private final long oldConcurrency = suppliedConcurrency;
        private Resolution resolution = Resolution.REVERSIBLE;

        private Prepared(long tick, long composition, Reference2DoubleMap<NetworkResourceType> recipes,
                         Reference2DoubleMap<NetworkResourceType> result, long concurrency, List<? extends ReversibleTransaction> transactions,
                         List<ResourceReservation> reservations) {
            this.tick = tick;
            this.composition = composition;
            this.recipes = recipes;
            this.result = result;
            this.concurrency = concurrency;
            this.transactions = transactions;
            this.reservations = reservations;
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
            recipeSupply = recipes;
            supplied = result;
            suppliedConcurrency = concurrency;
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
            recipeSupply = oldRecipes;
            supplied = oldSupply;
            suppliedConcurrency = oldConcurrency;
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }
}
