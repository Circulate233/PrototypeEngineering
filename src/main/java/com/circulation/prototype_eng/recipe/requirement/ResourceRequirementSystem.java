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
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.Iterator;
import org.jetbrains.annotations.Nullable;

/** PM's ordered multi-container resource IO, reserving actual quantities instead of simulating twice. */
public final class ResourceRequirementSystem implements RecipeRequirementSystem<ResourceRequirement> {
    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The owner binding intentionally retains its captured callback scope.
    public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                          RecipeStage stage) {
        var payload = requirement.payload();
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
        int parallels = RecipeParallelism.effective(process);
        double chance = payload.chance().effective(process);
        var tick = process.captureTickContext();
        return new RequirementPreparation() {
            @Nullable
            private ResourceComputation computation;
            @Nullable
            private ResourceAcquisition acquisition;

            private @Nullable ResourceComputation facts() {
                if (computation == null && captured.complete) {
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
            public @Nullable RecipePureComputation computation() {
                return facts();
            }

            @Override
            public void retainPureFacts() {
                process.retainPureFacts(stage, Objects.requireNonNull(computation));
            }

            @Override
            public RequirementTransaction acquire() {
                if (process.hatches() != hatches) {
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), List.of());
                }
                var budget = process.preparationBudget();
                if (computation == null) {
                    if (!captured.advance(budget)) {
                        return new PreparedRequirement(this);
                    }
                    facts();
                    return new PreparedRequirement(this);
                }
                if (acquisition == null) {
                    acquisition = new ResourceAcquisition(process, requirement, hatches.resources(), computation, inputs, outputs, resolves, random);
                }
                return acquisition.advance(budget, this);
            }
        };
    }

    /**
     * Original owner selection/preflight/reservation phases, with every inner visit resumable.
     */
    private static final class ResourceAcquisition {
        private final RecipeProcess process;
        private final RecipeRequirement<ResourceRequirement> requirement;
        private final List<ResourceHatch> hatches;
        private final ResourceComputation facts;
        private final List<GenericStack> inputs;
        private final boolean resolves;
        private final boolean needsOutput;
        private final SourceCapture current;
        private final AEKey2LongMap available = new AEKey2LongMap.LinkdOpenHashMap();
        private final AEKey2LongMap rawInputs = new AEKey2LongMap.LinkdOpenHashMap();
        private AEKey2LongMap consumed;
        private AEKey2LongMap checkedInputs;
        private final ObjectArrayList<ResourceReservation> checks = new ObjectArrayList<>();
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
                                    List<ResourceHatch> hatches, ResourceComputation facts, List<GenericStack> inputs,
                                    List<GenericStack> outputs, boolean resolves, boolean random) {
            this.process = process;
            this.requirement = requirement;
            this.hatches = hatches;
            this.facts = facts;
            this.inputs = inputs;
            this.resolves = resolves;
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
            };
        }

        private boolean releaseChecks(RecipeWorkBudget budget) {
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
            releaseChecks(new RecipeWorkBudget(Long.MAX_VALUE));
            if (selection != null) {
                selection.rollback();
            }
        }

        private RequirementTransaction advance(RecipeWorkBudget budget, RequirementPreparation binding) {
            var owned = new ObjectArrayList<ResourceReservation>();
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
                                if (entry.getLongValue() < 0) { throw new IllegalStateException("Negative available resource balance"); }
                                if (entry.getLongValue() == 0) { continue; }
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
                                consumed = new AEKey2LongMap.LinkdOpenHashMap();
                                checkedInputs = new AEKey2LongMap.LinkdOpenHashMap();
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
                        boolean needsInput = selection != null ? !selection.inputs().isEmpty() : !inputs.isEmpty();
                        if (reservation == null) {
                            reservation = new ReservationCursor(hatches,
                                input ? preflight ? checkedInputs : consumed : preflight ? facts.checkedOutputs() : facts.produced(), input,
                                input ? needsInput : needsOutput, !input && requirement.payload().voidExcessOutputs(), requirement.id().toString());
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
        private final List<ResourceHatch> hatches;
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
        private ProcessResult result = ProcessResult.SUCCESS;

        private ReservationCursor(List<ResourceHatch> hatches, AEKey2LongMap quantities, boolean input,
                                  boolean needsStorage, boolean voidExcess, String id) {
            this.hatches = hatches;
            this.quantities = quantities;
            this.input = input;
            this.needsStorage = needsStorage;
            this.voidExcess = voidExcess;
            this.id = id;
        }

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
                    if (!budget.take()) {
                        return false;
                    }
                    var storage = storages.get(source++);
                    var context = storage.context();
                    context.lock();
                    try {
                        if (!storage.isValid()) {
                            result = RecipeText.StorageChanged.blocked();
                            complete = true;
                            return true;
                        }
                        var snapshot = storage.snapshotFor(key);
                        long available = input ? snapshot.available().getLong(key) : storage.insert(key, remaining, Actionable.SIMULATE);
                        if (available < 0 || !input && available > remaining) {
                            throw new IllegalStateException("Storage violated simulated transfer contract");
                        }
                        long portion = Math.min(remaining, available);
                        if (portion == 0) {
                            continue;
                        }
                        var reserved = input ? storage.reserveExtract(snapshot, key, portion) : storage.reserveInsert(snapshot, key, portion);
                        if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                            result = RecipeText.StorageChanged.blocked();
                            complete = true;
                            return true;
                        }
                        owned.add(Objects.requireNonNull(reserved.reservation()));
                        remaining -= portion;
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
    }

    /**
     * Owner-only snapshot cursor. Every source/quantity/revision proof is charged where read.
     */
    private static final class SourceCapture {
        private final List<ResourceHatch> hatches;
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

        private SourceCapture(List<ResourceHatch> hatches) {
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
                sources.add(Objects.requireNonNull(source));
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
