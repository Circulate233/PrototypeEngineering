package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * A structure that supplies its team's network level and resources. Custom machine runtimes may implement this
 * interface without inheriting the default controller. The positive level and nonnegative FE upkeep
 * are fixed for the loaded generation.
 * Declare MachineOwnership.TYPE and DEVICE, and bind DEVICE as recreated in the machine assembly.
 */
public interface NetworkCenter {
    ComponentType<MachineInstance, MachineNetworkDevice> DEVICE = MachineNetworkDevice.capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "network_center"), (owner, identity) ->
            new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER,
                ((NetworkCenter) owner).networkLevel(), Objects.requireNonNull(owner.lifecycle()).formed(),
                identity.loadOrder(), ((NetworkCenter) owner).providedConcurrency(), ((NetworkCenter) owner).networkResources(), NetworkResourceModifier.EMPTY, 0, 0), () -> true,
                owner.energyPerTick() == 0 ? null : owner::reservePower));

    long energyPerTick();

    int networkLevel();

    long providedConcurrency();

    /**
     * Fixed, zero-default, read-only double resource contribution; concurrency is supplied separately.
     */
    default Reference2DoubleMap<NetworkResourceType> networkResources() {
        return Reference2DoubleMaps.emptyMap();
    }
}
