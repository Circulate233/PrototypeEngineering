package com.circulation.prototype_eng.api.recipe.importing;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
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
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Source data and conversion callbacks are evaluated once, at the first recipe access. */
public class ImportedRecipePool implements RecipePool {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ResourceLocation id;
    private final String translationKey;
    private final Supplier<? extends List<MachineRecipe>> source;
    private volatile RecipePoolType poolType;
    private volatile @Nullable RecipePool initialized;
    private @Nullable Throwable failure;
    private boolean initializing;

    public static ImportedRecipePool withoutContext(ResourceLocation id, String translationKey,
        Supplier<? extends List<RecipeBuilder>> source, @Nullable Predicate<RecipeBuilder> onCreate) {
        Objects.requireNonNull(source, "source");
        return new ImportedRecipePool(id, translationKey, () -> {
            var drafts = source.get();
            var imported = new ObjectArrayList<MachineRecipe>(drafts.size());
            for (int i = 0; i < drafts.size(); i++) {
                var draft = drafts.get(i);
                if (onCreate == null || onCreate.test(draft)) imported.add(draft.build());
            }
            return imported;
        });
    }

    public static ImportedRecipePool withoutContext(ResourceLocation id, String translationKey,
        Supplier<? extends List<RecipeBuilder>> source) {
        return withoutContext(id, translationKey, source, null);
    }

    public ImportedRecipePool(ResourceLocation id, String translationKey,
                              Supplier<? extends List<MachineRecipe>> source) {
        this(id, translationKey, source, RecipePoolType.GENERAL);
    }

    protected ImportedRecipePool(ResourceLocation id, String translationKey,
                                 Supplier<? extends List<MachineRecipe>> source, RecipePoolType poolType) {
        this.id = Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        this.source = Objects.requireNonNull(source, "source");
        this.poolType = Objects.requireNonNull(poolType, "poolType");
    }

    protected ImportedRecipePool(ImportedRecipePool original, List<MachineRecipe> recipes, List<MachineType> machines) {
        id = original.id;
        translationKey = original.translationKey;
        source = original.source;
        var implementation = original.data();
        poolType = original.poolType;
        initialized = Objects.requireNonNull(implementation.freeze(recipes, machines), "Frozen recipe pool");
    }

    private RecipePool data() {
        var current = initialized;
        return current == null ? initialize() : current;
    }

    private synchronized RecipePool initialize() {
        if (initialized != null) return initialized;
        if (failure != null) throw new IllegalStateException("Recipe import previously failed: " + id, failure);
        if (initializing) throw new IllegalStateException("Recursive recipe import: " + id);
        initializing = true;
        try {
            var imported = Objects.requireNonNull(source.get(), "Imported recipe source");
            var completed = poolType.create(id, translationKey, imported);
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

    public RecipePoolType poolType() {
        return poolType;
    }

    public synchronized ImportedRecipePool poolType(RecipePoolType value) {
        if (initialized != null || initializing || failure != null)
            throw new IllegalStateException("Recipe pool type is fixed after first access: " + id);
        poolType = Objects.requireNonNull(value, "poolType");
        return this;
    }

    public RecipePool implementation() {
        return data();
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
    public MachineRecipe prepareRecipe(MachineRecipe recipe) {
        return data().prepareRecipe(recipe);
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
    public ImportedRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new ImportedRecipePool(this, recipes, machines);
    }
}
