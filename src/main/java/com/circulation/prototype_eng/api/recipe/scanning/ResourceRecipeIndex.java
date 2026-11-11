package com.circulation.prototype_eng.api.recipe.scanning;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Frozen AEKey-to-recipe postings for explicitly guaranteed START input lower bounds. This migrates
 * PM's key inverted index without inferring eligibility from requirement types or payloads. Recipes
 * without a nonempty declaration remain candidates, including energy-only, resource-free, dynamic,
 * selective and externally modified recipes. All declared keys must have sufficient live availability.
 *
 * <p>The owner must rebuild or omit this index when factory, preparation, overlay or resource-access
 * assumptions change. Lookup reads current hatch snapshots and cannot certify future availability,
 * START success, a maximum parallelism or execution cost. It performs no acquisition or mutation.
 */
public final class ResourceRecipeIndex implements RecipeCandidateIndex {
    private final Reference2IntMap<MachineRecipe> order;
    private final ReferenceSet<MachineRecipe> indexed;
    private final Object2ObjectMap<AEKey, List<Posting>> postings;
    private final List<MachineRecipe> canonical;
    private final List<MachineRecipe> unfiltered;

    public ResourceRecipeIndex(RecipeCatalog catalog, Collection<Footprint> footprints) {
        this(Objects.requireNonNull(catalog, "catalog").recipes(), footprints);
    }

    private ResourceRecipeIndex(List<MachineRecipe> recipes, Collection<Footprint> footprints) {
        canonical = ObjectLists.unmodifiable(new ObjectArrayList<>(Objects.requireNonNull(recipes)));
        var ordinals = new Reference2IntOpenHashMap<MachineRecipe>();
        for (var recipe : canonical) {
            ordinals.put(recipe, ordinals.size());
        }
        order = Reference2IntMaps.unmodifiable(ordinals);
        var declared = new ReferenceOpenHashSet<MachineRecipe>();
        var filtered = new ReferenceOpenHashSet<MachineRecipe>();
        var mutable = new Object2ObjectOpenHashMap<AEKey, ObjectArrayList<Posting>>();
        for (var footprint : Objects.requireNonNull(footprints, "footprints")) {
            Objects.requireNonNull(footprint, "footprint");
            var recipe = footprint.recipe();
            if (!order.containsKey(recipe) || !declared.add(recipe)) {
                throw new IllegalArgumentException("Foreign or repeated recipe footprint: " + recipe.id());
            }
            if (footprint.inputs().isEmpty()) {
                continue;
            }
            filtered.add(recipe);
            int keys = footprint.inputs().size();
            for (var input : footprint.inputs()) {
                mutable.computeIfAbsent(input.what(), _ -> new ObjectArrayList<>()).add(new Posting(recipe, input.amount(), keys));
            }
        }
        indexed = ReferenceSets.unmodifiable(filtered);
        var retained = new ObjectArrayList<MachineRecipe>();
        for (var recipe : canonical) if (!filtered.contains(recipe)) retained.add(recipe);
        unfiltered = ObjectLists.unmodifiable(retained);
        var frozen = new Object2ObjectOpenHashMap<AEKey, List<Posting>>();
        mutable.forEach((key, values) -> frozen.put(key, ObjectLists.unmodifiable(values)));
        postings = Object2ObjectMaps.unmodifiable(frozen);
    }

    /**
     * Extracts existing fixed START inputs only after the owner's explicit stability assertion.
     * The predicate asserts the complete Footprint contract for the extracted inputs, including
     * factory/preparation/overlay effects. Supported definitions are flat canonical built-in resource
     * and energy requirements, with unconditional resource chance and no fuzzy/dynamic groups.
     * Selectors, checkpoints, attribute requirements, custom types and unsupported resource payloads
     * stay unfiltered even when the predicate returns true. TICK/END IO is not indexed.
     */
    public static ResourceRecipeIndex forFixedInputs(RecipeCatalog catalog, Predicate<MachineRecipe> stableInputs) {
        return forFixedInputs(Objects.requireNonNull(catalog, "catalog").recipes(), stableInputs);
    }

