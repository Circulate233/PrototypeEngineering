package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lazy common declarations preserving the provider's original discovery and freshness checks.
 */
public final class ConfiguredRecipePool implements RecipePool {
    private final RecipePool source;
    private final RecipeConfiguration configuration;
    private final List<RecipeRequirement<?>> additions;
    private final @Nullable String existingJeiPageId;
    private volatile @Nullable State state;

    public ConfiguredRecipePool(RecipePool source, RecipeConfiguration configuration) {
        this.source = Objects.requireNonNull(source, "Recipe pool source");
        this.configuration = Objects.requireNonNull(configuration, "Recipe configuration");
        additions = configuration.materialize(source.id());
        existingJeiPageId = source.existingJeiPageId();
    }

    private ConfiguredRecipePool(ConfiguredRecipePool original, @Nullable String existingJeiPageId) {
        source = original.source;
        configuration = original.configuration;
        additions = original.additions;
        state = original.state;
        this.existingJeiPageId = existingJeiPageId;
    }

    private ConfiguredRecipePool(RecipePool source, RecipeConfiguration configuration, List<RecipeRequirement<?>> additions,
                                 @Nullable String existingJeiPageId, @Nullable State state) {
        this.source = source;
        this.configuration = configuration;
        this.additions = additions;
        this.existingJeiPageId = existingJeiPageId;
        this.state = state;
    }

    @Override
    public ResourceLocation id() {
        return source.id();
    }

    @Override
    public String translationKey() {
        return source.translationKey();
    }

    @Override
    public List<MachineRecipe> recipes() {
        return data().recipes;
    }

    @Override
    public List<MachineType> machines() {
        var current = state;
        return current == null ? source.machines() : current.machines;
    }

    @Override
    public boolean hasExistingJeiPage() {
        return existingJeiPageId != null && !existingJeiPageId.isBlank();
    }

    @Override
    public @Nullable String existingJeiPageId() {
        return existingJeiPageId;
    }

    @Override
    public ConfiguredRecipePool withExistingJeiPage(@Nullable String categoryUid) {
        return new ConfiguredRecipePool(this, categoryUid);
    }

    @Override
    public ConfiguredRecipePool withAuxiliaryInputs(List<GenericStack> inputs) {
        var updated = new ConfiguredRecipePool(source, configuration.withAuxiliaryInputs(inputs));
        return updated.withExistingJeiPage(existingJeiPageId);
    }

    @Override
    public ConfiguredRecipePool withConfiguration(RecipeConfiguration additional) {
        return new ConfiguredRecipePool(source, configuration.merge(additional)).withExistingJeiPage(existingJeiPageId);
    }

    @Override
    public MachineRecipe prepareRecipe(MachineRecipe recipe) {
        if (additions.isEmpty()) return configuration.apply(source.prepareRecipe(recipe), additions);
        if (configuration.matches(recipe, additions)) return recipe;
        return configuration.apply(source.prepareRecipe(withoutAdditions(recipe)), additions);
    }

    private MachineRecipe withoutAdditions(MachineRecipe recipe) {
        var retained = new ObjectArrayList<RecipeRequirement<?>>(recipe.orderedRequirements().size());
        for (var requirement : recipe.orderedRequirements()) {
            if (!additions.contains(requirement)) retained.add(requirement);
        }
        return retained.size() == recipe.orderedRequirements().size() ? recipe
            : RecipeBuilder.from(recipe).requirements(retained).build();
    }

