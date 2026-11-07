package com.circulation.prototype_eng.api.gui;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;

/** Physical target and presentation metadata for the default hatch screens. */
public interface HatchGuiHost {
    AEBaseTile blockEntity();

    HatchMode mode();

    HatchTier tier();
}
