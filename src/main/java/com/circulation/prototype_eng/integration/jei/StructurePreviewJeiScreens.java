package com.circulation.prototype_eng.integration.jei;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewScreens;
import com.circulation.prototype_eng.api.machine.MachineType;

/**
 * Capture the actual immediate HEI screen; returning to a viewer never skips its original recipe page.
 */
@SuppressWarnings("unused") // Retained public HEI opening facade for existing addon callers.
@SideOnly(Side.CLIENT)
public final class StructurePreviewJeiScreens {
    private StructurePreviewJeiScreens() {
    }

    public static boolean open(MachineType machine) {
        return StructurePreviewScreens.open(machine);
    }
}
