package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewScreens;
import com.circulation.prototype_eng.api.machine.MachineType;

/** Capture the actual immediate HEI screen; returning to a viewer never skips its original recipe page. */
public final class StructurePreviewJeiScreens {
    private StructurePreviewJeiScreens() {}
    public static boolean open(MachineType machine) {
        return StructurePreviewScreens.open(machine);
    }
}
