package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Explicit role attachment; its runtime state is recreated while MachineOwnership persists identity.
 * Lifecycle callbacks run with declared ownership; an allowed role has its registration.
 */
@SuppressWarnings("DataFlowIssue")
public final class MachineNetworkDevice implements MachineLifecycleListener {
    public static final ComponentType<MachineInstance, MachineNetworkDevice> DEFAULT = capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "default_network_device"), (owner, identity) ->
            new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DEVICE, 0,
                owner.lifecycle().formed(), identity.loadOrder(), Reference2DoubleMaps.emptyMap(),
                NetworkResourceModifier.EMPTY, 0, 0), () -> true));
    private final MachineInstance owner;
    private final ComponentType<MachineInstance, MachineOwnership> ownershipType;
    private final BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkRuntime.Capture> source;
    private final Function<MachineLifecycleHost, NetworkRuntime> runtime;
    private final ComponentType<MachineInstance, MachineNetworkDevice> boundType;
    @Nullable
    private NetworkRuntime.Registration registration;
    private NetworkData networkData;
    private NetworkResourceState networkValues;

    public NetworkData networkData() {
        return networkData;
    }

    public void networkData(NetworkData data) {
        networkData = data;
    }

    public void networkValues(NetworkResourceState values) {
        networkValues = values;
    }

    public NetworkResourceState networkValues() {
        return networkValues;
    }

    public long energyCost(long amount) {
        return networkValues == null ? amount : NetworkResourceState.energy(amount, networkValues.energyMultiplier);
    }

    public double energyMultiplier() {
        return networkValues == null ? 1 : networkValues.energyMultiplier;
    }

    private MachineNetworkDevice(MachineInstance owner, ComponentType<MachineInstance, MachineNetworkDevice> boundType,
                                 ComponentType<MachineInstance, MachineOwnership> ownership,
                                 BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkRuntime.Capture> source,
                                 Function<MachineLifecycleHost, NetworkRuntime> runtime) {
        this.owner = owner;
        this.boundType = boundType;
        this.ownershipType = ownership;
        this.source = source;
        this.runtime = runtime;
        owner.bindNetworkDevice(this);
    }

    public boolean automatic() {
        return boundType == DEFAULT;
    }

    /**
     * Declare MachineOwnership.TYPE and bind this component as recreated in MachineAssembly.
     */
    public static ComponentType<MachineInstance, MachineNetworkDevice> component(ResourceLocation id,
                                                                                 BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source) {
        return component(id, MachineOwnership.TYPE, source, host -> ServerNetworkRuntime.forServer(host.server()));
    }

    /**
     * Custom hosts use the same lifecycle and exact identity rules with their own server runtime.
     */
    public static ComponentType<MachineInstance, MachineNetworkDevice> component(ResourceLocation id,
                                                                                 ComponentType<MachineInstance, MachineOwnership> ownership,
                                                                                 BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkDeviceSnapshot> source,
                                                                                 Function<MachineLifecycleHost, NetworkRuntime> runtime) {
        return capturedComponent(id, ownership, (owner, identity) -> {
            var snapshot = source.apply(owner, identity);
            // Convenience sources are observational: re-sampling checks resources and contents as well as formation.
            return new NetworkRuntime.Capture(snapshot, () -> snapshot.equals(source.apply(owner, identity)));
        }, runtime);
    }

    /**
     * The source must retain the identity and content/configuration version of every backend it reads
     * in its current supplier. Changes to unrelated components do not invalidate that supplier.
     */
    @SuppressWarnings("unused") // Public default-server entry for data-center and custom versioned hosts.
    public static ComponentType<MachineInstance, MachineNetworkDevice> capturedComponent(ResourceLocation id,
                                                                                         BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkRuntime.Capture> source) {
        return capturedComponent(id, MachineOwnership.TYPE, source, host -> ServerNetworkRuntime.forServer(host.server()));
    }

    public static ComponentType<MachineInstance, MachineNetworkDevice> capturedComponent(ResourceLocation id,
                                                                                         ComponentType<MachineInstance, MachineOwnership> ownership,
                                                                                         BiFunction<MachineInstance, MachineIdentityRegistry.Registration, NetworkRuntime.Capture> source,
                                                                                         Function<MachineLifecycleHost, NetworkRuntime> runtime) {
        return new ComponentType<>(id, (owner, type) -> new MachineNetworkDevice(owner, type, ownership, source, runtime), List.of(), Set.of(ownership));
    }

    public boolean allowsNetworkWork() {
        return registration != null && registration.allowsNetworkWork();
    }

    @Nullable
    public NetworkWorkState state() {
        return registration == null ? null : registration.state();
    }

    public @Nullable NetworkRuntime.TeamView view() {
        return registration == null ? null : registration.view();
    }

    public boolean readDisplayAtEndTick(Runnable read) {
        if (registration == null) return false;
        registration.readDisplayAtEndTick(read);
        return true;
    }

    /**
     * Owner-side budget access for this exact attachment and publication; never persisted.
     */
    @Nullable
    public NetworkRuntime.WorkPermit workPermit() {
        var lifecycle = owner.lifecycle();
        lifecycle.host().requireServerThread();
        return allowsNetworkWork() ? registration.workPermit() : null;
    }

    @Override
    @SuppressWarnings("resource") // The runtime belongs to the server owner; this component owns only its registration.
    public void onLoaded(MachineLifecycleEvent.Loaded event) {
        if (automatic() && !owner.requiresNetwork()) return;
        var ownership = owner.components().get(ownershipType);
        var identity = ownership.registration();
        registration = runtime.apply(event.context().host()).register(identity, this::capture);
    }

    @Nullable
    private NetworkRuntime.Capture capture(MachineIdentityRegistry.Registration identity) {
        var lifecycle = owner.lifecycle();
        var host = lifecycle.host();
        host.requireServerThread();
        if (!lifecycle.schedulingAllowed()) {
            return null;
        }
        var structureHost = host instanceof MachineStructureHost structure ? structure : null;
        var configuration = structureHost == null ? null : structureHost.structureConfiguration();
        if (structureHost != null && configuration == null) {
            return null;
        }
        boolean formed = lifecycle.formed();
        var captured = source.apply(owner, identity);
        var snapshot = captured.snapshot();
        return new NetworkRuntime.Capture(snapshot, () -> lifecycle.schedulingAllowed() && lifecycle.formed() == formed
            && (structureHost == null || structureHost.structureConfiguration() == configuration)
            && captured.current().getAsBoolean(), captured.centerPower(), captured.computerWork());
    }

    @Override
    public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
        if (registration != null) {
            registration.revoke();
        }
    }

    @Override
    public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
        if (registration != null) {
            registration.close();
        }
    }
}
