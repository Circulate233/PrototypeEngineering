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
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Single fixed START input to single fixed END output, with pool-local AEKey/long postings. */
public final class SimpleRecipePool implements RecipePool {
    private final ResourceLocation id;
    private final String translationKey;
    private final List<MachineRecipe> recipes;
    private final List<MachineType> machines;
    private final Predicate<MachineInstance> stableInputs;
    private final @Nullable String existingJeiPageId;
    private final Object2ObjectOpenHashMap<AEKey, List<Entry>> byInput = new Object2ObjectOpenHashMap<>();

    /** Uses the actual owner's scanner/input policy; unknown factories/providers retain every candidate. */
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
        this(id, translationKey, entries, List.of(), stableInputs, null);
    }

    private SimpleRecipePool(ResourceLocation id, String translationKey, List<Entry> entries, List<MachineType> machines,
                             Predicate<MachineInstance> stableInputs, @Nullable String existingJeiPageId) {
        this.id = Objects.requireNonNull(id);
        if (translationKey == null || translationKey.isBlank())
            throw new IllegalArgumentException("Pool title is required");
        this.translationKey = translationKey;
        this.stableInputs = Objects.requireNonNull(stableInputs);
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        var rows = new ObjectArrayList<MachineRecipe>();
        var postings = new Object2ObjectOpenHashMap<AEKey, ObjectArrayList<Entry>>();
        for (var entry : entries) {
            Objects.requireNonNull(entry);
            if (!ids.add(entry.recipe.id()))
                throw new IllegalArgumentException("Duplicate simple recipe: " + entry.recipe.id());
            rows.add(entry.recipe);
            postings.computeIfAbsent(entry.input.what(), _ -> new ObjectArrayList<>()).add(entry);
        }
        postings.forEach((key, values) -> {
            values.sort(Comparator.comparingLong(entry -> entry.input.amount()));
            byInput.put(key, ObjectLists.unmodifiable(values));
        });
        recipes = ObjectLists.unmodifiable(rows);
        this.machines = ObjectLists.unmodifiable(new ObjectArrayList<>(machines));
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

    @Override
    public List<MachineType> machines() {
        return machines;
    }

    @Override
    public boolean hasExistingJeiPage() {
        return existingJeiPageId != null;
    }

    @Override
    public @Nullable String existingJeiPageId() {
        return existingJeiPageId;
    }

    /** Immutable addon binding; existing lookup policy and native quantities remain unchanged. */
    public SimpleRecipePool withExistingJeiPage(@Nullable String categoryUid) {
        var entries = new ObjectArrayList<Entry>(recipes.size());
        for (var recipe : recipes) entries.add(Entry.of(recipe));
        return new SimpleRecipePool(id, translationKey, entries, machines, stableInputs, categoryUid);
    }

    @Override
    public SimpleRecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
        var entries = new ObjectArrayList<Entry>();
        for (var recipe : recipes) entries.add(Entry.of(recipe));
        return new SimpleRecipePool(id, translationKey, entries, machines, stableInputs, existingJeiPageId);
    }

    @Override
    public @Nullable RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
        return stableInputs.test(Objects.requireNonNull(machine)) ? new Lookup(machine) : null;
    }

    /** Exact simple definition. Amounts need not be one and keys can have any registered AEKeyType. */
    public record Entry(MachineRecipe recipe, GenericStack input, GenericStack output) {
        public Entry {
            Objects.requireNonNull(recipe);
            Objects.requireNonNull(input);
            Objects.requireNonNull(output);
            var resource = resource(recipe);
            if (!resource.inputs().getFirst().equals(input) || !resource.outputs().getFirst().equals(output))
                throw new IllegalArgumentException("Simple entry differs from its recipe definition: " + recipe.id());
        }

        public static Entry of(MachineRecipe recipe) {
            var resource = resource(recipe);
            return new Entry(recipe, resource.inputs().getFirst(), resource.outputs().getFirst());
        }

        public static Entry create(ResourceLocation id, int durationTicks, GenericStack input, GenericStack output,
                                   Set<ResourceLocation> groups) {
            var recipe = new MachineRecipe(id, durationTicks, ObjectArrayList.of(RecipeRequirements.RESOURCES.requirement(id,
                new ResourceRequirement(ObjectArrayList.of(input), ObjectArrayList.of(output)))), groups);
            return new Entry(recipe, input, output);
        }

        private static ResourceRequirement resource(MachineRecipe recipe) {
            Objects.requireNonNull(recipe);
            var requirements = recipe.orderedRequirements();
            if (requirements.size() != 1 || requirements.getFirst().type() != RecipeRequirements.RESOURCES
                || !(requirements.getFirst().payload() instanceof ResourceRequirement resource)
                || resource.inputs().size() != 1 || resource.outputs().size() != 1
                || !resource.inputsPerTick().isEmpty() || !resource.outputsPerTick().isEmpty()
                || !resource.fuzzyInputs().isEmpty() || !resource.dynamicInputs().isEmpty()
                || resource.randomOutputs() != null || resource.chance().percent() != 100 || resource.chance().attribute() != null)
                throw new IllegalArgumentException("Simple pool needs one fixed START input and END output: " + recipe.id());
            return resource;
        }
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

    /** Includes skipped bindings: a previously invalid/null view becoming usable invalidates the opinion. */
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

    /** Visits present storage keys and their postings; absent-key recipes are never enumerated. */
    private final class Lookup implements RecipeCandidateIndex.Cursor {
        private final MachineInstance machine;
        private final Reference2ObjectOpenHashMap<StorageContext, AEKey2LongMap> byContext = new Reference2ObjectOpenHashMap<>();
        private final AEKey2LongMap available = new AEKey2LongMap.SaturatingOpenHashMap();
        private final ObjectArrayList<Captured> captures = new ObjectArrayList<>();
        private final ObjectArrayList<Binding> bindings = new ObjectArrayList<>();
        private final ObjectArrayList<MachineRecipe> result = new ObjectArrayList<>();
        private List<ResourceHatch> hatches;
        private int hatchIndex;
        private Captured view;
        private Iterator<Object2LongMap.Entry<AEKey>> quantities;
        private Iterator<Object2LongMap.Entry<AEKey>> totals;
        private Object2LongMap.Entry<AEKey> total;
        private List<Entry> postings;
        private int postingIndex;
        private int proofIndex;
        private boolean bindingsChecked;
        private int phase;
        private boolean stale;

        Lookup(MachineInstance machine) {
            this.machine = machine;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            Objects.requireNonNull(budget);
            if (phase == 3) return true;
            if (stale) return false;
            while (true) {
                if (phase == 0) {
                    if (hatches == null) {
                        if (!budget.take()) return false;
                        hatches = MachineHatches.get(machine).resources();
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
                            var identity = storage == null ? null : Objects.requireNonNull(hatch.inputStorageIdentity());
                            var context = storage == null ? null : Objects.requireNonNull(storage.context());
                            bindings.add(new Binding(hatch, true, storage != null, identity, context, validStorage));
                            if (!validStorage) continue;
                            view = new Captured(hatch, storage, Objects.requireNonNull(context), Objects.requireNonNull(identity), Objects.requireNonNull(storage.beginQuantityCapture()));
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
                            var key = Objects.requireNonNull(entry.getKey());
                            long amount = entry.getLongValue();
                            if (amount < 0) throw new IllegalStateException("Negative input availability");
                            if (!byInput.containsKey(key)) continue;
                            var maxima = byContext.computeIfAbsent(view.context, _ -> new AEKey2LongMap.OpenHashMap());
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
                        }
                        total = null;
                    }
                    phase = 2;
                }
                if (phase == 2) {
                    if (!bindingsChecked) {
                        if (!budget.take()) return false;
                        bindingsChecked = true;
                        if (MachineHatches.get(machine).resources() != hatches) {
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
            if (!stableInputs.test(machine) || MachineHatches.get(machine).resources() != hatches) return false;
            for (var binding : bindings) if (!binding.current()) return false;
            for (var capture : captures) if (!capture.current()) return false;
            return true;
        }

        @Override
        public List<MachineRecipe> result() {
            if (phase != 3 || stale) throw new IllegalStateException("Simple pool opinion is incomplete or stale");
            return ObjectLists.unmodifiable(result);
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
                    Objects.requireNonNull(budget);
                    if (complete) return true;
                    if (stale) {
                        complete = true;
                        return true;
                    }
                    if (!ownerChecked) {
                        if (!budget.take()) return false;
                        ownerChecked = true;
                        if (!stableInputs.test(machine) || hatches != null && MachineHatches.get(machine).resources() != hatches) {
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
