package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Immutable complete candidate generation, including shapes not directly referenced by machines. */
@SuppressWarnings("ClassCanBeRecord") // Object identity denotes a catalog generation, including A -> B -> A.
public final class StructureCatalog {
    private final Map<ResourceLocation, StructureDefinition> definitions;

    public StructureCatalog(Collection<StructureDefinition> definitions) {
        var copy = new Object2ObjectLinkedOpenHashMap<ResourceLocation, StructureDefinition>();
        for (var definition : definitions) {
            Objects.requireNonNull(definition);
            var id = Objects.requireNonNull(definition.id());
            if (copy.putIfAbsent(id, definition) != null)
                throw new IllegalArgumentException("Duplicate structure ID: " + id);
        }
        this.definitions = Object2ObjectMaps.unmodifiable(copy);
    }

    public static StructureCatalog fromShapes(Map<ResourceLocation, ? extends MachineStructure> shapes) {
        var definitions = new ObjectArrayList<StructureDefinition>();
        shapes.forEach((id, shape) -> {
            if (!Objects.requireNonNull(id).equals(Objects.requireNonNull(shape).id()))
                throw new IllegalArgumentException("Structure key does not match its ID: " + id);
            definitions.add(new StructureDefinition(shape));
        });
        return new StructureCatalog(definitions);
    }

    @Nullable
    public StructureDefinition get(ResourceLocation id) {
        return definitions.get(Objects.requireNonNull(id));
    }

    public Map<ResourceLocation, StructureDefinition> definitions() {
        return definitions;
    }
}
