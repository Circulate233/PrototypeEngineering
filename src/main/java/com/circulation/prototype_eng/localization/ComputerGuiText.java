package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ComputerGuiText implements LocalizationEnum {
    Overview, Components, Running, Stopped, ResourceShortage, EnergyShortage,
    Installed, Concurrency, ComponentResources, AvailableDemand, OutputResources, EnergyUse, EnergyAvailable,
    Component, Count, ComponentConcurrency, EnergyPerTick, State, Paused, RecipeRunning, RecipeWaiting,
    EmptyComponents, RequiredPerItem, ProvidedPerItem, None;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".computer." + name();
    }
}
