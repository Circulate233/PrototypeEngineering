package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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
     * Registration uses AEItemKey identity; the supplied stack count does not affect the component definition.
     */
    @SuppressWarnings("DataFlowIssue")
    public void register(ItemStack item, NetworkComputerComponent provider) {
        if (item.isEmpty()) {
            throw new IllegalArgumentException("Computer component registration requires a nonempty item");
        }
        var key = AEItemKey.of(item);
        registerKey(key, machine -> provider.capture(key.toStack(), machine));
    }

    /**
     * Internal identity boundary shared by real item registration and detached adapter verification.
     */
    void registerKey(AEKey key, Function<MachineInstance, NetworkComputerComponent.Capture> capture) {
        if (frozen != null) {
            throw new IllegalStateException("Computer component registration has ended");
        }
        if (registrations.putIfAbsent(key, capture) != null) {
            throw new IllegalArgumentException("Duplicate computer component item");
        }
    }

    public void freeze() {
        if (frozen != null) {
            throw new IllegalStateException("Computer component registry already frozen");
        }
        frozen = new Frozen(registrations);
    }

    /**
     * Unknown items are ordinary inventory contents and supply nothing.
     */
    NetworkComputerComponent.Capture capture(AEKey key, MachineInstance computer) {
        var current = requireFrozen();
        var provider = current.providers.get(key);
        var itemProvider = key instanceof AEItemKey item && item.getItem() instanceof NetworkComputerComponent component ? component : null;
        if (provider == null && itemProvider == null) {
            throw new IllegalArgumentException("Unregistered computer component item");
        }
        return itemProvider == null ? provider.apply(computer)
            : itemProvider.capture(((AEItemKey) key).toStack(), computer);
    }

    void requireReady() {
        requireFrozen();
    }

    boolean registered(AEKey key) {
        return key instanceof AEItemKey item && item.getItem() instanceof NetworkComputerComponent || requireFrozen().providers.containsKey(key);
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Computer component registry is not ready");
        }
        return current;
    }

    private record Frozen(
        Object2ObjectOpenHashMap<AEKey, Function<MachineInstance, NetworkComputerComponent.Capture>> providers) {
    }
}
