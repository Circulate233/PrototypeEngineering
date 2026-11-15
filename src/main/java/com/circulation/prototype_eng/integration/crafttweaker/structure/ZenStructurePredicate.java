package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
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
import java.util.Set;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Predicate")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenStructurePredicate(StructurePredicate value) {

    /**
     * The common base facts are available without additional query declarations.
     */
    @ZenMethod
    public static ZenStructurePredicate custom(ZenStructureValidator validator) {
        return custom(new String[]{"biomeName", "biomeId", "dimension", "worldTime", "totalWorldTime",
            "daytime", "raining", "thundering"}, new int[][]{{0, 0, 0}}, new int[0][], validator);
    }

    /**
     * Positions are local offsets from the structure base or the actual controller, respectively.
     */
    @ZenMethod
    public static ZenStructurePredicate custom(String[] facts, int[][] offsets, int[][] controllerOffsets, ZenStructureValidator validator) {
        return custom(facts, new ZenStructureQuery[0], offsets, controllerOffsets, validator);
    }

    /**
     * Arbitrary observational samplers use the same query identity, copying and freshness proof.
     */
    @ZenMethod
    public static ZenStructurePredicate custom(String[] facts, ZenStructureQuery[] customFacts, int[][] offsets,
                                               int[][] controllerOffsets, ZenStructureValidator validator) {
        var queries = new ObjectArrayList<StructureQuery<?>>(facts.length);
        for (var fact : facts) queries.add(query(fact));
        for (var fact : customFacts) queries.add(fact.value());
        return new ZenStructurePredicate(StructurePredicates.custom(queries, positions(offsets), positions(controllerOffsets),
            captured -> validator.validate(new ZenStructureFacts(captured))));
    }

    private static List<BlockPos> positions(int[][] offsets) {
        var positions = new ObjectArrayList<BlockPos>(offsets.length);
        for (var offset : offsets) {
            if (offset == null || offset.length != 3)
                throw new IllegalArgumentException("Structure read offset requires exactly x, y, z");
            positions.add(new BlockPos(offset[0], offset[1], offset[2]));
        }
        return positions;
    }

    private static StructureQuery<?> query(String name) {
        return switch (name) {
            case "biomeName" -> StructureQueries.BIOME_NAME;
            case "biomeId" -> StructureQueries.BIOME;
            case "biomeTemperature" -> StructureQueries.BIOME_TEMPERATURE;
            case "biomeRainfall" -> StructureQueries.BIOME_RAINFALL;
            case "dimension" -> StructureQueries.DIMENSION;
            case "worldTime" -> StructureQueries.WORLD_TIME;
            case "totalWorldTime" -> StructureQueries.TOTAL_WORLD_TIME;
            case "daytime" -> StructureQueries.DAYTIME;
            case "raining" -> StructureQueries.RAINING;
            case "thundering" -> StructureQueries.THUNDERING;
            case "skyVisible" -> StructureQueries.SKY_VISIBLE;
            case "rainingAt" -> StructureQueries.RAINING_AT;
            case "light" -> StructureQueries.LIGHT;
            case "strongPower" -> StructureQueries.STRONG_POWER;
            case "height" -> StructureQueries.HEIGHT;
            case "notAir" -> StructureQueries.NON_AIR;
            case "hasTileEntity" -> StructureQueries.HAS_TILE_ENTITY;
            case "resourceHatch" -> StructureQueries.RESOURCE_HATCH;
            case "energyInputHatch" -> StructureQueries.ENERGY_INPUT_HATCH;
            case "energyOutputHatch" -> StructureQueries.ENERGY_OUTPUT_HATCH;
            case "blockState" -> StructureQueries.BLOCK_STATE;
            case "tileNbt" -> StructureQueries.TILE_NBT;
            default -> throw new IllegalArgumentException("Unknown structure fact: " + name);
        };
    }

    @ZenMethod
    public static ZenStructurePredicate block(String id) {
        var name = new ResourceLocation(id);
        if (!Block.REGISTRY.containsKey(name)) throw new IllegalArgumentException("Unknown structure block: " + id);
        return new ZenStructurePredicate(StructurePredicates.blocks(List.of(Block.REGISTRY.getObject(name))));
    }

    @ZenMethod
    public static ZenStructurePredicate state(IBlockState state) {
        return new ZenStructurePredicate(StructurePredicates.state(CraftTweakerMC.getBlockState(state)));
    }

    @ZenMethod
    public static ZenStructurePredicate nbt(IData expected) {
        var tag = NBTConverter.from(expected);
        if (!(tag instanceof NBTTagCompound compound))
            throw new IllegalArgumentException("Structure NBT requires a map");
        var fields = new Object2ObjectLinkedOpenHashMap<String, String>(compound.getSize());
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

    /**
     * ZenScript reserves "any" as a type token, so scripts use this callable OR alias.
     */
    @ZenMethod
    public static ZenStructurePredicate anyOf(ZenStructurePredicate[] predicates) {
        return any(predicates);
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
        for (var predicate : predicates) values.add(predicate.value);
        return values;
    }

    /**
     * JSON references this ID through the same startup registry, before predicate resolution.
     */
    @ZenMethod
    public void register(String id) {
        var name = new ResourceLocation(id);
        var predicate = value;
        DefinitionRegistrations.structurePredicates(event -> event.registry().register(name, Set.of(), (_, _) -> predicate));
    }

    @ZenMethod
    public ZenStructurePredicate at(int x, int y, int z) {
        return new ZenStructurePredicate(StructurePredicates.at(new BlockPos(x, y, z), value));
    }
}
