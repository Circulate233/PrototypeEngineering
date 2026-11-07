package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Canonical definition graph for one immutable recipe, including external structural wrappers. */
@SuppressWarnings("ClassCanBeRecord") // Only a traversed recipe may construct this index, not an arbitrary path map.
public final class RecipeDefinitionIndex {
    private final Map<RequirementPath, RecipeRequirement<?>> requirements;

    public RecipeDefinitionIndex(MachineRecipe recipe) {
        this(recipe.orderedRequirements());
    }

    public RecipeDefinitionIndex(RecipeProcess process) {
        this(process.orderedRequirements());
    }

    private RecipeDefinitionIndex(List<RecipeRequirement<?>> roots) {
        var entries = new Object2ObjectLinkedOpenHashMap<RequirementPath, RecipeRequirement<?>>();
        var visiting = new ReferenceOpenHashSet<RecipeRequirement<?>>();
        for (var root : roots) { add(root, root.path(null), visiting, entries); }
        requirements = Collections.unmodifiableMap(entries);
    }

    public RecipeRequirement<?> require(RequirementPath path) {
        var requirement = requirements.get(Objects.requireNonNull(path));
        if (requirement == null) { throw new IllegalArgumentException("Unknown saved requirement path: " + path); }
        return requirement;
    }

    public Map<RequirementPath, RecipeRequirement<?>> requirements() { return requirements; }

    private static <C> void add(RecipeRequirement<C> requirement, RequirementPath path,
                                ReferenceOpenHashSet<RecipeRequirement<?>> visiting,
                                Object2ObjectLinkedOpenHashMap<RequirementPath, RecipeRequirement<?>> entries) {
        if (requirement != requirement.definition()) { throw new IllegalArgumentException("Recipe structure contains an effective view"); }
        if (!visiting.add(requirement)) { throw new IllegalArgumentException("Cyclic nested requirement definitions"); }
        if (entries.putIfAbsent(path, requirement) != null) { throw new IllegalArgumentException("Duplicate nested requirement path: " + path); }
        for (var child : requirement.type().system().children(requirement.payload())) {
            Objects.requireNonNull(child);
            add(child, child.path(path), visiting, entries);
        }
        visiting.remove(requirement);
    }
}
