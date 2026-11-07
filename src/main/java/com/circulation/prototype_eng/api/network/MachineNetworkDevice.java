package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Explicit role attachment; its runtime state is recreated while MachineOwnership persists identity. */
public final class MachineNetworkDevice implements MachineLifecycleListener {
    private final MachineInstance owner;
    private final ComponentType<MachineInstance, MachineOwnership> ownershipType;
    private final BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source;
    private final Function<MachineLifecycleHost, NetworkRuntime> runtime;
    @Nullable private ComponentType<MachineInstance, ?> boundType;
    @Nullable private NetworkRuntime attachedRuntime;
    @Nullable private NetworkRuntime.Registration registration;
    @Nullable private MachineIdentityRegistry.Registration identity;
    private boolean loadAttempted;

    /** Declare MachineOwnership.TYPE and bind this component as recreated in MachineAssembly. */
    public static ComponentType<MachineInstance, MachineNetworkDevice> component(ResourceLocation id,
            BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source) {
        return component(id, MachineOwnership.TYPE, source, host -> ServerNetworkRuntime.forServer(host.server()));
    }

    /** Custom hosts use the same lifecycle and exact identity rules with their own server runtime. */
    public static ComponentType<MachineInstance, MachineNetworkDevice> component(ResourceLocation id,
            ComponentType<MachineInstance, MachineOwnership> ownership,
            BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source,
            Function<MachineLifecycleHost, NetworkRuntime> runtime) {
        Objects.requireNonNull(ownership);
        Objects.requireNonNull(source);
        Objects.requireNonNull(runtime);
        return new ComponentType<>(id, owner -> new MachineNetworkDevice(owner, ownership, source, runtime), List.of(), List.of(ownership));
    }

    private MachineNetworkDevice(MachineInstance owner, ComponentType<MachineInstance, MachineOwnership> ownership,
            BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source,
            Function<MachineLifecycleHost, NetworkRuntime> runtime) {
        this.owner = owner;
        this.ownershipType = ownership;
        this.source = source;
        this.runtime = runtime;
        owner.addRecipeWorkGate(this::allowsNetworkWork);
    }

    public boolean allowsNetworkWork() {
        return registration != null && boundType != null && owner.components().get(boundType) == this && registration.allowsNetworkWork();
    }

    @Nullable public NetworkWorkState state() { return registration == null ? null : registration.state(); }

    @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
        requireOwner(event);
        if (loadAttempted) { throw new IllegalStateException("A network device cannot load twice"); }
        loadAttempted = true;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.component() == this) {
                if (boundType != null) { throw new IllegalStateException("Network component has multiple bindings"); }
                boundType = binding.type();
            }
        }
        if (boundType == null) { throw new IllegalStateException("Network component is not installed on its owner"); }
        var ownership = Objects.requireNonNull(owner.components().get(ownershipType), "Network role requires declared ownership");
        identity = Objects.requireNonNull(ownership.registration(), "Ownership must load before its network role");
        if (identity.runtimeIdentity() != owner) { throw new IllegalArgumentException("Network role has foreign ownership"); }
        attachedRuntime = Objects.requireNonNull(runtime.apply(event.context().host()));
        registration = attachedRuntime.register(identity, this::capture);
    }

    @Nullable private NetworkRuntime.Capture capture() {
        var lifecycle = Objects.requireNonNull(owner.lifecycle());
        var host = lifecycle.host();
        host.requireServerThread();
        if (!lifecycle.schedulingAllowed() || owner.components().get(Objects.requireNonNull(boundType)) != this) { return null; }
        var structureHost = host instanceof MachineStructureHost structure ? structure : null;
        var configuration = structureHost == null ? null : structureHost.structureConfiguration();
        if (structureHost != null && configuration == null) { return null; }
        boolean formed = lifecycle.formed();
        var snapshot = Objects.requireNonNull(source.apply(owner, Objects.requireNonNull(identity)), "Network role snapshot");
        if (snapshot.formed() != formed) { throw new IllegalArgumentException("Network source changed physical formation state"); }
        return new NetworkRuntime.Capture(snapshot, () -> lifecycle.schedulingAllowed() && lifecycle.formed() == formed
            && owner.components().get(boundType) == this && (structureHost == null || structureHost.structureConfiguration() == configuration));
    }

    @Override public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
        requireOwner(event);
        if (attachedRuntime != null) { attachedRuntime.revoke(); }
    }

    @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
        requireOwner(event);
        if (registration != null) { registration.close(); }
    }

    private void requireOwner(MachineLifecycleEvent event) {
        if (event.context().machine() != owner) { throw new IllegalArgumentException("Network role belongs to another machine"); }
        event.context().host().requireServerThread();
    }
}
