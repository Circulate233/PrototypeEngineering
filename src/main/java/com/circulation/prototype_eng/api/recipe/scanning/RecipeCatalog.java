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

/**
 * Frozen canonical recipes in pool/declaration order. Instance pool selection defines the scope;
 * empty query groups impose no further restriction inside that scope.
 */
public final class RecipeCatalog {
    private final Map<ResourceLocation, RecipePool> pools;
    private final List<RecipePool> orderedPools;
    private final Map<ResourceLocation, MachineRecipe> recipes;
    private final List<MachineRecipe> orderedRecipes;
    private final Map<ResourceLocation, List<MachineRecipe>> byGroup;

    public RecipeCatalog(Collection<RecipePool> pools) {
        Objects.requireNonNull(pools, "pools");
        var knownPools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>();
        var canonical = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var groups = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<MachineRecipe>>();
        for (var pool : pools) {
            Objects.requireNonNull(pool, "pool");
            if (knownPools.putIfAbsent(pool.id(), pool) != null) {
                throw new IllegalArgumentException("Duplicate catalog pool: " + pool.id());
            }
            for (var recipe : Objects.requireNonNull(pool, "pool").recipes()) {
                var previous = canonical.putIfAbsent(recipe.id(), recipe);
                if (previous != null) {
                    if (previous != recipe) {
                        throw new IllegalArgumentException("Different canonical recipes share catalog ID: " + recipe.id());
                    }
                    continue;
                }
                for (var group : recipe.recipeGroups()) {
                    groups.computeIfAbsent(group, _ -> new ObjectArrayList<>()).add(recipe);
                }
            }
        }
        this.pools = Object2ObjectMaps.unmodifiable(knownPools);
        orderedPools = ObjectLists.unmodifiable(new ObjectArrayList<>(knownPools.values()));
        recipes = Object2ObjectMaps.unmodifiable(canonical);
        orderedRecipes = ObjectLists.unmodifiable(new ObjectArrayList<>(canonical.values()));
        var frozenGroups = new Object2ObjectOpenHashMap<ResourceLocation, List<MachineRecipe>>();
        groups.forEach((group, members) -> frozenGroups.put(group, ObjectLists.unmodifiable(members)));
        byGroup = Object2ObjectMaps.unmodifiable(frozenGroups);
    }

    public List<RecipePool> pools() { return orderedPools; }

    /** Lazy immutable view: resolving static IDs is visited by the scanner's original work budget. */
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

    /** Captures the actual instance override once; enumeration and recipe union share the caller budget. */
    @SuppressWarnings("ReturnOfInnerClass") // The budget cursor resolves only this canonical catalog's pools.
    public SelectionCapture beginSelection(MachineInstance machine) {
        return new SelectionCapture(machine);
    }

    @SuppressWarnings({"ReturnOfInnerClass", "StatementWithEmptyBody"}) // Explicit synchronous capture of the same catalog-bound cursor.
    public Selection select(MachineInstance machine) {
        var capture = beginSelection(machine);
        while (!capture.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* synchronous explicit caller */ }
        return capture.result();
    }

    /** Immutable recipe scope, without storage/formation/permission rights or authority over running work. */
    public final class Selection {
        private final RecipeCatalog catalog = RecipeCatalog.this;
        private final MachineInstance machine;
        private final ReferenceOpenHashSet<MachineRecipe> members;

        private Selection(MachineInstance machine, ReferenceOpenHashSet<MachineRecipe> members) {
            this.machine = machine;
            this.members = members;
        }

        /** Pool and canonical identity restriction for a new catalog admission, never a processing gate. */
        public boolean accepts(MachineInstance owner, MachineRecipe recipe) {
            if (owner != machine) throw new IllegalArgumentException("Pool selection belongs to another machine");
            return recipes.get(recipe.id()) == recipe && members.contains(recipe);
        }
    }

    public final class SelectionCapture {
        private final MachineInstance machine;
        private final Iterator<RecipePool> input;
        private final ReferenceOpenHashSet<RecipePool> selected = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<MachineRecipe> members = new ReferenceOpenHashSet<>();
        private Iterator<RecipePool> poolIterator;
        private Iterator<MachineRecipe> recipeIterator;
        private Selection result;

