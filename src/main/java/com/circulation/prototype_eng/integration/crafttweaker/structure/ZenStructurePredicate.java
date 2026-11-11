package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.block.IBlockState;
import crafttweaker.api.data.IData;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.mc1120.data.NBTConverter;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Predicate")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenStructurePredicate(StructurePredicate value) {
    public ZenStructurePredicate {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenStructurePredicate block(String id) {
        var name = new ResourceLocation(id);
        if (!Block.REGISTRY.containsKey(name)) throw new IllegalArgumentException("Unknown structure block: " + id);
        return new ZenStructurePredicate(StructurePredicates.blocks(List.of(Block.REGISTRY.getObject(name))));
    }

    @ZenMethod
    public static ZenStructurePredicate state(IBlockState state) {
        return new ZenStructurePredicate(StructurePredicates.state(CraftTweakerMC.getBlockState(Objects.requireNonNull(state))));
    }

    @ZenMethod
    public static ZenStructurePredicate nbt(IData expected) {
        var tag = NBTConverter.from(Objects.requireNonNull(expected));
        if (!(tag instanceof NBTTagCompound compound)) throw new IllegalArgumentException("Structure NBT requires a map");
        var fields = new Object2ObjectLinkedOpenHashMap<String, String>();
        for (var key : compound.getKeySet()) {
            var field = compound.getTag(key);
            fields.put(key, field instanceof NBTTagString string ? string.getString() : field.toString());
        }
        return new ZenStructurePredicate(StructurePredicates.nbt(fields));
    }

    @ZenMethod
    public static ZenStructurePredicate all(ZenStructurePredicate[] predicates) {
        return new ZenStructurePredicate(StructurePredicates.all(values(predicates)));
    }

    @ZenMethod
    public static ZenStructurePredicate any(ZenStructurePredicate[] predicates) {
        return new ZenStructurePredicate(StructurePredicates.any(values(predicates)));
    }

    @ZenMethod
    public ZenStructurePredicate at(int x, int y, int z) {
        return new ZenStructurePredicate(StructurePredicates.at(new BlockPos(x, y, z), value));
    }

    @ZenMethod
    public static ZenStructurePredicate notAir() {
        return new ZenStructurePredicate(StructurePredicates.notAir());
    }

    @ZenMethod
    public static ZenStructurePredicate hasTileEntity() {
        return new ZenStructurePredicate(StructurePredicates.hasTileEntity());
    }

    @ZenMethod
    public static ZenStructurePredicate resourceHatch() {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.RESOURCE_HATCH, Boolean.TRUE::equals));
    }

    @ZenMethod
    public static ZenStructurePredicate energyInputHatch() {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.ENERGY_INPUT_HATCH, Boolean.TRUE::equals));
    }

    @ZenMethod
    public static ZenStructurePredicate energyOutputHatch() {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.ENERGY_OUTPUT_HATCH, Boolean.TRUE::equals));
    }

    @ZenMethod
    public static ZenStructurePredicate dimension(int dimension) {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.DIMENSION, value -> value == dimension));
    }

    @ZenMethod
    public static ZenStructurePredicate biomes(String[] names) {
        var accepted = new ObjectOpenHashSet<ResourceLocation>();
        for (var name : names) accepted.add(new ResourceLocation(name));
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.BIOME, accepted::contains));
    }

    @ZenMethod
    public static ZenStructurePredicate daytime(boolean expected) {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.DAYTIME, value -> value == expected));
    }

    @ZenMethod
    public static ZenStructurePredicate raining(boolean expected) {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.RAINING, value -> value == expected));
    }

    @ZenMethod
    public static ZenStructurePredicate thundering(boolean expected) {
        return new ZenStructurePredicate(StructurePredicates.test(StructureQueries.THUNDERING, value -> value == expected));
    }

    static List<StructurePredicate> values(ZenStructurePredicate[] predicates) {
        var values = new ObjectArrayList<StructurePredicate>(predicates.length);
        for (var predicate : predicates) values.add(Objects.requireNonNull(predicate).value);
        return values;
    }
}
