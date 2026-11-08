package com.circulation.prototype_eng.api.recipe.scanning;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Iterator;

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

    /**
     * Extracts existing fixed START inputs only after the owner's explicit stability assertion.
     * The predicate asserts the complete Footprint contract for the extracted inputs, including
     * factory/preparation/overlay effects. Supported definitions are flat canonical built-in resource
     * and energy requirements, with unconditional resource chance and no fuzzy/dynamic groups.
     * Selectors, checkpoints, attribute requirements, custom types and unsupported resource payloads
     * stay unfiltered even when the predicate returns true. TICK/END IO is not indexed.
     */
    public static ResourceRecipeIndex forFixedInputs(RecipeCatalog catalog, Predicate<MachineRecipe> stableInputs) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(stableInputs, "stableInputs");
        var footprints = new ObjectArrayList<Footprint>();
        for (var recipe : catalog.recipes()) {
            if (stableInputs.test(recipe)) { footprints.add(new Footprint(recipe, fixedInputs(recipe))); }
        }
        return new ResourceRecipeIndex(catalog, footprints);
    }

    private static List<GenericStack> fixedInputs(MachineRecipe recipe) {
        var inputs = new ObjectArrayList<GenericStack>();
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.type() == RecipeRequirements.ENERGY) { continue; }
            if (requirement.type() != RecipeRequirements.RESOURCES || !(requirement.payload() instanceof ResourceRequirement resource)
                || !resource.fuzzyInputs().isEmpty() || !resource.dynamicInputs().isEmpty()
                || resource.chance().percent() != 100 || resource.chance().attribute() != null) {
                return ObjectLists.emptyList();
            }
            inputs.addAll(resource.inputs());
        }
        return inputs;
    }

    public ResourceRecipeIndex(RecipeCatalog catalog, Collection<Footprint> footprints) {
        canonical = Objects.requireNonNull(catalog, "catalog").recipes();
        var ordinals = new Reference2IntOpenHashMap<MachineRecipe>();
        for (var recipe : Objects.requireNonNull(catalog, "catalog").recipes()) {
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
            if (footprint.inputs().isEmpty()) { continue; }
            filtered.add(recipe);
            int keys = footprint.inputs().size();
            for (var input : footprint.inputs()) {
                mutable.computeIfAbsent(input.what(), _ -> new ObjectArrayList<>()).add(new Posting(recipe, input.amount(), keys));
            }
        }
        indexed = ReferenceSets.unmodifiable(filtered);
        var frozen = new Object2ObjectOpenHashMap<AEKey, List<Posting>>();
        mutable.forEach((key, values) -> frozen.put(key, ObjectLists.unmodifiable(values)));
        postings = Object2ObjectMaps.unmodifiable(frozen);
    }

    /** Always supplies an opinion; absent/empty input hatches still retain every undeclared recipe. */
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

    /** A private opinion until every view, posting and final current proof has been visited. */
    public final class QuantityLookup implements RecipeCandidateIndex.Cursor {
        private final MachineInstance machine;
        private final Set<ResourceLocation> groups;
        private final List<MachineRecipe> scope;
        private final ReferenceOpenHashSet<MachineRecipe> scoped = new ReferenceOpenHashSet<>();
        private final ReferenceOpenHashSet<MachineRecipe> accepted = new ReferenceOpenHashSet<>();
        private final Reference2IntOpenHashMap<MachineRecipe> matches = new Reference2IntOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<StorageContext, AEKey2LongMap> byContext = new Reference2ObjectOpenHashMap<>();
        private final AEKey2LongMap available = new AEKey2LongMap.SaturatingOpenHashMap();
        private final ObjectArrayList<CapturedView> captures = new ObjectArrayList<>();
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
                            if (!order.containsKey(scopedRecipe)) throw new IllegalArgumentException("Scope contains a foreign canonical recipe: " + scopedRecipe.id());
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
                            if (!hatch.isValid()) continue;
                            var storage = hatch.input();
                            if (storage == null || !storage.isValid()) continue;
                            var context = Objects.requireNonNull(storage.context(), "input context");
                            view = new CapturedView(hatch, storage, context, Objects.requireNonNull(storage.beginQuantityCapture()));
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
                            if (scoped.contains(posting.recipe()) && availableEntry.getLongValue() >= posting.amount()
                                && matches.addTo(posting.recipe(), 1) + 1 == posting.keys()) accepted.add(posting.recipe());
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
        public List<MachineRecipe> result() {
            if (phase != 5 || stale) throw new IllegalStateException("Resource opinion is incomplete or stale");
            return ObjectLists.unmodifiable(result);
        }
    }

    private record CapturedView(ResourceHatch hatch, ResourceStorage storage, StorageContext context,
                                ResourceStorage.QuantityCapture capture) {
        private boolean current() {
            return hatch.isValid() && hatch.input() == storage && storage.isValid() && storage.context() == context && capture.current();
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

    private record Posting(MachineRecipe recipe, long amount, int keys) { }
}