        private SelectionCapture(MachineInstance machine) {
            this.machine = Objects.requireNonNull(machine);
            input = Objects.requireNonNull(machine.getRecipePools(RecipeCatalog.this), "Selected recipe pools").iterator();
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (result != null) return true;
            if (poolIterator == null) {
                while (input.hasNext()) {
                    if (!budget.take()) return false;
                    var pool = Objects.requireNonNull(input.next(), "Selected recipe pool");
                    if (pools.get(pool.id()) != pool) throw new IllegalArgumentException("Selected recipe pool is foreign or counterfeit: " + pool.id());
                    selected.add(pool);
                }
                poolIterator = selected.iterator();
            }
            while (recipeIterator != null || poolIterator.hasNext()) {
                if (recipeIterator == null) {
                    if (!budget.take()) return false;
                    recipeIterator = poolIterator.next().recipes().iterator();
                }
                while (recipeIterator.hasNext()) {
                    if (!budget.take()) return false;
                    members.add(recipeIterator.next());
                }
                recipeIterator = null;
            }
            result = new Selection(machine, members);
            return true;
        }

        @SuppressWarnings("ReturnOfInnerClass") // A selection keeps its exact catalog and machine identity for admission.
        public Selection result() {
            if (result == null) throw new IllegalStateException("Pool selection is incomplete");
            return result;
        }
    }

    /** Canonical definitions in frozen pool/declaration order, for building external indexes. */
    public List<MachineRecipe> recipes() {
        return orderedRecipes;
    }

