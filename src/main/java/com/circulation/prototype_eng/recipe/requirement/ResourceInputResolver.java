package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** PM's first satisfiable candidate selection, accounting for fixed and earlier group demands. */
final class ResourceInputResolver {
    private ResourceInputResolver() { }

    static Selection resolve(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                              List<ResourceHatch> hatches, long checkTimes, AEKey2LongMap planned) {
        var locks = ResourceInputLocks.getOrCreate(process);
        var pending = new ObjectArrayList<Choice>();
        var inputs = new ObjectArrayList<>(requirement.payload().inputs());
        var selected = new Selection(inputs, locks, pending);
        var seen = new ReferenceOpenHashSet<StorageContext>();
        var available = new AEKey2LongMap.LinkdOpenHashMap();
        for (var hatch : hatches) {
            if (!hatch.isValid()) { continue; }
            var storage = hatch.input();
            if (storage == null || !storage.isValid() || !seen.add(storage.context())) { continue; }
            for (var entry : storage.snapshot().available().object2LongEntrySet()) {
                long amount = entry.getLongValue();
                if (amount < 0) { throw new IllegalStateException("Negative available resource balance"); }
                if (amount == 0) { continue; }
                long previous = available.getLong(entry.getKey());
                available.addTo(entry.getKey(), Math.min(amount, Long.MAX_VALUE - previous));
            }
        }
        String id = requirement.id().toString();
        if (seen.isEmpty()) { return selected.reject(RecipeText.NoResourceInput.blocked(id)); }
        for (var fixed : planned.object2LongEntrySet()) {
            long missing = fixed.getLongValue() - available.getLong(fixed.getKey());
            if (missing > 0) { return selected.reject(RecipeText.ResourcesMissing.blocked(id, Long.toString(missing))); }
        }
        var path = process.requirementPath();
        var fuzzy = requirement.payload().fuzzyInputs();
        for (int index = 0; index < fuzzy.size(); index++) {
            var group = fuzzy.get(index);
            var key = new ResourceInputLocks.Key(path, RecipeStage.START, ResourceInputLocks.Kind.FUZZY, index);
            if (!choose(selected, key, group.candidates(), group.amount(), checkTimes, available, planned)) {
                return selected.reject(RecipeText.ResourcesMissing.blocked(id, Long.toString(ChanceMath.scaleCount(group.amount(), checkTimes))));
            }
        }
        var dynamic = requirement.payload().dynamicInputs();
        for (int index = 0; index < dynamic.size(); index++) {
            var group = dynamic.get(index);
            var key = new ResourceInputLocks.Key(path, RecipeStage.START, ResourceInputLocks.Kind.DYNAMIC, index);
            // An existing process lock keeps its identity even if later inventory ordering changes.
            var candidates = locks.get(key) == null ? DynamicInputCandidates.enumerate(available.keySet(), group) : List.<AEKey>of();
            if (!choose(selected, key, candidates, group.amount(), checkTimes, available, planned)) {
                return selected.reject(RecipeText.ResourcesMissing.blocked(id, Long.toString(ChanceMath.scaleCount(group.amount(), checkTimes))));
            }
        }
        return selected;
    }

    private static boolean choose(Selection selected, ResourceInputLocks.Key lock, List<AEKey> candidates, long amount,
                                   long times, AEKey2LongMap available,
                                   AEKey2LongMap planned) {
        long needed = ChanceMath.scaleCount(amount, times);
        AEKey existing = selected.locks.get(lock);
        AEKey chosen = existing == null ? first(candidates, needed, available, planned) : existing;
        if (chosen == null || available.getLong(chosen) - planned.getLong(chosen) < needed) {
            return false;
        }
        // The availability check bounds planned + needed by a nonnegative saturated long balance.
        planned.addTo(chosen, needed);
        selected.inputs.add(new GenericStack(chosen, amount));
        if (existing == null) { selected.pending.add(new Choice(lock, chosen)); }
        return true;
    }

    @Nullable
    private static AEKey first(List<AEKey> candidates, long needed, AEKey2LongMap available,
                                AEKey2LongMap planned) {
        for (var candidate : candidates) {
            if (available.getLong(candidate) - planned.getLong(candidate) >= needed) { return candidate; }
        }
        return null;
    }

    static final class Selection {
        private ProcessResult result = ProcessResult.SUCCESS;
        private final ObjectArrayList<GenericStack> inputs;
        private final ResourceInputLocks locks;
        private final List<Choice> pending;
        private final ObjectArrayList<Choice> installed = new ObjectArrayList<>();

        private Selection(ObjectArrayList<GenericStack> inputs, ResourceInputLocks locks, List<Choice> pending) {
            this.inputs = inputs;
            this.locks = locks;
            this.pending = pending;
        }

        private Selection reject(ProcessResult rejection) { result = rejection; return this; }
        ProcessResult result() { return result; }
        List<GenericStack> inputs() { return inputs; }

        void install() {
            for (var choice : pending) {
                if (locks.putIfAbsent(choice.lock(), choice.resource())) { installed.add(choice); }
            }
        }

        void rollback() {
            Throwable failure = null;
            for (int index = installed.size() - 1; index >= 0; index--) {
                var choice = installed.get(index);
                try {
                    locks.remove(choice.lock(), choice.resource());
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) { failure = cleanup; }
                    else if (failure != cleanup) { failure.addSuppressed(cleanup); }
                }
            }
            installed.clear();
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }
    }

    private record Choice(ResourceInputLocks.Key lock, AEKey resource) { }
}
