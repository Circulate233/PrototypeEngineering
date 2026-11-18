package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ResearchOverviewGuiText implements LocalizationEnum {
    Overview, Picker, PickerTitle, Editor, Databases, HighestStation, DatabaseTierLabel, SelectableCount, PickerRule,
    Back, Close, Collapse, Export, Automatic, ManualLayout, Exported, ExportFailed,
    SwitchAutomatic, SwitchManual, DragResearchHint, SwitchManualHint, AutomaticRecalculated, ExportAllHint,
    ForgetAll, CompleteAll, ForgetAllHint, CompleteAllHint, AllCompleted, AllForgotten,
    Done, Partial, Available, Continue, Researching, Queued, Saved, Locked, Blocked, Special, DeviceShort,
    Materials, MaterialHint, NetworkResources, NetworkHint, Workload, ResearchTime, Stations, Contributor, Nobody,
    ConsumeInventory, ContributeHint, PauseHint, Paused, NoMaterials, NoResources, ManualResources,
    ResearchInfo, TeamLevel, PersonalLevel, TargetLevel, Tier, Duration, Delivery, ItemOnly, DatabaseAndItem,
    ResearchId, Coordinates, StartConditions, Center, Station, None,
    Prerequisites, Mutex, RequiredLevel, Page, Rewards, RewardLevel, ChooseOne, Claimed, ClaimReward, ClaimLater,
    Start, Download, WriteDatabase, Remove, Current, Pick, Forget, ForgetFrom, ConfirmForget, Cancel,
    ForgetLevels, ForgetDependent, ForgetQueue, ForgetKeep, PersonalMissing, Reserved, Denied, SavePending,
    MaxLevel, SpecialStart, SpecialDownload, ItemDownload, NotCompleted, NoCenter, NoDatabase, DatabaseLow,
    StationLow, QueueFull, AlreadyQueued, Downloaded, DownloadReady, CapacityFull, DownloadMutex,
    PickerHigh, PickerPartial, PickerFooter, RemoveHint, CurrentHint, EditorHint, EditorSpecialHint,
    CompletedCount, SelectablePageCount, Progress, CurrentLevels, ActionFailed,
    Selectable, CompletedLevel, DownloadInfo, ThisDatabaseTier, PickerLevelWarning,
    DiskOnly, ManualResearch, ManualNetwork, AutomaticCoordinates,
    Claimable, ClaimLevelHint, ChooseRewardHint, NoRewards, NoNextReward, NodePickedLevel, ForgetLabel,
    DetailProgress, Incomplete, ResearchTierLevel, ManualTierLevel,
    AddPage, AddResearch, EditResearch, PageId, PageTitle, ResearchSearch, ResearchChoice, NoResearchChoices,
    ContentData, ContentLanguage, ContentTarget, SaveContent, ContentSaved, ContentSaveFailed, ContentInvalid,
    EditorHelp, PageCreated, ResearchAdded, UnsavedChanges, DetailLoading, DetailUnavailable,
    EditorSaving, EditorTimedOut, CatalogChanged, ContentChanged, RewardDisplayUnavailable;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".research_overview." + name();
    }
}
