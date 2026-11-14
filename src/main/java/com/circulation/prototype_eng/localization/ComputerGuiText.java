package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum ComputerGuiText implements LocalizationEnum {
    Overview, Components, Recipes, Running, Stopped, ResourceShortage, EnergyShortage,
    Installed, Concurrency, ComponentResources, AvailableDemand, OutputResources, EnergyUse, EnergyAvailable,
    Component, Count, ComponentConcurrency, EnergyPerTick, State, Paused, Recipe, Inputs, Outputs,
    RecipeRunning, RecipeWaiting, RecipeCondition, RecipeInput, EmptyComponents, EmptyRecipes,
    RequiredPerItem, ProvidedPerItem, None, StructureNetwork, Partial, Failed, OutputsProgress, TickInputs, EndInputs;

    @Override
    public String getTranslationKey() {
        return "gui." + Tags.MOD_ID + ".computer." + name();
    }
}
