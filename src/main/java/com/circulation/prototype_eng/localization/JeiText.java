package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

public enum JeiText implements LocalizationEnum {
    Duration, Start, Tick, End, Chance, ChanceAttribute, ComputerSupplyRecipe, ComputerInternalSupply, ComputerNetworkSupply,
    Consume, Produce, ConsumeAt, ProduceAt, ConsumeChance, ProduceChance,
    Weighted, Alternative, Fuzzy, Dynamic, NoCandidates, CheckpointScaled, CheckpointUnscaled,
    Modifiers, AttributeAdded, AttributeRemoved, EnergyInput, EnergyOutput, EnergyInputPerTick, EnergyOutputPerTick, VoidExcess, CustomRequirement, UnsupportedIngredient, QueryIdentityConflict, MissingRenderer, Page, Previous, Next, PatternNoResources, PatternAmountOverflow, PatternCapacity, PatternStructureUnavailable, PatternStructureName;

    private final String translationKey = "jei." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
