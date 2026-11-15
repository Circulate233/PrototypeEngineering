package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;

import java.util.function.BooleanSupplier;

/**
 * One server-produced additive import candidate. Material, output and database preparation remain
 * borrowed read-only. The click admits this exact callback result instead of evaluating it again.
 */
public record ResearchItemImportPreview(ResearchDownloadMaterial material, ResearchItemImportEvaluation output,
                                        ResearchDiskImport.Preparation preparation, ResearchDataCenters.Target target,
                                        boolean machineInventory, BooleanSupplier current) {
    public boolean ready() {
        return preparation.result().accepted();
    }
}
