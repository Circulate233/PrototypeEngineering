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
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Iterator;

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

    /**
     * Owner-only demand/choice cursor over already frozen current balances and full pure matches.
     */
    static final class Cursor {
        private final RecipeRequirement<ResourceRequirement> requirement;
        private final long times;
        private final AEKey2LongMap available;
        private final AEKey2LongMap fixed;
        private final AEKey2LongMap planned = new AEKey2LongMap.LinkdOpenHashMap();
        private final List<List<AEKey>> dynamic;
        private final Selection selection;
        private final RequirementPath path;
        private Iterator<Object2LongMap.Entry<AEKey>> entries;
        private int phase;
        private int index;
        private int candidate;
        private int nonzero;
        private boolean complete;
        private boolean groupStarted;
        private ResourceInputLocks.Key groupKey;
        private long groupAmount;
        private long groupNeeded;
        @Nullable
        private AEKey groupExisting;
        private List<AEKey> groupCandidates;
        private int groupMaximum;

        Cursor(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement, long times,
               AEKey2LongMap fixed, AEKey2LongMap available, List<List<AEKey>> dynamic, boolean hasInput) {
            this.requirement = requirement;
            this.times = times;
            this.fixed = fixed;
            this.available = available;
            this.dynamic = dynamic;
            path = process.requirementPath();
            selection = new Selection(new ObjectArrayList<>(), ResourceInputLocks.getOrCreate(process), new ObjectArrayList<>());
            if (!hasInput) {
                reject(RecipeText.NoResourceInput.blocked(requirement.id().toString()));
            }
        }

        private void reject(ProcessResult result) {
            selection.reject(result);
            complete = true;
        }

        boolean advance(RecipeWorkBudget budget) {
            while (!complete) {
                if (phase == 0) {
                    if (entries == null) {
                        entries = fixed.object2LongEntrySet().iterator();
                    }
                    while (entries.hasNext()) {
                        if (!budget.take()) {
                            return false;
                        }
                        var entry = entries.next();
                        planned.addTo(entry.getKey(), entry.getLongValue());
                    }
                    entries = null;
                    phase++;
                } else if (phase == 1) {
                    var inputs = requirement.payload().inputs();
                    while (index < inputs.size()) {
                        if (!budget.take()) {
                            return false;
                        }
                        selection.inputs.add(inputs.get(index++));
                    }
                    index = 0;
                    phase++;
                } else if (phase == 2) {
                    if (entries == null) {
                        entries = planned.object2LongEntrySet().iterator();
                    }
                    while (entries.hasNext()) {
                        if (!budget.take()) {
                            return false;
                        }
                        var entry = entries.next();
                        long missing = entry.getLongValue() - available.getLong(entry.getKey());
                        if (missing > 0) {
                            reject(RecipeText.ResourcesMissing.blocked(requirement.id().toString(), Long.toString(missing)));
                            return true;
                        }
                    }
                    entries = null;
                    phase++;
                } else {
                    boolean fuzzy = phase == 3;
                    int groups = fuzzy ? requirement.payload().fuzzyInputs().size() : requirement.payload().dynamicInputs().size();
                    if (index == groups) {
                        if (fuzzy) {
                            phase++;
                            index = 0;
                            continue;
                        }
                        complete = true;
                        return true;
                    }
                    if (!groupStarted) {
                        if (!budget.take()) {
                            return false;
                        }
                        groupKey = new ResourceInputLocks.Key(path, RecipeStage.START,
                            fuzzy ? ResourceInputLocks.Kind.FUZZY : ResourceInputLocks.Kind.DYNAMIC, index);
                        groupAmount = fuzzy ? requirement.payload().fuzzyInputs().get(index).amount() : requirement.payload().dynamicInputs().get(index).amount();
                        groupNeeded = ChanceMath.scaleCount(groupAmount, times);
                        groupExisting = selection.locks.get(groupKey);
                        groupCandidates = fuzzy ? requirement.payload().fuzzyInputs().get(index).candidates() : dynamic.get(index);
                        groupMaximum = fuzzy ? Integer.MAX_VALUE : requirement.payload().dynamicInputs().get(index).maxCandidates();
                        groupStarted = true;
                    }
                    var key = groupKey;
                    long amount = groupAmount;
                    long needed = groupNeeded;
                    var existing = groupExisting;
                    var candidates = groupCandidates;
                    int maximum = groupMaximum;
                    AEKey chosen = existing;
                    if (existing == null) {
                        while (candidate < candidates.size() && nonzero < maximum) {
                            if (!budget.take()) {
                                return false;
                            }
                            var value = candidates.get(candidate++);
                            long balance = available.getLong(value);
                            if (!fuzzy && balance == 0) {
                                continue;
                            }
                            nonzero++;
                            if (balance - planned.getLong(value) >= needed) {
                                chosen = value;
                                break;
                            }
                        }
                    }
                    if (chosen == null || available.getLong(chosen) - planned.getLong(chosen) < needed) {
                        reject(RecipeText.ResourcesMissing.blocked(requirement.id().toString(), Long.toString(needed)));
                        return true;
                    }
                    planned.addTo(chosen, needed);
                    selection.inputs.add(new GenericStack(chosen, amount));
                    if (existing == null) {
                        selection.pending.add(new Choice(key, chosen));
                    }
                    index++;
                    candidate = 0;
                    nonzero = 0;
                    groupStarted = false;
                }
            }
            return true;
        }

        Selection selection() {
            if (!complete) {
                throw new IllegalStateException("Choice cursor is incomplete");
            }
            return selection;
        }
    }

    static final class Selection {
        private ProcessResult result = ProcessResult.SUCCESS;
        private int installedIndex;
        private final ObjectArrayList<GenericStack> inputs;
        private final ResourceInputLocks locks;
        private final List<Choice> pending;
        private ObjectArrayList<Choice> installed = new ObjectArrayList<>();
        private int rollbackIndex = -2;
        @Nullable private Throwable rollbackFailure;

        private Selection(ObjectArrayList<GenericStack> inputs, ResourceInputLocks locks, List<Choice> pending) {
            this.inputs = inputs;
            this.locks = locks;
            this.pending = pending;
        }

        private Selection reject(ProcessResult rejection) { result = rejection; return this; }
        ProcessResult result() { return result; }
        List<GenericStack> inputs() { return inputs; }

        void install() {
            install(new RecipeWorkBudget(Long.MAX_VALUE)); }

        boolean install(RecipeWorkBudget budget) {
            if (rollbackIndex != -2) throw new IllegalStateException("Cannot install choices after rollback begins");
            while (installedIndex < pending.size()) {
                if (!budget.take()) {
                    return false;
                }
                var choice = pending.get(installedIndex++);
                if (locks.putIfAbsent(choice.lock(), choice.resource())) { installed.add(choice); }
            }
            return true;
        }

        void rollback() {
            rollback(new RecipeWorkBudget(Long.MAX_VALUE));
                }

        boolean rollback(RecipeWorkBudget budget) {
            if (rollbackIndex == -2) rollbackIndex = installed.size() - 1;
            while (rollbackIndex >= 0) {
                if (!budget.take()) return false;
                var choice = installed.get(rollbackIndex--);
                try {
                    locks.remove(choice.lock(), choice.resource());
                } catch (RuntimeException | Error cleanup) {
                    if (rollbackFailure == null) {
                        rollbackFailure = cleanup;
                    } else if (rollbackFailure != cleanup) {
                        rollbackFailure.addSuppressed(cleanup);
                    }
                    if (cleanup instanceof VirtualMachineError fatal) throw fatal;
                }
            }
            installed = new ObjectArrayList<>();
            if (rollbackFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (rollbackFailure instanceof Error error) {
                throw error;
            }
            return true;
        }
    }

    private record Choice(ResourceInputLocks.Key lock, AEKey resource) { }
}
