package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.network.resource.ComputationHatch;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentInventory;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Recreated supply reader. Physical contents and restoration stay with the existing public warehouse API.
 * Keep this component attached during a loaded generation; unload before replacing or removing it.
 * Supply operations require an attached machine lifecycle.
 */
@SuppressWarnings("DataFlowIssue")
public final class MachineComputerComponents implements MachineLifecycleListener {
    private final MachineInstance owner;
    private final NetworkComputerComponentRegistry registry;
    @Nullable
    private final Function<MachineInstance, Sources> source;
    private final ObjectArrayList<ComputationHatch> observed = new ObjectArrayList<>();
    private final Runnable changed = () -> dirty = true;
    private volatile boolean dirty = true;
    private MachineHatches bindings;
    private NetworkComputerComponentInventory cached;
    private long compositionVersion;

    /**
     * Compares component identity, quantity and supply; material balances and reservations do not change composition.
     */
    public static boolean sameComposition(NetworkComputerComponentInventory before, NetworkComputerComponentInventory after) {
        var old = before.components();
        var next = after.components();
        if (old.size() != next.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            var a = old.get(i);
            var b = next.get(i);
            if (!a.item().equals(b.item()) || a.count() != b.count()
                || !a.definition().resourcesPerItem().equals(b.definition().resourcesPerItem())
                || !a.definition().requiredResources().equals(b.definition().requiredResources())
                || a.definition().energyPerTick() != b.definition().energyPerTick()
                || a.definition().concurrencyPerItem() != b.definition().concurrencyPerItem()) return false;
        }
        return true;
    }

    public long compositionVersion() {
        return compositionVersion;
    }

    /**
     * Safe for permission proofs; resource input holds do not change installed item composition.
     */
    public boolean compositionCurrent(long version) {
        if (dirty || version != compositionVersion || cached == null) return false;
        for (int index3 = 0; index3 < observed.size(); index3++) {
            var hatch = observed.get(index3);
            if (!hatch.isValid()) return false;
        }
        return cached.providersCurrent();
    }

    /**
     * An item implementation may signal a changed definition at the same owner boundary as its state change.
     */
    @SuppressWarnings("unused") // Component item implementations signal changes to their own dynamic definitions.
    public void invalidateSupply() {
        dirty = true;
    }

    public NetworkComputerComponentInventory installed() {
        requireThread();
        if (source != null)
            return NetworkComputerComponentInventory.capture(registry, owner, source.apply(owner).storages());
        var next = MachineHatches.get(owner);
        if (bindings != next) {
            unbind();
            var seen = new ReferenceOpenHashSet<ComputationHatch>();
            var elements = next.resources();
            for (int index1 = 0; index1 < elements.size(); index1++) {
                var hatch = elements.get(index1);
                if (hatch instanceof ComputationHatch computation && seen.add(computation)) {
                    observed.add(computation);
                    computation.addListener(changed);
                }
            }
            bindings = next;
            dirty = true;
        }
        if (dirty) {
            dirty = false;
            var storages = new ObjectArrayList<SlottedResourceStorage>(observed.size());
            for (int index2 = 0; index2 < observed.size(); index2++) {
                var hatch = observed.get(index2);
                storages.add(hatch.inventory());
            }
            var captured = NetworkComputerComponentInventory.capture(registry, owner, storages);
            if (cached == null || !sameComposition(cached, captured)) compositionVersion++;
            cached = captured;
        }
        return cached;
    }

    @Override
    public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
        installed();
    }

    @Override
    public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
        unbind();
    }

    private void unbind() {
        for (int index = 0; index < observed.size(); index++) {
            var hatch = observed.get(index);
            hatch.removeListener(changed);
        }
        observed.clear();
        bindings = null;
        dirty = true;
    }

    private MachineComputerComponents(MachineInstance owner,
                                      NetworkComputerComponentRegistry registry,
                                      @Nullable Function<MachineInstance, Sources> source) {
        this.owner = owner;
        this.registry = registry;
        this.source = source;
    }

    public static ComponentType<MachineInstance, MachineComputerComponents> component(ResourceLocation id, NetworkComputerComponentRegistry registry) {
        return new ComponentType<>(id, owner -> new MachineComputerComponents(owner, registry, null), List.of());
    }

    /**
     * Special hosts supply stable views of their physical slots, sharing the underlying StorageContext across aliases.
     */
    @SuppressWarnings("unused") // Public external warehouse source.
    public static ComponentType<MachineInstance, MachineComputerComponents> component(ResourceLocation id, NetworkComputerComponentRegistry registry,
                                                                                      Function<MachineInstance, Sources> source) {
        return new ComponentType<>(id, owner -> new MachineComputerComponents(owner, registry, source), List.of());
    }

    /**
     * Pair with MachineNetworkDevice.capturedComponent; numeric supply and logical lease capacity both come from items.
     */
    public NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity, int level, boolean formed,
                                                 Reference2DoubleMap<NetworkResourceType> declared, NetworkResourceModifier modifier) {
        requireThread();
        NetworkComputerComponentInventory installed;
        BooleanSupplier current;
        if (source == null) {
            installed = installed();
            long version = compositionVersion;
            current = () -> compositionCurrent(version);
        } else {
            var sources = source.apply(owner);
            installed = NetworkComputerComponentInventory.capture(registry, owner, sources.storages());
            current = () -> sources.current().getAsBoolean() && installed.current();
        }
        var combined = new Reference2DoubleOpenHashMap<>(declared);
        for (var entry : installed.resourcesPerTick().reference2DoubleEntrySet()) {
            double amount = entry.getDoubleValue();
            NetworkResourceModifier.sum(amount, combined.getDouble(entry.getKey()));
            combined.addTo(entry.getKey(), amount);
        }
        var snapshot = new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.COMPUTER, level, formed,
            identity.loadOrder(), installed.concurrencyPerTick(), combined, modifier, 0, 0);
        return new NetworkRuntime.Capture(snapshot, () -> {
            requireThread();
            return live() && owner.lifecycle().formed() == formed && current.getAsBoolean();
        });
    }

    private boolean live() {
        return owner.lifecycle().schedulingAllowed();
    }

    private void requireThread() {
        owner.lifecycle().host().requireServerThread();
    }

    /**
     * Exact external source bindings with a read-only proof that acquires no additional storage/domain locks.
     * The supplied list is borrowed and must remain unchanged for the lifetime of this capture.
     */
    public record Sources(List<? extends SlottedResourceStorage> storages, BooleanSupplier current) {
    }
}
