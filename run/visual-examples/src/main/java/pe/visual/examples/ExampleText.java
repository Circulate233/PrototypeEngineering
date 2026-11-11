package pe.visual.examples;

import ae2.core.localization.LocalizationEnum;

public enum ExampleText implements LocalizationEnum {
    Press, Mixer, Sorter, CustomPage, Input, Output, Kit, Charged, NoEnergy, Drained, NoEnergyOutput,
    Catalog, Wide, Choices, Amounts, Tick, Energy, Parallel, Cross, Structure, LongText,
    BoundaryKit, NoResourceInput, UnknownRecipe, Stocked, StockPartial,
    UnknownMachine, BuildBlocked, Built, BuildFailed;

    @Override
    public String getTranslationKey() {
        return "pe_visual_examples." + name();
    }
}
