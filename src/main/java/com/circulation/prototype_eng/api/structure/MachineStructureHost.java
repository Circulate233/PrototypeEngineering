package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Server-owned structure discovery for built-in or external controllers. Methods are observational,
 * may not force-load chunks and must reject calls off the owner thread. No host goes to a worker.
 */
public interface MachineStructureHost extends MachineLifecycleHost, StructureReadSource {
    @Override void requireServerThread();

    /** Null when the exact controller/world is no longer loaded and present or permission is revoked. */
    @Nullable StructureConfiguration structureConfiguration();

    /**
     * Current public contracts at a loaded position. Keep provider and capability object identities
     * stable for their lifetime, including absence; never substitute internal hatch type checks.
     * Built-in discovery uses the unsided capability. A specialized host may choose a defined side.
     */
    StructureHatchBinding hatchesAt(BlockPos position);

    /** Current optional provider identity at a loaded position, stable throughout discovery. */
    @Nullable
    default StructureComponentProvider structureComponentsAt(BlockPos position) {
        requireServerThread();
        return null;
    }
}
