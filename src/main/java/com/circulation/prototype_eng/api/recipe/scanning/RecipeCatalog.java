package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.Iterator;
import com.circulation.prototype_eng.api.machine.MachineType;
import it.unimi.dsi.fastutil.objects.AbstractObjectList;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

/** Canonical pool/recipe identities and priority are frozen once. Each pool owns its discovery strategy. */
public final class RecipeCatalog {
    private final Map<ResourceLocation, RecipePool> pools;
    private final List<RecipePool> orderedPools;
    private final Map<ResourceLocation, MachineRecipe> recipes;
    private final List<MachineRecipe> orderedRecipes;
    private final Reference2ObjectOpenHashMap<RecipePool, PoolMembers> members = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, ObjectArrayList<RecipePool>> owners = new Reference2ObjectOpenHashMap<>();
    private final Reference2IntOpenHashMap<MachineRecipe> priority = new Reference2IntOpenHashMap<>();

    public RecipeCatalog(Collection<? extends RecipePool> pools) {
        var knownPools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>();
        var canonical = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        for (var pool : Objects.requireNonNull(pools, "pools")) {
            Objects.requireNonNull(pool, "pool");
            if (knownPools.putIfAbsent(Objects.requireNonNull(pool.id()), pool) != null)
                throw new IllegalArgumentException("Duplicate catalog pool: " + pool.id());
            var poolRecipes = new ObjectArrayList<MachineRecipe>();
            var membership = new ReferenceOpenHashSet<MachineRecipe>();
            var groups = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<MachineRecipe>>();
            var ids = new ObjectLinkedOpenHashSet<ResourceLocation>();
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
                owners.computeIfAbsent(recipe, _ -> new ObjectArrayList<>()).add(pool);
                for (var group : recipe.recipeGroups())
                    groups.computeIfAbsent(group, _ -> new ObjectArrayList<>()).add(recipe);
            }
            var frozenGroups = new Object2ObjectOpenHashMap<ResourceLocation, List<MachineRecipe>>();
            groups.forEach((group, rows) -> frozenGroups.put(group, ObjectLists.unmodifiable(rows)));
            members.put(pool, new PoolMembers(ObjectLists.unmodifiable(poolRecipes), membership,
                Object2ObjectMaps.unmodifiable(frozenGroups)));
        }
        this.pools = Object2ObjectMaps.unmodifiable(knownPools);
        orderedPools = ObjectLists.unmodifiable(new ObjectArrayList<>(knownPools.values()));
        recipes = Object2ObjectMaps.unmodifiable(canonical);
        orderedRecipes = ObjectLists.unmodifiable(new ObjectArrayList<>(canonical.values()));
    }

    private record PoolMembers(List<MachineRecipe> recipes, ReferenceOpenHashSet<MachineRecipe> identities,
                               Map<ResourceLocation, List<MachineRecipe>> groups) {
    }

    public List<RecipePool> pools() { return orderedPools; }

    /** Lazy immutable static-ID view, visited by the original work budget. */
    public List<RecipePool> poolsFor(MachineType type) {
        var ids = Objects.requireNonNull(type).recipePoolIds();
        return ObjectLists.unmodifiable(new AbstractObjectList<>() {
            @Override public RecipePool get(int index) {
                var id = ids.get(index);
                var pool = pools.get(id);
                if (pool == null) throw new IllegalArgumentException("Unknown declared recipe pool: " + id);
                return pool;
            }
            @Override public int size() { return ids.size(); }
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

    /** Frozen selected pools identify membership; selection does not enumerate every recipe each pass. */
    public final class Selection {
        private final RecipeCatalog catalog = RecipeCatalog.this;
        private final MachineInstance machine;
        private final List<RecipePool> selectedPools;
        private final ReferenceOpenHashSet<RecipePool> selected;

        private Selection(MachineInstance machine, List<RecipePool> selectedPools, ReferenceOpenHashSet<RecipePool> selected) {
            this.machine = machine;
            this.selectedPools = ObjectLists.unmodifiable(new ObjectArrayList<>(selectedPools));
            this.selected = selected;
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
                    if (pools.get(pool.id()) != pool) throw new IllegalArgumentException("Selected recipe pool is foreign or counterfeit: " + pool.id());
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

    public List<MachineRecipe> recipes() {
        return orderedRecipes;
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
        if (Objects.requireNonNull(selection).catalog != this) throw new IllegalArgumentException("Foreign pool selection");
        return new CandidatePass(selection.machine, groups, indexes, selection);
    }

    /** Pool opinions union first; existing external index opinions intersect that same ordered scope. */
    public final class CandidatePass {
        private Selection selection;
        private SelectionCapture selectionCapture;
        private final MachineInstance machine;
        private final Iterator<ResourceLocation> inputGroups;
        private final Iterator<RecipeCandidateIndex> inputIndexes;
        private final ObjectLinkedOpenHashSet<ResourceLocation> groupCopy = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<RecipeCandidateIndex> indexCopy = new ObjectArrayList<>();
        private ObjectArrayList<MachineRecipe> orderedScope = new ObjectArrayList<>();
        private Reference2IntOpenHashMap<MachineRecipe> agreements = new Reference2IntOpenHashMap<>();
        private ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
        private ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private ReferenceOpenHashSet<MachineRecipe> accepted;
        private Set<ResourceLocation> frozenGroups;
        private List<MachineRecipe> scope;
        private int indexCursor;
        private int outputCursor;
        private RecipeCandidateIndex.Validation finalValidation;
        private int opinions;
        private RecipeCandidateIndex.Cursor lookup;
        private Iterator<MachineRecipe> opinion;
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
        private ObjectArrayList<RecipeCandidateIndex.Cursor> poolObserved = new ObjectArrayList<>();
        private Iterator<MachineRecipe> scopeIterator;
        private boolean stale;

        private CandidatePass(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes,
                              @Nullable Selection selection) {
            this.machine = Objects.requireNonNull(machine);
            inputGroups = Objects.requireNonNull(groups).iterator();
            inputIndexes = Objects.requireNonNull(indexes).iterator();
            this.selection = selection;
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
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
                    while (pool != null || poolIndex < selection.selectedPools.size()) {
                        if (pool == null) {
                            if (!budget.take()) return false;
                            pool = selection.selectedPools.get(poolIndex++);
                            poolMembers = members.get(pool);
                            poolLookup = pool.beginCandidates(machine, frozenGroups);
                            poolReady = poolLookup == null;
                            if (poolLookup != null) poolObserved.add(poolLookup);
                            else fallback();
                        }
                        if (!poolReady) {
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
                                if (recipes.get(pendingRecipe.id()) != pendingRecipe || !poolMembers.identities.contains(pendingRecipe))
                                    throw new IllegalArgumentException("Pool candidate is foreign or outside its pool: " + pendingRecipe.id());
                                pendingGroups = pendingRecipe.recipeGroups().iterator();
                                groupAccepted = frozenGroups.isEmpty();
                            }
                            while (!groupAccepted && pendingGroups.hasNext()) {
                        if (!budget.take()) return false;
                                groupAccepted = frozenGroups.contains(pendingGroups.next());
                    }
                            if (groupAccepted) chosen.put(priority.getInt(pendingRecipe), pendingRecipe);
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
                        if (lookup == null) {
                            if (!budget.take()) return false;
                            lookup = Objects.requireNonNull(indexCopy.get(indexCursor).beginLookup(machine, frozenGroups, scope));
                            observed.add(lookup);
                        }
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
            if (frozenGroups.isEmpty()) poolOpinion = poolMembers.recipes.iterator();
            else fallbackGroups = frozenGroups.iterator();
        }

        public boolean current() {
            return !stale;
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
                    if (stale) { complete = true; return true; }
                    while (cursor < poolChecks + externalChecks) {
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
                    return !stale;
                }
            };
        }

        /** Keep selected pools/groups, but recapture both pool strategies and external live indexes. */
        public void restartLiveIndexes() {
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
            if (phase != 7 || stale) throw new IllegalStateException("Candidate pass is incomplete or stale");
            return ObjectLists.unmodifiable(result);
        }
    }
}
