package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * A structure that supplies its team's network level. Custom machine runtimes may implement this
 * interface without inheriting the default controller. The level is fixed for the loaded generation.
 * Declare MachineOwnership.TYPE and DEVICE, and bind DEVICE as recreated in the machine assembly.
 */
public interface NetworkCenter {
    ComponentType<MachineInstance, MachineNetworkDevice> DEVICE = MachineNetworkDevice.capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "network_center"), (owner, identity) ->
            new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER,
                ((NetworkCenter) owner).networkLevel(), Objects.requireNonNull(owner.lifecycle()).formed(),
                identity.loadOrder(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));

    int networkLevel();
}
