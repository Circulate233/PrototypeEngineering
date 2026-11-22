package com.circulation.prototype_eng.api.recipe.importing;

import java.util.List;

public final class ImportedPoolTestCatalog {
    private ImportedPoolTestCatalog() {}

    public static List<PoolType> types() {
        return List.of(
            new PoolType("ic2", "BlastFurnaceRecipePool"),
            new PoolType("ic2", "BlockCutterRecipePool"),
            new PoolType("ic2", "CannerBottleRecipePool"),
            new PoolType("ic2", "CannerEnrichRecipePool"),
            new PoolType("ic2", "CentrifugeRecipePool"),
            new PoolType("ic2", "CompressorRecipePool"),
            new PoolType("ic2", "ElectrolyzerRecipePool"),
            new PoolType("ic2", "ExtractorRecipePool"),
            new PoolType("ic2", "FermenterRecipePool"),
            new PoolType("ic2", "MaceratorRecipePool"),
            new PoolType("ic2", "MetalFormerCuttingRecipePool"),
            new PoolType("ic2", "MetalFormerExtrudingRecipePool"),
            new PoolType("ic2", "MetalFormerRollingRecipePool"),
            new PoolType("ic2", "OreWashingRecipePool"),
            new PoolType("minecraft", "FurnaceRecipePool"),
            new PoolType("nuclearcraft", "AlloyFurnaceRecipePool"),
            new PoolType("nuclearcraft", "AssemblerRecipePool"),
            new PoolType("nuclearcraft", "CentrifugeRecipePool"),
            new PoolType("nuclearcraft", "ChemicalReactorRecipePool"),
            new PoolType("nuclearcraft", "CrystallizerRecipePool"),
            new PoolType("nuclearcraft", "DecayHastenerRecipePool"),
            new PoolType("nuclearcraft", "ElectricFurnaceRecipePool"),
            new PoolType("nuclearcraft", "ElectrolyzerRecipePool"),
            new PoolType("nuclearcraft", "EnricherRecipePool"),
            new PoolType("nuclearcraft", "ExtractorRecipePool"),
            new PoolType("nuclearcraft", "FuelReprocessorRecipePool"),
            new PoolType("nuclearcraft", "InfuserRecipePool"),
            new PoolType("nuclearcraft", "IngotFormerRecipePool"),
            new PoolType("nuclearcraft", "ManufactoryRecipePool"),
            new PoolType("nuclearcraft", "MelterRecipePool"),
            new PoolType("nuclearcraft", "MultiblockDistillerRecipePool"),
            new PoolType("nuclearcraft", "MultiblockElectrolyzerRecipePool"),
            new PoolType("nuclearcraft", "MultiblockInfiltratorRecipePool"),
            new PoolType("nuclearcraft", "PressurizerRecipePool"),
            new PoolType("nuclearcraft", "RockCrusherRecipePool"),
            new PoolType("nuclearcraft", "SaltMixerRecipePool"),
            new PoolType("nuclearcraft", "SeparatorRecipePool"),
            new PoolType("nuclearcraft", "SupercoolerRecipePool"),
            new PoolType("tconstruct", "AlloyRecipePool"),
            new PoolType("tconstruct", "BasinCastingRecipePool"),
            new PoolType("tconstruct", "DryingRecipePool"),
            new PoolType("tconstruct", "MeltingRecipePool"),
            new PoolType("tconstruct", "TableCastingRecipePool"),
            new PoolType("thaumcraft", "CrucibleRecipePool"),
            new PoolType("thaumcraft", "InfusionRecipePool"),
            new PoolType("thermalexpansion", "BrewerRecipePool"),
            new PoolType("thermalexpansion", "CentrifugeMobRecipePool"),
            new PoolType("thermalexpansion", "CentrifugeRecipePool"),
            new PoolType("thermalexpansion", "ChargerRecipePool"),
            new PoolType("thermalexpansion", "CompactorCoinRecipePool"),
            new PoolType("thermalexpansion", "CompactorGearRecipePool"),
            new PoolType("thermalexpansion", "CompactorPlateRecipePool"),
            new PoolType("thermalexpansion", "CompactorRecipePool"),
            new PoolType("thermalexpansion", "CrucibleRecipePool"),
            new PoolType("thermalexpansion", "EnchanterEmpoweredRecipePool"),
            new PoolType("thermalexpansion", "EnchanterRecipePool"),
            new PoolType("thermalexpansion", "ExtruderIgneousRecipePool"),
            new PoolType("thermalexpansion", "ExtruderSedimentaryRecipePool"),
            new PoolType("thermalexpansion", "FurnacePyrolysisRecipePool"),
            new PoolType("thermalexpansion", "FurnaceRecipePool"),
            new PoolType("thermalexpansion", "InsolatorRecipePool"),
            new PoolType("thermalexpansion", "InsolatorTreeRecipePool"),
            new PoolType("thermalexpansion", "PrecipitatorRecipePool"),
            new PoolType("thermalexpansion", "PulverizerRecipePool"),
            new PoolType("thermalexpansion", "RefineryPotionRecipePool"),
            new PoolType("thermalexpansion", "RefineryRecipePool"),
            new PoolType("thermalexpansion", "SawmillRecipePool"),
            new PoolType("thermalexpansion", "SmelterRecipePool"),
            new PoolType("thermalexpansion", "TransposerExtractRecipePool"),
            new PoolType("thermalexpansion", "TransposerFillRecipePool"));
    }

    public record PoolType(String modid, String name) {
        public String javaClassName() {
            return "com.circulation.prototype_eng.api.recipe.importing." + modid + "." + name;
        }

        public String zenClassName() {
            return "com.circulation.prototype_eng.integration.crafttweaker.recipe.importing." + modid + ".Zen" + name;
        }
    }
}
