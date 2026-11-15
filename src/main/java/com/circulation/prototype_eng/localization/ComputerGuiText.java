package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ComputerGuiText implements LocalizationEnum {
    Overview, Components, Running, Stopped, ResourceShortage, EnergyShortage,
    ComponentResources, AvailableDemand, OutputResources, PerTick, EnergyUse, EnergyDemand, EnergyAvailable,
    Component, Count, ComponentConcurrency, EnergyPerTick, State, Paused, RecipeRunning, RecipeWaiting,
    EmptyComponents, None, ConcurrencyLabel, InstalledLabel, InstalledCount, RequiredPerItemLabel, ProvidedPerItemLabel;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".computer." + name();
    }
}
