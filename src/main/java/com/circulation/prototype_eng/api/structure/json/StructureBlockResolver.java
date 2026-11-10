package com.circulation.prototype_eng.api.structure.json;

import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * Definition-time block resolution. Implementations reject unknown IDs instead of defaulting to air.
 */
public interface StructureBlockResolver {
    StructurePredicate block(ResourceLocation id);

    StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt);

    StructurePredicate regex(String expression);

    StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt);

    List<StructureDisplay.BlockOption> displayRegex(String expression);
}
