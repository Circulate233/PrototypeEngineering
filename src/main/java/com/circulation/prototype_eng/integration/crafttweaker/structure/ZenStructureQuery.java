package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.structure.StructureQuery;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/**
 * Reuse this descriptor instance wherever its detached fact is declared or read.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.structure.Query")
@SuppressWarnings("unused") // Script query factory.
public record ZenStructureQuery(StructureQuery<NBTBase> value) {

    @ZenMethod
    public static ZenStructureQuery create(String id, ZenStructureCapture capture) {
        return new ZenStructureQuery(new StructureQuery<>(new ResourceLocation(id), (world, position) -> {
            var data = capture.capture(CraftTweakerMC.getIWorld(world), CraftTweakerMC.getIBlockPos(position));
            return data == null ? null : NBTConverter.from(data);
        }, NBTBase::copy, Objects::equals));
    }
}
