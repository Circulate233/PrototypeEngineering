package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.GroupedResourceLookup;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class SimpleRecipePool implements RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final List<MachineRecipe> recipes;
    private final List<GenericStack> auxiliaryInputs;
    private final List<MachineType> machines;
    private final ResourceRecipeIndex inputIndex;
    private final Predicate<MachineInstance> stableInputs;
    private final @Nullable String existingJeiPageId;
    private final boolean generalDefinitions;
    private final boolean sparseLookup;
    private final Object2ObjectOpenHashMap<AEKey, List<IndexedEntry>> byInput;
    private final Reference2IntOpenHashMap<MachineRecipe> priority;

    /**
     * Uses the actual owner's scanner/input policy; unknown factories/providers retain every candidate.
     */
    public SimpleRecipePool(ResourceLocation id, String translationKey, List<Entry> entries) {
        this(id, translationKey, entries, MachineInstance::supportsStableFixedInputLowerBounds);
    }

    /**
     * The predicate explicitly guarantees that this machine's complete START path still needs the
     * declared input quantity from its public hatch views, including every factory, probability,
     * local modifier, preparation and overlay. Unknown configurations must return false. An earlier
     * callback must not manufacture a missing input. The index does not authorize acquisition.
     */
    public SimpleRecipePool(ResourceLocation id, String translationKey, List<Entry> entries, Predicate<MachineInstance> stableInputs) {
        this(id, translationKey, definitions(entries), List.of(), List.of(), stableInputs, null);
    }

    public static SimpleRecipePool forRecipes(ResourceLocation id, String translationKey, List<MachineRecipe> recipes) {
        return new SimpleRecipePool(id, translationKey, recipes, List.of(), List.of(),
            MachineInstance::supportsStableFixedInputLowerBounds, null, true);
    }

    private static List<MachineRecipe> definitions(List<Entry> entries) {
        var recipes = new ObjectArrayList<MachineRecipe>(entries.size());
        for (int i = 0; i < entries.size(); i++) recipes.add(entries.get(i).recipe());
        return recipes;
    }

    private SimpleRecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> definitions,
                             List<GenericStack> auxiliaryInputs, List<MachineType> machines,
                             Predicate<MachineInstance> stableInputs, @Nullable String existingJeiPageId) {
        this(id, translationKey, definitions, auxiliaryInputs, machines, stableInputs, existingJeiPageId, false);
    }

    private SimpleRecipePool(ResourceLocation id, String translationKey, List<MachineRecipe> definitions,
                             List<GenericStack> auxiliaryInputs, List<MachineType> machines,
                             Predicate<MachineInstance> stableInputs, @Nullable String existingJeiPageId,
                             boolean generalDefinitions) {
        this.id = id;
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        ResourceRequirement.validateStacks(auxiliaryInputs);
        this.auxiliaryInputs = auxiliaryInputs;
        this.stableInputs = stableInputs;
        this.generalDefinitions = generalDefinitions;
        byInput = new Object2ObjectOpenHashMap<>();
        priority = new Reference2IntOpenHashMap<>();
        var ids = new ObjectOpenHashSet<ResourceLocation>(definitions.size());
        var footprints = new ObjectArrayList<ResourceRecipeIndex.Footprint>(definitions.size());
        var postings = new Object2ObjectOpenHashMap<AEKey, ObjectArrayList<IndexedEntry>>(definitions.size());
        boolean sparse = true;
        for (int index5 = 0; index5 < definitions.size(); index5++) {
            var recipe = definitions.get(index5);
            var primary = generalDefinitions ? singleInput(recipe, auxiliaryInputs)
                : resource(recipe, auxiliaryInputs).inputs().getFirst();
            if (!ids.add(recipe.id()))
                throw new IllegalArgumentException("Duplicate simple recipe: " + recipe.id());
            priority.put(recipe, index5);
            if (primary == null) {
                sparse = false;
                continue;
            }
            var entry = new IndexedEntry(recipe, primary);
            footprints.add(new ResourceRecipeIndex.Footprint(recipe, List.of(primary)));
            postings.computeIfAbsent(primary.what(), _ -> new ObjectArrayList<>()).add(entry);
        }
        postings.forEach((key, values) -> {
            values.sort(Comparator.comparingLong(entry -> entry.input.amount()));
            byInput.put(key, values);
        });
        recipes = definitions;
        inputIndex = new ResourceRecipeIndex(recipes, footprints);
        sparseLookup = sparse;
        this.machines = machines;
        this.existingJeiPageId = existingJeiPageId;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public String translationKey() {
        return translationKey;
    }

    @Override
    public List<MachineRecipe> recipes() {
        return recipes;
    }

    public List<GenericStack> auxiliaryInputs() {
        return auxiliaryInputs;
    }

    @Override
    public RecipePool withConfiguration(RecipeConfiguration configuration) {
        var common = RecipeConfiguration.builder().auxiliaryInputs(auxiliaryInputs).build().merge(configuration);
        return new ConfiguredRecipePool(withAuxiliaryInputs(List.of()), common);
    }

    /**
     * Returns a new declaration with shared START inputs. Bind revisions, codecs and callbacks to
     * the returned pool's recipes; their identities change when the shared inputs change.
     */
    public SimpleRecipePool withAuxiliaryInputs(List<GenericStack> inputs) {
        ResourceRequirement.validateStacks(inputs);
        if (auxiliaryInputs.equals(inputs)) return this;
        var updated = new ObjectArrayList<MachineRecipe>(recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            var recipe = recipes.get(i);
            updated.add(generalDefinitions ? withGeneralAuxiliary(recipe, auxiliaryInputs, inputs)
                : withInputs(recipe, resource(recipe, auxiliaryInputs), inputs));
        }
        return new SimpleRecipePool(id, translationKey, updated, inputs, machines, stableInputs, existingJeiPageId, generalDefinitions);
    }

    @Override
    public MachineRecipe prepareRecipe(MachineRecipe recipe) {
        if (generalDefinitions) {
            if (!auxiliaryInputs.isEmpty()) {
                for (var requirement : recipe.orderedRequirements()) {
                    if (requirement.id().equals(auxiliaryId())) {
                        singleInput(recipe, auxiliaryInputs);
                        return recipe;
                    }
                }
                singleInput(recipe, List.of());
                return withGeneralAuxiliary(recipe, List.of(), auxiliaryInputs);
            }
            singleInput(recipe, List.of());
            return recipe;
        }
        if (auxiliaryInputs.isEmpty()) return recipe;
        var requirements = recipe.orderedRequirements();
        if (requirements.size() == 1
            && requirements.getFirst().payload() instanceof ResourceRequirement resource && resource.inputs().size() != 1) {
            resource(recipe, auxiliaryInputs);
            return recipe;
        }
        var resource = Entry.resource(recipe);
        return withInputs(recipe, resource, auxiliaryInputs);
    }

    private static MachineRecipe withInputs(MachineRecipe recipe, ResourceRequirement resource, List<GenericStack> auxiliary) {
        var inputs = new ObjectArrayList<GenericStack>(auxiliary.size() + 1);
        inputs.add(resource.inputs().getFirst());
        inputs.addAll(auxiliary);
        var replacement = new ResourceRequirement(inputs, resource.inputsPerTick(), resource.outputsPerTick(), resource.outputs(),
            resource.voidExcessOutputs(), resource.chance(), resource.randomOutputs(), resource.fuzzyInputs(),
            resource.dynamicInputs(), resource.inputAllocation());
        var requirement = recipe.orderedRequirements().getFirst();
        return new MachineRecipe(recipe.id(), recipe.durationTicks(),
            List.of(RecipeRequirements.RESOURCES.requirement(requirement.id(), replacement)), recipe.recipeGroups(),
            recipe.maxConcurrentThreads(), recipe.parallelismSelection());
    }

    private static ResourceRequirement resource(MachineRecipe recipe, List<GenericStack> auxiliary) {
        var resource = Entry.resource(recipe, auxiliary.size() + 1);
        for (int i = 0; i < auxiliary.size(); i++)
            if (!resource.inputs().get(i + 1).equals(auxiliary.get(i)))
                throw new IllegalArgumentException("Simple recipe does not contain its pool's auxiliary inputs: " + recipe.id());
        return resource;
    }

    private ResourceLocation auxiliaryId() {
        return new ResourceLocation(id.getNamespace(), id.getPath() + "/auxiliary_inputs");
    }

    private MachineRecipe withGeneralAuxiliary(MachineRecipe recipe, List<GenericStack> previous, List<GenericStack> inputs) {
        singleInput(recipe, previous);
        var requirements = new ObjectArrayList<RecipeRequirement<?>>(recipe.orderedRequirements().size() + 1);
        for (var requirement : recipe.orderedRequirements()) {
            if (!requirement.id().equals(auxiliaryId())) requirements.add(requirement);
        }
        if (!inputs.isEmpty()) requirements.add(RecipeRequirements.RESOURCES.requirement(auxiliaryId(),
            new ResourceRequirement(inputs, List.of())));
        return RecipeBuilder.from(recipe).requirements(requirements).build();
    }

    private @Nullable GenericStack singleInput(MachineRecipe recipe, List<GenericStack> auxiliary) {
        int count = 0;
        GenericStack primary = null;
        boolean fixed = true;
        boolean foundAuxiliary = auxiliary.isEmpty();
        for (var requirement : recipe.orderedRequirements()) {
            if (requirement.id().equals(auxiliaryId())) {
                if (auxiliary.isEmpty() || requirement.type() != RecipeRequirements.RESOURCES
                    || !(requirement.payload() instanceof ResourceRequirement resource)
                    || !resource.equals(new ResourceRequirement(auxiliary, List.of())))
                    throw new IllegalArgumentException("Single-input recipe has conflicting auxiliary inputs: " + recipe.id());
                foundAuxiliary = true;
                continue;
            }
            if (requirement.type() == RecipeRequirements.SELECTIVE || requirement.type() == RecipeRequirements.CHECKPOINT)
                throw new IllegalArgumentException("Single-input recipes require flat START inputs: " + recipe.id());
            if (requirement.type() != RecipeRequirements.RESOURCES) {
                fixed &= requirement.type() == RecipeRequirements.ENERGY;
                continue;
            }
            var resources = (ResourceRequirement) requirement.payload();
            if (!resources.inputsPerTick().isEmpty())
                throw new IllegalArgumentException("Single-input recipes cannot declare additional TICK inputs: " + recipe.id());
            count = Math.addExact(count, Math.addExact(resources.inputs().size(),
                Math.addExact(resources.fuzzyInputs().size(), resources.dynamicInputs().size())));
            if (!resources.inputs().isEmpty()) primary = resources.inputs().getFirst();
            fixed &= resources.fuzzyInputs().isEmpty() && resources.dynamicInputs().isEmpty()
                && resources.chance().percent() == 100 && resources.chance().attribute() == null;
        }
        if (count != 1 || !foundAuxiliary)
            throw new IllegalArgumentException("Single-input pool needs exactly one flat START input: " + recipe.id());
        return fixed ? primary : null;
    }

    @Override
    public List<MachineType> machines() {
        return machines;
    }

    @Override
    public List<RecipeInventoryMatch> match(AEKey2LongMap inventory, int parallelism) {
        return inputIndex.match(inventory, parallelism);
    }

    @Override
    public RecipeCandidateIndex.Cursor beginMatch(AEKey2LongMap inventory, int parallelism, Set<ResourceLocation> groups) {
        return inputIndex.beginMatch(inventory, parallelism, groups);
    }

    @Override
    public boolean hasExistingJeiPage() {
        return existingJeiPageId != null;
    }

    @Override
    public @Nullable String existingJeiPageId() {
        return existingJeiPageId;
    }

    /**
     * Immutable addon binding; existing lookup policy and native quantities remain unchanged.
     */
    public SimpleRecipePool withExistingJeiPage(@Nullable String categoryUid) {
        return new SimpleRecipePool(this, categoryUid);
    }

    private SimpleRecipePool(SimpleRecipePool source, @Nullable String categoryUid) {
        id = source.id;
        translationKey = source.translationKey;
        recipes = source.recipes;
        auxiliaryInputs = source.auxiliaryInputs;
        machines = source.machines;
        inputIndex = source.inputIndex;
        stableInputs = source.stableInputs;
        existingJeiPageId = categoryUid;
        byInput = source.byInput;
        priority = source.priority;
        generalDefinitions = source.generalDefinitions;
        sparseLookup = source.sparseLookup;
    }

    @Override
    public SimpleRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        return new SimpleRecipePool(id, translationKey, recipes, auxiliaryInputs, machines, stableInputs, existingJeiPageId, generalDefinitions);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        if (!stableInputs.test(machine)) return null;
        return sparseLookup
            ? new GroupedResourceLookup(machine, priority::getInt, group -> new Lookup(machine, group.hatches().resources()))
            : inputIndex.beginLookup(machine, groups, recipes);
    }

    /**
     * Exact simple definition. Amounts need not be one and keys can have any registered AEKeyType.
     */
    public record Entry(MachineRecipe recipe, GenericStack input, List<GenericStack> outputs) {
        public Entry {
            ResourceRequirement.validateStacks(outputs);
            var resource = resource(recipe);
            if (!resource.inputs().getFirst().equals(input) || !resource.outputs().equals(outputs))
                throw new IllegalArgumentException("Simple entry differs from its recipe definition: " + recipe.id());
        }

        public static Entry of(MachineRecipe recipe) {
            var resource = resource(recipe);
            return new Entry(recipe, resource.inputs().getFirst(), resource.outputs());
        }

        public static Entry create(ResourceLocation id, int durationTicks, GenericStack input, GenericStack output,
                                   Set<ResourceLocation> groups) {
            return create(id, durationTicks, input, ObjectArrayList.of(output), groups);
        }

        public static Entry create(ResourceLocation id, int durationTicks, GenericStack input, List<GenericStack> outputs,
                                   Set<ResourceLocation> groups) {
            var recipe = new MachineRecipe(id, durationTicks, ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(id,
                new ResourceRequirement(ObjectArrayList.of(input), outputs))), groups);
            return Entry.of(recipe);
        }

        private static ResourceRequirement resource(MachineRecipe recipe) {
            return resource(recipe, 1);
        }

        private static ResourceRequirement resource(MachineRecipe recipe, int inputCount) {
            var requirements = recipe.orderedRequirements();
            if (requirements.size() != 1 || requirements.getFirst().type() != RecipeRequirements.RESOURCES
                || !(requirements.getFirst().payload() instanceof ResourceRequirement resource)
                || resource.inputs().size() != inputCount
                || !resource.inputsPerTick().isEmpty() || !resource.outputsPerTick().isEmpty()
                || !resource.fuzzyInputs().isEmpty() || !resource.dynamicInputs().isEmpty()
                || resource.randomOutputs() != null || resource.chance().percent() != 100 || resource.chance().attribute() != null)
                throw new IllegalArgumentException("Simple pool needs one fixed START input, its shared inputs and fixed END outputs: " + recipe.id());
            return resource;
        }
    }

    private record IndexedEntry(MachineRecipe recipe, GenericStack input) {
    }

    private record Captured(ResourceHatch hatch, ResourceStorage storage, StorageContext context, Object identity,
                            ResourceStorage.QuantityCapture quantities) {
        boolean current() {
            if (!hatch.isValid()) return false;
            var current = hatch.input();
            return current != null && current.isValid() && current.context() == context
                && hatch.inputStorageIdentity() == identity && storage.isValid() && storage.context() == context && quantities.current();
        }
    }

    /**
     * Includes skipped bindings: a previously invalid/null view becoming usable invalidates the opinion.
     */
    private record Binding(ResourceHatch hatch, boolean valid, boolean present, @Nullable Object identity,
                           @Nullable StorageContext context, boolean storageValid) {
        boolean current() {
            if (hatch.isValid() != valid) return false;
            if (!valid) return true;
            var storage = hatch.input();
            return present ? storage != null && storage.isValid() == storageValid && storage.context() == context
                && hatch.inputStorageIdentity() == identity : storage == null;
        }
    }

    /**
     * Visits present storage keys and their postings; absent-key recipes are never enumerated.
     */
    private final class Lookup implements RecipeCandidateIndex.Cursor {
        private final MachineInstance machine;
        private final MachineHatches sourceBindings;
        private final List<? extends ResourceHatch> selectedHatches;
        private final Reference2ObjectOpenHashMap<StorageContext, AEKey2LongMap> byContext = new Reference2ObjectOpenHashMap<>();
        private final AEKey2LongMap available = new AEKey2LongMap.SaturatingOpenHashMap(6);
        private final ObjectArrayList<Captured> captures = new ObjectArrayList<>();
        private final ObjectArrayList<Binding> bindings = new ObjectArrayList<>();
        private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private final Reference2IntOpenHashMap<MachineRecipe> ceilings = new Reference2IntOpenHashMap<>();
        private List<? extends ResourceHatch> hatches;
        private int hatchIndex;
        private Captured view;
        private Iterator<Object2LongMap.Entry<AEKey>> quantities;
        private Iterator<Object2LongMap.Entry<AEKey>> totals;
        private Object2LongMap.Entry<AEKey> total;
        private List<IndexedEntry> postings;
        private int postingIndex;
        private int proofIndex;
        private boolean bindingsChecked;
        private int phase;
        private boolean stale;

        Lookup(MachineInstance machine, List<? extends ResourceHatch> selectedHatches) {
            this.machine = machine;
            this.sourceBindings = MachineHatches.get(machine);
            this.selectedHatches = selectedHatches;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (phase == 3) return true;
            if (stale) return false;
            while (true) {
                if (phase == 0) {
                    if (hatches == null) {
                        if (!budget.take()) return false;
                        hatches = selectedHatches;
                    }
                    while (view != null || hatchIndex < hatches.size()) {
                        if (view == null) {
                            if (!budget.take()) return false;
                            var hatch = hatches.get(hatchIndex++);
                            if (!hatch.isValid()) {
                                bindings.add(new Binding(hatch, false, false, null, null, false));
                                continue;
                            }
                            var storage = hatch.input();
                            boolean validStorage = storage != null && storage.isValid();
                            var identity = storage == null ? null : hatch.inputStorageIdentity();
                            var context = storage == null ? null : storage.context();
                            bindings.add(new Binding(hatch, true, storage != null, identity, context, validStorage));
                            if (!validStorage) continue;
                            view = new Captured(hatch, storage, context, identity,
                                (byInput.size() == 1 ? storage.beginQuantityCapture(byInput.keySet().iterator().next()) : storage.beginQuantityCapture()));
                            captures.add(view);
                        }
                        if (quantities == null) {
                            if (!view.quantities.advance(budget)) return false;
                            if (!view.current()) {
                                stale = true;
                                return false;
                            }
                            quantities = view.quantities.available().object2LongEntrySet().iterator();
                        }
                        while (quantities.hasNext()) {
                            if (!budget.take()) return false;
                            var entry = quantities.next();
                            var key = entry.getKey();
                            long amount = entry.getLongValue();
                            if (amount < 0) throw new IllegalStateException("Negative input availability");
                            if (!byInput.containsKey(key)) continue;
                            var maxima = byContext.computeIfAbsent(view.context, _ -> new AEKey2LongMap.OpenHashMap(6));
                            long previous = maxima.getLong(key);
                            if (amount > previous) {
                                maxima.addTo(key, amount - previous);
                                available.addTo(key, amount - previous);
                            }
                        }
                        quantities = null;
                        view = null;
                    }
                    totals = available.object2LongEntrySet().iterator();
                    phase = 1;
                }
                if (phase == 1) {
                    while (total != null || totals.hasNext()) {
                        if (total == null) {
                            if (!budget.take()) return false;
                            total = totals.next();
                            postings = byInput.get(total.getKey());
                            postingIndex = 0;
                        }
                        while (postingIndex < postings.size()) {
                            if (!budget.take()) return false;
                            var entry = postings.get(postingIndex++);
                            if (entry.input.amount() > total.getLongValue()) {
                                postingIndex = postings.size();
                                break;
                            }
                            result.add(entry.recipe);
                            ceilings.put(entry.recipe, RecipeInventoryMatch.limit(total.getLongValue(), entry.input.amount(), Integer.MAX_VALUE));
                        }
                        total = null;
                    }
                    phase = 2;
                }
                if (phase == 2) {
                    if (!bindingsChecked) {
                        if (!budget.take()) return false;
                        bindingsChecked = true;
                        if (MachineHatches.get(machine) != sourceBindings) {
                            stale = true;
                            return false;
                        }
                    }
                    while (proofIndex < bindings.size() + captures.size()) {
                        if (!budget.take()) return false;
                        boolean current = proofIndex < bindings.size() ? bindings.get(proofIndex).current()
                            : captures.get(proofIndex - bindings.size()).current();
                        proofIndex++;
                        if (!current) {
                            stale = true;
                            return false;
                        }
                    }
                    phase = 3;
                    return true;
                }
            }
        }

        @Override
        public boolean current() {
            if (stale) return false;
            if (phase != 3) return true;
            if (!stableInputs.test(machine) || MachineHatches.get(machine) != sourceBindings) return false;
            for (int index1 = 0; index1 < bindings.size(); index1++) {
                var binding = bindings.get(index1);
                if (!binding.current()) return false;
            }
            for (int index2 = 0; index2 < captures.size(); index2++) {
                var capture = captures.get(index2);
                if (!capture.current()) return false;
            }
            return true;
        }

        @Override
        public List<MachineRecipe> result() {
            if (phase != 3 || stale) throw new IllegalStateException("Simple pool opinion is incomplete or stale");
            return result;
        }

        @Override
        public int inputParallelismLimit(MachineRecipe recipe) {
            if (phase != 3 || stale) throw new IllegalStateException("Simple pool opinion is incomplete or stale");
            return ceilings.getOrDefault(recipe, Integer.MAX_VALUE);
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Validation retains this lookup's exact binding and quantity proofs.
        public RecipeCandidateIndex.Validation beginValidation() {
            return new RecipeCandidateIndex.Validation() {
                private final int bindingCount = bindings.size();
                // An unfinished quantity capture has published no opinion and can restart itself.
                private final int captureCount = captures.size() - (view != null && quantities == null ? 1 : 0);
                private int cursor;
                private boolean ownerChecked;
                private boolean complete;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (complete) return true;
                    if (stale) {
                        complete = true;
                        return true;
                    }
                    if (!ownerChecked) {
                        if (!budget.take()) return false;
                        ownerChecked = true;
                        if (!stableInputs.test(machine) || hatches != null && MachineHatches.get(machine) != sourceBindings) {
                            stale = true;
                            complete = true;
                            return true;
                        }
                    }
                    while (cursor < bindingCount + captureCount) {
                        if (!budget.take()) return false;
                        boolean valid = cursor < bindingCount ? bindings.get(cursor).current()
                            : captures.get(cursor - bindingCount).current();
                        cursor++;
                        if (!valid) {
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
                    if (!complete) throw new IllegalStateException("Simple pool validation is incomplete");
                    return !stale;
                }
            };
        }
    }
}
