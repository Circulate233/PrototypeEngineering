package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * Loading-time exact AE item identities; one immutable publication closes registration before world use.
 */
public final class NetworkComputerComponentRegistry {
    public static final NetworkComputerComponentRegistry INSTANCE = new NetworkComputerComponentRegistry();
    private final Object2ObjectOpenHashMap<AEKey, Function<MachineInstance, NetworkComputerComponent.Capture>> registrations = new Object2ObjectOpenHashMap<>();
    @Nullable
    private volatile Frozen frozen;

    /**
     * Registration keys must identify exactly one item; count is absent from the AEItemKey identity.
     */
    public void register(ItemStack item, NetworkComputerComponent provider) {
        Objects.requireNonNull(item);
        Objects.requireNonNull(provider);
        if (item.isEmpty() || item.getCount() != 1) {
            throw new IllegalArgumentException("Computer component registration requires one nonempty item");
        }
        var key = Objects.requireNonNull(AEItemKey.of(item));
        registerKey(key, machine -> provider.capture(key.toStack(), machine));
    }

    /**
     * Internal identity boundary shared by real item registration and detached adapter verification.
     */
    void registerKey(AEKey key, Function<MachineInstance, NetworkComputerComponent.Capture> capture) {
        if (frozen != null) {
            throw new IllegalStateException("Computer component registration has ended");
        }
        Objects.requireNonNull(key);
        Objects.requireNonNull(capture);
        if (registrations.putIfAbsent(key, capture) != null) {
            throw new IllegalArgumentException("Duplicate computer component item");
        }
    }

    public void freeze(NetworkResourceRegistry resources) {
        if (frozen != null) {
            throw new IllegalStateException("Computer component registry already frozen");
        }
        if (!Objects.requireNonNull(resources).isFrozen()) {
            throw new IllegalStateException("Computer components require frozen computing resources");
        }
        frozen = new Frozen(new Object2ObjectOpenHashMap<>(registrations), resources);
        registrations.clear();
    }

    /**
     * Unknown items are ordinary inventory contents and supply nothing.
     */
    NetworkComputerComponent.Capture capture(AEKey key, MachineInstance computer) {
        var current = requireFrozen();
        var provider = current.providers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException("Unregistered computer component item");
        }
        var capture = Objects.requireNonNull(provider.apply(computer), "Computer component supply capture");
        var supplied = capture.resourcesPerItem();
        var validated = new Object2LongOpenHashMap<NetworkResourceType>(supplied.size());
        for (var entry : supplied.object2LongEntrySet()) {
            var type = current.resources.canonical(entry.getKey());
            long amount = entry.getLongValue();
            if (amount < 0) {
                throw new IllegalArgumentException("Negative computer component supply: " + type.id());
            }
            validated.put(type, amount);
        }
        return new NetworkComputerComponent.Capture(validated, capture.current());
    }

    void requireReady() {
        requireFrozen();
    }

    boolean registered(AEKey key) {
        return requireFrozen().providers.containsKey(key);
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Computer component registry is not ready");
        }
        return current;
    }

    private record Frozen(
        Object2ObjectOpenHashMap<AEKey, Function<MachineInstance, NetworkComputerComponent.Capture>> providers,
        NetworkResourceRegistry resources) {
    }
}