    /**
     * Empty groups select all canonical recipes in the selected pools, including ungrouped recipes.
     * Nonempty groups select their union inside those pools. Every index sees that same immutable scope.
     */
    public List<MachineRecipe> candidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        return candidates(select(machine), groups, indexes);
            }

    public List<MachineRecipe> candidates(Selection selection, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        var pass = beginCandidates(selection, groups, indexes);
        var budget = new RecipeWorkBudget(Long.MAX_VALUE);
        while (!pass.advance(budget)) {
            if (!pass.current()) {
                pass = beginCandidates(selection, groups, indexes);
            }
        }
        return pass.result();
    }

    /**
     * Begins without copying or enumerating caller collections. They must remain unchanged until
     * the pass freezes them. All library visits, including index output validation, use this budget.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public CandidatePass beginCandidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        return new CandidatePass(machine, groups, indexes, null);
    }

    @SuppressWarnings("ReturnOfInnerClass") // The original discovery cursor remains bound to this canonical catalog.
    public CandidatePass beginCandidates(Selection selection, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        if (Objects.requireNonNull(selection).catalog != this) throw new IllegalArgumentException("Foreign pool selection");
        return new CandidatePass(selection.machine, groups, indexes, selection);
    }

    public final class CandidatePass {
        private Selection selection;
        private SelectionCapture selectionCapture;
        private final MachineInstance machine;
        private final Iterator<ResourceLocation> inputGroups;
        private final Iterator<RecipeCandidateIndex> inputIndexes;
        private final ObjectLinkedOpenHashSet<ResourceLocation> groupCopy = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<RecipeCandidateIndex> indexCopy = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<MachineRecipe> scoped = new ReferenceOpenHashSet<>();
        private final ObjectArrayList<MachineRecipe> orderedScope = new ObjectArrayList<>();
        private Reference2IntOpenHashMap<MachineRecipe> agreements = new Reference2IntOpenHashMap<>();
        private ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
        private ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private ReferenceOpenHashSet<MachineRecipe> accepted;
        private Set<ResourceLocation> frozenGroups;
        private List<MachineRecipe> scope;
        private Iterator<ResourceLocation> groupIterator;
        private List<MachineRecipe> members;
        private int memberCursor;
        private int canonicalCursor;
        private int indexCursor;
        private int outputCursor;
        private int opinions;
        private RecipeCandidateIndex.Cursor lookup;
        private Iterator<MachineRecipe> opinion;
        private int proofCursor;
        private int phase;
        private boolean stale;

        private CandidatePass(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes, Selection selection) {
            this.machine = Objects.requireNonNull(machine, "machine");
            this.selection = selection;
            inputGroups = Objects.requireNonNull(groups, "groups").iterator();
            inputIndexes = Objects.requireNonNull(indexes, "indexes").iterator();
        }

        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (phase == 6) {
                return true;
            }
            if (stale) {
                return false;
            }
            while (true) {
                if (phase == 0) {
                    if (selection == null) {
                        if (selectionCapture == null) {
                            if (!budget.take()) return false;
                            selectionCapture = beginSelection(machine);
                        }
                        if (!selectionCapture.advance(budget)) return false;
                        selection = selectionCapture.result();
                        selectionCapture = null;
                    }
                    while (inputGroups.hasNext()) {
                        if (!budget.take()) return false;
                        groupCopy.add(Objects.requireNonNull(inputGroups.next(), "group"));
                    }
                    while (inputIndexes.hasNext()) {
                        if (!budget.take()) return false;
                        indexCopy.add(Objects.requireNonNull(inputIndexes.next(), "index"));
                    }
                    frozenGroups = ObjectSets.unmodifiable(groupCopy);
                    groupIterator = groupCopy.iterator();
                    if (selection.members.isEmpty()) {
                        phase = 6;
                        return true;
                    }
                    phase = 1;
                }
                if (phase == 1) {
                    if (!groupCopy.isEmpty()) {
                        while (members != null || groupIterator.hasNext()) {
                            if (members == null) {
                                if (!budget.take()) return false;
                                members = byGroup.get(groupIterator.next());
                                memberCursor = 0;
                                if (members == null) continue;
                            }
                            while (memberCursor < members.size()) {
                                if (!budget.take()) return false;
                                var recipe = members.get(memberCursor++);
                                if (selection.accepts(machine, recipe)) scoped.add(recipe);
                            }
                            members = null;
                        }
                    }
                    phase = 2;
                }
                if (phase == 2) {
                    while (canonicalCursor < orderedRecipes.size()) {
                        if (!budget.take()) return false;
                        var recipe = orderedRecipes.get(canonicalCursor++);
                        if (groupCopy.isEmpty() && selection.accepts(machine, recipe)) scoped.add(recipe);
                        if (scoped.contains(recipe)) orderedScope.add(recipe);
                    }
                    scope = ObjectLists.unmodifiable(orderedScope);
                    phase = scope.isEmpty() ? 6 : 3;
                    if (phase == 6) return true;
                }
                if (phase == 3) {
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
                            if (recipes.get(candidate.id()) != candidate || !scoped.contains(candidate)) {
                                throw new IllegalArgumentException("Indexed recipe is foreign, counterfeit or outside its group scope: " + candidate.id());
                            }
                            if (accepted.add(candidate)) agreements.addTo(candidate, 1);
                        }
                        opinion = null;
                        lookup = null;
                        indexCursor++;
                    }
                    phase = 4;
                }
                if (phase == 4) {
                    while (outputCursor < scope.size()) {
                        if (!budget.take()) return false;
                        var recipe = scope.get(outputCursor++);
                        if (agreements.getInt(recipe) == opinions) result.add(recipe);
                    }
                    phase = 5;
                }
                if (phase == 5) {
                    while (proofCursor < observed.size()) {
                        if (!budget.take()) return false;
                        if (!observed.get(proofCursor++).current()) {
                            stale = true;
                            return false;
                        }
                    }
                    phase = 6;
                    return true;
                }
            }
        }

        public boolean current() {
            return !stale;
        }

        /**
         * A later attempt may reuse only frozen catalog/scope facts. Every live index capture and
         * partial opinion is discarded before reading a new tick; no old storage binding is a lease.
         */
        public void restartLiveIndexes() {
            if (phase < 3) return;
            agreements = new Reference2IntOpenHashMap<>();
            observed = new ObjectArrayList<>();
            result = new ObjectArrayList<>();
            accepted = null;
            lookup = null;
            opinion = null;
            indexCursor = 0;
            outputCursor = 0;
            proofCursor = 0;
            opinions = 0;
            stale = false;
            phase = scope.isEmpty() ? 6 : 3;
        }

        public List<MachineRecipe> result() {
            if (phase != 6 || stale) throw new IllegalStateException("Candidate pass is incomplete or stale");
            return ObjectLists.unmodifiable(result);
        }
    }
}
