package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import ae2.api.stacks.AEKey2LongMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Server-safe pool and optional pure candidate strategy; client pages bind separately.
 */
public interface RecipePool {
    ResourceLocation id();

    String translationKey();

    List<MachineRecipe> recipes();

    List<MachineType> machines();

    /** First canonical inventory candidate; the scanner still checks FE, conditions and START. */
    default @Nullable MachineRecipe match(AEKey2LongMap inventory) {
        var matches = match(inventory, 1);
        return matches.isEmpty() ? null : matches.getFirst().recipe();
    }

    /** Ordered candidates with independent quantity ceilings, not a reservation of shared inputs. */
    default List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        return ResourceRecipeIndex.forFixedInputs(recipes(), _ -> true).match(inventory, parallelism);
    }

    /** Whether this pool delegates JEI recipes to a category supplied by another registration. */
    default boolean hasExistingJeiPage() {
        return false;
    }

    /** Exact HEI category UID; null or blank keeps this library's ordinary page registration. */
    default @Nullable String existingJeiPageId() {
        return null;
    }

    /** Create this implementation's immutable canonical definition with complete registrations and
     * static reverse bindings. Retain strategy/configuration and exactly the supplied instances/order.
     * Registry validates the result before publishing any pool or index. */
    RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines);

    /** Pure pool-local discovery. Null (including a cursor's null result) falls back only to this
     * pool; empty rejects this pool. Return canonical members conservatively: never omit a recipe
     * that could succeed under this strategy's responsibility or execute START/acquire/mutate.
     * Meter internal visits and keep partial output private. Admission and permissions remain separate. */
    default @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return null;
    }
}