    private State data() {
        var current = state;
        if (current != null) return current;
        synchronized (this) {
            current = state;
            if (current != null) return current;
            var raw = source.recipes();
            var prepared = new ObjectArrayList<MachineRecipe>(raw.size());
            for (int i = 0; i < raw.size(); i++) prepared.add(configuration.apply(raw.get(i), additions));
            current = new State(source, raw, prepared, source.machines());
            state = current;
            return current;
        }
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        var current = data();
        if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
        if (!configuration.preservesInputDiscovery()) {
            var complete = new ObjectArrayList<RecipeInventoryMatch>(current.recipes.size());
            for (var recipe : current.recipes) complete.add(new RecipeInventoryMatch(recipe, parallelism));
            return complete;
        }
        var matches = current.provider.match(inventory, parallelism);
        var mapped = new ObjectArrayList<RecipeInventoryMatch>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            var match = matches.get(i);
            mapped.add(new RecipeInventoryMatch(current.prepared(match.recipe()), match.maximumParallelism()));
        }
        return mapped;
    }

    @Override
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        var current = data();
        if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
        if (!configuration.preservesInputDiscovery()) return new CompleteCursor(current.recipes, groups, parallelism);
        return new MappingCursor(current, current.provider.beginMatch(inventory, parallelism, Set.of()), groups);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        if (!configuration.preservesInputDiscovery()) return null;
        var current = data();
        var cursor = current.provider.beginCandidates(machine, Set.of());
        return cursor == null ? null : new MappingCursor(current, cursor, groups);
    }

    @Override
    public ConfiguredRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        var raw = new ObjectArrayList<MachineRecipe>(recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            if (!configuration.matches(recipe, additions))
                throw new IllegalArgumentException("Recipe lost common pool declarations: " + recipe.id());
            raw.add(withoutAdditions(recipe));
        }
        var frozen = source.freeze(raw, machines);
        var current = new State(frozen, raw, recipes, machines);
        return new ConfiguredRecipePool(frozen, configuration, additions, existingJeiPageId, current);
    }

    private static final class State {
        private final RecipePool provider;
        private final List<MachineRecipe> recipes;
        private final List<MachineType> machines;
        private final Reference2ObjectOpenHashMap<MachineRecipe, MachineRecipe> forward = new Reference2ObjectOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<MachineRecipe, MachineRecipe> reverse = new Reference2ObjectOpenHashMap<>();

        private State(RecipePool provider, List<MachineRecipe> raw, List<MachineRecipe> prepared, List<MachineType> machines) {
            if (raw.size() != prepared.size()) throw new IllegalArgumentException("Recipe mapping count differs");
            this.provider = provider;
            recipes = prepared;
            this.machines = machines;
            for (int i = 0; i < raw.size(); i++) {
                if (!raw.get(i).id().equals(prepared.get(i).id()))
                    throw new IllegalArgumentException("Recipe mapping identity differs");
                forward.put(raw.get(i), prepared.get(i));
                reverse.put(prepared.get(i), raw.get(i));
            }
        }

        private MachineRecipe prepared(MachineRecipe recipe) {
            var result = forward.get(recipe);
            if (result == null) throw new IllegalArgumentException("Provider returned foreign recipe: " + recipe.id());
            return result;
        }

        private MachineRecipe raw(MachineRecipe recipe) {
            var result = reverse.get(recipe);
            if (result == null) throw new IllegalArgumentException("Unknown configured recipe: " + recipe.id());
            return result;
        }
    }

    private static final class MappingCursor implements RecipeCandidateIndex.Cursor {
        private final State state;
        private final RecipeCandidateIndex.Cursor source;
        private final Set<ResourceLocation> groups;
        private final ObjectArrayList<MachineRecipe> mapped = new ObjectArrayList<>();
        private @Nullable Iterator<MachineRecipe> iterator;
        private @Nullable MachineRecipe pending;
        private @Nullable Iterator<ResourceLocation> pendingGroups;
        private boolean acquired;
        private boolean complete;
        private boolean noOpinion;

        private MappingCursor(State state, RecipeCandidateIndex.Cursor source, Set<ResourceLocation> groups) {
            this.state = state;
            this.source = source;
            this.groups = groups;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (complete) return true;
            if (!acquired) {
                if (!source.advance(budget)) return false;
                var result = source.result();
                acquired = true;
                if (result == null) {
                    noOpinion = true;
                    complete = true;
                    return true;
                }
                iterator = result.iterator();
            }
            while (pending != null || Objects.requireNonNull(iterator).hasNext()) {
                if (pending == null) {
                    if (!budget.take()) return false;
                    pending = state.prepared(iterator.next());
                    if (groups.isEmpty()) {
                        mapped.add(pending);
                        pending = null;
                        continue;
                    }
                    pendingGroups = pending.recipeGroups().iterator();
                }
                var candidates = Objects.requireNonNull(pendingGroups);
                while (candidates.hasNext()) {
                    if (!budget.take()) return false;
                    if (groups.contains(candidates.next())) {
                        mapped.add(pending);
                        break;
                    }
                }
                pending = null;
                pendingGroups = null;
            }
            complete = true;
            return true;
        }

        @Override
        public @Nullable Collection<MachineRecipe> result() {
            if (!complete) throw new IllegalStateException("Configured pool lookup is incomplete");
            return noOpinion ? null : mapped;
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            return source.inputParallelismLimit(state.raw(recipe));
        }

        @Override
        public boolean current() {
            return source.current();
        }

        @Override
        public RecipeCandidateIndex.Validation beginValidation() {
            return source.beginValidation();
        }
    }

    private static final class CompleteCursor implements RecipeCandidateIndex.Cursor {
        private final List<MachineRecipe> recipes;
        private final Set<ResourceLocation> groups;
        private final int parallelism;
        private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private int index;
        private @Nullable MachineRecipe pending;
        private @Nullable Iterator<ResourceLocation> pendingGroups;

        private CompleteCursor(List<MachineRecipe> recipes, Set<ResourceLocation> groups, int parallelism) {
            this.recipes = recipes;
            this.groups = groups;
            this.parallelism = parallelism;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            while (pending != null || index < recipes.size()) {
                if (pending == null) {
                    if (!budget.take()) return false;
                    pending = recipes.get(index++);
                    if (groups.isEmpty()) {
                        result.add(pending);
                        pending = null;
                        continue;
                    }
                    pendingGroups = pending.recipeGroups().iterator();
                }
                var candidates = Objects.requireNonNull(pendingGroups);
                while (candidates.hasNext()) {
                    if (!budget.take()) return false;
                    if (groups.contains(candidates.next())) {
                        result.add(pending);
                        break;
                    }
                }
                pending = null;
                pendingGroups = null;
            }
            return true;
        }

        @Override
        public Collection<MachineRecipe> result() {
            if (index != recipes.size() || pending != null)
                throw new IllegalStateException("Configured pool lookup is incomplete");
            return result;
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            return parallelism;
        }
    }
}
