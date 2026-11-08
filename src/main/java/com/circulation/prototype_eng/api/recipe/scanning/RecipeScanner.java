package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlot;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import java.util.Iterator;
import java.util.function.BooleanSupplier;

/**
 * Ordered PM recipe admission with bounded cooperative search. Each logical pass snapshots the
 * instance's selected canonical pools once, through all budget yields. Empty slot groups add no
 * group restriction inside that pool scope; explicit groups restrict that slot. Selection changes
 * are observed by the next pass after admission/exhaustion or an explicit safe reset. The scanner belongs to
 * one machine's exclusive execution owner. It retains only a search cursor, never a reservation or
 * scheduler authority between calls. A pass observes changing live storage at each probe, so it is
 * not an atomic global maximum. The actual process START remains authoritative after admission.
 */
public final class RecipeScanner {
    private final MachineWorkSlots host;
    private final RecipeCatalog catalog;
    private final RecipeProcessFactory factory;
    private final List<RecipeParallelismConstraint> constraints;
    private final List<RecipeCandidateIndex> indexes;
    private final List<RecipeScanPreparation> preparations;
    private final Predicate<MachineRecipe> monotonicStart;
    private final LongSupplier seeds;
    private final Set<ResourceLocation> groups;
    private List<MachineRecipe> candidates = List.of();
    private int cursor;
    @Nullable private Search search;
    private boolean scanning;
    private boolean faulted;
    @Nullable private ScanAttempt activeAttempt;
    @Nullable private RecipeCatalog.CandidatePass discovery;
    @Nullable private RecipeCatalog.Selection selection;
    @Nullable private RecipeCatalog.SelectionCapture selectionCapture;

