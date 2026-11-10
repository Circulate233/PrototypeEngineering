package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum JeiText implements LocalizationEnum {
    Input, Output, Duration, Definition, Start, Tick, End, Amount, Chance, ChanceAttribute,
    Consume, Produce, ConsumeAt, ProduceAt, ConsumeChance, ProduceChance,
    Weighted, Alternative, Fuzzy, Dynamic, NoCandidates, CheckpointScaled, CheckpointUnscaled,
    Modifiers, Attribute, AttributeAdded, AttributeRemoved, EnergyInput, EnergyOutput, EnergyInputPerTick, EnergyOutputPerTick, VoidExcess, CustomRequirement, Runtime,
    UnsupportedIngredient, QueryIdentityConflict, MissingRenderer, Page, Previous, Next, More, Machines,
    PatternNoResources, PatternAmountOverflow, PatternCapacity, PatternStructureUnavailable, PatternStructureName;

    private final String translationKey = "jei." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