    public static ResourceRecipeIndex forFixedInputs(List<MachineRecipe> recipes, Predicate<MachineRecipe> stableInputs) {
        Objects.requireNonNull(recipes);
        Objects.requireNonNull(stableInputs, "stableInputs");
        var footprints = new ObjectArrayList<Footprint>();
        for (var recipe : recipes) {
            if (stableInputs.test(recipe)) {
                footprints.add(new Footprint(recipe, fixedInputs(recipe)));
            }
        }
        return new ResourceRecipeIndex(recipes, footprints);
    }

    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        var lookup = new InventoryLookup(inventory, parallelism, Set.of());
        lookup.advance(new RecipeWorkBudget(Long.MAX_VALUE));
        var result = new ObjectArrayList<RecipeInventoryMatch>();
        for (var recipe : lookup.result()) result.add(new RecipeInventoryMatch(recipe, lookup.inputParallelismLimit(recipe)));
        return ObjectLists.unmodifiable(result);
    }

    /** Pure snapshot matching; every inventory entry, posting and output member spends its own credit. */
    @SuppressWarnings("ReturnOfInnerClass") // This pure lookup retains only the immutable index and supplied snapshot.
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return new InventoryLookup(inventory, parallelism, groups);
    }

    private final class InventoryLookup implements RecipeCandidateIndex.Cursor {
        private final AEKey2LongMap inventory;
        private final int parallelism;
        private final Iterator<ResourceLocation> inputGroups;
        private final ObjectLinkedOpenHashSet<ResourceLocation> groups = new ObjectLinkedOpenHashSet<>();
        private final Reference2IntOpenHashMap<MachineRecipe> matches = new Reference2IntOpenHashMap<>();
        private final Reference2IntOpenHashMap<MachineRecipe> ceilings = new Reference2IntOpenHashMap<>();
        private final Int2ObjectAVLTreeMap<MachineRecipe> chosen = new Int2ObjectAVLTreeMap<>();
        private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private Iterator<Object2ObjectMap.Entry<AEKey, List<Posting>>> singleInputs;
        private Iterator<Object2LongMap.Entry<AEKey>> entries;
        private Object2LongMap.Entry<AEKey> entry;
        private List<Posting> keyPostings;
        private int postingIndex;
        private int unfilteredIndex;
        private Iterator<MachineRecipe> ordered;
        private MachineRecipe pending;
        private Iterator<ResourceLocation> pendingGroups;
        private boolean groupAccepted;
        private int singleCeiling;
        private int phase;

        private InventoryLookup(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
            this.inventory = Objects.requireNonNull(inventory);
            if (parallelism < 1) throw new IllegalArgumentException("Nonpositive matching parallelism");
            this.parallelism = parallelism;
            singleCeiling = parallelism;
            inputGroups = Objects.requireNonNull(groups).iterator();
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (phase == 3) return true;
            if (phase == 0) {
                while (inputGroups.hasNext()) {
                    if (!budget.take()) return false;
                    groups.add(Objects.requireNonNull(inputGroups.next()));
                }
                if (canonical.size() == 1) singleInputs = postings.object2ObjectEntrySet().iterator();
                else entries = inventory.object2LongEntrySet().iterator();
                phase = 1;
            }
            if (phase == 1) {
                if (singleInputs != null) {
                    while (singleCeiling != 0 && singleInputs.hasNext()) {
                        if (!budget.take()) return false;
                        var required = singleInputs.next();
                        singleCeiling = Math.min(singleCeiling, RecipeInventoryMatch.limit(inventory.getLong(required.getKey()),
                            required.getValue().getFirst().amount, parallelism));
                    }
                    var recipe = canonical.getFirst();
                    if (singleCeiling > 0) {
                        chosen.put(order.getInt(recipe), recipe);
                        ceilings.put(recipe, singleCeiling);
                    }
                } else {
                    while (entry != null || entries.hasNext()) {
                        if (entry == null) {
                            if (!budget.take()) return false;
                            entry = entries.next();
                            keyPostings = postings.get(entry.getKey());
                            postingIndex = 0;
                        }
                        if (keyPostings != null) {
                            while (postingIndex < keyPostings.size()) {
                                if (!budget.take()) return false;
                                var posting = keyPostings.get(postingIndex++);
                                int ceiling = RecipeInventoryMatch.limit(entry.getLongValue(), posting.amount, parallelism);
                                if (ceiling == 0) continue;
                                ceilings.put(posting.recipe, Math.min(ceilings.getOrDefault(posting.recipe, parallelism), ceiling));
                                if (matches.addTo(posting.recipe, 1) + 1 == posting.keys) chosen.put(order.getInt(posting.recipe), posting.recipe);
                            }
                        }
                        entry = null;
                    }
                }
                phase = 2;
            }
            if (phase == 2) {
                while (unfilteredIndex < unfiltered.size()) {
                    if (!budget.take()) return false;
                    var recipe = unfiltered.get(unfilteredIndex++);
                    chosen.put(order.getInt(recipe), recipe);
                }
                if (ordered == null) ordered = chosen.values().iterator();
                while (pending != null || ordered.hasNext()) {
                    if (pending == null) {
                        if (!budget.take()) return false;
                        var recipe = ordered.next();
                        pending = recipe;
                        pendingGroups = recipe.recipeGroups().iterator();
                        groupAccepted = groups.isEmpty();
                    }
                    while (!groupAccepted && pendingGroups.hasNext()) {
                        if (!budget.take()) return false;
                        groupAccepted = groups.contains(pendingGroups.next());
                    }
                    if (groupAccepted) result.add(pending);
                    pending = null;
                }
                phase = 3;
            }
            return true;
        }

        @Override
        public List<MachineRecipe> result() {
            requireComplete();
            return ObjectLists.unmodifiable(result);
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            requireComplete();
            return ceilings.getOrDefault(recipe, parallelism);
        }

        private void requireComplete() {
            if (phase != 3) throw new IllegalStateException("Inventory opinion is incomplete");
        }
    }

    private static List<GenericStack> fixedInputs(MachineRecipe recipe) {
        var inputs = new ObjectArrayList<GenericStack>();
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.type() == RecipeRequirements.ENERGY) {
                continue;
            }
            if (requirement.type() != RecipeRequirements.RESOURCES || !(requirement.payload() instanceof ResourceRequirement resource)
                || !resource.fuzzyInputs().isEmpty() || !resource.dynamicInputs().isEmpty()
                || resource.chance().percent() != 100 || resource.chance().attribute() != null) {
                return ObjectLists.emptyList();
            }
            inputs.addAll(resource.inputs());
        }
        return inputs;
    }

    /**
     * Always supplies an opinion; absent/empty input hatches still retain every undeclared recipe.
     */
    @Override
    public List<MachineRecipe> lookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope) {
        var cursor = beginLookup(machine, groups, scope);
        var budget = new RecipeWorkBudget(Long.MAX_VALUE);
        while (!cursor.advance(budget)) {
            if (!cursor.current()) cursor = beginLookup(machine, groups, scope);
        }
        return cursor.result();
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public QuantityLookup beginLookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope) {
        return new QuantityLookup(machine, groups, scope);
    }

    private record CapturedView(ResourceHatch hatch, ResourceStorage storage, StorageContext context, Object identity,
                                ResourceStorage.QuantityCapture capture) {
        private boolean current() {
            if (!hatch.isValid()) return false;
            var current = hatch.input();
            return current != null && current.isValid() && current.context() == context
                && hatch.inputStorageIdentity() == identity && storage.isValid() && storage.context() == context && capture.current();
        }
    }

    /** Skipped null/invalid inputs also belong to the exact captured lookup opinion. */
    private record Binding(ResourceHatch hatch, boolean valid, boolean present, @Nullable Object identity,
                           @Nullable StorageContext context, boolean storageValid) {
        private boolean current() {
            if (hatch.isValid() != valid) return false;
            if (!valid) return true;
            var storage = hatch.input();
            return present ? storage != null && storage.isValid() == storageValid && storage.context() == context
                && hatch.inputStorageIdentity() == identity : storage == null;
        }
    }

    /**
     * An explicit eligibility assertion, not an inferred copy of definition inputs. Before ANY
     * successful START, the machine's public input snapshots must jointly contain at least these
     * quantities, across every candidate seed, parallelism, probability policy, local modifier,
     * factory, preparation and overlay that can be used with this index. Earlier START callbacks
     * must not manufacture a declared input that was unavailable at lookup. Omit a recipe whose
     * fixed inputs may disappear, shrink, become optional or be replaced by other keys.
     *
     * <p>Each AEKey keeps full AE2 identity; repeated equal keys are summed with saturation at long
     * capacity, distinct types stay distinct. Saturation is a conservative lower bound, not proof of
     * full quantity feasibility across multiple requirements. Empty inputs opt out of filtering.
     * Extra undeclared requirements need not be proved.
     */
    public record Footprint(MachineRecipe recipe, List<GenericStack> inputs) {
        public Footprint {
            Objects.requireNonNull(recipe, "recipe");
            var amounts = new AEKey2LongMap.LinkdOpenHashMap();
            for (var input : Objects.requireNonNull(inputs, "inputs")) {
                Objects.requireNonNull(input, "input");
                var key = Objects.requireNonNull(input.what(), "input key");
                long amount = input.amount();
                long previous = amounts.getLong(key);
                if (amount <= 0) {
                    throw new IllegalArgumentException("Nonpositive mandatory input amount");
                }
                amounts.addTo(key, Math.min(amount, Long.MAX_VALUE - previous));
            }
            var normalized = new ObjectArrayList<GenericStack>(amounts.size());
            amounts.object2LongEntrySet().forEach(entry -> normalized.add(new GenericStack(entry.getKey(), entry.getLongValue())));
            inputs = ObjectLists.unmodifiable(normalized);
        }
    }

    private record Posting(MachineRecipe recipe, long amount, int keys) {
    }

    /**
     * A private opinion until every view, posting and final current proof has been visited.
     */
    public final class QuantityLookup implements RecipeCandidateIndex.Cursor {
        private final MachineInstance machine;
        private final Set<ResourceLocation> groups;
        private final List<MachineRecipe> scope;
        private final ReferenceOpenHashSet<MachineRecipe> scoped = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<MachineRecipe> accepted = new ReferenceOpenHashSet<>();
        private final Reference2IntOpenHashMap<MachineRecipe> matches = new Reference2IntOpenHashMap<>();
        private final Reference2IntOpenHashMap<MachineRecipe> ceilings = new Reference2IntOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<StorageContext, AEKey2LongMap> byContext = new Reference2ObjectOpenHashMap<>();
        private final AEKey2LongMap available = new AEKey2LongMap.SaturatingOpenHashMap();
        private final ObjectArrayList<CapturedView> captures = new ObjectArrayList<>();
        private final ObjectArrayList<Binding> bindings = new ObjectArrayList<>();
        private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private List<ResourceHatch> hatches;
        private int scopeCursor;
        private MachineRecipe scopedRecipe;
        private Iterator<ResourceLocation> recipeGroups;
        private boolean groupAccepted;
        private int required;
        private int hatchCursor;
        private CapturedView view;
        private Iterator<Object2LongMap.Entry<AEKey>> quantities;
        private Iterator<Object2LongMap.Entry<AEKey>> availableEntries;
        private Object2LongMap.Entry<AEKey> availableEntry;
        private List<Posting> keyPostings;
        private int postingCursor;
        private int outputCursor;
        private int proofCursor;
        private int bindingProofCursor;
        private boolean bindingsChecked;
        private int phase;
        private boolean stale;

        private QuantityLookup(MachineInstance machine, Set<ResourceLocation> groups, List<MachineRecipe> scope) {
            this.machine = Objects.requireNonNull(machine, "machine");
            this.groups = Objects.requireNonNull(groups, "groups");
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (phase == 5) return true;
            if (stale) return false;
            while (true) {
                if (phase == 0) {
                    while (scopedRecipe != null || scopeCursor < scope.size()) {
                        if (scopedRecipe == null) {
                            if (!budget.take()) return false;
                            scopedRecipe = Objects.requireNonNull(scope.get(scopeCursor++), "scoped recipe");
                            if (!order.containsKey(scopedRecipe))
                                throw new IllegalArgumentException("Scope contains a foreign canonical recipe: " + scopedRecipe.id());
                            recipeGroups = scopedRecipe.recipeGroups().iterator();
                            groupAccepted = groups.isEmpty();
                        }
                        while (!groupAccepted && recipeGroups.hasNext()) {
                            if (!budget.take()) return false;
                            groupAccepted = groups.contains(recipeGroups.next());
                        }
                        if (groupAccepted && scoped.add(scopedRecipe)) {
                            if (indexed.contains(scopedRecipe)) required++;
                            else accepted.add(scopedRecipe);
                        }
                        scopedRecipe = null;
                    }
                    phase = required == 0 ? 3 : 1;
                }
                if (phase == 1) {
                    if (hatches == null) {
                        if (!budget.take()) return false;
                        hatches = MachineHatches.get(machine).resources();
                    }
                    while (view != null || hatchCursor < hatches.size()) {
                        if (view == null) {
                            if (!budget.take()) return false;
                            var hatch = hatches.get(hatchCursor++);
                            boolean valid = hatch.isValid();
                            var storage = valid ? hatch.input() : null;
                            var context = storage == null ? null : Objects.requireNonNull(storage.context(), "input context");
                            var identity = storage == null ? null : Objects.requireNonNull(hatch.inputStorageIdentity(), "input identity");
                            boolean storageValid = storage != null && storage.isValid();
                            bindings.add(new Binding(hatch, valid, storage != null, identity, context, storageValid));
                            if (!valid || storage == null || !storageValid) continue;
                            view = new CapturedView(hatch, storage, context, identity, Objects.requireNonNull(postings.size() == 1
                                ? storage.beginQuantityCapture(postings.keySet().iterator().next()) : storage.beginQuantityCapture()));
                            captures.add(view);
                        }
                        if (quantities == null) {
                            if (!view.capture.advance(budget)) return false;
                            if (!view.current()) {
                                stale = true;
                                return false;
                            }
                            quantities = Objects.requireNonNull(view.capture.available()).object2LongEntrySet().iterator();
                        }
                        while (quantities.hasNext()) {
                            if (!budget.take()) return false;
                            var entry = quantities.next();
                            var key = Objects.requireNonNull(entry.getKey(), "input key");
                            long amount = entry.getLongValue();
                            if (amount < 0) throw new IllegalStateException("Negative input availability");
                            if (!postings.containsKey(key)) continue;
                            var maxima = byContext.computeIfAbsent(view.context, _ -> new AEKey2LongMap.OpenHashMap());
                            long previous = maxima.getLong(key);
                            if (amount > previous) {
                                long delta = amount - previous;
                                maxima.addTo(key, delta);
                                available.addTo(key, delta);
                            }
                        }
                        quantities = null;
                        view = null;
                    }
                    availableEntries = available.object2LongEntrySet().iterator();
                    phase = 2;
                }
                if (phase == 2) {
                    while (availableEntry != null || availableEntries.hasNext()) {
                        if (availableEntry == null) {
                            if (!budget.take()) return false;
                            availableEntry = availableEntries.next();
                            keyPostings = postings.get(availableEntry.getKey());
                            postingCursor = 0;
                        }
                        while (postingCursor < keyPostings.size()) {
                            if (!budget.take()) return false;
                            var posting = keyPostings.get(postingCursor++);
                            if (scoped.contains(posting.recipe()) && availableEntry.getLongValue() >= posting.amount()) {
                                int bound = RecipeInventoryMatch.limit(availableEntry.getLongValue(), posting.amount(), Integer.MAX_VALUE);
                                ceilings.put(posting.recipe(), Math.min(ceilings.getOrDefault(posting.recipe(), Integer.MAX_VALUE), bound));
                                if (matches.addTo(posting.recipe(), 1) + 1 == posting.keys()) accepted.add(posting.recipe());
                            }
                        }
                        availableEntry = null;
                    }
                    phase = 3;
                }
                if (phase == 3) {
                    while (outputCursor < canonical.size()) {
                        if (!budget.take()) return false;
                        var recipe = canonical.get(outputCursor++);
                        if (accepted.contains(recipe)) result.add(recipe);
                    }
                    phase = 4;
                }
                if (phase == 4) {
                    if (!bindingsChecked && hatches != null) {
                        if (!budget.take()) return false;
                        bindingsChecked = true;
                        if (MachineHatches.get(machine).resources() != hatches) {
                            stale = true;
                            return false;
                        }
                    }
                    while (bindingProofCursor < bindings.size()) {
                        if (!budget.take()) return false;
                        if (!bindings.get(bindingProofCursor++).current()) {
                            stale = true;
                            return false;
                        }
                    }
                    while (proofCursor < captures.size()) {
                        if (!budget.take()) return false;
                        if (!captures.get(proofCursor++).current()) {
                            stale = true;
                            return false;
                        }
                    }
                    phase = 5;
                    return true;
                }
            }
        }

        @Override
        public boolean current() {
            return !stale;
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Validation retains this lookup's exact quantity observations.
        public RecipeCandidateIndex.Validation beginValidation() {
            return new RecipeCandidateIndex.Validation() {
                private final int captureCount = captures.size() - (view != null && quantities == null ? 1 : 0);
                private final int bindingCount = bindings.size();
                private int cursor;
                private int bindingCursor;
                private boolean ownerChecked;
                private boolean complete;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    Objects.requireNonNull(budget);
                    if (complete) return true;
                    if (stale) {
                        complete = true;
                        return true;
                    }
                    if (!ownerChecked) {
                        if (!budget.take()) return false;
                        ownerChecked = true;
                        if (hatches != null && MachineHatches.get(machine).resources() != hatches) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                    }
                    while (bindingCursor < bindingCount) {
                        if (!budget.take()) return false;
                        if (!bindings.get(bindingCursor++).current()) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                    }
                    while (cursor < captureCount) {
                        if (!budget.take()) return false;
                        if (!captures.get(cursor++).current()) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                    }
                    complete = true;
                    return true;
                }

                @Override
                public boolean current() {
                    if (!complete) throw new IllegalStateException("Resource validation is incomplete");
                    return !stale;
                }
            };
        }

        @Override
        public List<MachineRecipe> result() {
            if (phase != 5 || stale) throw new IllegalStateException("Resource opinion is incomplete or stale");
            return ObjectLists.unmodifiable(result);
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            if (phase != 5 || stale) throw new IllegalStateException("Resource opinion is incomplete or stale");
            return ceilings.getOrDefault(recipe, Integer.MAX_VALUE);
        }
    }
}
