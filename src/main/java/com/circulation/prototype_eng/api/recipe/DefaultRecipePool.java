package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * General immutable pool; stable input paths share a cached inventory index, others retain ordered fallback.
 */
public final class DefaultRecipePool implements RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final List<MachineRecipe> recipes;
    private final List<MachineType> machines;
    private final ResourceRecipeIndex inputIndex;
    private final @Nullable String existingJeiPageId;

    public DefaultRecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
        this(id, translationKey, recipes, List.of(), null);
    }

    private DefaultRecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes, List<MachineType> machines,
                              @Nullable String existingJeiPageId) {
        this.id = Objects.requireNonNull(id);
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        var ids = new ObjectOpenHashSet<ResourceLocation>(recipes.size());
        var copy = new ObjectArrayList<MachineRecipe>(recipes.size());
        for (int index1 = 0; index1 < recipes.size(); index1++) {
            var recipe = recipes.get(index1);
            Objects.requireNonNull(recipe);
            if (!ids.add(recipe.id()))
                throw new IllegalArgumentException("Duplicate recipe in pool " + id + ": " + recipe.id());
            copy.add(recipe);
        }
        this.recipes = ObjectLists.unmodifiable(copy);
        inputIndex = ResourceRecipeIndex.forFixedInputs(this.recipes, _ -> true);
        this.machines = ObjectLists.unmodifiable(new ObjectArrayList<>(machines));
        this.existingJeiPageId = existingJeiPageId;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    @Override
    public List<MachineRecipe> recipes() {
        return recipes;
    }

    @Override
    public List<MachineType> machines() {
        return machines;
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        return inputIndex.match(inventory, parallelism);
    }

    @Override
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return inputIndex.beginMatch(inventory, parallelism, groups);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return machine.supportsStableFixedInputLowerBounds() ? inputIndex.beginLookup(machine, groups, recipes) : null;
    }

    @Override
    public boolean hasExistingJeiPage() {
        return existingJeiPageId != null;
    }

    @Override
    public @Nullable String existingJeiPageId() {
        return existingJeiPageId;
    }

    /**
     * Immutable addon binding; the external category is resolved when HEI registration is complete.
     */
    public DefaultRecipePool withExistingJeiPage(@Nullable String categoryUid) {
        return new DefaultRecipePool(id, translationKey, recipes, machines, categoryUid);
    }

    @Override
    public DefaultRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new DefaultRecipePool(id, translationKey, recipes, machines, existingJeiPageId);
    }
}
