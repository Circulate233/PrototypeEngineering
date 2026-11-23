package com.circulation.prototype_eng.integration.crafttweaker.structure;

import ae2.block.AEBaseTileBlock;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.structure.StructureAnchorRotation;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.block.IBlockState;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.block.Block;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.function.Function;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Construction")
@SuppressWarnings("unused") // ZenScript invokes these declarations.
public final class ZenConstruction {
    private ZenConstruction() {
    }

    @ZenMethod
    public static void register(IBlockState anchor, String structure, String translationKey) {
        var state = CraftTweakerMC.getBlockState(anchor);
        var id = new ResourceLocation(structure);
        DefinitionRegistrations.runtime(_ -> StructureConstructionRegistry.INSTANCE.register(state, id, () -> translationKey));
    }

    @ZenMethod
    public static void registerFourWay(IBlockState anchor, String structure, String translationKey, String facing) {
        registerRotation(anchor, structure, translationKey, state -> StructureAnchorRotation.fourWay(direction(state, facing)));
    }

    @ZenMethod
    public static void registerSixWay(IBlockState anchor, String structure, String translationKey, String facing) {
        registerRotation(anchor, structure, translationKey, state -> StructureAnchorRotation.sixWay(direction(state, facing)));
    }

    @ZenMethod
    public static void registerTwentyFourWay(IBlockState anchor, String structure, String translationKey) {
        registerRotation(anchor, structure, translationKey, state -> {
            if (state.getBlock() instanceof AEBaseTileBlock<?> block) return StructureAnchorRotation.twentyFourWay(block);
            throw new IllegalArgumentException("Twenty-four-way ZenScript registration requires an AE tile block: " + state);
        });
    }

    private static void registerRotation(IBlockState anchor, String structure, String translationKey,
                                         Function<net.minecraft.block.state.IBlockState, StructureAnchorRotation> rotation) {
        var state = CraftTweakerMC.getBlockState(anchor);
        var id = new ResourceLocation(structure);
        DefinitionRegistrations.runtime(_ -> StructureConstructionRegistry.INSTANCE.register(state, id, () -> translationKey, rotation.apply(state)));
    }

    private static PropertyDirection direction(net.minecraft.block.state.IBlockState state, String name) {
        if (state.getBlock().getBlockState().getProperty(name) instanceof PropertyDirection property) return property;
        throw new IllegalArgumentException("Unknown construction direction property '" + name + "' on " + state.getBlock().getRegistryName());
    }

    @ZenMethod
    public static void registerBlock(String block, String structure, String translationKey) {
        var blockId = new ResourceLocation(block);
        var id = new ResourceLocation(structure);
        DefinitionRegistrations.runtime(_ -> {
            if (!Block.REGISTRY.containsKey(blockId))
                throw new IllegalArgumentException("Unknown construction anchor: " + blockId);

            var elements = Block.REGISTRY.getObject(blockId).getBlockState().getValidStates();
            for (int index = 0; index < elements.size(); index++) {
                var state = elements.get(index);
                StructureConstructionRegistry.INSTANCE.register(state, id, () -> translationKey);
            }
        });
    }
}
