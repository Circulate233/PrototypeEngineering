package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.block.IBlockState;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Ordinary external anchors reuse registered structures and the normal builder workflow. */
@ZenRegister
@ZenClass("mods.prototype_eng.structure.Construction")
@SuppressWarnings("unused") // ZenScript invokes these declarations.
public final class ZenConstruction {
    private ZenConstruction() { }

    @ZenMethod
    public static void register(IBlockState anchor, String structure, String translationKey) {
        var state = CraftTweakerMC.getBlockState(anchor);
        var id = new ResourceLocation(structure);
        DefinitionRegistrations.runtime(_ -> StructureConstructionRegistry.INSTANCE.register(state, id, () -> translationKey));
    }

    @ZenMethod
    public static void registerBlock(String block, String structure, String translationKey) {
        var blockId = new ResourceLocation(block);
        var id = new ResourceLocation(structure);
        DefinitionRegistrations.runtime(_ -> {
            if (!Block.REGISTRY.containsKey(blockId)) throw new IllegalArgumentException("Unknown construction anchor: " + blockId);
            for (var state : Block.REGISTRY.getObject(blockId).getBlockState().getValidStates())
                StructureConstructionRegistry.INSTANCE.register(state, id, () -> translationKey);
        });
    }
}
