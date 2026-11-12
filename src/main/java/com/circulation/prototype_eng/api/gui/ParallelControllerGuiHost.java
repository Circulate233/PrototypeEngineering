package com.circulation.prototype_eng.api.gui;

import ae2.core.localization.LocalizationEnum;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.ParallelController;

/** Optional GUI responsibility for external controller block entities. */
public interface ParallelControllerGuiHost {
    AEBaseTile blockEntity();

    ParallelController controller();

    LocalizationEnum displayName();

    void setSelectedParallelism(int amount);
}
