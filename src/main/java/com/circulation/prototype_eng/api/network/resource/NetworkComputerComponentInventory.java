package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Committed physical slots, including extraction holds; neither available balances nor pending outputs supply items.
 */
public final class NetworkComputerComponentInventory {
    private final List<StorageContext> contexts;
    private final LongArrayList revisions;
    private final List<BooleanSupplier> providers;
    private final Reference2DoubleMap<NetworkResourceType> resources;
    private final boolean capturedValid;
    private final List<Installed> components;
    private final long concurrency;

    private NetworkComputerComponentInventory(List<StorageContext> contexts, LongArrayList revisions,
                                              List<BooleanSupplier> providers, Reference2DoubleMap<NetworkResourceType> resources, boolean capturedValid, List<Installed> components) {
        this.contexts = contexts;
        this.revisions = revisions;
        this.providers = providers;
        this.resources = resources;
        this.capturedValid = capturedValid;
        this.components = components;
        long total = 0;
        for (var component : components)
            total = Math.addExact(total, Math.multiplyExact(component.count(), component.definition().concurrencyPerItem()));
        concurrency = total;
    }

    public static NetworkComputerComponentInventory capture(NetworkComputerComponentRegistry registry, MachineInstance computer,
                                                            Collection<? extends SlottedResourceStorage> sources) {
        Objects.requireNonNull(registry).requireReady();
        Objects.requireNonNull(computer);
        var storages = new ObjectArrayList<SlottedResourceStorage>(sources.size());
        var seen = new ReferenceOpenHashSet<StorageContext>(sources.size());
        for (var storage : Objects.requireNonNull(sources)) {
            var context = Objects.requireNonNull(Objects.requireNonNull(storage).context());
            if (seen.add(context)) {
                storages.add(storage);
            }
        }
        storages.sort(Comparator.comparingLong(storage -> storage.context().order()));
        var contexts = new ObjectArrayList<StorageContext>(storages.size());
        storages.forEach(storage -> contexts.add(storage.context()));
        var installed = new AEKeyLinkedOpenHashMap(6);
        var revisions = new LongArrayList(storages.size());
        boolean capturedValid = true;
        StorageContext.lockAll(contexts);
        try {
            for (var storage : storages) {
                var context = storage.context();
                if (!context.isValid() || !storage.isValid()) {
                    capturedValid = false;
                    break;
                }
                revisions.add(context.revision());
                for (int slot = 0; slot < storage.size(); slot++) {
                    var stack = storage.getStack(slot);
                    if (stack == null) {
                        continue;
                    }
                    if (stack.amount() <= 0) {
                        throw new IllegalArgumentException("Installed component quantity must be positive");
                    }
                    if (!registry.registered(stack.what())) {
                        continue;
                    }
                    long previous = installed.getLong(stack.what());
                    if (stack.amount() > Long.MAX_VALUE - previous) {
                        throw new ArithmeticException("Installed component count overflow");
                    }
                    installed.addTo(stack.what(), stack.amount());
                }
            }
        } finally {
            unlock(contexts);
        }
        if (!capturedValid) {
            installed.clear();
        }
        var providers = new ObjectArrayList<BooleanSupplier>();
        var components = new ObjectArrayList<Installed>();
        var resources = calculate(registry, computer, installed, providers, components);
        return new NetworkComputerComponentInventory(contexts, revisions, providers, resources, capturedValid, components);
    }

    private static Reference2DoubleMap<NetworkResourceType> calculate(NetworkComputerComponentRegistry registry, MachineInstance computer, AEKey2LongMap installed,
                                                                      List<BooleanSupplier> providers, List<Installed> components) {
        var totals = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        installed.forEachEntry((key, count) -> {
            var captured = registry.capture(key, computer);
            providers.add(captured.current());
            components.add(new Installed(key, count, captured));
            for (var entry : captured.resourcesPerItem().reference2DoubleEntrySet()) {
                double perItem = entry.getDoubleValue();
                if (perItem == 0) {
                    continue;
                }
                double amount = NetworkResourceModifier.product(perItem, count);
                NetworkResourceModifier.sum(amount, totals.getDouble(entry.getKey()));
                totals.addTo(entry.getKey(), amount);
            }
        });
        return totals;
    }

    private static void unlock(List<StorageContext> contexts) {
        for (int index = contexts.size() - 1; index >= 0; index--) {
            contexts.get(index).unlockDeferred();
        }
    }

    public Reference2DoubleMap<NetworkResourceType> resourcesPerTick() {
        return resources;
    }

    public long concurrencyPerTick() {
        return concurrency;
    }

    /**
     * Read-only installed identities and their captured component definitions.
     */
    public List<Installed> components() {
        return components;
    }

    /**
     * Safe inside a foreign storage fence: version proofs acquire no physical storage locks or resample providers.
     */
    public boolean current() {
        if (!capturedValid || !currentStorages()) return false;
        return providersCurrent() && currentStorages();
    }

    /**
     * Event-driven hosts prove installed item identity separately from unrelated resource balances.
     */
    public boolean providersCurrent() {
        for (var provider : providers) if (!provider.getAsBoolean()) return false;
        return true;
    }

    public record Installed(AEKey item, long count, NetworkComputerComponent.Capture definition) {
    }

    private boolean currentStorages() {
        for (int index = 0; index < contexts.size(); index++) {
            if (!contexts.get(index).current(revisions.getLong(index))) {
                return false;
            }
        }
        return true;
    }
}
