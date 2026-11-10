package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/** Read-only geometry, material and projection text; shared by GUI, commands and JEI. */
public enum StructurePreviewText implements LocalizationEnum {
    Title, Open, Back, Building, Unavailable, Failed, View3D, Layer, Layers, Reset, Materials,
    Search, SortIssues, SortCount, SortName, Match, Mismatch, Unloaded, Unknown, Scan, Selected,
    Alternatives, Slices, Occurrence, Project, Stop, Follow, Locked, Rotate, Position, Material,
    North, East, South, West, Usage, Started, Stopped, Counts, Issues, FormedPreviewFuture,
    KeyCategory, KeyLock, KeyRotatePositive, KeyRotateNegative, Orientation, ViewOptions, AutoRotate, Wireframe, FormedPreview, MachineContextRequired, MachineContextMismatch;

    private final String translationKey = "gui." + Tags.MOD_ID + ".StructurePreview." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
