package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.AbstractObjectList;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Canonical pool/recipe identities and priority are frozen once. Each pool owns its discovery strategy. */
public final class RecipeCatalog {
    private static final int COMBINATION_CACHE_SIZE = 16;
    private static final int RETRY_INDEX_CACHE_SIZE = 64;
    private static final ResourceLocation COMBINATION_ID = new ResourceLocation("prototype_eng", "selected_recipe_pools");
    private final Map<ResourceLocation, RecipePool> pools;
    private final List<RecipePool> orderedPools;
    private final Map<ResourceLocation, MachineRecipe> recipes;
    private final List<MachineRecipe> orderedRecipes;
    private final boolean stableFixedInputLowerBounds;
    private final Reference2ObjectOpenHashMap<RecipePool, PoolMembers> members = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, ObjectArrayList<RecipePool>> owners = new Reference2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<MachineRecipe> priority = new Reference2IntOpenHashMap<>();
    private final ObjectArrayList<Combination> combinations = new ObjectArrayList<>();
    private final Reference2ObjectLinkedOpenHashMap<MachineRecipe, ResourceRecipeIndex> retryIndexes = new Reference2ObjectLinkedOpenHashMap<>();

    public RecipeCatalog(Collection<? extends RecipePool> pools) {
        var knownPools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>(pools.size());
        var canonical = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        for (var pool : Objects.requireNonNull(pools, "pools")) {
            Objects.requireNonNull(pool, "pool");
            if (knownPools.putIfAbsent(Objects.requireNonNull(pool.id()), pool) != null)
                throw new IllegalArgumentException("Duplicate catalog pool: " + pool.id());
            var poolRecipes = new ObjectArrayList<MachineRecipe>(pool.recipes().size());
            var membership = new ReferenceOpenHashSet<MachineRecipe>(pool.recipes().size());
            var groups = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<MachineRecipe>>();
            var ids = new ObjectLinkedOpenHashSet<ResourceLocation>(pool.recipes().size());
            for (var recipe : Objects.requireNonNull(pool.recipes(), "pool recipes")) {
                Objects.requireNonNull(recipe, "pool recipe");
                if (!ids.add(recipe.id()))
                    throw new IllegalArgumentException("Duplicate recipe in pool: " + recipe.id());
                var previous = canonical.putIfAbsent(recipe.id(), recipe);
                if (previous != null && previous != recipe)
                    throw new IllegalArgumentException("Different canonical recipes share catalog ID: " + recipe.id());
                if (previous == null) priority.put(recipe, priority.size());
                poolRecipes.add(recipe);
                membership.add(recipe);
                owners.computeIfAbsent(recipe, _ -> new ObjectArrayList<>(2)).add(pool);
                for (var group : recipe.recipeGroups())
                    groups.computeIfAbsent(group, _ -> new ObjectArrayList<>(6)).add(recipe);
            }
            var frozenGroups = new Object2ObjectOpenHashMap<ResourceLocation, List<MachineRecipe>>(groups.size());
            groups.forEach((group, rows) -> frozenGroups.put(group, ObjectLists.unmodifiable(rows)));
            members.put(pool, new PoolMembers(ObjectLists.unmodifiable(poolRecipes), membership,
                Object2ObjectMaps.unmodifiable(frozenGroups)));
        }
        this.pools = Object2ObjectMaps.unmodifiable(knownPools);
        orderedPools = ObjectLists.unmodifiable(new ObjectArrayList<>(knownPools.values()));
        recipes = Object2ObjectMaps.unmodifiable(canonical);
        orderedRecipes = ObjectLists.unmodifiable(new ObjectArrayList<>(canonical.values()));
        boolean stable = true;
        for (var recipe : orderedRecipes)
            if (!recipe.parallelismSelection().stableFixedInputLowerBounds()) {
                stable = false;
                break;
            }
        stableFixedInputLowerBounds = stable;
    }

    public List<RecipePool> pools() {
        return orderedPools;
    }

    public List<MachineRecipe> recipes() {
        return orderedRecipes;
    }

