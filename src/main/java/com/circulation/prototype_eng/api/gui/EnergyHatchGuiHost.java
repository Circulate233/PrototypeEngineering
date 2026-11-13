package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.energy.EnergyHatchSettings;
import org.jetbrains.annotations.Nullable;

/**
 * Optional default FE screen access; external hosts can supply their own storage and settings.
 */
public interface EnergyHatchGuiHost extends HatchGuiHost, EnergyHatchSettings {
    /**
     * Primary storage for existing hosts: input when present, otherwise output.
     */
    EnergyHatch storage();

    /**
     * Energy supplied externally and consumed by the machine.
     */
    default @Nullable EnergyHatch inputStorage() {
        return mode().hasInput() ? storage() : null;
    }

    /**
     * Energy produced by the machine and extracted externally.
     */
    default @Nullable EnergyHatch outputStorage() {
        return mode().hasOutput() ? storage() : null;
    }
}
