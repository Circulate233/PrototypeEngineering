package com.circulation.prototype_eng.structure.json;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.json.StructureBlockResolver;
import com.circulation.prototype_eng.api.structure.json.StructureJsonParameters;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** PM standard effects decoded into existing detached query predicates; no live matching callbacks. */
public final class BuiltinStructurePredicates {
    private BuiltinStructurePredicates() {}

    public static void register(StructurePredicateRegistry registry) {
        constant(registry, "any", StructurePredicates.all(List.of()));
        constant(registry, "not_air", StructurePredicates.notAir());
        constant(registry, "has_tile_entity", StructurePredicates.hasTileEntity());
        constant(registry, "overworld_only", StructurePredicates.test(StructureQueries.DIMENSION, dimension -> dimension == 0));
        constant(registry, "nether_only", StructurePredicates.test(StructureQueries.DIMENSION, dimension -> dimension == -1));
        constant(registry, "end_only", StructurePredicates.test(StructureQueries.DIMENSION, dimension -> dimension == 1));
        constant(registry, "day_only", StructurePredicates.test(StructureQueries.DAYTIME, Boolean.TRUE::equals));
        constant(registry, "night_only", StructurePredicates.test(StructureQueries.DAYTIME, Boolean.FALSE::equals));
        constant(registry, "clear_weather_only", StructurePredicates.all(List.of(
            StructurePredicates.test(StructureQueries.RAINING, Boolean.FALSE::equals),
            StructurePredicates.test(StructureQueries.THUNDERING, Boolean.FALSE::equals))));
        registry.register(id("block_id_regex"), Set.of("pattern"), (parameters, blocks) -> {
            parameters.fields("pattern");
            String expression = parameters.nonBlank("pattern");
            try {
                return blocks.regex(expression);
            } catch (RuntimeException failure) {
                throw parameters.at("pattern").error("Cannot resolve block regex", failure);
            }
        });
        registry.register(id("tile_nbt"), Set.of("nbt", "checks"), BuiltinStructurePredicates::tileNbt);
    }

    private static StructurePredicate tileNbt(StructureJsonParameters parameters, StructureBlockResolver blocks) {
        parameters.fields("nbt", "checks");
        if (parameters.has("nbt") == parameters.has("checks"))
            throw parameters.error("tile_nbt requires exactly one of nbt or checks");
        if (parameters.has("nbt")) return StructurePredicates.nbt(parameters.stringMap("nbt"));
        var checks = new ObjectArrayList<StructurePredicate>();
        for (var check : parameters.array("checks")) {
            check.fields("pos", "nbt", "blockId", "meta");
            check.at("pos");
            check.at("nbt");
            var nbt = check.stringMap("nbt");
            if (nbt.isEmpty()) throw check.at("nbt").error("A validator check needs at least one NBT constraint");
            StructurePredicate predicate = StructurePredicates.nbt(nbt);
            if (check.has("blockId")) {
                var blockId = check.id("blockId");
                StructurePredicate block;
                try {
                    block = check.has("meta") ? blocks.state(blockId, check.meta(), Map.of()) : blocks.block(blockId);
                } catch (RuntimeException failure) {
                    throw check.at("blockId").error("Cannot resolve validator block " + blockId, failure);
                }
                predicate = StructurePredicates.all(List.of(block, predicate));
            } else if (check.has("meta")) throw check.at("meta").error("meta requires blockId");
            checks.add(StructurePredicates.at(check.position("pos", BlockPos.ORIGIN), predicate));
        }
        if (checks.isEmpty()) throw parameters.at("checks").error("tile_nbt checks must not be empty");
        return StructurePredicates.all(checks);
    }

    private static void constant(StructurePredicateRegistry registry, String path, StructurePredicate predicate) {
        registry.register(id(path), Set.of(), (parameters, _) -> {
            parameters.fields();
            return predicate;
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Tags.MOD_ID, path);
    }
}
