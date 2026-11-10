package com.circulation.prototype_eng.api.structure.json;

import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * Definition-time block resolution. Implementations reject unknown IDs instead of defaulting to air.
 */
public interface StructureBlockResolver {
    StructurePredicate block(ResourceLocation id);

    StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt);

    /** Existing resolvers explicitly reject unsupported full-state constraints. */
    default StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt, Map<String, String> properties) {
        if (!properties.isEmpty()) throw new IllegalArgumentException("Resolver does not support block state properties");
        return state(id, meta, nbt);
    }

    /** One resolved declaration owns both matching and presentation; native resolvers retain their state facts. */
    default StructurePredicate resolveState(ResourceLocation id, int meta, Map<String, String> nbt, Map<String, String> properties) {
        return StructurePredicates.withDisplay(state(id, meta, nbt, properties),
            new StructureDisplay("state:" + id + "@" + meta, List.of(displayState(id, meta, nbt, properties)), null));
    }

    StructurePredicate regex(String expression);

    StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt);

    default StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt, Map<String, String> properties) {
        if (!properties.isEmpty()) throw new IllegalArgumentException("Resolver does not support block state properties");
        return displayState(id, meta, nbt);
    }

    List<StructureDisplay.BlockOption> displayRegex(String expression);
}
