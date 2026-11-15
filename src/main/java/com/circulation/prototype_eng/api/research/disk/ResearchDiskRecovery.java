package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Original physical backend plus optional current authority, captured before entering the world writer.
 */
public record ResearchDiskRecovery(ResearchDiskStorage storage, ResearchDataCenters.Target target,
                                   BooleanSupplier physicalCurrent,
                                   @Nullable OwnerTeamResolution membership,
                                   @Nullable ResearchPermissionContext permission,
                                   BooleanSupplier authorized) {
    public ResearchDiskRecovery {
        if ((membership == null) != (permission == null))
            throw new IllegalArgumentException("Disk authority capture is incomplete");
    }
}
