package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.ResourceLocation;

import java.util.Iterator;
import java.util.List;

/**
 * PM's ordered multi-container resource IO, reserving actual quantities instead of simulating twice.
 */
public final class ResourceRequirementSystem implements RecipeRequirementSystem<ResourceRequirement> {
    static RequirementTransaction checkPresence(RecipeProcess process, ResourceLocation id, ResourceRequirement resources) {
        var inputs = resources.inputs();
        var hatches = process.hatches().resources();
        if (!resources.fuzzyInputs().isEmpty() || !resources.dynamicInputs().isEmpty()) {
            var selection = ResourceInputResolver.resolve(process, RecipeRequirements.RESOURCES.requirement(id, resources),
                hatches, 1, scaled(inputs, 1));
            if (selection.result() != ProcessResult.SUCCESS) return new ReservedRequirement(selection.result(), List.of());
            inputs = selection.inputs();
        }
        var result = preflight(hatches, inputs, 1, resources.inputAllocation(), RecipeStage.START,
            new AEKeyLinkedOpenHashMap(0), !inputs.isEmpty(), false, false, id.toString());
        return new ReservedRequirement(result, List.of());
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                                  RecipeStage stage, List<GenericStack> inputs, List<GenericStack> outputs) {
        var payload = batchResources(process, requirement.payload(), stage);
        boolean batched = payload != requirement.payload();
        if (batched) {
            inputs = payload.inputsPerTick();
            outputs = payload.outputsPerTick();
        }
        var pool = stage == RecipeStage.END ? payload.randomOutputs() : null;
        boolean randomOutputs = pool != null && pool.hasOutputs();
        boolean resolveInputs = stage == RecipeStage.START && (!payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty());
        if (inputs.isEmpty() && outputs.isEmpty() && !randomOutputs && !resolveInputs) {
            return RequirementTransaction.SUCCESS;
        }
        var reservations = new ObjectArrayList<ResourceReservation>(6);
        ResourceInputResolver.Selection selection = null;
        try {
            var hatches = process.hatches().resources();
            String id = requirement.id().toString();
            int parallelism = batched ? 1 : RecipeParallelism.effective(process);
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
            var consumed = payload.inputAllocation() == InputAllocation.SPLITTABLE
                ? scaled(inputs, times) : new AEKeyLinkedOpenHashMap(6);
            var produced = scaled(outputs, times);
            AEKey2LongMap random = null;
            if (randomOutputs) {
                random = new AEKeyLinkedOpenHashMap(6);
                randomOutputs(process, pool, salt, times, random);
                merge(produced, random);
            }
            boolean needsOutput = !outputs.isEmpty() || randomOutputs;
            if (times != checkTimes) {
                var checkedOutputs = scaled(outputs, checkTimes);
                if (random != null) {
                    merge(checkedOutputs, random);
                }
                ProcessResult checked = preflight(hatches, inputs, checkTimes, payload.inputAllocation(), stage, checkedOutputs,
                    !inputs.isEmpty(), needsOutput, payload.voidExcessOutputs(), id);
                if (checked != ProcessResult.SUCCESS) {
                    return new ReservedRequirement(checked, reservations);
                }
            }
            ProcessResult result = payload.inputAllocation() == InputAllocation.SPLITTABLE
                ? reserve(hatches, consumed, true, !inputs.isEmpty(), false, id, reservations)
                : reserveAllocated(hatches, inputs, times, payload.inputAllocation(), stage, id, reservations);
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
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            ReservedRequirement.rollbackAfterFailure(reservations, failure);
            throw failure;
        }
    }