    /** Resolves transient retry hints against this catalog's exact canonical definition. */
    public @Nullable MachineRecipe recipe(ResourceLocation id) {
        return recipes.get(Objects.requireNonNull(id));
    }

    /** Owner capture for a retry whose caller guarantees stable canonical fixed inputs. */
    @SuppressWarnings("ReturnOfInnerClass") // The keyed capture retains this exact owner's quantity and revision proof.
    public synchronized RecipeCandidateIndex.Cursor beginFixedInputCandidates(MachineRecipe recipe, MachineInstance machine,
                                                                              Set<ResourceLocation> groups) {
        if (recipes.get(Objects.requireNonNull(recipe).id()) != recipe) throw new IllegalArgumentException("Foreign retry recipe");
        var index = retryIndexes.getAndMoveToLast(recipe);
        if (index == null) {
            index = ResourceRecipeIndex.forFixedInputs(ObjectLists.singleton(recipe), _ -> true);
            if (retryIndexes.size() == RETRY_INDEX_CACHE_SIZE) retryIndexes.removeFirst();
            retryIndexes.put(recipe, index);
        }
        return index.beginLookup(machine, groups, ObjectLists.singleton(recipe));
    }

    /** Candidates are in canonical order; rotate by a stable ID without copying their list. */
    int candidateOffset(List<MachineRecipe> candidates, @Nullable ResourceLocation previous) {
        if (previous == null || candidates.size() < 2) return 0;
        var recipe = recipes.get(previous);
        if (recipe == null) return 0;
        int ordinal = priority.getInt(recipe);
        int low = 0, high = candidates.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (priority.getInt(candidates.get(middle)) <= ordinal) low = middle + 1;
            else high = middle;
        }
        return low == candidates.size() ? 0 : low;
    }

    /** Shared immutable summary; scanners combine it with their own factory/preparation guarantee. */
    public boolean supportsStableFixedInputLowerBounds() {
        return stableFixedInputLowerBounds;
    }

    /** Lazy immutable static-ID view, visited by the original work budget. */
    public List<RecipePool> poolsFor(MachineType type) {
        var ids = Objects.requireNonNull(type).recipePoolIds();
        return ObjectLists.unmodifiable(new AbstractObjectList<>() {
            @Override
            public RecipePool get(int index) {
                var id = ids.get(index);
                var pool = pools.get(id);
                if (pool == null) throw new IllegalArgumentException("Unknown declared recipe pool: " + id);
                return pool;
            }

            @Override
            public int size() {
                return ids.size();
            }
        });
    }

    @SuppressWarnings("ReturnOfInnerClass")
    public SelectionCapture beginSelection(MachineInstance machine) {
        return new SelectionCapture(machine);
    }

    @SuppressWarnings({"StatementWithEmptyBody", "ReturnOfInnerClass"})
    public Selection select(MachineInstance machine) {
        var capture = beginSelection(machine);
        while (!capture.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* explicit synchronous caller */ }
        return capture.result();
    }

    public List<MachineRecipe> candidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        return candidates(select(machine), groups, indexes);
    }

    public List<MachineRecipe> candidates(Selection selection, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        var pass = beginCandidates(selection, groups, indexes);
        var budget = new RecipeWorkBudget(Long.MAX_VALUE);
        while (!pass.advance(budget)) if (!pass.current()) pass = beginCandidates(selection, groups, indexes);
        return pass.result();
    }

    @SuppressWarnings("ReturnOfInnerClass")
    public CandidatePass beginCandidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        return new CandidatePass(machine, groups, indexes, null);
    }

    @SuppressWarnings("ReturnOfInnerClass")
    public CandidatePass beginCandidates(Selection selection, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        return beginCandidates(selection, groups, indexes, () -> true);
    }

    @SuppressWarnings("ReturnOfInnerClass") // The scanner captures the exact source generation for this pass.
    CandidatePass beginCandidates(Selection selection, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes,
                                  BooleanSupplier definitionCurrent) {
        if (Objects.requireNonNull(selection).catalog != this)
            throw new IllegalArgumentException("Foreign pool selection");
        return new CandidatePass(selection.machine, groups, indexes, selection, definitionCurrent);
    }

    private record PoolMembers(List<MachineRecipe> recipes, ReferenceOpenHashSet<MachineRecipe> identities,
                               Map<ResourceLocation, List<MachineRecipe>> groups) {
    }

    /** Compiled on first discovery demand and shared through a bounded identity cache. */
    private synchronized @Nullable RecipePool combined(List<RecipePool> selectedPools) {
        if (selectedPools.isEmpty()) return null;
        if (selectedPools.size() == 1) return selectedPools.getFirst();
        for (int index = 0; index < combinations.size(); index++) {
            var combination = combinations.get(index);
            if (!combination.matches(selectedPools)) continue;
            if (index != combinations.size() - 1) {
                combinations.remove(index);
                combinations.add(combination);
            }
            return combination.pool;
        }
        var providers = ObjectLists.unmodifiable(new ObjectArrayList<>(selectedPools));
        var pool = new CompositeRecipePool(COMBINATION_ID, providers.getFirst().translationKey(), providers);
        if (combinations.size() == COMBINATION_CACHE_SIZE) combinations.removeFirst();
        combinations.add(new Combination(providers, pool));
        return pool;
    }

    private record Combination(List<RecipePool> providers, CompositeRecipePool pool) {
        private boolean matches(List<RecipePool> selected) {
            if (providers.size() != selected.size()) return false;
            for (int index = 0; index < providers.size(); index++) if (providers.get(index) != selected.get(index)) return false;
            return true;
        }
    }

    /** Frozen selected pools identify membership; selection does not enumerate every recipe each pass. */
    public final class Selection {
        private final RecipeCatalog catalog = RecipeCatalog.this;
        private final MachineInstance machine;
        private final ReferenceOpenHashSet<RecipePool> selected;
        private final List<RecipePool> selectedPools;
        private volatile @Nullable RecipePool combinedPool;

        private Selection(MachineInstance machine, ObjectArrayList<RecipePool> selectedPools, ReferenceOpenHashSet<RecipePool> selected) {
            this.machine = machine;
            this.selected = selected;
            // SelectionCapture exclusively owns this completed provider list; no recipe is visited here.
            this.selectedPools = ObjectLists.unmodifiable(selectedPools);
            combinedPool = selectedPools.size() == 1 ? selectedPools.getFirst() : null;
        }

        /**
         * Empty selections have no pool and one provider stays exact. Mixed providers compile lazily
         * on the first caller, normally the discovery worker; an explicit owner call requests compilation there.
         */
        public @Nullable RecipePool combinedPool() {
            if (selectedPools.size() < 2) return combinedPool;
            var pool = combinedPool;
            if (pool == null) {
                pool = combined(selectedPools);
                combinedPool = pool;
            }
            return pool;
        }

        public boolean accepts(MachineInstance owner, MachineRecipe recipe) {
            if (owner != machine) throw new IllegalArgumentException("Pool selection belongs to another machine");
            if (recipes.get(recipe.id()) != recipe) return false;
            var recipeOwners = owners.get(recipe);
            for (var pool : recipeOwners) if (selected.contains(pool)) return true;
            return false;
        }
    }

    public final class SelectionCapture {
        private final MachineInstance machine;
        private final Iterator<RecipePool> input;
        private final ReferenceOpenHashSet<RecipePool> selected = new ReferenceOpenHashSet<>();
        private final ObjectArrayList<RecipePool> ordered = new ObjectArrayList<>();
        private Selection result;

        private SelectionCapture(MachineInstance machine) {
            this.machine = Objects.requireNonNull(machine);
            input = Objects.requireNonNull(machine.getRecipePools(RecipeCatalog.this), "Selected recipe pools").iterator();
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (result != null) return true;
            while (input.hasNext()) {
                if (!budget.take()) return false;
                var pool = Objects.requireNonNull(input.next(), "Selected recipe pool");
                if (pools.get(pool.id()) != pool)
                    throw new IllegalArgumentException("Selected recipe pool is foreign or counterfeit: " + pool.id());
                if (selected.add(pool)) ordered.add(pool);
            }
            result = new Selection(machine, ordered, selected);
            return true;
        }

        @SuppressWarnings("ReturnOfInnerClass")
        public Selection result() {
            if (result == null) throw new IllegalStateException("Pool selection is incomplete");
            return result;
        }
    }

    /** Pool opinions union first; existing external index opinions intersect that same ordered scope. */
    public final class CandidatePass {
        private final MachineInstance machine;
        private final BooleanSupplier definitionCurrent;
        private final Iterator<ResourceLocation> inputGroups;
        private final Iterator<RecipeCandidateIndex> inputIndexes;
        private final ObjectLinkedOpenHashSet<ResourceLocation> groupCopy = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<RecipeCandidateIndex> indexCopy = new ObjectArrayList<>();
        private Selection selection;
        private SelectionCapture selectionCapture;
        private Set<ResourceLocation> frozenGroups;
        private int phase;
        private int poolIndex;
        private RecipePool pool;
        private PoolMembers poolMembers;
        private RecipeCandidateIndex.Cursor poolLookup;
        private boolean poolReady;
        private Iterator<ResourceLocation> fallbackGroups;
        private Iterator<MachineRecipe> poolOpinion;
        private MachineRecipe pendingRecipe;
        private Iterator<ResourceLocation> pendingGroups;
        private boolean groupAccepted;
        private Int2ObjectAVLTreeMap<MachineRecipe> chosen = new Int2ObjectAVLTreeMap<>();
        private Reference2IntOpenHashMap<MachineRecipe> inventoryBounds = new Reference2IntOpenHashMap<>();
        private ObjectArrayList<RecipeCandidateIndex.Cursor> poolObserved = new ObjectArrayList<>();
        private Iterator<MachineRecipe> scopeIterator;
        private ObjectArrayList<MachineRecipe> orderedScope = new ObjectArrayList<>();
        private List<MachineRecipe> scope;
        private Reference2IntOpenHashMap<MachineRecipe> agreements = new Reference2IntOpenHashMap<>();
        private ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
        private ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private ReferenceOpenHashSet<MachineRecipe> accepted;
        private RecipeCandidateIndex.Cursor lookup;
        private Iterator<MachineRecipe> opinion;
        private int indexCursor;
        private int outputCursor;
        private RecipeCandidateIndex.Validation finalValidation;
        private int opinions;
        private boolean stale;

        private CandidatePass(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes,
                              @Nullable Selection selection) {
            this(machine, groups, indexes, selection, () -> true);
        }

        private CandidatePass(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes,
                              @Nullable Selection selection, BooleanSupplier definitionCurrent) {
            this.machine = Objects.requireNonNull(machine);
            this.definitionCurrent = Objects.requireNonNull(definitionCurrent);
            inputGroups = Objects.requireNonNull(groups).iterator();
            inputIndexes = Objects.requireNonNull(indexes).iterator();
            this.selection = selection;
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (!definitionCurrent.getAsBoolean()) return false;
            if (phase == 7) return true;
            if (stale) return false;
            while (true) {
                if (phase == 0) {
                    while (inputGroups.hasNext()) {
                        if (!budget.take()) return false;
                        groupCopy.add(Objects.requireNonNull(inputGroups.next()));
                    }
                    while (inputIndexes.hasNext()) {
                        if (!budget.take()) return false;
                        indexCopy.add(Objects.requireNonNull(inputIndexes.next()));
                    }
                    frozenGroups = ObjectSets.unmodifiable(groupCopy);
                    phase = 1;
                }
                if (phase == 1) {
                    if (selection == null) {
                        if (selectionCapture == null) {
                            if (!budget.take()) return false;
                            selectionCapture = beginSelection(machine);
                        }
                        if (!selectionCapture.advance(budget)) return false;
                        selection = selectionCapture.result();
                    }
                    phase = 2;
                }
                if (phase == 2) {
                    while (pool != null || poolIndex == 0 && selection.combinedPool() != null) {
                        if (!definitionCurrent.getAsBoolean()) return false;
                        if (pool == null) {
                            if (!budget.take()) return false;
                            pool = Objects.requireNonNull(selection.combinedPool());
                            poolIndex = 1;
                            poolMembers = members.get(pool);
                            poolLookup = pool.beginCandidates(machine, frozenGroups);
                            poolReady = poolLookup == null;
                            if (poolLookup != null) poolObserved.add(poolLookup);
                            else fallback();
                        }
                        if (!poolReady) {
                            if (!definitionCurrent.getAsBoolean()) return false;
                            if (!poolLookup.advance(budget)) {
                                stale = !poolLookup.current();
                                return false;
                            }
                            var values = poolLookup.result();
                            if (values == null) fallback();
                            else poolOpinion = values.iterator();
                            poolReady = true;
                        }
                        while (pendingRecipe != null || poolOpinion != null || fallbackGroups != null) {
                            if (pendingRecipe == null) {
                                if (poolOpinion == null || !poolOpinion.hasNext()) {
                                    poolOpinion = null;
                                    if (fallbackGroups == null || !fallbackGroups.hasNext()) {
                                        fallbackGroups = null;
                                        break;
                                    }
                                    if (!budget.take()) return false;
                                    var values = poolMembers.groups.get(fallbackGroups.next());
                                    if (values != null) poolOpinion = values.iterator();
                                    continue;
                                }
                                if (!budget.take()) return false;
                                pendingRecipe = Objects.requireNonNull(poolOpinion.next(), "pool candidate");
                                if (recipes.get(pendingRecipe.id()) != pendingRecipe || (poolMembers == null
                                    ? !selection.accepts(machine, pendingRecipe) : !poolMembers.identities.contains(pendingRecipe)))
                                    throw new IllegalArgumentException("Pool candidate is foreign or outside its pool: " + pendingRecipe.id());
                                pendingGroups = pendingRecipe.recipeGroups().iterator();
                                groupAccepted = frozenGroups.isEmpty();
                            }
                            while (!groupAccepted && pendingGroups.hasNext()) {
                                if (!budget.take()) return false;
                                groupAccepted = frozenGroups.contains(pendingGroups.next());
                            }
                            if (groupAccepted) {
                                chosen.put(priority.getInt(pendingRecipe), pendingRecipe);
                                int bound = poolLookup == null ? Integer.MAX_VALUE : poolLookup.inputParallelismLimit(pendingRecipe);
                                if (bound < 1) throw new IllegalArgumentException("Invalid pool input ceiling");
                                inventoryBounds.put(pendingRecipe, Math.max(inventoryBounds.getOrDefault(pendingRecipe, 0), bound));
                            }
                            pendingRecipe = null;
                        }
                        pool = null;
                        poolLookup = null;
                        poolMembers = null;
                    }
                    scopeIterator = chosen.values().iterator();
                    phase = 3;
                }
                if (phase == 3) {
                    while (scopeIterator.hasNext()) {
                        if (!budget.take()) return false;
                        orderedScope.add(scopeIterator.next());
                    }
                    scope = ObjectLists.unmodifiable(orderedScope);
                    phase = scope.isEmpty() ? 6 : 4;
                }
                if (phase == 4) {
                    while (indexCursor < indexCopy.size()) {
                        if (!definitionCurrent.getAsBoolean()) return false;
                        if (lookup == null) {
                            if (!budget.take()) return false;
                            lookup = Objects.requireNonNull(indexCopy.get(indexCursor).beginLookup(machine, frozenGroups, scope));
                            observed.add(lookup);
                        }
                        if (!definitionCurrent.getAsBoolean()) return false;
                        if (!lookup.advance(budget)) {
                            stale = !lookup.current();
                            return false;
                        }
                        if (opinion == null) {
                            var values = lookup.result();
                            if (values == null) {
                                lookup = null;
                                indexCursor++;
                                continue;
                            }
                            opinions++;
                            opinion = values.iterator();
                            accepted = new ReferenceOpenHashSet<>();
                        }
                        while (opinion.hasNext()) {
                            if (!budget.take()) return false;
                            var candidate = Objects.requireNonNull(opinion.next(), "indexed candidate");
                            if (recipes.get(candidate.id()) != candidate || !chosen.containsKey(priority.getInt(candidate)))
                                throw new IllegalArgumentException("Indexed recipe is foreign or outside its group scope: " + candidate.id());
                            if (accepted.add(candidate)) agreements.addTo(candidate, 1);
                        }
                        opinion = null;
                        lookup = null;
                        indexCursor++;
                    }
                    phase = 5;
                }
                if (phase == 5) {
                    while (outputCursor < scope.size()) {
                        if (!budget.take()) return false;
                        var recipe = scope.get(outputCursor++);
                        if (agreements.getInt(recipe) == opinions) result.add(recipe);
                    }
                    phase = 6;
                }
                if (phase == 6) {
                    if (finalValidation == null) finalValidation = beginValidation();
                    if (!finalValidation.advance(budget) || !finalValidation.current()) return false;
                    phase = 7;
                    return true;
                }
            }
        }

        private void fallback() {
            if (poolMembers == null) throw new IllegalStateException("Composite pool must preserve its own child fallbacks");
            if (frozenGroups.isEmpty()) poolOpinion = poolMembers.recipes.iterator();
            else fallbackGroups = frozenGroups.iterator();
        }

        public boolean current() {
            return !stale && definitionCurrent.getAsBoolean();
        }

        /** Rechecks every observed live opinion, preserving partial discovery while proofs stay current. */
        @SuppressWarnings("ReturnOfInnerClass") // Validation retains the observations of this exact pass.
        public RecipeCandidateIndex.Validation beginValidation() {
            return new RecipeCandidateIndex.Validation() {
                private final int poolChecks = poolObserved.size();
                private final int externalChecks = observed.size();
                private int cursor;
                private RecipeCandidateIndex.Validation child;
                private boolean complete;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    Objects.requireNonNull(budget);
                    if (complete) return true;
                    if (!CandidatePass.this.current()) {
                        complete = true;
                        return true;
                    }
                    while (cursor < poolChecks + externalChecks) {
                        if (!definitionCurrent.getAsBoolean()) {
                            complete = true;
                            return true;
                        }
                        if (child == null) {
                            if (!budget.take()) return false;
                            var opinion = cursor < poolChecks ? poolObserved.get(cursor) : observed.get(cursor - poolChecks);
                            child = Objects.requireNonNull(opinion.beginValidation());
                        }
                        if (!child.advance(budget)) return false;
                        if (!child.current()) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                        child = null;
                        cursor++;
                    }
                    complete = true;
                    return true;
                }

                @Override
                public boolean current() {
                    if (!complete) throw new IllegalStateException("Candidate validation is incomplete");
                    return CandidatePass.this.current();
                }
            };
        }

        /** Keep selected pools/groups, but recapture both pool strategies and external live indexes. */
        public void restartLiveIndexes() {
            inventoryBounds = new Reference2IntOpenHashMap<>();
            if (phase < 2) return;
            poolIndex = 0;
            pool = null;
            poolMembers = null;
            poolLookup = null;
            poolReady = false;
            fallbackGroups = null;
            poolOpinion = null;
            pendingRecipe = null;
            pendingGroups = null;
            chosen = new Int2ObjectAVLTreeMap<>();
            poolObserved = new ObjectArrayList<>();
            scopeIterator = null;
            orderedScope = new ObjectArrayList<>();
            scope = null;
            agreements = new Reference2IntOpenHashMap<>();
            observed = new ObjectArrayList<>();
            result = new ObjectArrayList<>();
            accepted = null;
            lookup = null;
            opinion = null;
            indexCursor = 0;
            outputCursor = 0;
            finalValidation = null;
            opinions = 0;
            stale = false;
            phase = 2;
        }

        public List<MachineRecipe> result() {
            if (phase != 7 || !current()) throw new IllegalStateException("Candidate pass is incomplete or stale");
            return ObjectLists.unmodifiable(result);
        }

        /** A union takes the least restrictive pool ceiling; observations still require fresh validation. */
        public int inputParallelismLimit(MachineRecipe recipe) {
            if (phase != 7 || !current()) throw new IllegalStateException("Candidate pass is incomplete or stale");
            return inventoryBounds.getOrDefault(recipe, Integer.MAX_VALUE);
        }
    }
}
