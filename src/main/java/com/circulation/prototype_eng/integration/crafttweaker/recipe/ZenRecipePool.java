package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.DeferredRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeConfiguration;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ClientJeiDeclarations;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiOptions;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiPage;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.function.Supplier;

// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Pool")
public class ZenRecipePool {
    private final RecipePool value;
    private final Supplier<List<ZenRecipe>> recipeSource;
    private volatile @Nullable List<ZenRecipe> recipeBindings;
    private final boolean deferred;
    private final @Nullable ImportedRecipePool importedSource;
    @Nullable
    private final ZenJeiOptions options;
    @Nullable
    private final ZenJeiPage page;

    private ZenRecipePool(RecipePool value, List<ZenRecipe> recipes) {
        this(value, recipes, null, null);
    }

    private ZenRecipePool(RecipePool value, @Nullable List<ZenRecipe> recipes, @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {
        this(value, () -> recipes, false, options, page);
        recipeBindings = recipes;
    }

    private ZenRecipePool(RecipePool value, Supplier<List<ZenRecipe>> recipeSource, boolean deferred,
                          @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {
        this(value, recipeSource, deferred, options, page, value instanceof ImportedRecipePool imported ? imported : null);
    }

    private ZenRecipePool(RecipePool value, Supplier<List<ZenRecipe>> recipeSource, boolean deferred,
                          @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page, @Nullable ImportedRecipePool importedSource) {
        this.value = value;
        this.recipeSource = recipeSource;
        this.deferred = deferred;
        this.options = options;
        this.page = page;
        this.importedSource = importedSource;
    }

    public static ZenRecipePool imported(RecipePool pool, String revision) {
        return new ZenRecipePool(pool, revision);
    }

    protected ZenRecipePool(RecipePool pool, String revision) {
        this(pool, importedBindings(pool, revision), true, null, null);
    }

    private static Supplier<List<ZenRecipe>> importedBindings(RecipePool pool, String revision) {
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("Recipe revision is required");
        return () -> {
            var recipes = pool.recipes();
            var result = new ObjectArrayList<ZenRecipe>(recipes.size());
            for (int i = 0; i < recipes.size(); i++) result.add(new ZenRecipe(recipes.get(i), revision));
            return result;
        };
    }

    /**
     * Addons retain their actual pool lookup policy and exact recipe/codec handles.
     */
    public static ZenRecipePool of(RecipePool pool, ZenRecipe[] recipes) {
        var bindings = new ObjectArrayList<ZenRecipe>(recipes.length);
        if (pool.recipes().size() != recipes.length)
            throw new IllegalArgumentException("Pool recipe binding count differs");
        for (int i = 0; i < recipes.length; i++) {
            var recipe = recipes[i];
            if (pool.recipes().get(i) != recipe.recipe())
                throw new IllegalArgumentException("Pool must retain exact recipe bindings");
            bindings.add(recipe);
        }
        return new ZenRecipePool(pool, bindings);
    }

    @ZenMethod
    public static ZenRecipePool create(String id, String translationKey, ZenRecipe[] recipes) {
        var definitions = new ObjectArrayList<ZenRecipe>(recipes.length);
        var values = new ObjectArrayList<MachineRecipe>(recipes.length);
        for (var recipe : recipes) {
            definitions.add(recipe);
            values.add(recipe.recipe());
        }
        return new ZenRecipePool(new DefaultRecipePool(ZenRecipeSupport.id(id), translationKey, values),
            definitions);
    }

    @ZenMethod
    public static ZenRecipePool simple(String id, String translationKey, ZenRecipe[] recipes) {
        var entries = new ObjectArrayList<SimpleRecipePool.Entry>(recipes.length);
        for (var recipe : recipes) entries.add(SimpleRecipePool.Entry.of(recipe.recipe()));
        return of(new SimpleRecipePool(ZenRecipeSupport.id(id), translationKey, entries), recipes);
    }

    @ZenMethod
    public ZenRecipePool auxiliaryInputs(ZenResource[] inputs) {
        return transformed(value.withAuxiliaryInputs(ZenRecipeSupport.stacks(inputs)));
    }

    @ZenMethod
    public ZenRecipePool poolType(String type) {
        if (importedSource == null) throw new IllegalStateException("Only imported recipe pools have a selectable implementation");
        importedSource.poolType(switch (type) {
            case "single_input" -> RecipePoolType.SINGLE_INPUT;
            case "general" -> RecipePoolType.GENERAL;
            default -> throw new IllegalArgumentException("Unknown recipe pool type: " + type);
        });
        return this;
    }

    @ZenMethod
    public ZenRecipePool requirements(ZenRequirement[] requirements) {
        return transformed(value.withRequirements(ZenRecipeSupport.requirements(requirements)));
    }

    @ZenMethod
    public ZenRecipePool durationTicks(int durationTicks) {
        return configured(RecipeConfiguration.builder().durationTicks(durationTicks).build());
    }

    @ZenMethod
    public ZenRecipePool maxThreads(int maxThreads) {
        return configured(RecipeConfiguration.builder().maxConcurrentThreads(maxThreads).build());
    }

    @ZenMethod
    public ZenRecipePool groups(String[] groups) {
        var ids = new ObjectArrayList<ResourceLocation>(groups.length);
        for (var group : groups) ids.add(ZenRecipeSupport.id(group));
        return configured(RecipeConfiguration.builder().recipeGroups(ids).build());
    }

    @ZenMethod
    public ZenRecipePool parallelism(ZenParallelismSelection selection) {
        return configured(RecipeConfiguration.builder().parallelismSelection(selection.value()).build());
    }

    @ZenMethod
    public ZenRecipePool event(String id, String stage, ZenRecipeEventCallback callback) {
        return transformed(value.withEvent(ZenRecipeSupport.id(id), RecipeStage.valueOf(stage),
            (process, currentStage) -> callback.acquire(new ZenRecipeEventContext(process, currentStage)).value()));
    }

    private ZenRecipePool configured(RecipeConfiguration configuration) {
        return transformed(value.withConfiguration(configuration));
    }

    private ZenRecipePool transformed(RecipePool updated) {
        if (updated == value) return this;
        return new ZenRecipePool(updated, () -> {
            var recipes = recipes();
            var definitions = updated.recipes();
            if (recipes.size() != definitions.size())
                throw new IllegalArgumentException("Pool configuration changed recipe membership");
            var bindings = new ObjectArrayList<ZenRecipe>(recipes.size());
            for (int i = 0; i < recipes.size(); i++) {
                var previous = recipes.get(i);
                var recipe = definitions.get(i);
                bindings.add(recipe == previous.recipe() ? previous : new ZenRecipe(previous.codec().withRecipe(recipe)));
            }
            return bindings;
        }, deferred, options, page, importedSource);
    }

    /**
     * Combines the exact providers and recipe/codec handles; no child is registered implicitly.
     */
    @ZenMethod
    public static ZenRecipePool combine(String id, String translationKey, ZenRecipePool[] pools) {
        var providers = new ObjectArrayList<RecipePool>(pools.length);
        boolean deferred = false;
        for (var pool : pools) {
            providers.add(pool.value);
            deferred |= pool.deferred;
        }
        var poolId = ZenRecipeSupport.id(id);
        RecipePool combined = deferred
            ? new DeferredRecipePool(poolId, translationKey, () -> new CompositeRecipePool(poolId, translationKey, providers))
            : new CompositeRecipePool(poolId, translationKey, providers);
        var result = new ZenRecipePool(combined, () -> combinedBindings(combined, pools), deferred, null, null);
        if (!deferred) result.recipes();
        return result;
    }

    private static List<ZenRecipe> combinedBindings(RecipePool combined, ZenRecipePool[] pools) {
        var handles = new Reference2ObjectOpenHashMap<MachineRecipe, ZenRecipe>();
        for (var pool : pools) {
            var recipes = pool.recipes();
            for (int index3 = 0; index3 < recipes.size(); index3++) {
                var recipe = recipes.get(index3);
                var previous = handles.putIfAbsent(recipe.recipe(), recipe);
                if (previous != null && (previous.codec() != recipe.codec() || !previous.revision().equals(recipe.revision())))
                    throw new IllegalArgumentException("Shared recipe must retain one exact codec and revision: " + recipe.id());
            }
        }
        var bindings = new ObjectArrayList<ZenRecipe>(combined.recipes().size());
        var elements = combined.recipes();
        for (int index = 0; index < elements.size(); index++) {
            var recipe = elements.get(index);
            bindings.add(handles.get(recipe));
        }
        return bindings;
    }

    @ZenMethod
    public ZenRecipePool existingJeiPage(String categoryUid) {
        return new ZenRecipePool(value.withExistingJeiPage(categoryUid), this::recipes, deferred, options, page, importedSource);
    }

    @ZenMethod
    public ZenRecipePool jeiOptions(ZenJeiOptions settings) {
        return new ZenRecipePool(value, this::recipes, deferred, settings, page, importedSource);
    }

    @ZenMethod
    public ZenRecipePool customJeiPage(ZenJeiPage handle) {
        return new ZenRecipePool(value, this::recipes, deferred, options, handle, importedSource);
    }

    public void declareClient() {
        ClientJeiDeclarations.declare(value.id(), options, page);
    }

    @ZenMethod
    public void register() {
        declareClient();
        DefinitionRegistrations.recipes(event -> {
            var recipes = recipes();
            event.registry().register(value);
            for (int index2 = 0; index2 < recipes.size(); index2++) {
                var recipe = recipes.get(index2);
                event.registry().declareRevision(recipe.recipe(), recipe.revision());
            }
        });
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    public RecipePool value() {
        return value;
    }

    public synchronized List<ZenRecipe> recipes() {
        if (recipeBindings == null) recipeBindings = recipeSource.get();
        return recipeBindings;
    }

    public boolean deferred() {
        return deferred;
    }
}
