package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/**
 * Read-only geometry, material and projection text; shared by GUI, commands and JEI.
 */
public enum StructurePreviewText implements LocalizationEnum {
    Title, Unavailable, Unknown, Follow, Locked, Position, Material,
    North, East, South, West, Usage, Started, Stopped, KeyCategory, KeyLock, KeyRotatePositive, KeyRotateNegative, Orientation, MachineContextMismatch;

    private final String translationKey = "gui." + Tags.MOD_ID + ".StructurePreview." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
