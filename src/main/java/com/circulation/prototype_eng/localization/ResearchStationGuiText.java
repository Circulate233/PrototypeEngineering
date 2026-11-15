package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ResearchStationGuiText implements LocalizationEnum {
    Resources, AvailableRemaining, NetworkResources, OccupiedLimit, Queue,
    Up, Down, SelectCurrent, Remove, Empty, EmptyQueue, NoMaterials, NoNetworkResources,
    Running, Waiting, Manual, Denied, Workload, InProgress, Completed;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".research_station." + name();
    }
}