    /** Safe default for arbitrary wrappers, probability, selectors and external attribute modifiers. */
    public RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        this(host, catalog, RecipeProcessFactory.STANDARD, List.of(new FixedIoScanConstraint()), List.of(), _ -> false, seeds);
    }

    /**
     * monotonicStart is an explicit assertion about the COMPLETE probe, including factory local
     * modifiers, all constraints, requirements and storage implementations. It must never infer
     * monotonicity merely from a requirement type ID. False uses exhaustive descending search.
     */
    public RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                         List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes,
                         Predicate<MachineRecipe> monotonicStart, LongSupplier seeds) {
        this(host, catalog, factory, constraints, indexes, monotonicStart, seeds, List.of());
    }

    /** Preparations project first-tick component state and undo it before an accepted candidate is admitted. */
    public RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                         List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes,
                         Predicate<MachineRecipe> monotonicStart, LongSupplier seeds, List<RecipeScanPreparation> preparations) {
        this.host = Objects.requireNonNull(host);
        this.catalog = Objects.requireNonNull(catalog);
        this.factory = Objects.requireNonNull(factory);
        this.constraints = immutable(constraints);
        this.indexes = immutable(indexes);
        this.preparations = immutable(preparations);
        this.monotonicStart = Objects.requireNonNull(monotonicStart);
        this.seeds = Objects.requireNonNull(seeds);
        var scope = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (var slot : host.slots()) {
            if (slot.allowedRecipeGroups().isEmpty()) {
                scope.clear();
                break;
            }
            scope.addAll(slot.allowedRecipeGroups());
        }
        groups = ObjectSets.unmodifiable(scope);
    }

    public MachineWorkSlots host() { return host; }

    /** Refresh dynamic selection only after the old attempt's cleanup and source obligations settle. */
    public void reset() {
        if (scanning || faulted || activeAttempt != null) {
            throw new IllegalStateException("Settle the active scan attempt before resetting its pool selection");
        }
        host.snapshot();
        resetPass();
    }

    /** At most one new process per call. Call at most once per scheduled machine tick. */
    public Result scan(ScanLimits limits) {
        Objects.requireNonNull(limits);
        if (scanning || faulted || activeAttempt != null) { throw new IllegalStateException("Recipe scanner is executing or faulted"); }
        host.snapshot();
        if (workPaused()) { return result(Status.PAUSED, 0, 0); }
        scanning = true;
        try {
            return advance(limits);
        } catch (RuntimeException | Error failure) {
            faulted = true;
            throw failure;
        } finally {
            scanning = false;
        }
    }

    /**
     * Exact bounded owner attempt. Its allowance comes from the caller; neither this handle nor a
     * nested constraint/probe creates a fresh allowance or grants another tick/deadline. At most one
     * attempt may admit during the caller's scheduled machine tick.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ScanAttempt beginScan(ScanLimits limits) {
        Objects.requireNonNull(limits);
        if (scanning || faulted || activeAttempt != null) throw new IllegalStateException("Recipe scanner is executing or faulted");
        activeAttempt = new ScanAttempt(limits);
        return activeAttempt;
    }

    @Nullable
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ScanAttempt pendingScan() {
        return activeAttempt;
    }

    public final class ScanAttempt {
        private final Thread owner = Thread.currentThread();
        private final ScanLimits limits;
        private final ObjectOpenHashSet<ResourceLocation> running = new ObjectOpenHashSet<>();
        private int active;
        private int preflightCursor;
        private int examined;
        private int probes;
        private int phase;
        private int slotCursor;
        private Iterator<ResourceLocation> slotGroups;
        @Nullable private WorkSlot checkingSlot;
        @Nullable private WorkSlot slot;
        @Nullable private MachineRecipe recipe;
        private int parentLimit;
        @Nullable private MachineAttributes.ValueCapture parentValue;
        @Nullable private MachineAttributes.ValueCapture capacityValue;
        private int bound;
        private int boundCursor;
        private int amount;
        private boolean finalBest;
        @Nullable private RecipeProcess process;
        @Nullable private RecipeScanProbes.Session probe;
        @Nullable private RecipeProcessFactory.Creation creation;
        @Nullable private MachineWorkSlots.Admission admission;
        private boolean stopping;
        private boolean executing;
        private boolean finished;
        @Nullable private Result terminal;
        @Nullable private Throwable failure;

        private ScanAttempt(ScanLimits limits) {
            this.limits = limits;
            if (host.pendingTickBatch() != null) throw new IllegalStateException("Recipe scanner overlaps a work-slot batch");
        }

        public Result advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            requireOwner();
            Objects.requireNonNull(budget);
            Objects.requireNonNull(forwardAllowed);
            if (finished) return Objects.requireNonNull(terminal);
            executing = true;
            try {
                return advanceBody(budget, () -> !stopping && !faulted && !workPaused() && forwardAllowed.getAsBoolean());
            } catch (RuntimeException | Error observed) {
                faulted = true;
                stopping = true;
                if (failure == null) failure = observed;
                if (probe != null) probe.cancel();
                throw observed;
            } finally {
                executing = false;
            }
        }

        private Result advanceBody(RecipeWorkBudget budget, BooleanSupplier allowed) {
            while (true) {
                if (!allowed.getAsBoolean()) {
                    stopping = true;
                    if (admission != null) {
                        admission.cancel();
                        admission = null;
                    }
                    if (probe != null) {
                        probe.cancel();
                        if (!probe.advance(budget, () -> false)) return yielded();
                        probe = null;
                    }
                    return finish(result(Status.PAUSED, examined, probes));
                }
                if (phase == 0) {
                    while (preflightCursor < host.slots().size()) {
                        if (!budget.take()) return yielded();
                        var captured = host.slots().get(preflightCursor++).process();
                        if (captured == null) continue;
                        captured.requirePersistenceBoundary();
                        if (captured.host() != host) throw new IllegalStateException("Work slot lost its process ownership");
                        running.add(captured.recipe().id());
                        active++;
                    }
                    if (capacityValue == null) capacityValue = host.owner().attributes().beginValueCapture(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
                    if (!capacityValue.advance(budget)) {
                        if (!capacityValue.current()) capacityValue = null;
                        return yielded();
                    }
                    int capacity = Math.clamp((long) capacityValue.result(), 0, host.slots().size());
                    capacityValue = null;
                    if (active >= capacity) return finish(result(Status.FULL, examined, probes));
                    phase = 1;
                }
                if (phase == 1) {
                    if (selection == null) {
                        if (selectionCapture == null) {
                            if (!budget.take()) return yielded();
                            selectionCapture = catalog.beginSelection(host.owner());
                        }
                        if (!selectionCapture.advance(budget)) return yielded();
                        selection = selectionCapture.result();
                        selectionCapture = null;
                    }
                    if (candidates.isEmpty()) {
                        if (discovery == null) discovery = catalog.beginCandidates(Objects.requireNonNull(selection), groups, indexes);
                        if (!discovery.advance(budget)) {
                            if (!discovery.current()) discovery.restartLiveIndexes();
                            return yielded();
                        }
                        candidates = discovery.result();
                    }
                    phase = 2;
                }
                if (phase == 2) {
                    if (cursor == candidates.size()) {
                        resetPass();
                        return finish(result(Status.EXHAUSTED, examined, probes));
                    }
                    if (recipe == null) {
                        if (examined >= limits.candidates() || !budget.take()) return yielded();
                        recipe = candidates.get(cursor);
                        examined++;
                        slotCursor = 0;
                        checkingSlot = null;
                        slot = null;
                        slotGroups = null;
                        if (running.contains(recipe.id())) {
                            nextCandidate();
                            continue;
                        }
                    }
                    while (slot == null && (checkingSlot != null || slotCursor < host.slots().size())) {
                        if (checkingSlot == null) {
                            if (!budget.take()) return yielded();
                            checkingSlot = host.slots().get(slotCursor++);
                            if (checkingSlot.process() != null) {
                                checkingSlot = null;
                                continue;
                            }
                            if (checkingSlot.allowedRecipeGroups().isEmpty()) {
                                slot = checkingSlot;
                                break;
                            }
                            slotGroups = recipe.recipeGroups().iterator();
                        }
                        while (slotGroups.hasNext()) {
                            if (!budget.take()) return yielded();
                            if (checkingSlot.allowedRecipeGroups().contains(slotGroups.next())) {
                                slot = checkingSlot;
                                break;
                            }
                        }
                        checkingSlot = null;
                    }
                    if (slot == null) {
                        nextCandidate();
                        continue;
                    }
                    if (parentValue == null) parentValue = host.owner().attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
                    if (!parentValue.advance(budget)) {
                        if (!parentValue.current()) parentValue = null;
                        return yielded();
                    }
                    parentLimit = Math.max(1, (int) Math.floor(parentValue.result()));
                    parentValue = null;
                    bound = parentLimit;
                    boundCursor = 0;
                    phase = search != null && search.parentLimit == parentLimit ? 4 : 3;
                }
                if (phase == 3) {
                    while (boundCursor < constraints.size()) {
                        if (!budget.take()) return yielded();
                        int nextBound = constraints.get(boundCursor++).upperBound(host.owner(), recipe, bound);
                        if (nextBound < 0 || nextBound > bound) throw new IllegalArgumentException("Invalid recipe parallelism bound");
                        bound = nextBound;
                    }
                    if (!budget.take()) return yielded();
                    search = new Search(parentLimit, bound, monotonicStart.test(recipe), seeds.getAsLong(), ProbabilityTuning.defaultTuning());
                    phase = 4;
                }
                if (phase == 4) {
                    var current = Objects.requireNonNull(search);
                    if (probe == null && creation == null) {
                        if (current.low > current.high && current.best == 0) {
                            nextCandidate();
                            continue;
                        }
                        if (probes >= limits.probes() || !budget.take()) return yielded();
                        finalBest = current.low > current.high;
                        amount = finalBest ? current.best : current.monotonic ? (int) (current.low + (current.high - current.low) / 2) : (int) current.high;
                        creation = Objects.requireNonNull(factory.beginCreation(host.owner(), recipe, current.seed, current.tuning));
                    }
                    if (creation != null) {
                        if (!creation.advance(budget)) {
                            if (!creation.current()) creation = null;
                            return yielded();
                        }
                        process = configureCandidate(creation.result(), recipe, amount, current);
                        creation = null;
                        probe = RecipeScanProbes.begin(process, preparations, constraints);
                        probes++;
                    }
                    if (!probe.advance(budget, allowed)) return yielded();
                    boolean accepted = probe.result() == ProcessResult.SUCCESS;
                    probe = null;
                    if (finalBest) {
                        if (accepted) phase = 5;
                        else {
                            search = null;
                            return finish(yielded());
                        }
                    } else if (accepted && !current.monotonic) {
                        phase = 5;
                    } else {
                        if (accepted) {
                            current.best = amount;
                            current.low = (long) amount + 1;
                        } else current.high = (long) amount - 1;
                        process = null;
                        continue;
                    }
                }
                if (phase == 5) {
                    if (admission == null) {
                        if (!budget.take()) return yielded();
                        admission = host.beginAdmission(Objects.requireNonNull(slot).name(), Objects.requireNonNull(process), Objects.requireNonNull(selection));
                    }
                    if (!admission.advance(budget, allowed)) return yielded();
                    boolean admitted = admission.admitted();
                    admission = null;
                    if (!admitted) {
                        search = null;
                        return finish(yielded());
                    }
                    resetPass();
                    return finish(new Result(Status.ADMITTED, Objects.requireNonNull(process), examined, probes));
                }
            }
        }

        private void nextCandidate() {
            next();
            recipe = null;
            process = null;
            slot = null;
            parentValue = null;
            phase = 2;
        }

        private Result yielded() { return result(Status.YIELDED, examined, probes); }

        private Result finish(Result result) {
            if (result.status() == Status.YIELDED && !indexes.isEmpty()) {
                if (discovery != null) discovery.restartLiveIndexes();
                candidates = List.of();
                cursor = 0;
                search = null;
            }
            finished = true;
            terminal = result;
            activeAttempt = null;
            return result;
        }

        /**
         * Releases only an obligation-free search yield. A later tick re-observes every live index;
         * the canonical/search cursor and seed are pure facts, not old preparation or source rights.
         */
        public void releasePureYield() {
            requireOwner();
            if (hasObligations()) throw new IllegalStateException("Probe cleanup must finish before machine successors");
            if (discovery != null) discovery.restartLiveIndexes();
            finish(yielded());
        }

        public boolean hasObligations() {
            requireOwner();
            return !finished && (admission != null || probe != null && probe.hasObligations());
        }
        public boolean finished() { requireOwner(); return finished; }
        public void cancel() { requireOwner(); stopping = true; if (probe != null) probe.cancel(); }
        public @Nullable Throwable failure() { requireOwner(); return failure; }
        public @Nullable RecipePureComputation computation() { requireOwner(); return probe == null ? null : probe.computation(); }
        public void finishComputation(RecipePureComputation expected) {
            requireOwner();
            if (probe == null) throw new IllegalArgumentException("No scan computation");
            probe.finishComputation(expected);
        }
        public List<ResourceBatch.Source> sources() { requireOwner(); return probe == null ? List.of() : probe.sources(); }
        public boolean resolvingSources() { requireOwner(); return probe != null && probe.resolvingSources(); }
        public void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            requireOwner();
            if (probe == null) throw new IllegalArgumentException("No scan source action");
            probe.finishSourceResolution(expected, resolved);
        }
        private void requireOwner() {
            if (Thread.currentThread() != owner || executing || !finished && activeAttempt != this) throw new IllegalStateException("Scan requires its idle captured owner");
        }
    }

    @SuppressWarnings("StatementWithEmptyBody") // Legacy synchronous calls drain the same pool capture without creating an owner attempt.
    private Result advance(ScanLimits limits) {
        var running = new ObjectOpenHashSet<ResourceLocation>();
        var active = host.activeProcesses();
        for (var process : active) { running.add(process.recipe().id()); }
        int capacity = host.capacity();
        if (workPaused()) { return result(Status.PAUSED, 0, 0); }
        if (active.size() >= capacity) { return result(Status.FULL, 0, 0); }
        if (selection == null) {
            if (selectionCapture == null) selectionCapture = catalog.beginSelection(host.owner());
            while (!selectionCapture.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* synchronous explicit caller */ }
            selection = selectionCapture.result();
            selectionCapture = null;
        }
        if (candidates.isEmpty()) { candidates = catalog.candidates(selection, groups, indexes); }
        int examined = 0;
        int probes = 0;
        while (cursor < candidates.size() && examined < limits.candidates()) {
            if (workPaused()) { return result(Status.PAUSED, examined, probes); }
            var recipe = candidates.get(cursor);
            examined++;
            var slot = eligibleSlot(recipe);
            if (slot == null || running.contains(recipe.id())) { next(); continue; }
            int parentLimit = Math.max(1, (int) Math.floor(host.owner().attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1)));
            if (search == null || search.parentLimit != parentLimit) { search = begin(recipe, parentLimit); }
            if (workPaused()) { return result(Status.PAUSED, examined, probes); }
            while (search.low <= search.high && probes < limits.probes()) {
                if (workPaused()) { return result(Status.PAUSED, examined, probes); }
                int amount = search.monotonic ? (int) (search.low + (search.high - search.low) / 2) : (int) search.high;
                var process = candidate(recipe, amount, search);
                if (workPaused()) { return result(Status.PAUSED, examined, probes); }
                probes++;
                boolean accepted = canStart(process);
                if (workPaused()) { return result(Status.PAUSED, examined, probes); }
                if (accepted && !search.monotonic) { return admit(slot, process, examined, probes); }
                if (accepted) {
                    search.best = amount;
                    search.low = (long) amount + 1;
                } else {
                    search.high = (long) amount - 1;
                }
            }
            if (search.low <= search.high) { return result(Status.YIELDED, examined, probes); }
            if (search.best != 0) {
                if (probes >= limits.probes()) { return result(Status.YIELDED, examined, probes); }
                var process = candidate(recipe, search.best, search);
                if (workPaused()) { return result(Status.PAUSED, examined, probes); }
                probes++;
                boolean accepted = canStart(process);
                if (workPaused()) { return result(Status.PAUSED, examined, probes); }
                if (accepted) { return admit(slot, process, examined, probes); }
                // A previous success is not a lease. Re-observe on a later call if live storage changed.
                search = null;
                return result(Status.YIELDED, examined, probes);
            }
            next();
            if (probes >= limits.probes() && cursor < candidates.size()) { return result(Status.YIELDED, examined, probes); }
        }
        if (workPaused()) { return result(Status.PAUSED, examined, probes); }
        if (cursor == candidates.size()) {
            resetPass();
            return result(Status.EXHAUSTED, examined, probes);
        }
        return result(Status.YIELDED, examined, probes);
    }

    private Search begin(MachineRecipe recipe, int parentLimit) {
        int limit = parentLimit;
        for (var constraint : constraints) {
            int bound = constraint.upperBound(host.owner(), recipe, limit);
            if (bound < 0 || bound > limit) { throw new IllegalArgumentException("Invalid recipe parallelism bound"); }
            limit = bound;
        }
        return new Search(parentLimit, limit, monotonicStart.test(recipe), seeds.getAsLong(), ProbabilityTuning.defaultTuning());
    }

    private RecipeProcess candidate(MachineRecipe recipe, int amount, Search current) {
        var process = Objects.requireNonNull(factory.create(host.owner(), recipe, current.seed, current.tuning));
        return configureCandidate(process, recipe, amount, current);
        }

    private RecipeProcess configureCandidate(RecipeProcess process, MachineRecipe recipe, int amount, Search current) {
        if (process.owner() != host.owner() || process.recipe() != recipe || process.host() != null || process.seed() != current.seed
            || !process.probabilityTuning().equals(current.tuning) || process.state().lifecycle() != RecipeProcess.Lifecycle.NEW) {
            throw new IllegalArgumentException("Recipe factory changed candidate identity, policy or lifecycle");
        }
        process.requirePersistenceBoundary();
        // amount includes the machine baseline, but NOT this process's local modifier operations.
        process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, amount);
        return process;
    }

    private boolean canStart(RecipeProcess process) {
        return RecipeScanProbes.check(process, preparations, this::checkPrepared) == ProcessResult.SUCCESS;
    }

    private ProcessResult checkPrepared(RecipeProcess process) {
        for (var constraint : constraints) {
            if (workPaused()) { return RecipeText.Cancelled.blocked(); }
            var result = Objects.requireNonNull(constraint.test(process));
            if (result != ProcessResult.SUCCESS) { return result; }
        }
        if (workPaused()) { return RecipeText.Cancelled.blocked(); }
        return RecipeStageExecutor.probeStart(process);
    }

    private Result admit(WorkSlot slot, RecipeProcess process, int examined, int probes) {
        if (workPaused()) { return result(Status.PAUSED, examined, probes); }
        if (!host.startIn(slot.name(), process, Objects.requireNonNull(selection))) {
            if (workPaused()) { return result(Status.PAUSED, examined, probes); }
            // Admission can change after a pure constraint only if the host configuration changed.
            search = null;
            return result(Status.YIELDED, examined, probes);
        }
        resetPass();
        return new Result(Status.ADMITTED, process, examined, probes);
    }

    @Nullable
    private WorkSlot eligibleSlot(MachineRecipe recipe) {
        for (var slot : host.slots()) {
            if (slot.process() == null && slot.definition().accepts(recipe)) { return slot; }
        }
        return null;
    }

    private void next() { cursor++; search = null; }
    private boolean workPaused() {
        var lifecycle = host.owner().lifecycle();
        return lifecycle != null && lifecycle.retired() || !host.owner().recipeWorkAllowed();
    }
    private void resetPass() {
        selection = null;
        selectionCapture = null;
        discovery = null;
        candidates = List.of(); cursor = 0; search = null; }
    private static Result result(Status status, int examined, int probes) { return new Result(status, null, examined, probes); }

    private static <T> List<T> immutable(List<T> values) {
        var result = new ObjectArrayList<T>();
        for (var value : values) { result.add(Objects.requireNonNull(value)); }
        return ObjectLists.unmodifiable(result);
    }

    public enum Status { ADMITTED, YIELDED, EXHAUSTED, FULL, PAUSED }

    public record Result(Status status, @Nullable RecipeProcess process, int candidates, int probes) { }

    private static final class Search {
        private final int parentLimit;
        private final boolean monotonic;
        private final long seed;
        private final ProbabilityTuning tuning;
        private long low = 1;
        private long high;
        private int best;

        private Search(int parentLimit, int high, boolean monotonic, long seed, ProbabilityTuning tuning) {
            this.parentLimit = parentLimit;
            this.high = high;
            this.monotonic = monotonic;
            this.seed = seed;
            this.tuning = tuning;
        }
    }
}
