package com.circulation.prototype_eng.api.recipe.scanning;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
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
import java.util.Comparator;
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
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(scope, "scope");
        var scoped = new ReferenceOpenHashSet<MachineRecipe>();
        var result = new ObjectArrayList<MachineRecipe>();
        for (var recipe : scope) {
            Objects.requireNonNull(recipe, "scoped recipe");
            if (!order.containsKey(recipe)) {
                throw new IllegalArgumentException("Scope contains a foreign canonical recipe: " + recipe.id());
            }
            if (inGroups(recipe, groups) && scoped.add(recipe) && !indexed.contains(recipe)) {
                result.add(recipe);
            }
        }
        if (scoped.isEmpty()) { return ObjectLists.emptyList(); }
        if (result.size() != scoped.size()) {
            var matches = new Reference2IntOpenHashMap<MachineRecipe>();
            var available = availableInputs(machine);
            for (var entry : available.object2LongEntrySet()) {
                for (var posting : postings.get(entry.getKey())) {
                    if (scoped.contains(posting.recipe()) && entry.getLongValue() >= posting.amount()
                        && matches.addTo(posting.recipe(), 1) + 1 == posting.keys()) {
                        result.add(posting.recipe());
                    }
                }
            }
        }
        result.sort(Comparator.comparingInt(order::getInt));
        return ObjectLists.unmodifiable(result);
    }

    private Object2LongOpenHashMap<AEKey> availableInputs(MachineInstance machine) {
        var byContext = new Reference2ObjectOpenHashMap<StorageContext, Object2LongOpenHashMap<AEKey>>();
        var available = new Object2LongOpenHashMap<AEKey>();
        for (var hatch : MachineHatches.get(machine).resources()) {
            if (!hatch.isValid()) { continue; }
            var storage = hatch.input();
            if (storage == null || !storage.isValid()) { continue; }
            var context = Objects.requireNonNull(storage.context(), "input context");
            var snapshot = Objects.requireNonNull(storage.snapshot(), "input snapshot");
            if (snapshot.context() != context) {
                throw new IllegalStateException("Input snapshot changed its physical context");
            }
            var maxima = byContext.computeIfAbsent(context, _ -> new Object2LongOpenHashMap<>());
            for (var entry : snapshot.available().object2LongEntrySet()) {
                var key = Objects.requireNonNull(entry.getKey(), "input key");
                long amount = entry.getLongValue();
                if (amount < 0) { throw new IllegalStateException("Negative input availability"); }
                if (!postings.containsKey(key)) { continue; }
                long previous = maxima.getLong(key);
                if (amount > previous) {
                    maxima.put(key, amount);
                    long delta = amount - previous;
                    long total = available.getLong(key);
                    available.put(key, delta > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + delta);
                }
            }
        }
        return available;
    }

    private static boolean inGroups(MachineRecipe recipe, Set<ResourceLocation> groups) {
        for (var group : recipe.recipeGroups()) {
            if (groups.contains(group)) { return true; }
        }
        return false;
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
            var amounts = new Object2LongLinkedOpenHashMap<AEKey>();
            for (var input : Objects.requireNonNull(inputs, "inputs")) {
                Objects.requireNonNull(input, "input");
                var key = Objects.requireNonNull(input.what(), "input key");
                long amount = input.amount();
                long previous = amounts.getLong(key);
                if (amount <= 0) {
                    throw new IllegalArgumentException("Nonpositive mandatory input amount");
                }
                amounts.put(key, amount > Long.MAX_VALUE - previous ? Long.MAX_VALUE : previous + amount);
            }
            var normalized = new ObjectArrayList<GenericStack>(amounts.size());
            amounts.object2LongEntrySet().forEach(entry -> normalized.add(new GenericStack(entry.getKey(), entry.getLongValue())));
            inputs = ObjectLists.unmodifiable(normalized);
        }
    }

    private record Posting(MachineRecipe recipe, long amount, int keys) { }
}
