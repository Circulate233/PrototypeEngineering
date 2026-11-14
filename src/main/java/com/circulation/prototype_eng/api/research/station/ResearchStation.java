package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;

/**
 * Research-station capability. Rates and upkeep are fixed for the loaded generation; rate maps are
 * borrowed read-only with zero as their default return value. Levels are positive, upkeep and numeric
 * rates are nonnegative. Station definitions choose their own rates without a resource-type cap.
 * Missing resource types contribute zero. Custom runtimes may implement this
 * interface and declare DEVICE with MachineOwnership.TYPE without extending the default controller.
 * Role callbacks run after the machine lifecycle has been attached.
 */
@SuppressWarnings("DataFlowIssue")
public interface ResearchStation {
    ComponentType<MachineInstance, MachineNetworkDevice> DEVICE = MachineNetworkDevice.capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "research_station"), (owner, identity) ->
            new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.RESEARCH_STATION,
                ((ResearchStation) owner).researchLevel(), owner.lifecycle().formed(),
                identity.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));

    int researchLevel();

    Reference2DoubleMap<NetworkResourceType> researchRates();

    long energyPerTick();
}
