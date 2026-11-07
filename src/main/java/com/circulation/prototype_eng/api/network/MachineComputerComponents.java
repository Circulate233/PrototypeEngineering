package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentInventory;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/** Recreated supply reader. Physical contents and restoration stay with the existing public warehouse contracts. */
public final class MachineComputerComponents implements MachineLifecycleListener {
    private final MachineInstance owner;
    private final NetworkComputerComponentRegistry registry;
    @Nullable private final Function<MachineInstance, Sources> source;
    @Nullable private ComponentType<MachineInstance, ?> boundType;
    private boolean loadAttempted;

    public static ComponentType<MachineInstance, MachineComputerComponents> component(ResourceLocation id, NetworkComputerComponentRegistry registry) {
        Objects.requireNonNull(registry);
        return new ComponentType<>(id, owner -> new MachineComputerComponents(owner, registry, null), List.of());
    }

    /** Special hosts supply stable views of their physical slots, sharing the underlying StorageContext across aliases. */
    @SuppressWarnings("unused") // Public external warehouse source.
    public static ComponentType<MachineInstance, MachineComputerComponents> component(ResourceLocation id, NetworkComputerComponentRegistry registry,
            Function<MachineInstance, Sources> source) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(source);
        return new ComponentType<>(id, owner -> new MachineComputerComponents(owner, registry, source), List.of());
    }

    private MachineComputerComponents(MachineInstance owner, NetworkComputerComponentRegistry registry,
            @Nullable Function<MachineInstance, Sources> source) {
        this.owner = owner;
        this.registry = registry;
        this.source = source;
    }

    @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
        requireThread();
        if (event.context().machine() != owner || loadAttempted) { throw new IllegalStateException("Computer supply reader cannot load for another or repeated host"); }
        loadAttempted = true;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.component() == this) {
                if (boundType != null) { throw new IllegalStateException("Computer supply reader has multiple bindings"); }
                boundType = binding.type();
            }
        }
        Objects.requireNonNull(boundType, "Computer supply reader is not installed");
    }

    /** Pair with MachineNetworkDevice.capturedComponent; numeric supply and logical lease capacity both come from items. */
    public NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity, int level, boolean formed,
            Object2LongMap<NetworkResourceType> declared, NetworkResourceModifier modifier) {
        requireThread();
        if (!live() || identity.runtimeIdentity() != owner || Objects.requireNonNull(owner.lifecycle()).formed() != formed) {
            throw new IllegalStateException("Computer capture requires its exact live owner and formation");
        }
        var hatches = MachineHatches.get(owner);
        boolean capturedHostsValid = source != null || hatches.resources().stream().allMatch(ResourceHatch::isValid);
        var sources = source == null ? new Sources(capturedHostsValid ? sources(hatches) : List.of(),
            () -> MachineHatches.get(owner) == hatches && hatches.resources().stream().allMatch(ResourceHatch::isValid))
            : Objects.requireNonNull(source.apply(owner), "Computer physical sources capture");
        var installed = NetworkComputerComponentInventory.capture(registry, owner, sources.storages());
        var combined = new Object2LongOpenHashMap<>(NetworkResourceModifier.copyResources(declared));
        for (var entry : installed.resourcesPerTick().object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount > Long.MAX_VALUE - combined.getLong(entry.getKey())) { throw new ArithmeticException("Computer declared and item supply overflow"); }
            combined.addTo(entry.getKey(), amount);
        }
        var snapshot = new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.COMPUTER, level, formed,
            identity.loadOrder(), combined, modifier, 0, 0);
        return new NetworkRuntime.Capture(snapshot, () -> {
            requireThread();
            return capturedHostsValid && live() && Objects.requireNonNull(owner.lifecycle()).formed() == formed
                && sources.current().getAsBoolean() && installed.current() && sources.current().getAsBoolean();
        });
    }

    private List<? extends SlottedResourceStorage> sources(MachineHatches hatches) {
        var result = new ObjectArrayList<SlottedResourceStorage>();
        for (var hatch : hatches.resources()) {
            if (hatch.input() instanceof SlottedResourceStorage input) { result.add(input); }
            if (hatch.output() instanceof SlottedResourceStorage output) { result.add(output); }
        }
        return List.copyOf(result);
    }

    /** Exact external source bindings with a read-only proof that acquires no additional storage/domain locks. */
    public record Sources(List<? extends SlottedResourceStorage> storages, BooleanSupplier current) {
        public Sources {
            storages = List.copyOf(storages);
            Objects.requireNonNull(current);
        }
    }

    private boolean live() {
        return boundType != null && owner.components().get(boundType) == this && Objects.requireNonNull(owner.lifecycle()).schedulingAllowed();
    }

    private void requireThread() { Objects.requireNonNull(owner.lifecycle()).host().requireServerThread(); }
}
