package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum DataCenterGuiText implements LocalizationEnum {
    Stored, Level, Summary, Selected, StoredLevel, Empty, Denied, ReadOnly, Inactive, SavePending,
    Remove, ConfirmRemove, Cancel, RemoveHint, Download, Import, ImportMode, DownloadMode, SelectResearch,
    NoSelection, InsertItem, ResearchTier, ResearchCost, None, TeamLoss, MultipleResearch, Input, Output,
    DownloadPreview, ImportPreview, Busy, TierTooLow, CapacityFull, MaxLevelReached, MutexBlocked,
    ResearchUnavailable, Changed, Completed;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".data_center." + name();
    }
}
