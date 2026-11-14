package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.util.List;
import java.util.Map;

/**
 * Canonical definition graph, including external structural wrappers. Definitions must form an
 * acyclic graph with unique paths and remain unchanged for the lifetime of this index.
 */
@SuppressWarnings("ClassCanBeRecord")
public final class RecipeDefinitionIndex {
    private final Map<RequirementPath, RecipeRequirement<?>> requirements;

    public RecipeDefinitionIndex(MachineRecipe recipe) {
        this(recipe.orderedRequirements());
    }

    public RecipeDefinitionIndex(RecipeProcess process) {
        this(process.orderedRequirements());
    }

    private RecipeDefinitionIndex(List<RecipeRequirement<?>> roots) {
        var entries = new Object2ObjectLinkedOpenHashMap<RequirementPath, RecipeRequirement<?>>(6);
        for (int index2 = 0; index2 < roots.size(); index2++) {
            var root = roots.get(index2);
            add(root, root.path(null), entries);
        }
        requirements = entries;
    }

    private static <C> void add(RecipeRequirement<C> requirement, RequirementPath path,
                                Object2ObjectLinkedOpenHashMap<RequirementPath, RecipeRequirement<?>> entries) {
        entries.put(path, requirement);

        var elements = requirement.type().system().children(requirement.payload());
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var child = elements.get(index1);
            add(child, child.path(path), entries);
        }

    }

    public RecipeRequirement<?> require(RequirementPath path) {
        var requirement = requirements.get(path);
        if (requirement == null) {
            throw new IllegalArgumentException("Unknown saved requirement path: " + path);
        }
        return requirement;
    }

    /**
     * Returns the indexed definitions. Callers must not modify this map.
     */
    public Map<RequirementPath, RecipeRequirement<?>> requirements() {
        return requirements;
    }
}
