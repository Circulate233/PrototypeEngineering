package com.circulation.prototype_eng.api.recipe.importing;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Source data and conversion callbacks are evaluated once, at the first recipe access. */
public class ImportedRecipePool<C> implements RecipePool {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ResourceLocation id;
    private final String translationKey;
    private final Supplier<? extends List<ImportedRecipe<C>>> source;
    private final BiPredicate<RecipeBuilder, C> onCreate;
    private volatile @Nullable DefaultRecipePool initialized;
    private @Nullable Throwable failure;
    private boolean initializing;

    public static ImportedRecipePool<@Nullable Void> withoutContext(ResourceLocation id, String translationKey,
        Supplier<? extends List<RecipeBuilder>> source, Predicate<RecipeBuilder> onCreate) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(onCreate, "onCreate");
        return new ImportedRecipePool<>(id, translationKey, () -> {
            var drafts = source.get();
            var imported = new ObjectArrayList<ImportedRecipe<@Nullable Void>>(drafts.size());
            for (int i = 0; i < drafts.size(); i++) imported.add(new ImportedRecipe<>(drafts.get(i), null));
            return imported;
        }, (recipe, _) -> onCreate.test(recipe));
    }

    public ImportedRecipePool(ResourceLocation id, String translationKey,
                              Supplier<? extends List<ImportedRecipe<C>>> source) {
        this(id, translationKey, source, (_, _) -> true);
    }

    public ImportedRecipePool(ResourceLocation id, String translationKey,
                              Supplier<? extends List<ImportedRecipe<C>>> source,
                              BiPredicate<RecipeBuilder, C> onCreate) {
        this.id = Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        this.source = Objects.requireNonNull(source, "source");
        this.onCreate = Objects.requireNonNull(onCreate, "onCreate");
    }

    protected ImportedRecipePool(ImportedRecipePool<C> original, List<MachineRecipe> recipes, List<MachineType> machines) {
        id = original.id;
        translationKey = original.translationKey;
        source = original.source;
        onCreate = original.onCreate;
        initialized = original.data().freeze(recipes, machines);
    }

    private DefaultRecipePool data() {
        var current = initialized;
        return current == null ? initialize() : current;
    }

    private synchronized DefaultRecipePool initialize() {
        if (initialized != null) return initialized;
        if (failure != null) throw new IllegalStateException("Recipe import previously failed: " + id, failure);
        if (initializing) throw new IllegalStateException("Recursive recipe import: " + id);
        initializing = true;
        try {
            var imported = Objects.requireNonNull(source.get(), "Imported recipe source");
            var accepted = new ObjectArrayList<MachineRecipe>(imported.size());
            for (int i = 0; i < imported.size(); i++) {
                var entry = imported.get(i);
                if (onCreate.test(entry.recipe(), entry.context())) accepted.add(entry.recipe().build());
            }
            var completed = new DefaultRecipePool(id, translationKey, accepted);
            initialized = completed;
            return completed;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            LOGGER.error("Failed to import recipe pool {}", id, ex);
            throw ex;
        } finally {
            initializing = false;
        }
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
        return data().recipes();
    }

    @Override
    public List<MachineType> machines() {
        var current = initialized;
        return current == null ? List.of() : current.machines();
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        return data().match(inventory, parallelism);
    }

    @Override
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return data().beginMatch(inventory, parallelism, groups);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return data().beginCandidates(machine, groups);
    }

    @Override
    public ImportedRecipePool<C> freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new ImportedRecipePool<>(this, recipes, machines);
    }
}
