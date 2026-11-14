package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ResearchStationGuiText implements LocalizationEnum {
    Resources, AvailableRemaining, NetworkResources, OccupiedLimit, Accumulated, Queue, Current, TargetLevel,
    WorkingStations, Up, Down, SelectCurrent, Remove, Empty, EmptyQueue, NoMaterials, NoNetworkResources,
    Running, Waiting, Manual, Denied;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".research_station." + name();
    }
}
