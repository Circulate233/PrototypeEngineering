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

/** Frozen canonical recipes in pool/declaration order; empty query groups select the complete catalog. */
public final class RecipeCatalog {
    private final Map<ResourceLocation, MachineRecipe> recipes;
    private final List<MachineRecipe> orderedRecipes;
    private final Map<ResourceLocation, List<MachineRecipe>> byGroup;

    public RecipeCatalog(Collection<RecipePool> pools) {
        Objects.requireNonNull(pools, "pools");
        var canonical = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var groups = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<MachineRecipe>>();
        for (var pool : pools) {
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
        recipes = Object2ObjectMaps.unmodifiable(canonical);
        orderedRecipes = ObjectLists.unmodifiable(new ObjectArrayList<>(canonical.values()));
        var frozenGroups = new Object2ObjectOpenHashMap<ResourceLocation, List<MachineRecipe>>();
        groups.forEach((group, members) -> frozenGroups.put(group, ObjectLists.unmodifiable(members)));
        byGroup = Object2ObjectMaps.unmodifiable(frozenGroups);
    }

    /** Canonical definitions in frozen pool/declaration order, for building external indexes. */
    public List<MachineRecipe> recipes() {
        return orderedRecipes;
    }

    /**
     * Empty groups select every canonical recipe, including recipes with no groups. Nonempty groups
     * select their union. Each index sees the same full immutable scope; nonnull results intersect.
     */
    public List<MachineRecipe> candidates(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
        var pass = beginCandidates(machine, groups, indexes);
        var budget = new RecipeWorkBudget(Long.MAX_VALUE);
        while (!pass.advance(budget)) {
            if (!pass.current()) {
                pass = beginCandidates(machine, groups, indexes);
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
        return new CandidatePass(machine, groups, indexes);
    }

    public final class CandidatePass {
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

        private CandidatePass(MachineInstance machine, Set<ResourceLocation> groups, List<RecipeCandidateIndex> indexes) {
            this.machine = Objects.requireNonNull(machine, "machine");
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
                                scoped.add(members.get(memberCursor++));
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
                        if (groupCopy.isEmpty()) scoped.add(recipe);
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
