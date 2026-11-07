package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.energy.EnergyHatchSettings;

/** Optional default FE screen access; external hosts can supply their own storage and settings. */
public interface EnergyHatchGuiHost extends HatchGuiHost, EnergyHatchSettings {
    EnergyHatch storage();
}
