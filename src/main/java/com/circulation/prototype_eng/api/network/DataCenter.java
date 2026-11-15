package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;

/**
 * A network database backed by one attached DataCenterStorage component. Custom controllers may
 * implement this capability without extending the default controller. Declare MachineOwnership.TYPE,
 * the persistent storage component and DEVICE; recreate only the role during restoration.
 * Role callbacks run after the machine lifecycle has been attached.
 */
@SuppressWarnings("DataFlowIssue")
public interface DataCenter {
    ComponentType<MachineInstance, MachineNetworkDevice> DEVICE = MachineNetworkDevice.capturedComponent(
        new ResourceLocation(Tags.MOD_ID, "data_center"), (owner, identity) -> ((DataCenter) owner).networkCapture(identity));

    DataCenterStorage dataCenterStorage();

    /**
     * Owner-side capture of the attached database's real contents and version proof.
     */
    default NetworkRuntime.Capture networkCapture(MachineIdentityRegistry.Registration identity) {
        var storage = dataCenterStorage();
        var contents = storage.capture();
        var snapshot = contents.snapshot();
        return new NetworkRuntime.Capture(new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DATA_CENTER,
            snapshot.level(), ((MachineInstance) this).lifecycle().formed(), identity.loadOrder(),
            Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, snapshot.capacity(), snapshot.storedResearch().size(), snapshot),
            () -> dataCenterStorage() == storage && contents.current().getAsBoolean());
    }
}
