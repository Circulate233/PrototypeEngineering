package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum JeiText implements LocalizationEnum {
    Duration, Start, Tick, End, Chance, ChanceAttribute, ComputerUnitResource, ComputerUnit,
    ResearchDownload, ResearchDatabaseImport, ResearchDownloadRequirement, ResearchImportIncrement, ResearchLevelConsumption, ResearchNoConsumption,
    Consume, Produce, ConsumeAt, ProduceAt, ConsumeChance, ProduceChance, NotConsumed, PresentAmount,
    Weighted, Alternative, Fuzzy, CheckpointScaled, CheckpointUnscaled,
    EnergyInput, EnergyOutput, EnergyInputPerTick, EnergyOutputPerTick, VoidExcess, CustomRequirement, UnsupportedIngredient, QueryIdentityConflict, MissingRenderer, Page, Previous, Next, PatternNoResources, PatternAmountOverflow, PatternCapacity, PatternStructureUnavailable, PatternStructureName;

    private final String translationKey = "jei." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
