package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.List;
import java.util.Objects;

/** PM's ordered multi-container resource IO, reserving actual quantities instead of simulating twice. */
public final class ResourceRequirementSystem implements RecipeRequirementSystem<ResourceRequirement> {
    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.START, requirement.payload().inputs(), ObjectLists.emptyList());
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.TICK, requirement.payload().inputsPerTick(), requirement.payload().outputsPerTick());
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.END, ObjectLists.emptyList(), requirement.payload().outputs());
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                                   RecipeStage stage, List<GenericStack> inputs, List<GenericStack> outputs) {
        var payload = requirement.payload();
        var pool = stage == RecipeStage.END ? payload.randomOutputs() : null;
        boolean randomOutputs = pool != null && pool.hasOutputs();
        boolean resolveInputs = stage == RecipeStage.START && (!payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty());
        if (inputs.isEmpty() && outputs.isEmpty() && !randomOutputs && !resolveInputs) {
            return RequirementTransaction.SUCCESS;
        }
        var reservations = new ObjectArrayList<ResourceReservation>();
        ResourceInputResolver.Selection selection = null;
        try {
            var hatches = process.hatches().resources();
            String id = requirement.id().toString();
            int parallelism = RecipeParallelism.effective(process);
            double chance = payload.chance().effective(process);
            String salt = "resource:" + process.requirementPath() + ":" + stage
                + (stage == RecipeStage.TICK ? ":" + process.state().successfulTicks() : "");
            long times = chance == 100 ? parallelism
                : ChanceMath.sampleTimes(process.random(salt), parallelism, chance, process.probabilityTuning());
            long checkTimes = Math.max(parallelism, ChanceMath.maxTimes(parallelism, chance));
            if (resolveInputs) {
                selection = ResourceInputResolver.resolve(process, requirement, hatches, checkTimes, scaled(inputs, checkTimes));
                if (selection.result() != ProcessResult.SUCCESS) {
                    return new ReservedRequirement(selection.result(), reservations);
                }
                inputs = selection.inputs();
            }
            var consumed = scaled(inputs, times);
            var produced = scaled(outputs, times);
            AEKey2LongMap random = null;
            if (randomOutputs) {
                random = new AEKey2LongMap.LinkdOpenHashMap();
                randomOutputs(process, Objects.requireNonNull(pool), salt, times, random);
                merge(produced, random);
            }
            boolean needsOutput = !outputs.isEmpty() || randomOutputs;
            if (times != checkTimes) {
                var checkedOutputs = scaled(outputs, checkTimes);
                if (random != null) { merge(checkedOutputs, random); }
                ProcessResult checked = preflight(hatches, scaled(inputs, checkTimes), checkedOutputs,
                    !inputs.isEmpty(), needsOutput, payload.voidExcessOutputs(), id);
                if (checked != ProcessResult.SUCCESS) {
                    return new ReservedRequirement(checked, reservations);
                }
            }
            ProcessResult result = reserve(hatches, consumed, true, !inputs.isEmpty(), false, id, reservations);
            if (result == ProcessResult.SUCCESS) {
                result = reserve(hatches, produced, false, needsOutput, payload.voidExcessOutputs(), id, reservations);
            }
            var reserved = new ReservedRequirement(result, reservations);
            if (result == ProcessResult.SUCCESS && selection != null) {
                selection.install();
                return new ResolvedResources(reserved, selection);
            }
            return reserved;
        } catch (RuntimeException | Error failure) {
            if (selection != null) {
                try {
                    selection.rollback();
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) { failure.addSuppressed(cleanup); }
                }
            }
            ReservedRequirement.rollbackAfterFailure(reservations, failure);
            throw failure;
        }
    }

    /** Chosen identities are provisional until the enclosing stage succeeds, just like its tokens. */
    private record ResolvedResources(ReservedRequirement reserved, ResourceInputResolver.Selection selection)
        implements RequirementTransaction {
        @Override public ProcessResult result() { return reserved.result(); }
        @Override public List<ResourceReservation> reservations() { return reserved.reservations(); }
        @Override public void commit() { }
        @Override public void rollback() { selection.rollback(); }
    }

    /**
     * PM's conservative fixed-quantity check, with real temporary holds covering shared slots/keys.
     * Release these before actual reservations: checks do not consume resources and can be shared by
     * later requirements. Exact execution still reserves afresh, so an intervening change cannot overdraw.
     */
    private static ProcessResult preflight(List<ResourceHatch> hatches, AEKey2LongMap inputs,
                                           AEKey2LongMap outputs, boolean needsInput,
                                           boolean needsOutput, boolean voidExcess, String id) {
        var checks = new ObjectArrayList<ResourceReservation>();
        ProcessResult result;
        try {
            result = reserve(hatches, inputs, true, needsInput, false, id, checks);
            if (result == ProcessResult.SUCCESS) {
                result = reserve(hatches, outputs, false, needsOutput, voidExcess, id, checks);
            }
        } catch (RuntimeException | Error failure) {
            ReservedRequirement.rollbackAfterFailure(checks, failure);
            throw failure;
        }
        ResourceTransactions.rollback(checks);
        return result;
    }

    private static AEKey2LongMap scaled(List<GenericStack> stacks, long times) {
        var amounts = new AEKey2LongMap.LinkdOpenHashMap();
        if (times == 0) { return amounts; }
        for (var stack : stacks) {
            addChecked(amounts, stack.what(), stack.amount());
        }
        for (var entry : amounts.object2LongEntrySet()) {
            entry.setValue(ChanceMath.scaleCount(entry.getLongValue(), times));
        }
        return amounts;
    }

    private static void merge(AEKey2LongMap into, AEKey2LongMap from) {
        for (var entry : from.object2LongEntrySet()) {
            addChecked(into, entry.getKey(), entry.getLongValue());
        }
    }

    private static void randomOutputs(RecipeProcess process, RandomOutputPool pool, String salt, long times,
                                      AEKey2LongMap output) {
        if (times == 0) { return; }
        if (pool.pickCount() >= pool.candidates().size()) {
            // Every positive-weight entry is certain; avoid one sampling/allocation pass per parallel.
            var stacks = new ObjectArrayList<GenericStack>();
            for (var candidate : pool.candidates()) { stacks.add(candidate.value()); }
            merge(output, scaled(stacks, times));
            return;
        }
        // Preserve PM's exact per-execution weighted choice. Planned preparation must budget this work.
        for (long execution = 0; execution < times; execution++) {
            var selected = WeightedSampling.sampleWithoutReplacement(process.random(salt + ":random:" + execution),
                pool.candidates(), pool.pickCount());
            for (var stack : selected) {
                addChecked(output, stack.what(), stack.amount());
            }
        }
    }

    /** Requirement quantities are nonnegative; overflow must fail before changing the accumulated map. */
    private static void addChecked(AEKey2LongMap amounts, AEKey key, long amount) {
        if (amount > Long.MAX_VALUE - amounts.getLong(key)) {
            throw new ArithmeticException("long overflow");
        }
        amounts.addTo(key, amount);
    }

    private static ProcessResult reserve(List<ResourceHatch> hatches, AEKey2LongMap quantities,
                                          boolean input, boolean needsStorage, boolean voidExcess, String id,
                                          List<ResourceReservation> reservations) {
        if (!needsStorage) {
            return ProcessResult.SUCCESS;
        }
        var storages = new ObjectArrayList<ResourceStorage>();
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var hatch : hatches) {
            if (!hatch.isValid()) { continue; }
            var storage = input ? hatch.input() : hatch.output();
            if (storage != null && storage.isValid() && visited.add(storage.context())) {
                storages.add(storage);
            }
        }
        if (storages.isEmpty()) {
            return (input ? RecipeText.NoResourceInput : RecipeText.NoResourceOutput).blocked(id);
        }
        for (var entry : quantities.object2LongEntrySet()) {
            AEKey key = entry.getKey();
            long remaining = entry.getLongValue();
            for (var storage : storages) {
                var context = storage.context();
                context.lock();
                try {
                    if (!storage.isValid()) { return RecipeText.StorageChanged.blocked(); }
                    var snapshot = storage.snapshot();
                    long available = input ? snapshot.available().getLong(key)
                        : storage.insert(key, remaining, Actionable.SIMULATE);
                    if (available < 0 || !input && available > remaining) {
                        throw new IllegalStateException("Storage violated the quantity or simulated transfer contract");
                    }
                    long portion = Math.min(remaining, available);
                    if (portion == 0) { continue; }
                    var reserved = input ? storage.reserveExtract(snapshot, key, portion)
                        : storage.reserveInsert(snapshot, key, portion);
                    if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                        return RecipeText.StorageChanged.blocked();
                    }
                    reservations.add(Objects.requireNonNull(reserved.reservation()));
                    remaining -= portion;
                } finally {
                    context.unlock();
                }
                if (remaining == 0) { break; }
            }
            if (remaining > 0 && (input || !voidExcess)) {
                return (input ? RecipeText.ResourcesMissing : RecipeText.ResourceOutputFull)
                    .blocked(id, Long.toString(remaining));
            }
        }
        return ProcessResult.SUCCESS;
    }
}
