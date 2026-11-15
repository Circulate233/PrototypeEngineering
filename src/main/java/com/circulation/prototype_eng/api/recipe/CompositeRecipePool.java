package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Ordered union of independent pool providers; child strategies and their freshness proofs remain intact.
 */
public final class CompositeRecipePool implements RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final List<RecipePool> pools;
    private final List<Members> members;
    private final List<MachineRecipe> recipes;
    private final List<MachineType> machines;
    private final Object2ObjectMap<ResourceLocation, MachineRecipe> canonical;
    private final Reference2IntMap<MachineRecipe> priority;

    public CompositeRecipePool(ResourceLocation id, String translationKey, List<? extends RecipePool> pools) {
        this(id, translationKey, pools, List.of());
    }

    private CompositeRecipePool(ResourceLocation id, String translationKey, List<? extends RecipePool> pools,
                                List<MachineType> machines) {
        this.id = id;
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        var providers = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>(pools.size());
        var definitions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var order = new Reference2IntOpenHashMap<MachineRecipe>();
        var membership = new ObjectArrayList<Members>(pools.size());
        for (int index8 = 0; index8 < pools.size(); index8++) {
            var pool = pools.get(index8);
            var previousPool = providers.putIfAbsent(pool.id(), pool);
            if (previousPool != null) {
                if (previousPool != pool)
                    throw new IllegalArgumentException("Different child pools share ID: " + pool.id());
                continue;
            }
            var childRecipes = pool.recipes();
            var identities = new ReferenceOpenHashSet<MachineRecipe>(pool.recipes().size());
            int previousOrder = -1, minimumOrder = Integer.MAX_VALUE;
            boolean monotonic = true;
            var elements5 = pool.recipes();
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var recipe = elements5.get(index5);
                if (!identities.add(recipe))
                    throw new IllegalArgumentException("Repeated child recipe: " + recipe.id());
                var previous = definitions.putIfAbsent(recipe.id(), recipe);
                if (previous != null && previous != recipe)
                    throw new IllegalArgumentException("Different canonical recipes share ID: " + recipe.id());
                if (previous == null) order.put(recipe, order.size());
                int ordinal = order.getInt(recipe);
                monotonic &= ordinal > previousOrder;
                previousOrder = ordinal;
                minimumOrder = Math.min(minimumOrder, ordinal);
            }
            membership.add(new Members(pool, childRecipes, identities, minimumOrder, monotonic));
        }
        this.pools = new ObjectArrayList<>(providers.values());
        members = membership;
        recipes = new ObjectArrayList<>(definitions.values());
        this.machines = machines;
        canonical = definitions;
        priority = order;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    /**
     * Child order is stable; repeated references to the same provider are visited once.
     */
    public List<RecipePool> pools() {
        return pools;
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
    public @Nullable MachineRecipe match(AEKey2LongMap inventory) {
        MachineRecipe best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (int index7 = 0; index7 < members.size(); index7++) {
            var child = members.get(index7);
            if (child.minimumOrder >= bestOrder) continue;
            if (child.monotonic) {
                var candidate = child.pool.match(inventory);
                if (candidate != null) {
                    requireMember(child, candidate);
                    int order = priority.getInt(candidate);
                    if (order < bestOrder) {
                        best = candidate;
                        bestOrder = order;
                    }
                }
            } else {

                var elements = child.pool.match(inventory, 1);
                for (int index1 = 0; index1 < elements.size(); index1++) {
                    var match = elements.get(index1);
                    var candidate = match.recipe();
                    requireMember(child, candidate);
                    int order = priority.getInt(candidate);
                    if (order < bestOrder) {
                        best = candidate;
                        bestOrder = order;
                    }
                }

            }
            if (bestOrder == 0) break;
        }
        return best;
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
        var bounds = new Reference2IntOpenHashMap<MachineRecipe>();
        var chosen = new Int2ObjectAVLTreeMap<MachineRecipe>();
        for (int index4 = 0; index4 < members.size(); index4++) {
            var child = members.get(index4);
            var elements2 = child.pool.match(inventory, parallelism);
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var match = elements2.get(index2);
                var recipe = match.recipe();
                requireMember(child, recipe);
                if (match.maximumParallelism() > parallelism)
                    throw new IllegalArgumentException("Child inventory ceiling exceeds requested parallelism");
                chosen.put(priority.getInt(recipe), recipe);
                bounds.put(recipe, Math.max(bounds.getInt(recipe), match.maximumParallelism()));
            }
        }
        var result = new ObjectArrayList<RecipeInventoryMatch>(chosen.size());
        for (var recipe : chosen.values()) result.add(new RecipeInventoryMatch(recipe, bounds.getInt(recipe)));
        return result;
    }

    private void requireMember(Members child, MachineRecipe recipe) {
        if (!child.identities.contains(recipe) || canonical.get(recipe.id()) != recipe)
            throw new IllegalArgumentException("Child candidate is foreign or outside its pool: " + recipe.id());
    }

    @Override
    public CompositeRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        if (recipes.size() != this.recipes.size())
            throw new IllegalArgumentException("Composite recipes must belong to a child pool");
        var replacements = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>(recipes.size());
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            if (!this.recipes.get(index).id().equals(recipe.id()) || replacements.putIfAbsent(recipe.id(), recipe) != null)
                throw new IllegalArgumentException("Composite freeze changed recipe membership or order");
        }
        var frozen = new ObjectArrayList<RecipePool>(members.size());
        for (int index6 = 0; index6 < members.size(); index6++) {
            var child = members.get(index6);
            var definitions = new ObjectArrayList<MachineRecipe>(child.recipes.size());
            for (int index3 = 0; index3 < child.recipes.size(); index3++) {
                var recipe = child.recipes.get(index3);
                definitions.add(replacements.get(recipe.id()));
            }
            var provider = child.pool.freeze(definitions, machines);
            frozen.add(provider);
        }
        return new CompositeRecipePool(id, translationKey, frozen, machines);
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The retained cursor belongs to these exact child providers.
    public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return new Lookup(machine, groups);
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The pure cursor retains immutable inventory and exact child providers.
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return new Lookup(inventory, parallelism, groups);
    }

    private record Members(RecipePool pool, List<MachineRecipe> recipes, ReferenceOpenHashSet<MachineRecipe> identities,
                           int minimumOrder, boolean monotonic) {
    }

    /**
     * Input groups are borrowed unchanged throughout this lookup and its validation.
     */
    private final class Lookup implements RecipeCandidateIndex.Cursor {
        private final @Nullable MachineInstance machine;
        private final @Nullable AEKey2LongMap inventory;
        private final int parallelism;
        private final Set<ResourceLocation> groups;
        private final ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
        private final Int2ObjectAVLTreeMap<MachineRecipe> chosen = new Int2ObjectAVLTreeMap<>();
        private final Reference2IntOpenHashMap<MachineRecipe> bounds = new Reference2IntOpenHashMap<>();
        private final ObjectArrayList<MachineRecipe> output = new ObjectArrayList<>();
        private int childIndex;
        private Members child;
        private RecipeCandidateIndex.Cursor cursor;
        private Iterator<MachineRecipe> opinion;
        private boolean opinionKnown;
        private MachineRecipe pending;
        private Iterator<ResourceLocation> pendingGroups;
        private boolean accepted;
        private Iterator<MachineRecipe> ordered;
        private RecipeCandidateIndex.Validation validation;
        private int phase = 1;
        private boolean stale;

        private Lookup(@NotNull MachineInstance machine, Set<ResourceLocation> groups) {
            this.machine = machine;
            inventory = null;
            parallelism = Integer.MAX_VALUE;
            this.groups = groups;
        }

        private Lookup(@NotNull AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
            machine = null;
            this.inventory = inventory;
            if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
            this.parallelism = parallelism;
            this.groups = groups;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (phase == 4) return true;
            if (stale) return false;
            if (phase == 1) {
                while (child != null || childIndex < members.size()) {
                    if (child == null) {
                        if (!budget.take()) return false;
                        child = members.get(childIndex++);
                        cursor = inventory == null ? child.pool.beginCandidates(machine, groups)
                            : child.pool.beginMatch(inventory, parallelism, groups);
                        opinionKnown = false;
                        if (cursor == null) opinion = child.recipes.iterator();
                        else observed.add(cursor);
                    }
                    if (opinion == null) {
                        if (!cursor.advance(budget)) {
                            stale = !cursor.current();
                            return false;
                        }
                        var result = cursor.result();
                        opinionKnown = result != null;
                        opinion = result == null ? child.recipes.iterator() : result.iterator();
                    }
                    while (pending != null || opinion.hasNext()) {
                        if (pending == null) {
                            if (!budget.take()) return false;
                            pending = opinion.next();
                            requireMember(child, pending);
                            pendingGroups = pending.recipeGroups().iterator();
                            accepted = groups.isEmpty();
                        }
                        while (!accepted && pendingGroups.hasNext()) {
                            if (!budget.take()) return false;
                            accepted = groups.contains(pendingGroups.next());
                        }
                        if (accepted) {
                            int bound = opinionKnown ? cursor.inputParallelismLimit(pending) : Integer.MAX_VALUE;
                            if (bound < 1 || bound > parallelism)
                                throw new IllegalArgumentException("Invalid child input ceiling");
                            chosen.put(priority.getInt(pending), pending);
                            bounds.put(pending, Math.max(bounds.getInt(pending), bound));
                        }
                        pending = null;
                    }
                    child = null;
                    cursor = null;
                    opinion = null;
                }
                ordered = chosen.values().iterator();
                phase = 2;
            }
            if (phase == 2) {
                while (ordered.hasNext()) {
                    if (!budget.take()) return false;
                    output.add(ordered.next());
                }
                phase = 3;
            }
            if (phase == 3) {
                if (validation == null) validation = beginValidation();
                if (!validation.advance(budget) || !validation.current()) return false;
                phase = 4;
            }
            return true;
        }

        @Override
        public Collection<MachineRecipe> result() {
            requireComplete();
            return output;
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            requireComplete();
            return bounds.getOrDefault(recipe, Integer.MAX_VALUE);
        }

        private void requireComplete() {
            if (phase != 4 || stale) throw new IllegalStateException("Composite pool opinion is incomplete or stale");
        }

        @Override
        public boolean current() {
            return !stale;
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Each proof retains this lookup's exact observed child cursors.
        public RecipeCandidateIndex.Validation beginValidation() {
            return new RecipeCandidateIndex.Validation() {
                private final int count = observed.size();
                private int index;
                private RecipeCandidateIndex.Validation check;
                private boolean complete;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (complete) return true;
                    if (stale) {
                        complete = true;
                        return true;
                    }
                    while (index < count) {
                        if (check == null) {
                            if (!budget.take()) return false;
                            check = observed.get(index).beginValidation();
                        }
                        if (!check.advance(budget)) return false;
                        if (!check.current()) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                        check = null;
                        index++;
                    }
                    complete = true;
                    return true;
                }

                @Override
                public boolean current() {
                    if (!complete) throw new IllegalStateException("Composite pool validation is incomplete");
                    return !stale;
                }
            };
        }
    }
}
