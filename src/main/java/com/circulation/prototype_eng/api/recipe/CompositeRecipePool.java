package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered union of independent pool providers; child strategies and their freshness proofs remain intact. */
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
        this.id = Objects.requireNonNull(id);
        if (translationKey == null || translationKey.isBlank()) throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        var providers = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>();
        var definitions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var order = new Reference2IntOpenHashMap<MachineRecipe>();
        var membership = new ObjectArrayList<Members>();
        for (var pool : Objects.requireNonNull(pools)) {
            Objects.requireNonNull(pool);
            var previousPool = providers.putIfAbsent(Objects.requireNonNull(pool.id()), pool);
            if (previousPool != null) {
                if (previousPool != pool) throw new IllegalArgumentException("Different child pools share ID: " + pool.id());
                continue;
            }
            var childRecipes = new ObjectArrayList<MachineRecipe>();
            var identities = new ReferenceOpenHashSet<MachineRecipe>();
            int previousOrder = -1, minimumOrder = Integer.MAX_VALUE;
            boolean monotonic = true;
            for (var recipe : Objects.requireNonNull(pool.recipes())) {
                Objects.requireNonNull(recipe);
                if (!identities.add(recipe)) throw new IllegalArgumentException("Repeated child recipe: " + recipe.id());
                var previous = definitions.putIfAbsent(recipe.id(), recipe);
                if (previous != null && previous != recipe) throw new IllegalArgumentException("Different canonical recipes share ID: " + recipe.id());
                if (previous == null) order.put(recipe, order.size());
                int ordinal = order.getInt(recipe);
                monotonic &= ordinal > previousOrder;
                previousOrder = ordinal;
                minimumOrder = Math.min(minimumOrder, ordinal);
                childRecipes.add(recipe);
            }
            membership.add(new Members(pool, ObjectLists.unmodifiable(childRecipes), identities, minimumOrder, monotonic));
        }
        this.pools = ObjectLists.unmodifiable(new ObjectArrayList<>(providers.values()));
        members = ObjectLists.unmodifiable(membership);
        recipes = ObjectLists.unmodifiable(new ObjectArrayList<>(definitions.values()));
        this.machines = ObjectLists.unmodifiable(new ObjectArrayList<>(Objects.requireNonNull(machines)));
        canonical = Object2ObjectMaps.unmodifiable(definitions);
        priority = Reference2IntMaps.unmodifiable(order);
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    /** Child order is stable; repeated references to the same provider are visited once. */
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
        Objects.requireNonNull(inventory);
        MachineRecipe best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (var child : members) {
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
                for (var match : child.pool.match(inventory, 1)) {
                    var candidate = Objects.requireNonNull(match).recipe();
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
        Objects.requireNonNull(inventory);
        if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
        var bounds = new Reference2IntOpenHashMap<MachineRecipe>();
        var chosen = new Int2ObjectAVLTreeMap<MachineRecipe>();
        for (var child : members) {
            for (var match : child.pool.match(inventory, parallelism)) {
                Objects.requireNonNull(match);
                var recipe = match.recipe();
                requireMember(child, recipe);
                if (match.maximumParallelism() > parallelism) throw new IllegalArgumentException("Child inventory ceiling exceeds requested parallelism");
                chosen.put(priority.getInt(recipe), recipe);
                bounds.put(recipe, Math.max(bounds.getInt(recipe), match.maximumParallelism()));
            }
        }
        var result = new ObjectArrayList<RecipeInventoryMatch>(chosen.size());
        for (var recipe : chosen.values()) result.add(new RecipeInventoryMatch(recipe, bounds.getInt(recipe)));
        return ObjectLists.unmodifiable(result);
    }

    private void requireMember(Members child, MachineRecipe recipe) {
        if (!child.identities.contains(recipe) || canonical.get(recipe.id()) != recipe)
            throw new IllegalArgumentException("Child candidate is foreign or outside its pool: " + recipe.id());
    }

    @Override
    public CompositeRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        Objects.requireNonNull(recipes);
        Objects.requireNonNull(machines);
        if (recipes.size() != this.recipes.size()) throw new IllegalArgumentException("Composite recipes must belong to a child pool");
        var replacements = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = Objects.requireNonNull(recipes.get(index));
            if (!this.recipes.get(index).id().equals(recipe.id()) || replacements.putIfAbsent(recipe.id(), recipe) != null)
                throw new IllegalArgumentException("Composite freeze changed recipe membership or order");
        }
        var frozen = new ObjectArrayList<RecipePool>(members.size());
        for (var child : members) {
            var childRecipes = new ObjectArrayList<MachineRecipe>(child.recipes.size());
            for (var recipe : child.recipes) childRecipes.add(Objects.requireNonNull(replacements.get(recipe.id())));
            var definitions = ObjectLists.unmodifiable(childRecipes);
            var users = ObjectLists.unmodifiable(new ObjectArrayList<>(machines));
            var provider = Objects.requireNonNull(child.pool.freeze(definitions, users));
            if (!child.pool.id().equals(provider.id()) || !child.pool.translationKey().equals(provider.translationKey())
                || child.pool.hasExistingJeiPage() != provider.hasExistingJeiPage()
                || !Objects.equals(child.pool.existingJeiPageId(), provider.existingJeiPageId())
                || !sameInstances(definitions, provider.recipes()) || !sameInstances(users, provider.machines()))
                throw new IllegalArgumentException("Child freeze changed canonical definition: " + child.pool.id());
            frozen.add(provider);
        }
        return new CompositeRecipePool(id, translationKey, frozen, machines);
    }

    private static boolean sameInstances(List<?> expected, List<?> actual) {
        if (actual == null || expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) if (expected.get(index) != actual.get(index)) return false;
        return true;
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

    private final class Lookup implements RecipeCandidateIndex.Cursor {
        private final @Nullable MachineInstance machine;
        private final @Nullable AEKey2LongMap inventory;
        private final int parallelism;
        private final Iterator<ResourceLocation> inputGroups;
        private final ObjectLinkedOpenHashSet<ResourceLocation> groups = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
        private final Int2ObjectAVLTreeMap<MachineRecipe> chosen = new Int2ObjectAVLTreeMap<>();
        private final Reference2IntOpenHashMap<MachineRecipe> bounds = new Reference2IntOpenHashMap<>();
        private final ObjectArrayList<MachineRecipe> output = new ObjectArrayList<>();
        private Set<ResourceLocation> frozenGroups;
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
        private int phase;
        private boolean stale;

        private Lookup(MachineInstance machine, Set<ResourceLocation> groups) {
            this.machine = Objects.requireNonNull(machine);
            inventory = null;
            parallelism = Integer.MAX_VALUE;
            inputGroups = Objects.requireNonNull(groups).iterator();
        }

        private Lookup(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
            machine = null;
            this.inventory = Objects.requireNonNull(inventory);
            if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
            this.parallelism = parallelism;
            inputGroups = Objects.requireNonNull(groups).iterator();
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (phase == 4) return true;
            if (stale) return false;
            if (phase == 0) {
                while (inputGroups.hasNext()) {
                    if (!budget.take()) return false;
                    groups.add(Objects.requireNonNull(inputGroups.next()));
                }
                frozenGroups = ObjectSets.unmodifiable(groups);
                phase = 1;
            }
            if (phase == 1) {
                while (child != null || childIndex < members.size()) {
                    if (child == null) {
                        if (!budget.take()) return false;
                        child = members.get(childIndex++);
                        cursor = inventory == null ? child.pool.beginCandidates(Objects.requireNonNull(machine), frozenGroups)
                            : Objects.requireNonNull(child.pool.beginMatch(inventory, parallelism, frozenGroups));
                        opinionKnown = false;
                        if (cursor == null) opinion = child.recipes.iterator();
                        else observed.add(cursor);
                    }
                    if (opinion == null) {
                        if (!Objects.requireNonNull(cursor).advance(budget)) {
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
                            pending = Objects.requireNonNull(opinion.next());
                            requireMember(child, pending);
                            pendingGroups = pending.recipeGroups().iterator();
                            accepted = frozenGroups.isEmpty();
                        }
                        while (!accepted && pendingGroups.hasNext()) {
                            if (!budget.take()) return false;
                            accepted = frozenGroups.contains(pendingGroups.next());
                        }
                        if (accepted) {
                            int bound = opinionKnown ? Objects.requireNonNull(cursor).inputParallelismLimit(pending) : Integer.MAX_VALUE;
                            if (bound < 1 || bound > parallelism) throw new IllegalArgumentException("Invalid child input ceiling");
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
            return ObjectLists.unmodifiable(output);
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
                    Objects.requireNonNull(budget);
                    if (complete) return true;
                    if (stale) {
                        complete = true;
                        return true;
                    }
                    while (index < count) {
                        if (check == null) {
                            if (!budget.take()) return false;
                            check = Objects.requireNonNull(observed.get(index).beginValidation());
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