    /**
     * PM's conservative fixed-quantity check, with real temporary holds covering shared slots/keys.
     * Release these before actual reservations: checks do not consume resources and can be shared by
     * later requirements. Exact execution still reserves afresh, so an intervening change cannot overdraw.
     */
    private static ProcessResult preflight(List<? extends ResourceHatch> hatches, List<GenericStack> inputs,
                                           long times, InputAllocation allocation, RecipeStage stage,
                                           AEKey2LongMap outputs, boolean needsInput,
                                           boolean needsOutput, boolean voidExcess, String id) {
        var checks = new ObjectArrayList<ResourceReservation>(6);
        ProcessResult result;
        try {
            result = allocation == InputAllocation.SPLITTABLE
                ? reserve(hatches, scaled(inputs, times), true, needsInput, false, id, checks)
                : reserveAllocated(hatches, inputs, times, allocation, stage, id, checks);
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

    private static ProcessResult reserveAllocated(List<? extends ResourceHatch> hatches, List<GenericStack> inputs,
                                                  long times, InputAllocation allocation, RecipeStage stage,
                                                  String id, List<ResourceReservation> reservations) {
        var cursor = new ReservationCursor(hatches, new AEKeyLinkedOpenHashMap(6), true,
            !inputs.isEmpty(), false, id);
        cursor.allocate(inputs, times, allocation, stage);
        if (!cursor.advance(new RecipeWorkBudget(Long.MAX_VALUE), reservations))
            throw new IllegalStateException("Unbounded input allocation did not finish");
        return cursor.result;
    }

    private static AEKey2LongMap scaled(List<GenericStack> stacks, long times) {
        var amounts = new AEKeyLinkedOpenHashMap(stacks.size());
        if (times == 0) {
            return amounts;
        }
        for (int index2 = 0; index2 < stacks.size(); index2++) {
            var stack = stacks.get(index2);
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
        if (times == 0) {
            return;
        }
        if (pool.pickCount() >= pool.candidates().size()) {
            // Every positive-weight entry is certain; avoid one sampling/allocation pass per parallel.
            var stacks = new ObjectArrayList<GenericStack>(pool.candidates().size());
            var elements = pool.candidates();
            for (int index3 = 0; index3 < elements.size(); index3++) {
                var candidate = elements.get(index3);
                stacks.add(candidate.value());
            }
            merge(output, scaled(stacks, times));
            return;
        }
        // Preserve PM's exact per-execution weighted choice. Planned preparation must budget this work.
        for (long execution = 0; execution < times; execution++) {
            var selected = WeightedSampling.sampleWithoutReplacement(process.random(salt + ":random:" + execution),
                pool.candidates(), pool.pickCount());
            for (int index4 = 0; index4 < selected.size(); index4++) {
                var stack = selected.get(index4);
                addChecked(output, stack.what(), stack.amount());
            }
        }
    }

    /**
     * Requirement quantities are nonnegative; overflow must fail before changing the accumulated map.
     */
    private static void addChecked(AEKey2LongMap amounts, AEKey key, long amount) {
        if (amount > Long.MAX_VALUE - amounts.getLong(key)) {
            throw new ArithmeticException("long overflow");
        }
        amounts.addTo(key, amount);
    }

    private static ProcessResult reserve(List<? extends ResourceHatch> hatches, AEKey2LongMap quantities,
                                         boolean input, boolean needsStorage, boolean voidExcess, String id,
                                         List<ResourceReservation> reservations) {
        if (!needsStorage) {
            return ProcessResult.SUCCESS;
        }
        var storages = new ObjectArrayList<ResourceStorage>(hatches.size());
        var visited = new ReferenceOpenHashSet<StorageContext>(hatches.size());
        for (int index6 = 0; index6 < hatches.size(); index6++) {
            var hatch = hatches.get(index6);
            if (!hatch.isValid()) {
                continue;
            }
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
            for (int index1 = 0; index1 < storages.size(); index1++) {
                var storage = storages.get(index1);
                var context = storage.context();
                context.lock();
                try {
                    if (!storage.isValid()) {
                        return RecipeText.StorageChanged.blocked();
                    }
                    var snapshot = storage.snapshot();
                    long available = input ? snapshot.available().getLong(key)
                        : storage.insert(key, remaining, Actionable.SIMULATE);
                    if (available < 0 || !input && available > remaining) {
                        throw new IllegalStateException("Storage violated the quantity or simulated transfer contract");
                    }
                    long portion = Math.min(remaining, available);
                    if (portion == 0) {
                        continue;
                    }
                    var reserved = input ? storage.reserveExtract(snapshot, key, portion)
                        : storage.reserveInsert(snapshot, key, portion);
                    if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                        return RecipeText.StorageChanged.blocked();
                    }
                    reservations.add(reserved.reservation());
                    remaining -= portion;
                } finally {
                    context.unlock();
                }
                if (remaining == 0) {
                    break;
                }
            }
            if (remaining > 0 && (input || !voidExcess)) {
                return (input ? RecipeText.ResourcesMissing : RecipeText.ResourceOutputFull)
                    .blocked(id, Long.toString(remaining));
            }
        }
        return ProcessResult.SUCCESS;
    }

    private static ResourceRequirement batchResources(RecipeProcess process, ResourceRequirement payload, RecipeStage stage) {
        var timing = process.crossRecipeTiming();
        if (timing == null || stage != RecipeStage.TICK || payload.chance().effective(process) != 100) return payload;
        long times = timing.materialTimes(process);
        return new ResourceRequirement(payload.inputs(), batchStacks(payload.inputsPerTick(), times), batchStacks(payload.outputsPerTick(), times),
            payload.outputs(), payload.voidExcessOutputs(), ResourceChance.ALWAYS, payload.randomOutputs(), payload.fuzzyInputs(), payload.dynamicInputs(), payload.inputAllocation());
    }

    private static List<GenericStack> batchStacks(List<GenericStack> values, long times) {
        if (times == 0 || values.isEmpty()) return List.of();
        var result = new ObjectArrayList<GenericStack>(values.size());
        for (int index5 = 0; index5 < values.size(); index5++) {
            var value = values.get(index5);
            result.add(new GenericStack(value.what(), Math.multiplyExact(value.amount(), times)));
        }
        return result;
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The owner binding intentionally retains its captured callback scope.
    public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                          RecipeStage stage) {
        var payload = batchResources(process, requirement.payload(), stage);
        boolean batched = payload != requirement.payload();
        var inputs = switch (stage) {
            case START -> payload.inputs();
            case TICK -> payload.inputsPerTick();
            case END -> List.<GenericStack>of();
        };
        var outputs = switch (stage) {
            case START -> List.<GenericStack>of();
            case TICK -> payload.outputsPerTick();
            case END -> payload.outputs();
        };
        boolean resolves = stage == RecipeStage.START && (!payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty());
        boolean random = stage == RecipeStage.END && payload.randomOutputs() != null && payload.randomOutputs().hasOutputs();
        if (inputs.isEmpty() && outputs.isEmpty() && !resolves && !random) {
            return null;
        }
        var hatches = process.hatches();
        var captured = new SourceCapture(resolves ? hatches.resources() : List.of());
        captured.advance(process.preparationBudget());
        String salt = "resource:" + process.requirementPath() + ":" + stage
            + (stage == RecipeStage.TICK ? ":" + process.state().successfulTicks() : "");
        var parallelCapture = process.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        var chanceCapture = payload.chance().attribute() == null ? null : process.attributes().beginValueCapture(payload.chance().attribute(), 1);
        var tick = process.captureTickContext();
        return new RequirementPreparation() {
            @Nullable
            private ResourceComputation computation;
            @Nullable
            private ResourceAcquisition acquisition;
            private boolean attributesReady;
            private int parallels;
            private double chance;

            private @Nullable ResourceComputation facts() {
                if (computation == null && captured.complete && attributesReady) {
                    var previous = process.takePureFacts(stage);
                    computation = previous instanceof ResourceComputation retained
                        && retained.matches(payload, stage, process.seed(), salt, parallels, chance, process.probabilityTuning(),
                        captured.amounts, hatches.resources().size(), tick)
                        ? new ResourceComputation(payload, stage, process.seed(), salt, parallels, chance,
                        process.probabilityTuning(), captured.amounts, hatches.resources().size(), tick, retained)
                        : new ResourceComputation(payload, stage, process.seed(), salt, parallels, chance,
                        process.probabilityTuning(), captured.amounts, hatches.resources().size(), tick, null);
                }
                return computation;
            }

            @Override
            public boolean ownerWorkMetered() {
                return true;
            }

            @Override
            public boolean captureOnlyPreparation() {
                return computation == null;
            }

            @Override
            public @Nullable RecipePureComputation computation() {
                return facts();
            }

            @Override
            public void retainPureFacts() {
                process.retainPureFacts(stage, computation);
            }

            @Override
            public void withdrawPureFacts(RecipePureComputation expected) {
                if (computation != expected)
                    throw new IllegalStateException("Pure facts belong to another resource binding");
                process.withdrawPureFacts(stage, expected);
            }

            @Override
            public RequirementTransaction acquire() {
                if (process.hatches() != hatches) {
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), List.of());
                }
                var budget = process.preparationBudget();
                if (!parallelCapture.current() || chanceCapture != null && !chanceCapture.current())
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), List.of());
                if (!attributesReady) {
                    if (!parallelCapture.advance(budget) || chanceCapture != null && !chanceCapture.advance(budget))
                        return new PreparedRequirement(this);
                    parallels = batched ? 1 : Math.max(1, (int) Math.floor(parallelCapture.result()));
                    chance = payload.chance().percent() * Math.max(0, chanceCapture == null ? 1 : chanceCapture.result());
                    if (!Double.isFinite(chance))
                        throw new IllegalStateException("Resource chance multiplier overflow");
                    attributesReady = true;
                }
                if (computation == null) {
                    if (!captured.advance(budget)) {
                        return new PreparedRequirement(this);
                    }
                    facts();
                    return new PreparedRequirement(this);
                }
                if (acquisition == null) {
                    acquisition = new ResourceAcquisition(process, requirement, hatches.resources(), computation, inputs, outputs, resolves, random, stage);
                }
                return acquisition.advance(budget, this);
            }
        };
    }

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

    /**
     * Original owner selection/preflight/reservation phases, with every inner visit resumable.
     */
    private static final class ResourceAcquisition {
        private final RecipeProcess process;
        private final RecipeRequirement<ResourceRequirement> requirement;
        private final List<? extends ResourceHatch> hatches;
        private final ResourceComputation facts;
        private final List<GenericStack> inputs;
        private final boolean resolves;
        private final boolean needsOutput;
        private final RecipeStage stage;
        private final SourceCapture current;
        private final AEKey2LongMap available = new AEKeyLinkedOpenHashMap(6);
        private final AEKey2LongMap rawInputs = new AEKeyLinkedOpenHashMap(6);
        private AEKey2LongMap consumed;
        private AEKey2LongMap checkedInputs;
        private ObjectArrayList<ResourceReservation> checks = new ObjectArrayList<>();
        @Nullable
        private RecipeStageExecutor.Acquisition.FlatCandidate checksCleanup;
        @Nullable
        private Iterator<Object2LongMap.Entry<AEKey>> entries;
        @Nullable
        private ResourceInputResolver.Cursor choices;
        @Nullable
        private ResourceInputResolver.Selection selection;
        @Nullable
        private ReservationCursor reservation;
        private ProcessResult result = ProcessResult.SUCCESS;

        private int phase;
        private int source;
        private int stack;
        private int verified;

        private ResourceAcquisition(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                    List<? extends ResourceHatch> hatches, ResourceComputation facts, List<GenericStack> inputs,
                                    List<GenericStack> outputs, boolean resolves, boolean random, RecipeStage stage) {
            this.process = process;
            this.requirement = requirement;
            this.hatches = hatches;
            this.facts = facts;
            this.inputs = inputs;
            this.resolves = resolves;
            this.stage = stage;
            needsOutput = !outputs.isEmpty() || random;
            current = new SourceCapture(resolves ? hatches : List.of());
            consumed = facts.consumed();
            checkedInputs = facts.checkedInputs();
        }

        private RequirementTransaction pause(RequirementPreparation binding, ObjectArrayList<ResourceReservation> owned) {
            return new PreparedRequirement(binding) {
                @Override
                public List<ResourceReservation> reservations() {
                    return owned;
                }

                @Override
                public void rollback() {
                    cancel();
                    super.rollback();
                }

                @Override
                public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
                    if (!cancelWithinBudget(budget)) return false;
                    return super.rollbackWithinBudget(budget);
                }
            };
        }

        private void retainChecks() {
            if (checksCleanup != null || !process.incrementalAcquisition()) return;
            var retained = checks;
            checksCleanup = process.capturePreparedCleanup(new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public List<ResourceReservation> reservations() {
                    return retained;
                }

                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                }
            });
        }

        private boolean releaseChecks(RecipeWorkBudget budget) {
            if (checksCleanup != null) {
                checksCleanup.reject();
                if (!checksCleanup.cleaned()) return false;
                checksCleanup.releaseCleaned();
                checksCleanup = null;
                checks = new ObjectArrayList<>();
                return true;
            }
            while (!checks.isEmpty()) {
                if (!budget.take()) {
                    return false;
                }
                ResourceTransactions.rollback(List.of(checks.getLast()));
                checks.removeLast();
            }
            return true;
        }

        private void cancel() {
            if (!cancelWithinBudget(new RecipeWorkBudget(Long.MAX_VALUE)))
                throw new IllegalStateException("Preflight checks still own source cleanup obligations");
        }

        private boolean cancelWithinBudget(RecipeWorkBudget budget) {
            if (checksCleanup != null && !checksCleanup.cleaned()) return false;
            if (!releaseChecks(budget)) return false;
            return selection == null || selection.rollback(budget);
        }

        private RequirementTransaction advance(RecipeWorkBudget budget, RequirementPreparation binding) {
            var owned = new ObjectArrayList<ResourceReservation>(6);
            try {
                while (true) {
                    if (phase == 0) {
                        if (!current.advance(budget)) {
                            return pause(binding, owned);
                        }
                        while (source < current.amounts.size()) {
                            if (entries == null) {
                                entries = current.amounts.get(source).object2LongEntrySet().iterator();
                            }
                            while (entries.hasNext()) {
                                if (!budget.take()) {
                                    return pause(binding, owned);
                                }
                                var entry = entries.next();
                                if (entry.getLongValue() < 0) {
                                    throw new IllegalStateException("Negative available resource balance");
                                }
                                if (entry.getLongValue() == 0) {
                                    continue;
                                }
                                if (!facts.knows(entry.getKey())) {
                                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), owned);
                                }
                                long old = available.getLong(entry.getKey());
                                available.addTo(entry.getKey(), Math.min(entry.getLongValue(), Long.MAX_VALUE - old));
                            }
                            source++;
                            entries = null;
                        }
                        phase++;
                    } else if (phase == 1) {
                        if (resolves) {
                            if (choices == null) {
                                choices = new ResourceInputResolver.Cursor(process, requirement, facts.checkTimes(),
                                    facts.checkedInputs(), available, facts.dynamic(), !current.sources.isEmpty());
                            }
                            if (!choices.advance(budget)) {
                                return pause(binding, owned);
                            }
                            selection = choices.selection();
                            if (selection.result() != ProcessResult.SUCCESS) {
                                return new ReservedRequirement(selection.result(), owned);
                            }
                        }
                        phase++;
                    } else if (phase == 2) {
                        if (selection != null) {
                            var chosen = selection.inputs();
                            while (stack < chosen.size()) {
                                if (!budget.take()) {
                                    return pause(binding, owned);
                                }
                                var value = chosen.get(stack++);
                                addChecked(rawInputs, value.what(), value.amount());
                            }
                            if (entries == null) {
                                entries = rawInputs.object2LongEntrySet().iterator();
                                consumed = new AEKeyLinkedOpenHashMap(6);
                                checkedInputs = new AEKeyLinkedOpenHashMap(6);
                            }
                            while (entries.hasNext()) {
                                if (!budget.take()) {
                                    return pause(binding, owned);
                                }
                                var entry = entries.next();
                                consumed.addTo(entry.getKey(), ChanceMath.scaleCount(entry.getLongValue(), facts.times()));
                                checkedInputs.addTo(entry.getKey(), ChanceMath.scaleCount(entry.getLongValue(), facts.checkTimes()));
                            }
                            entries = null;
                        }
                        phase = 25;
                    } else if (phase == 25) {
                        while (verified < current.captures.size()) {
                            if (!budget.take()) {
                                return pause(binding, owned);
                            }
                            if (!current.captures.get(verified++).current()) {
                                return new ReservedRequirement(RecipeText.StorageChanged.blocked(), owned);
                            }
                        }
                        phase = facts.times() != facts.checkTimes() ? 3 : 6;
                    } else if (phase == 3 || phase == 4 || phase == 6 || phase == 7) {
                        boolean input = phase == 3 || phase == 6;
                        boolean preflight = phase < 6;
                        if (preflight) retainChecks();
                        boolean needsInput = selection != null ? !selection.inputs().isEmpty() : !inputs.isEmpty();
                        if (reservation == null) {
                            reservation = new ReservationCursor(hatches,
                                input ? preflight ? checkedInputs : consumed : preflight ? facts.checkedOutputs() : facts.produced(), input,
                                input ? needsInput : needsOutput, !input && requirement.payload().voidExcessOutputs(), requirement.id().toString());
                            if (input && requirement.payload().inputAllocation() != InputAllocation.SPLITTABLE) {
                                reservation.allocate(selection == null ? inputs : selection.inputs(),
                                    preflight ? facts.checkTimes() : facts.times(), requirement.payload().inputAllocation(), stage);
                            }
                        }
                        if (!reservation.advance(budget, preflight ? checks : owned)) {
                            return pause(binding, owned);
                        }
                        result = reservation.result;
                        reservation = null;
                        if (result != ProcessResult.SUCCESS) {
                            if (!preflight) {
                                return new ReservedRequirement(result, owned);
                            }
                            phase = 5;
                        } else if (phase == 4) {
                            phase = 5;
                        } else {
                            phase++;
                        }
                    } else if (phase == 5) {
                        if (!releaseChecks(budget)) {
                            return pause(binding, owned);
                        }
                        if (result != ProcessResult.SUCCESS) {
                            return new ReservedRequirement(result, owned);
                        }
                        phase = 6;
                    } else {
                        if (selection != null && !selection.install(budget)) {
                            return pause(binding, owned);
                        }
                        var reserved = new ReservedRequirement(ProcessResult.SUCCESS, owned);
                        return selection == null ? reserved : new ResolvedResources(reserved, selection);
                    }
                }
            } catch (RuntimeException | Error failure) {
                if (process.incrementalAcquisition()) {
                    if (checksCleanup != null) checksCleanup.reject();
                    @SuppressWarnings("UnnecessaryLocalVariable") // Cleanup retains this exact list even if later cursor state changes.

                    var retained = owned;
                    var cleanup = process.capturePreparedCleanup(new RequirementTransaction() {
                        @Override
                        public ProcessResult result() {
                            return ProcessResult.SUCCESS;
                        }

                        @Override
                        public List<ResourceReservation> reservations() {
                            return retained;
                        }

                        @Override
                        public void commit() {
                        }

                        @Override
                        public void rollback() {
                            if (selection != null) selection.rollback();
                        }

                        @Override
                        public boolean rollbackWithinBudget(RecipeWorkBudget work) {
                            return selection == null || selection.rollback(work);
                        }
                    });
                    cleanup.reject();
                    throw failure;
                }
                try {
                    cancel();
                } catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                ReservedRequirement.rollbackAfterFailure(owned, failure);
                throw failure;
            }
        }
    }

    private static final class ReservationCursor {
        private final List<? extends ResourceHatch> hatches;
        private final AEKey2LongMap quantities;
        private final boolean input;
        private final boolean needsStorage;
        private final boolean voidExcess;
        private final String id;
        private final ObjectArrayList<ResourceStorage> storages = new ObjectArrayList<>();
        private final ReferenceOpenHashSet<StorageContext> seen = new ReferenceOpenHashSet<>();
        @Nullable
        private Iterator<Object2LongMap.Entry<AEKey>> entries;
        @Nullable
        private AEKey key;
        private long remaining;
        private int hatch;
        private int source;
        private boolean complete;
        @Nullable
        private ReservationCaptureToken capture;
        private ProcessResult result = ProcessResult.SUCCESS;

        @Nullable
        private List<GenericStack> projections;
        private long times;
        @Nullable
        private InputAllocation allocation;
        private RecipeStage allocationStage;
        private int projection;
        private int capturedSource;
        private int verifiedSource;
        @Nullable
        private ObjectArrayList<StorageSnapshot> snapshots;
        @Nullable
        private ObjectArrayList<InputAllocation.Source> sourceFacts;
        @Nullable
        private ObjectArrayList<InputAllocation.Source> initialFacts;
        @Nullable
        private ResourceStorage.QuantityCapture quantityCapture;
        @Nullable
        private Iterator<Object2LongMap.Entry<AEKey>> quantityEntries;
        @Nullable
        private ImmutableResourceAmounts.Builder fullAmounts;
        @Nullable
        private InputAllocationPlan plan;

        private ReservationCursor(List<? extends ResourceHatch> hatches, AEKey2LongMap quantities, boolean input,
                                  boolean needsStorage, boolean voidExcess, String id) {
            this.hatches = hatches;
            this.quantities = quantities;
            this.input = input;
            this.needsStorage = needsStorage;
            this.voidExcess = voidExcess;
            this.id = id;
        }

        private void allocate(List<GenericStack> values, long multiplier, InputAllocation policy, RecipeStage stage) {
            projections = values;
            times = multiplier;
            allocation = policy;
            allocationStage = stage;
            snapshots = new ObjectArrayList<>();
            sourceFacts = new ObjectArrayList<>();
        }

        @SuppressWarnings("DataFlowIssue")
        private boolean advance(RecipeWorkBudget budget, List<ResourceReservation> owned) {
            if (complete || !needsStorage) {
                complete = true;
                return true;
            }
            while (hatch < hatches.size()) {
                if (!budget.take()) {
                    return false;
                }
                var value = hatches.get(hatch++);
                var storage = input ? value.input() : value.output();
                if (value.isValid() && storage != null && storage.isValid() && seen.add(storage.context())) {
                    storages.add(storage);
                }
            }
            if (storages.isEmpty()) {
                result = (input ? RecipeText.NoResourceInput : RecipeText.NoResourceOutput).blocked(id);
                complete = true;
                return true;
            }
            if (allocation != null) return advanceAllocated(budget, owned);
            if (entries == null) {
                entries = quantities.object2LongEntrySet().iterator();
            }
            while (key != null || entries.hasNext()) {
                if (key == null) {
                    if (!budget.take()) {
                        return false;
                    }
                    var entry = entries.next();
                    key = entry.getKey();
                    remaining = entry.getLongValue();
                    source = 0;
                }
                while (source < storages.size()) {
                    if (remaining == 0) break;
                    if (capture == null && !budget.take()) return false;
                    var storage = storages.get(source);
                    var context = storage.context();
                    context.lock();
                    try {
                        if (!storage.isValid()) {
                            result = RecipeText.StorageChanged.blocked();
                            complete = true;
                            return true;
                        }
                        if (capture == null) {
                            var snapshot = storage.snapshotFor(key);
                            var nativeCapture = input ? storage.beginReserveExtract(snapshot, key, remaining) : storage.beginReserveInsert(snapshot, key, remaining);
                            capture = new ReservationCaptureToken(storage.context(), nativeCapture);
                            owned.add(capture); // One original handle is retained even before its native journal completes.
                        }
                        if (!capture.advance(budget)) return false;
                        var reserved = capture.result;
                        long portion = capture.amount;
                        if (portion < 0 || portion > remaining)
                            throw new IllegalStateException("Storage violated reservation capture contract");
                        if (reserved.status() != ReservationResult.Status.ACCEPTED && reserved.status() != ReservationResult.Status.INSUFFICIENT) {
                            result = RecipeText.StorageChanged.blocked();
                            complete = true;
                            return true;
                        }
                        remaining -= portion;
                        capture = null;
                        source++;
                    } finally {
                        context.unlock();
                    }
                    if (remaining == 0) {
                        break;
                    }
                }
                if (remaining > 0 && (input || !voidExcess)) {
                    result = (input ? RecipeText.ResourcesMissing : RecipeText.ResourceOutputFull).blocked(id, Long.toString(remaining));
                    complete = true;
                    return true;
                }
                key = null;
            }
            complete = true;
            return true;
        }

        @SuppressWarnings("DataFlowIssue")
        private boolean advanceAllocated(RecipeWorkBudget budget, List<ResourceReservation> owned) {
            var values = projections;
            while (key != null || projection < values.size()) {
                if (key == null) {
                    if (!budget.take()) return false;
                    var value = values.get(projection++);
                    remaining = ChanceMath.scaleCount(value.amount(), times);
                    if (remaining == 0) continue;
                    key = value.what();
                }
                while (capturedSource < storages.size()) {
                    var storage = storages.get(capturedSource);
                    if (allocation.kind() == InputAllocation.Kind.CUSTOM && initialFacts == null) {
                        if (quantityCapture == null) {
                            if (!budget.take()) return false;
                            quantityCapture = storage.beginQuantityCapture();
                        }
                        if (!quantityCapture.advance(budget)) return false;
                        if (quantityEntries == null) {
                            quantityEntries = quantityCapture.available().object2LongEntrySet().iterator();
                            fullAmounts = ImmutableResourceAmounts.builder();
                        }
                        while (quantityEntries.hasNext()) {
                            if (!budget.take()) return false;
                            var entry = quantityEntries.next();
                            if (entry.getLongValue() < 0)
                                throw new IllegalStateException("Negative source snapshot balance");
                            fullAmounts.addTo(entry.getKey(), entry.getLongValue());
                        }
                    }
                    if (!budget.take()) return false;
                    var context = storage.context();
                    context.lock();
                    try {
                        if (!storage.isValid()) return changed();
                        var snapshot = storage.snapshotFor(key);
                        if (snapshot.context() != context || !context.current(snapshot.revision())
                            || quantityCapture != null && !quantityCapture.current()) return changed();
                        long available = snapshot.available().getLong(key);
                        long extractable = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
                        if (available < 0 || extractable < 0)
                            throw new IllegalStateException("Storage violated input allocation availability");
                        snapshots.add(snapshot);
                        var initial = initialFacts == null ? null : initialFacts.get(capturedSource);
                        var amounts = initial != null ? initial.resources() : fullAmounts == null ? InputAllocationPlan.NO_RESOURCES
                            : fullAmounts.build();
                        sourceFacts.add(new InputAllocation.Source(capturedSource,
                            initial == null ? snapshot.revision() : initial.revision(),
                            Math.min(available, extractable), amounts, 0, extractable));
                    } finally {
                        context.unlock();
                    }
                    quantityCapture = null;
                    quantityEntries = null;
                    fullAmounts = null;
                    capturedSource++;
                }
                if (plan == null) {
                    if (initialFacts == null) initialFacts = sourceFacts;
                    plan = new InputAllocationPlan(allocation, allocationStage,
                        projection - 1, key, remaining, sourceFacts);
                }
                if (!plan.advance(budget)) return false;
                if (plan.missing() != 0) {
                    result = RecipeText.ResourcesMissing.blocked(id, Long.toString(plan.missing()));
                    complete = true;
                    return true;
                }
                while (verifiedSource < storages.size()) {
                    if (!budget.take()) return false;
                    var storage = storages.get(verifiedSource);
                    var snapshot = snapshots.get(verifiedSource++);
                    var context = storage.context();
                    context.lock();
                    try {
                        if (!storage.isValid() || !context.current(snapshot.revision())) return changed();
                    } finally {
                        context.unlock();
                    }
                }
                while (source < storages.size()) {
                    if (capture == null && !budget.take()) return false;
                    long expected = plan.amount(source);
                    if (expected == 0) {
                        source++;
                        continue;
                    }
                    var storage = storages.get(source);
                    var context = storage.context();
                    context.lock();
                    try {
                        if (!storage.isValid()) return changed();
                        if (capture == null) {
                            var snapshot = snapshots.get(source);
                            if (!context.current(snapshot.revision())) return changed();
                            capture = new ReservationCaptureToken(context, storage.beginReserveExtract(snapshot, key, expected));
                            owned.add(capture);
                        }
                        if (!capture.advance(budget)) return false;
                        if (capture.result.status() != ReservationResult.Status.ACCEPTED
                            || capture.amount != expected) return changed();
                        capture = null;
                        source++;
                    } finally {
                        context.unlock();
                    }
                }
                key = null;
                plan = null;
                source = 0;
                capturedSource = 0;
                verifiedSource = 0;
                snapshots = new ObjectArrayList<>();
                sourceFacts = new ObjectArrayList<>();
            }
            complete = true;
            return true;
        }

        private boolean changed() {
            result = RecipeText.StorageChanged.blocked();
            complete = true;
            return true;
        }
    }

    /**
     * One inventory identity spans native partial capture, token transfer and exact source undo.
     */
    private static final class ReservationCaptureToken implements ResourceReservation {
        private final StorageContext context;
        private final ResourceStorage.ReservationCapture capture;
        @Nullable
        private ReservationResult result;
        @Nullable
        private ResourceReservation token;
        private long amount;
        private boolean cancelled;

        private ReservationCaptureToken(StorageContext context, ResourceStorage.ReservationCapture capture) {
            this.context = context;
            this.capture = capture;
        }

        private boolean advance(RecipeWorkBudget budget) {
            if (result != null) return true;
            if (!capture.advance(budget)) return false;
            amount = capture.amount();
            result = capture.result();
            token = result.reservation();
            if (token != null && token.context() != context)
                throw new IllegalStateException("Captured reservation changed its physical context");
            return true;
        }

        @Override
        public StorageContext context() {
            return context;
        }

        @Override
        public boolean canApply() {
            return canApplyWithinBudget(new RecipeWorkBudget(Long.MAX_VALUE)) == BudgetResult.ACCEPTED;
        }

        @Override
        public BudgetResult canApplyWithinBudget(RecipeWorkBudget budget) {
            if (cancelled || result == null) return BudgetResult.REJECTED;
            return token == null ? budget.take() ? BudgetResult.ACCEPTED : BudgetResult.YIELDED : token.canApplyWithinBudget(budget);
        }

        @Override
        public void apply() {
            if (cancelled || result == null) throw new IllegalStateException("Incomplete capture cannot apply");
            if (token != null) token.apply(); // Preserve the native legacy all-domain lock contract.
        }

        @Override
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            if (cancelled || result == null) throw new IllegalStateException("Incomplete capture cannot apply");
            return token == null ? budget.take() : token.applyWithinBudget(budget);
        }

        @Override
        public boolean supportsSequentialUse() {
            return token == null || token.supportsSequentialUse();
        }

        @Override
        public void exposeApplied() {
            if (token != null) token.exposeApplied();
        }

        @Override
        public boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
            return token == null ? budget.take() : token.exposeAppliedWithinBudget(budget);
        }

        @Override
        public void complete() {
            if (result == null) throw new IllegalStateException("Incomplete capture cannot complete");
            if (token != null) token.complete();
        }

        @Override
        public boolean completeWithinBudget(RecipeWorkBudget budget) {
            if (token != null) return token.completeWithinBudget(budget);
            if (result == null) throw new IllegalStateException("Incomplete capture cannot complete");
            return budget.take();
        }

        @Override
        public void rollback() {
            if (cancelled) return;
            if (token != null) {
                token.rollback();
            } else {
                if (!capture.cancel()) throw new IllegalStateException("Native capture cleanup remains pending");
            }
            cancelled = true;
        }

        @Override
        public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
            if (cancelled) return true;
            boolean complete = token == null ? capture.cancel(budget) : token.rollbackWithinBudget(budget);
            if (complete) cancelled = true;
            return complete;
        }

        @Override
        public Resolution resolution() {
            return cancelled ? Resolution.ROLLED_BACK : token == null ? capture.resolution() : token.resolution();
        }

        @Override
        public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            if (cancelled) return Resolution.ROLLED_BACK;
            return token == null ? capture.resolutionWithinBudget(budget) : token.resolutionWithinBudget(budget);
        }
    }

    /**
     * Owner-only snapshot cursor. Every source/quantity/revision proof is charged where read.
     */
    private static final class SourceCapture {
        private final List<? extends ResourceHatch> hatches;
        private ObjectArrayList<ResourceStorage> sources = new ObjectArrayList<>();
        private ObjectArrayList<ResourceStorage.QuantityCapture> captures = new ObjectArrayList<>();
        private ObjectArrayList<AEKey2LongMap> amounts = new ObjectArrayList<>();
        private ReferenceOpenHashSet<StorageContext> seen = new ReferenceOpenHashSet<>();
        @Nullable
        private ResourceStorage source;
        @Nullable
        private ResourceStorage.QuantityCapture current;
        private int index;
        private int checked;
        private boolean complete;

        private SourceCapture(List<? extends ResourceHatch> hatches) {
            this.hatches = hatches;
        }

        private boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            while (index < hatches.size() || current != null) {
                if (current == null) {
                    if (!budget.take()) {
                        return false;
                    }
                    var hatch = hatches.get(index++);
                    var candidate = hatch.input();
                    if (!hatch.isValid() || candidate == null || !candidate.isValid() || !seen.add(candidate.context())) {
                        continue;
                    }
                    source = candidate;
                    current = candidate.beginQuantityCapture();
                }
                if (!current.advance(budget)) {
                    return false;
                }
                sources.add(source);
                captures.add(current);
                amounts.add(current.available());
                current = null;
                source = null;
            }
            while (checked < captures.size()) {
                if (!budget.take()) {
                    return false;
                }
                if (!captures.get(checked++).current()) {
                    // No effects have been acquired. Restart without mixing quantities from different revisions.
                    sources = new ObjectArrayList<>();
                    captures = new ObjectArrayList<>();
                    amounts = new ObjectArrayList<>();
                    seen = new ReferenceOpenHashSet<>();
                    index = 0;
                    checked = 0;
                    return false;
                }
            }
            complete = true;
            return true;
        }
    }

    /**
     * Chosen identities are provisional until the enclosing stage succeeds, just like its tokens.
     */
    private record ResolvedResources(ReservedRequirement reserved, ResourceInputResolver.Selection selection)
        implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return reserved.result();
        }

        @Override
        public List<ResourceReservation> reservations() {
            return reserved.reservations();
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
            selection.rollback();
        }
    }
}
