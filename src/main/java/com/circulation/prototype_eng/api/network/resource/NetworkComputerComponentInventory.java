package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Committed physical slots, including extraction holds; neither available balances nor pending outputs supply items.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class NetworkComputerComponentInventory {
    private final List<StorageContext> contexts;
    private final LongArrayList revisions;
    private final Reference2DoubleMap<NetworkResourceType> resources;
    private final boolean capturedValid;
    private final List<Installed> components;
    private final Reference2DoubleOpenHashMap<NetworkResourceType> required = new Reference2DoubleOpenHashMap<>();
    private final long energy;
    private final long concurrency;

    private NetworkComputerComponentInventory(List<StorageContext> contexts, LongArrayList revisions,
                                              NetworkComputerComponentRegistry registry, MachineInstance computer,
                                              AEKey2LongMap installed, boolean capturedValid) {
        this.contexts = contexts;
        this.revisions = revisions;
        this.capturedValid = capturedValid;
        components = new ObjectArrayList<>(installed.size());
        var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        long total = 0, energyTotal = 0;
        for (var entry : installed.object2LongEntrySet()) {
            var key = entry.getKey();
            long count = entry.getLongValue();
            var captured = registry.capture(key, computer);
            components.add(new Installed(key, count, captured));
            total = Math.addExact(total, Math.multiplyExact(count, captured.concurrencyPerItem()));
            energyTotal = Math.addExact(energyTotal, Math.multiplyExact(count, captured.energyPerTick()));
            for (var resource : captured.requiredResources().reference2DoubleEntrySet()) {
                double amount = NetworkResourceModifier.product(resource.getDoubleValue(), count);
                NetworkResourceModifier.sum(amount, required.getDouble(resource.getKey()));
                required.addTo(resource.getKey(), amount);
            }
            for (var resource : captured.resourcesPerItem().reference2DoubleEntrySet()) {
                double amount = NetworkResourceModifier.product(resource.getDoubleValue(), count);
                NetworkResourceModifier.sum(amount, resources.getDouble(resource.getKey()));
                resources.addTo(resource.getKey(), amount);
            }
        }
        this.resources = resources;
        concurrency = total;
        energy = energyTotal;
    }

    public static NetworkComputerComponentInventory capture(NetworkComputerComponentRegistry registry, MachineInstance computer,
                                                            Collection<? extends SlottedResourceStorage> sources) {
        registry.requireReady();
        var storages = new ObjectArrayList<SlottedResourceStorage>(sources.size());
        var seen = new ReferenceOpenHashSet<StorageContext>(sources.size());
        for (var storage : sources) {
            var context = storage.context();
            if (seen.add(context)) {
                storages.add(storage);
            }
        }
        storages.sort(Comparator.comparingLong(storage -> storage.context().order()));
        var contexts = new ObjectArrayList<StorageContext>(storages.size());
        for (int index = 0; index < storages.size(); index++) contexts.add(storages.get(index).context());
        var installed = new AEKeyLinkedOpenHashMap(6);
        var revisions = new LongArrayList(storages.size());
        boolean capturedValid = true;
        StorageContext.lockAll(contexts);
        try {
            for (int index1 = 0; index1 < storages.size(); index1++) {
                var storage = storages.get(index1);
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
        return new NetworkComputerComponentInventory(contexts, revisions, registry, computer, installed, capturedValid);
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
     * Borrow read-only and capture again after composition changes.
     */
    public Reference2DoubleMap<NetworkResourceType> requiredResources() {
        return required;
    }

    public long energyPerTick() {
        return energy;
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
        return providersCurrent();
    }

    /**
     * Event-driven hosts prove installed item identity separately from unrelated resource balances.
     */
    public boolean providersCurrent() {
        for (int index = 0; index < components.size(); index++)
            if (!components.get(index).definition().current().getAsBoolean()) return false;
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
