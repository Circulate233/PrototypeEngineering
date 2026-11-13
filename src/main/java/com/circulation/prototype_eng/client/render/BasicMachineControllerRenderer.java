package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Only this default implementation chooses the library's concrete controller entity and view channel.
 */
public final class BasicMachineControllerRenderer extends MachineControllerRenderer<MachineControllerBlockEntity> {
    public BasicMachineControllerRenderer() {
        super(MachineControllerBlockEntity.class);
    }

    @SuppressWarnings("resource")
    // The proxy owns this shared view manager; this renderer only queries its exact attachment.
    @Override
    protected ClientMachineRenderSnapshots.@Nullable View view(MachineControllerBlockEntity tile) {
        return snapshots().view(tile.getWorld(), tile, tile.getPos());
    }

    @Override
    protected ComponentData.@Nullable Snapshot animationData(MachineControllerBlockEntity tile, ClientMachineRenderSnapshots.View view) {
        var data = tile.dataSnapshot();
        return data != null && data.generation() == view.snapshot().display().generation() ? data.machine() : null;
    }
}
