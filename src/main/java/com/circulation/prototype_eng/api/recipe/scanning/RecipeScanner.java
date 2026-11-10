package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlot;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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
    private final ControllerMode mode;
    private final boolean stablePipeline;
    private final MachineWorkSlots host;
    private RecipeCatalog catalog;
    private final RecipeProcessFactory factory;
    private final List<RecipeParallelismConstraint> constraints;
    private List<RecipeCandidateIndex> indexes;
    @Nullable
    private RecipeRegistry source;
    @Nullable
    private RecipeRegistry.Snapshot generation;
    @Nullable
    private RecipeProcessCodecs requiredCodecs;
    @Nullable
    private RecipeProcessCodecs.Snapshot generationCodecs;
    @Nullable
    private Function<RecipeCatalog, List<RecipeCandidateIndex>> indexFactory;
    private boolean started;
    private final List<RecipeScanPreparation> preparations;
    private final Predicate<MachineRecipe> monotonicStart;
    private final LongSupplier seeds;
    private final Set<ResourceLocation> groups;
    private final Object2ObjectOpenHashMap<String, PassState> threadStates = new Object2ObjectOpenHashMap<>();
    private List<MachineRecipe> candidates = List.of();
    private int cursor;
    @Nullable
    private Search search;
    private boolean scanning;
    private boolean faulted;
    @Nullable
    private ScanAttempt activeAttempt;
    @Nullable
    private RecipeCatalog.CandidatePass discovery;
    @Nullable
    private RecipeCatalog.Selection selection;
    @Nullable
    private RecipeCatalog.SelectionCapture selectionCapture;
    @Nullable
    private String selectedThread;
    private int selectedThreadIndex;
    private int nextThreadIndex;
    @Nullable
    private MachineWorkSlots.SharedUsage candidateBasis;
    @Nullable
    private RecipeCandidateIndex.Validation discoveryValidation;
    private boolean crossRoundAdmitted;
    @Nullable
    private MachineRecipe crossRoundRecipe;
    private Preflight preflight = new Preflight(-1);
    @Nullable
    private PureProgress pureProgress;
    /**
     * Safe default for arbitrary wrappers, probability, selectors and external attribute modifiers.
     */
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

    /**
     * Preparations project first-tick component state and undo it before an accepted candidate is admitted.
     */
    public RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                         List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes,
                         Predicate<MachineRecipe> monotonicStart, LongSupplier seeds, List<RecipeScanPreparation> preparations) {
        this(host, catalog, factory, constraints, indexes, monotonicStart, seeds, preparations, ControllerMode.LEGACY, false);
    }

    private RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                          List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes,
                          Predicate<MachineRecipe> monotonicStart, LongSupplier seeds, List<RecipeScanPreparation> preparations,
                          ControllerMode mode, boolean stablePipeline) {
        this.mode = mode;
        this.stablePipeline = stablePipeline;
        this.host = Objects.requireNonNull(host);
        this.catalog = Objects.requireNonNull(catalog);
        this.factory = Objects.requireNonNull(factory);
        this.constraints = immutable(constraints);
        this.indexes = immutable(indexes);
        this.preparations = immutable(preparations);
        this.monotonicStart = Objects.requireNonNull(monotonicStart);
        this.seeds = Objects.requireNonNull(seeds);
        var scope = new ObjectLinkedOpenHashSet<ResourceLocation>();
        boolean unrestricted = false;
        for (var slot : host.slots()) {
            if (slot.allowedRecipeGroups().isEmpty()) {
                scope.clear();
                unrestricted = true;
                break;
            }
            scope.addAll(slot.allowedRecipeGroups());
        }
        if (host.dynamicRoster() && !unrestricted) {
            if (host.dynamicRecipeGroups().isEmpty()) scope.clear();
            else scope.addAll(host.dynamicRecipeGroups());
        }
        groups = ObjectSets.unmodifiable(scope);
        host.owner().registerRecipeScanner(this);
    }

    public static RecipeScanner multithreaded(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        return new RecipeScanner(host, catalog, RecipeProcessFactory.STANDARD, List.of(new FixedIoScanConstraint()),
            List.of(), _ -> false, seeds, List.of(), ControllerMode.MULTITHREAD, true);
    }

    public static RecipeScanner crossRecipe(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        return new RecipeScanner(host, catalog, RecipeProcessFactory.STANDARD, List.of(new FixedIoScanConstraint()),
            List.of(), _ -> false, seeds, List.of(), ControllerMode.CROSS_RECIPE, true);
    }

    /** External factories/preparations keep the controller mode; optional input pruning stays unknown. */
    public static RecipeScanner multithreaded(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                                              List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes, Predicate<MachineRecipe> monotonicStart,
                                              LongSupplier seeds, List<RecipeScanPreparation> preparations) {
        return new RecipeScanner(host, catalog, factory, constraints, indexes, monotonicStart, seeds, preparations,
            ControllerMode.MULTITHREAD, false);
    }

    public static RecipeScanner crossRecipe(MachineWorkSlots host, RecipeCatalog catalog, RecipeProcessFactory factory,
                                            List<RecipeParallelismConstraint> constraints, List<RecipeCandidateIndex> indexes, Predicate<MachineRecipe> monotonicStart,
                                            LongSupplier seeds, List<RecipeScanPreparation> preparations) {
        return new RecipeScanner(host, catalog, factory, constraints, indexes, monotonicStart, seeds, preparations,
            ControllerMode.CROSS_RECIPE, false);
    }

    private static Result result(Status status, int examined, int probes) {
        return new Result(status, null, examined, probes);
    }

    private static <T> List<T> immutable(List<T> values) {
        var result = new ObjectArrayList<T>();
        for (var value : values) {
            result.add(Objects.requireNonNull(value));
        }
        return ObjectLists.unmodifiable(result);
    }

    /** Explicit known default factory/preparation/constraint path, with every recipe's local event guard. */
    public boolean supportsStableFixedInputLowerBounds() {
        // An unvisited scanner must not lend an old true guarantee to another scanner's new pass.
        return stablePipeline && (source == null ? catalog : source.snapshot().catalog()).supportsStableFixedInputLowerBounds();
    }

    /**
     * Binds this factory-created scanner before its first scan. A null resolver explicitly selects
     * independent, nonpersistent following. Unknown external indexes retain their initial opinion
     * only for this catalog; later generations use the pools' own indexes without that opinion.
     */
    public RecipeScanner follow(RecipeRegistry registry, @Nullable RecipeProcessCodecs codecs) {
        return follow(registry, codecs, null);
    }

    /** The optional factory rebuilds external indexes from each exact catalog, including the first. */
    public RecipeScanner follow(RecipeRegistry registry, @Nullable RecipeProcessCodecs codecs,
                                @Nullable Function<RecipeCatalog, List<RecipeCandidateIndex>> rebuildIndexes) {
        Objects.requireNonNull(registry, "registry");
        if (started || source != null || scanning || faulted || activeAttempt != null)
            throw new IllegalStateException("Bind the recipe source before scanning");
        var captured = registry.snapshot();
        if (captured.catalog() != catalog)
            throw new IllegalArgumentException("Scanner factory must use the source's exact catalog");
        if (codecs != null && registry.processCodecs() != codecs)
            throw new IllegalArgumentException("Scanner and recipe source require the same process codecs");
        var rebuilt = rebuildIndexes == null ? indexes : rebuildIndexes(rebuildIndexes, catalog);
        source = registry;
        generation = captured;
        requiredCodecs = codecs;
        generationCodecs = codecs == null ? null : codecs.snapshot();
        indexFactory = rebuildIndexes;
        indexes = rebuilt;
        return this;
    }

    /** Pure assembly validation; it never upgrades a fixed or independent scanner's contract. */
    public void validateProcessCodecs(RecipeProcessCodecs actual) {
        Objects.requireNonNull(actual, "actual");
        if (source != null && (requiredCodecs == null || requiredCodecs != actual || source.processCodecs() != actual))
            throw new IllegalArgumentException("Persistent following scanner and work slots require the source's process codecs");
    }

    private boolean generationCurrent() {
        return source == null || source.isCurrent(Objects.requireNonNull(generation));
    }

    private BooleanSupplier captureGeneration() {
        var capturedSource = source;
        var captured = generation;
        return capturedSource == null ? () -> true : () -> capturedSource.isCurrent(Objects.requireNonNull(captured));
    }

    private void requireCandidateCodec(MachineRecipe recipe) {
        if (generationCodecs != null) generationCodecs.require(recipe);
    }

    /** Called only with no active attempt: all retained state is then pure, across every thread. */
    private void refreshGeneration() {
        if (source == null || generationCurrent()) return;
        var captured = source.snapshot();
        var rebuilt = indexFactory == null ? List.<RecipeCandidateIndex>of() : rebuildIndexes(indexFactory, captured.catalog());
        var codecs = requiredCodecs == null ? null : requiredCodecs.snapshot();
        clearPasses();
        catalog = captured.catalog();
        indexes = rebuilt;
        generationCodecs = codecs;
        generation = captured;
    }

    private List<RecipeCandidateIndex> rebuildIndexes(Function<RecipeCatalog, List<RecipeCandidateIndex>> rebuild,
                                                       RecipeCatalog target) {
        boolean previous = scanning;
        scanning = true;
        try {
            return immutable(rebuild.apply(target));
        } catch (RuntimeException | Error failure) {
            faulted = true;
            throw failure;
        } finally {
            scanning = previous;
        }
    }

    private void clearPasses() {
        resetPass();
        threadStates.clear();
        selectedThread = null;
        crossRoundAdmitted = false;
        crossRoundRecipe = null;
        candidateBasis = null;
    }

    public MachineWorkSlots host() {
        return host;
    }

    /**
     * Refresh dynamic selection only after the old attempt's cleanup and source obligations settle.
     */
    public void reset() {
        if (scanning || faulted || activeAttempt != null) {
            throw new IllegalStateException("Settle the active scan attempt before resetting its pool selection");
        }
        host.snapshot();
        clearPasses();
    }

    /**
     * A controller may admit multiple logical processes within this shared scan allowance.
     * Call at most once per scheduled machine tick.
     */
    public Result scan(ScanLimits limits) {
        Objects.requireNonNull(limits);
        if (scanning || faulted || activeAttempt != null) {
            throw new IllegalStateException("Recipe scanner is executing or faulted");
        }
        host.snapshot();
        started = true;
        refreshGeneration();
        if (workPaused()) {
            return result(Status.PAUSED, 0, 0);
        }
        scanning = true;
        try {
            return mode == ControllerMode.LEGACY ? advance(limits) : advanceController(limits);
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
        if (scanning || faulted || activeAttempt != null)
            throw new IllegalStateException("Recipe scanner is executing or faulted");
        started = true;
        refreshGeneration();
        activeAttempt = new ScanAttempt(limits);
        return activeAttempt;
    }

    @Nullable
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ScanAttempt pendingScan() {
        return activeAttempt;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    // Legacy synchronous calls drain the same pool capture without creating an owner attempt.
    private Result advance(ScanLimits limits) {
        var running = new ObjectOpenHashSet<ResourceLocation>();
        var active = host.activeProcesses();
        for (var process : active) {
            running.add(process.recipe().id());
        }
        int capacity = host.capacity();
        if (workPaused()) {
            return result(Status.PAUSED, 0, 0);
        }
        if (active.size() >= capacity) {
            return result(Status.FULL, 0, 0);
        }
        if (selection == null) {
            if (selectionCapture == null) selectionCapture = catalog.beginSelection(host.owner());
            while (!selectionCapture.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* synchronous explicit caller */ }
            selection = selectionCapture.result();
            selectionCapture = null;
        }
        if (workPaused()) return result(Status.PAUSED, 0, 0);
        if (discovery != null) {
            var validation = discovery.beginValidation();
            while (!validation.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* explicit synchronous caller */ }
            if (workPaused()) return result(Status.PAUSED, 0, 0);
            if (!validation.current()) {
                discovery.restartLiveIndexes();
                candidates = List.of();
                cursor = 0;
                search = null;
            }
        }
        if (candidates.isEmpty()) {
            if (discovery == null) discovery = catalog.beginCandidates(selection, passGroups(), indexes, captureGeneration());
            while (!discovery.advance(new RecipeWorkBudget(Long.MAX_VALUE))) {
                if (workPaused()) return result(Status.PAUSED, 0, 0);
                if (!discovery.current()) discovery.restartLiveIndexes();
            }
            if (workPaused()) return result(Status.PAUSED, 0, 0);
            candidates = discovery.result();
        }
        int examined = 0;
        int probes = 0;
        while (cursor < candidates.size() && examined < limits.candidates()) {
            if (workPaused()) {
                return result(Status.PAUSED, examined, probes);
            }
            var recipe = candidates.get(cursor);
            examined++;
            if (mode != ControllerMode.LEGACY) {
                candidateBasis = host.sharedUsage(recipe);
                if (candidateBasis.available() == 0) return result(Status.FULL, examined, probes);
                if (candidateBasis.recipeThreads() >= recipe.maxConcurrentThreads()) {
                    next();
                    continue;
                }
            }
            var slot = eligibleSlot(recipe);
            if (slot == null || mode == ControllerMode.LEGACY && running.contains(recipe.id())) {
                next();
                continue;
            }
            int parentLimit = mode == ControllerMode.LEGACY
                ? Math.max(1, (int) Math.floor(host.owner().attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1)))
                : (int) Math.min(Integer.MAX_VALUE, Objects.requireNonNull(candidateBasis).available());
            if (search == null || search.parentLimit != parentLimit) {
                search = begin(recipe, parentLimit);
            }
            if (workPaused()) {
                return result(Status.PAUSED, examined, probes);
            }
            while (search.low <= search.high && probes < limits.probes()) {
                if (workPaused()) {
                    return result(Status.PAUSED, examined, probes);
                }
                int amount = search.monotonic ? (int) (search.low + (search.high - search.low) / 2) : (int) search.high;
                var process = candidate(recipe, amount, search);
                if (workPaused()) {
                    return result(Status.PAUSED, examined, probes);
                }
                probes++;
                boolean accepted = canStart(process);
                if (workPaused()) {
                    return result(Status.PAUSED, examined, probes);
                }
                if (accepted && !search.monotonic) {
                    return admit(slot, process, examined, probes);
                }
                if (accepted) {
                    search.best = amount;
                    search.low = (long) amount + 1;
                } else {
                    search.high = (long) amount - 1;
                }
            }
            if (search.low <= search.high) {
                return result(Status.YIELDED, examined, probes);
            }
            if (search.best != 0) {
                if (probes >= limits.probes()) {
                    return result(Status.YIELDED, examined, probes);
                }
                var process = candidate(recipe, search.best, search);
                if (workPaused()) {
                    return result(Status.PAUSED, examined, probes);
                }
                probes++;
                boolean accepted = canStart(process);
                if (workPaused()) {
                    return result(Status.PAUSED, examined, probes);
                }
                if (accepted) {
                    return admit(slot, process, examined, probes);
                }
                // A previous success is not a lease. Re-observe on a later call if live storage changed.
                search = null;
                return result(Status.YIELDED, examined, probes);
            }
            next();
            if (probes >= limits.probes() && cursor < candidates.size()) {
                return result(Status.YIELDED, examined, probes);
            }
        }
        if (workPaused()) {
            return result(Status.PAUSED, examined, probes);
        }
        if (cursor == candidates.size()) {
            if (discovery != null) {
                var finalProof = discovery.beginValidation();
                while (!finalProof.advance(new RecipeWorkBudget(Long.MAX_VALUE))) { /* explicit synchronous caller */ }
                if (!finalProof.current()) {
                    discovery.restartLiveIndexes();
                    candidates = List.of();
                    cursor = 0;
                    search = null;
                    return result(Status.YIELDED, examined, probes);
                }
            }
            resetPass();
            return result(Status.EXHAUSTED, examined, probes);
        }
        return result(Status.YIELDED, examined, probes);
    }

    private Search begin(MachineRecipe recipe, int parentLimit) {
        int limit = parentLimit;
        for (var constraint : constraints) {
            int bound = constraint.upperBound(host.owner(), recipe, limit);
            if (bound < 0 || bound > limit) {
                throw new IllegalArgumentException("Invalid recipe parallelism bound");
            }
            limit = bound;
        }
        return new Search(recipe, parentLimit, limit, monotonicStart.test(recipe), seeds.getAsLong(), ProbabilityTuning.defaultTuning());
    }

    private RecipeProcess candidate(MachineRecipe recipe, int amount, Search current) {
        requireCandidateCodec(recipe);
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
        recipe.parallelismSelection().select(process);
        return process;
    }

    private boolean canStart(RecipeProcess process) {
        if (workPaused()) return false;
        requireCandidateCodec(process.recipe());
        return RecipeScanProbes.check(process, preparations, this::checkPrepared) == ProcessResult.SUCCESS;
    }

    private ProcessResult checkPrepared(RecipeProcess process) {
        for (var constraint : constraints) {
            if (workPaused()) {
                return RecipeText.Cancelled.blocked();
            }
            var result = Objects.requireNonNull(constraint.test(process));
            if (result != ProcessResult.SUCCESS) {
                return result;
            }
        }
        if (workPaused()) {
            return RecipeText.Cancelled.blocked();
        }
        return RecipeStageExecutor.probeStart(process);
    }

    @SuppressWarnings("StatementWithEmptyBody") // Drain the same final authorization gate for an explicit synchronous caller.
    private Result admit(WorkSlot slot, RecipeProcess process, int examined, int probes) {
        if (workPaused()) {
            return result(Status.PAUSED, examined, probes);
        }
        requireCandidateCodec(process.recipe());
        var admission = mode == ControllerMode.LEGACY ? host.beginAdmission(slot.name(), process, Objects.requireNonNull(selection))
            : host.beginAdmission(slot.name(), process, Objects.requireNonNull(selection), Objects.requireNonNull(candidateBasis));
        try {
            while (!admission.advance(new RecipeWorkBudget(Long.MAX_VALUE), () -> {
                if (workPaused()) return false;
                requireCandidateCodec(process.recipe());
                return true;
            })) { /* synchronous explicit caller */ }
        } catch (RuntimeException | Error failure) {
            admission.cancel();
            throw failure;
        }
        boolean admitted = admission.admitted();
        if (!admitted) {
            if (workPaused()) {
                return result(Status.PAUSED, examined, probes);
            }
            // Admission can change after a pure constraint only if the host configuration changed.
            search = null;
            return result(Status.YIELDED, examined, probes);
        }
        if (mode == ControllerMode.CROSS_RECIPE) {
            crossRoundAdmitted = true;
            crossRoundRecipe = process.recipe();
            next();
        } else resetPass();
        return new Result(Status.ADMITTED, process, examined, probes);
    }

    @Nullable
    private WorkSlot eligibleSlot(MachineRecipe recipe) {
        if (mode == ControllerMode.MULTITHREAD) {
            var slot = host.slots().get(selectedThreadIndex);
            return slot.process() == null && slot.definition().accepts(recipe) ? slot : null;
        }
        for (var slot : host.slots()) {
            if (slot.process() == null && slot.definition().accepts(recipe)) {
                return slot;
            }
        }
        return mode == ControllerMode.CROSS_RECIPE ? host.dynamicScanSlot(recipe) : null;
    }

    private Result advanceController(ScanLimits limits) {
        int examined = 0;
        int probes = 0;
        int visited = 0;
        RecipeProcess admitted = null;
        while (examined < limits.candidates() && probes < limits.probes()) {
            if (mode == ControllerMode.MULTITHREAD) {
                if (host.slots().isEmpty() || visited >= host.slots().size()) break;
                selectedThreadIndex = Math.floorMod(nextThreadIndex, host.slots().size());
                if (host.slots().get(selectedThreadIndex).process() != null) {
                    nextThreadIndex++;
                    visited++;
                    continue;
                }
                selectThread(host.slots().get(selectedThreadIndex).name());
            }
            var outcome = advance(new ScanLimits(limits.candidates() - examined, limits.probes() - probes));
            examined += outcome.candidates();
            probes += outcome.probes();
            if (outcome.process() != null) admitted = outcome.process();
            if (mode == ControllerMode.MULTITHREAD && (outcome.status() == Status.ADMITTED || outcome.status() == Status.EXHAUSTED)) {
                nextThreadIndex = (selectedThreadIndex + 1) % host.slots().size();
                visited++;
                continue;
            }
            if (mode == ControllerMode.CROSS_RECIPE && outcome.status() == Status.ADMITTED) continue;
            if (mode == ControllerMode.CROSS_RECIPE && outcome.status() == Status.EXHAUSTED && crossRoundAdmitted) {
                crossRoundAdmitted = false;
                if (host.sharedUsage(Objects.requireNonNull(crossRoundRecipe)).available() > 0) continue;
            }
            if (mode == ControllerMode.MULTITHREAD && outcome.status() == Status.YIELDED)
                nextThreadIndex = (selectedThreadIndex + 1) % host.slots().size();
            return admitted == null ? new Result(outcome.status(), null, examined, probes)
                : new Result(outcome.status() == Status.PAUSED ? Status.PAUSED : Status.ADMITTED, admitted, examined, probes);
        }
        return new Result(admitted == null ? Status.YIELDED : Status.ADMITTED, admitted, examined, probes);
    }

    private void selectThread(String thread) {
        if (selectedThread != null) {
            var state = threadStates.computeIfAbsent(selectedThread, _ -> new PassState());
            state.candidates = candidates;
            state.cursor = cursor;
            state.search = search;
            state.discovery = discovery;
            state.selection = selection;
            state.selectionCapture = selectionCapture;
            state.discoveryValidation = discoveryValidation;
            state.progress = pureProgress;
        }
        var state = threadStates.computeIfAbsent(thread, _ -> new PassState());
        candidates = state.candidates;
        cursor = state.cursor;
        search = state.search;
        discovery = state.discovery;
        selection = state.selection;
        selectionCapture = state.selectionCapture;
        discoveryValidation = state.discoveryValidation;
        pureProgress = state.progress;
        selectedThread = thread;
    }

    private Set<ResourceLocation> passGroups() {
        return mode == ControllerMode.MULTITHREAD ? host.slots().get(selectedThreadIndex).allowedRecipeGroups() : groups;
    }

    private void next() {
        cursor++;
        search = null;
    }

    private boolean workPaused() {
        var lifecycle = host.owner().lifecycle();
        return lifecycle != null && lifecycle.retired() || !host.owner().recipeWorkAllowed() || !generationCurrent();
    }

    private void resetPass() {
        selection = null;
        selectionCapture = null;
        discovery = null;
        discoveryValidation = null;
        pureProgress = null;
        candidates = List.of();
        cursor = 0;
        search = null;
    }

    private enum ControllerMode {LEGACY, MULTITHREAD, CROSS_RECIPE}

    public enum Status {ADMITTED, YIELDED, EXHAUSTED, FULL, PAUSED}

    private static final class PassState {
        private List<MachineRecipe> candidates = List.of();
        private int cursor;
        @Nullable
        private Search search;
        @Nullable
        private RecipeCatalog.CandidatePass discovery;
        @Nullable
        private RecipeCatalog.Selection selection;
        @Nullable
        private RecipeCatalog.SelectionCapture selectionCapture;
        @Nullable
        private RecipeCandidateIndex.Validation discoveryValidation;
        @Nullable
        private PureProgress progress;
    }

    /** Unpublished host facts may survive a pure yield while the machine roster stays identical. */
    private static final class Preflight {
        private final long rosterRevision;
        private final ObjectOpenHashSet<ResourceLocation> running = new ObjectOpenHashSet<>();
        private int active;
        private int cursor;
        @Nullable
        private MachineAttributes.ValueCapture capacity;

        private Preflight(long rosterRevision) {
            this.rosterRevision = rosterRevision;
        }
    }

    public record Result(Status status, @Nullable RecipeProcess process, int candidates, int probes) {
    }

    private static final class Search {
        private final MachineRecipe recipe;
        private final int parentLimit;
        private final boolean monotonic;
        private final long seed;
        private final ProbabilityTuning tuning;
        private long low = 1;
        private long high;
        private int best;

        private Search(MachineRecipe recipe, int parentLimit, int high, boolean monotonic, long seed, ProbabilityTuning tuning) {
            this.recipe = recipe;
            this.parentLimit = parentLimit;
            this.high = high;
            this.monotonic = monotonic;
            this.seed = seed;
            this.tuning = tuning;
        }
    }

    /** Only obligation-free cursors; no admission, START session, worker or source authority. */
    private static final class PureProgress {
        private final ScanAttempt saved;
        private final long rosterRevision;
        private final MachineAttributes.FamilyCapture family;

        private PureProgress(ScanAttempt saved) {
            this.saved = saved;
            rosterRevision = saved.hostOwner().recipeRosterRevision();
            family = saved.hostOwner().attributes().familyCapture();
        }
    }

    public final class ScanAttempt {
        private final Thread owner = Thread.currentThread();
        private final ScanLimits limits;
        private int examined;
        private int probes;
        private int phase;
        private int slotCursor;
        private Iterator<ResourceLocation> slotGroups;
        @Nullable
        private WorkSlot checkingSlot;
        @Nullable
        private WorkSlot slot;
        @Nullable
        private MachineRecipe recipe;
        private int parentLimit;
        @Nullable
        private MachineAttributes.ValueCapture parentValue;
        private int bound;
        private int boundCursor;
        private int amount;
        private boolean finalBest;
        @Nullable
        private RecipeProcess process;
        @Nullable
        private RecipeScanProbes.Session probe;
        @Nullable
        private RecipeProcessFactory.Creation creation;
        @Nullable
        private RecipeProcessFactory.Creation creationProof;
        @Nullable
        private MachineWorkSlots.Admission admission;
        private boolean stopping;
        private boolean executing;
        private boolean finished;
        @Nullable
        private Result terminal;
        @Nullable
        private Throwable failure;
        @Nullable
        private MachineWorkSlots.UsageCapture sharedCapture;
        @Nullable
        private MachineWorkSlots.SharedUsage basis;
        @Nullable
        private RecipeProcess lastAdmitted;
        private int logicalVisited;
        private boolean threadChosen;
        private boolean discoveryValidated;
        @Nullable
        private RecipeCandidateIndex.Validation exhaustionValidation;
        private boolean exhaustionChecked;

        private ScanAttempt(ScanLimits limits) {
            this.limits = limits;
            if (host.pendingTickBatch() != null)
                throw new IllegalStateException("Recipe scanner overlaps a work-slot batch");
        }

        public Result advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            requireOwner();
            Objects.requireNonNull(budget);
            Objects.requireNonNull(forwardAllowed);
            if (finished) return Objects.requireNonNull(terminal);
            executing = true;
            try {
                return advanceBody(budget, () -> !stopping && !faulted && forwardAllowed.getAsBoolean() && !workPaused());
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
                    var stopped = prepareThread(budget);
                    if (stopped != null) return stopped;
                }
                if (phase == 1) {
                    var discoveryResult = discoverCandidates(budget, allowed);
                    if (discoveryResult != null) return discoveryResult;
                    if (phase == 1) continue;
                }
                if (phase == 2) {
                    if (cursor == candidates.size()) {
                        if (!exhaustionChecked && discovery != null) {
                            if (exhaustionValidation == null) exhaustionValidation = discovery.beginValidation();
                            if (!exhaustionValidation.advance(budget)) return yielded();
                            if (!exhaustionValidation.current()) {
                                discovery.restartLiveIndexes();
                                candidates = List.of();
                                cursor = 0;
                                search = null;
                                discardPureCandidate();
                                phase = 1;
                                exhaustionValidation = null;
                                continue;
                            }
                            exhaustionChecked = true;
                        }
                        if (mode == ControllerMode.CROSS_RECIPE && crossRoundAdmitted) {
                            if (examined >= limits.candidates() || probes >= limits.probes()) return yielded();
                            if (sharedCapture == null)
                                sharedCapture = host.beginSharedUsage(Objects.requireNonNull(crossRoundRecipe));
                            if (!sharedCapture.advance(budget)) {
                                if (!sharedCapture.current()) sharedCapture = null;
                                return yielded();
                            }
                            boolean anotherRound = sharedCapture.result().available() > 0;
                            sharedCapture = null;
                            crossRoundAdmitted = false;
                            if (anotherRound) {
                                resetPass();
                                clearExhaustionProof();
                                phase = 1;
                                continue;
                            }
                            resetPass();
                            clearExhaustionProof();
                            return finish(completed(Status.FULL));
                        }
                        resetPass();
                        if (mode == ControllerMode.MULTITHREAD && nextLogicalThread()) continue;
                        return finish(completed(Status.EXHAUSTED));
                    }
                    if (recipe == null) {
                        if (examined >= limits.candidates() || !budget.take()) return yielded();
                        recipe = candidates.get(cursor);
                        examined++;
                        slotCursor = mode == ControllerMode.MULTITHREAD ? selectedThreadIndex : 0;
                        checkingSlot = null;
                        slot = null;
                        slotGroups = null;
                        if (mode == ControllerMode.LEGACY && preflight.running.contains(recipe.id())
                            || mode != ControllerMode.LEGACY && host.owner().recipeThreadCount(recipe.id()) >= recipe.maxConcurrentThreads()) {
                            nextCandidate();
                            continue;
                        }
                    }
                    int slotEnd = mode == ControllerMode.MULTITHREAD ? selectedThreadIndex + 1 : host.slots().size();
                    while (slot == null && (checkingSlot != null || slotCursor < slotEnd)) {
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
                            slotGroups = Objects.requireNonNull(recipe).recipeGroups().iterator();
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
                    if (slot == null && mode == ControllerMode.CROSS_RECIPE) {
                        if (!budget.take()) return yielded();
                        slot = host.appendRuntimeSlot(recipe);
                    }
                    if (slot == null) {
                        nextCandidate();
                        continue;
                    }
                    if (mode == ControllerMode.LEGACY) {
                        if (parentValue == null)
                            parentValue = host.owner().attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
                        if (!parentValue.advance(budget)) {
                            if (!parentValue.current()) parentValue = null;
                            return yielded();
                        }
                        parentLimit = Math.max(1, (int) Math.floor(parentValue.result()));
                        parentValue = null;
                    } else {
                        if (sharedCapture == null)
                            sharedCapture = host.beginSharedUsage(Objects.requireNonNull(recipe));
                        if (!sharedCapture.advance(budget)) {
                            if (!sharedCapture.current()) sharedCapture = null;
                            return yielded();
                        }
                        basis = sharedCapture.result();
                        sharedCapture = null;
                        if (basis.available() == 0) return finish(completed(Status.FULL));
                        if (basis.recipeThreads() >= Objects.requireNonNull(recipe).maxConcurrentThreads()) {
                            nextCandidate();
                            continue;
                        }
                        parentLimit = (int) Math.min(Integer.MAX_VALUE, basis.available());
                    }
                    bound = parentLimit;
                    boundCursor = 0;
                    boolean sameSearch = search != null && search.recipe == recipe && search.parentLimit == parentLimit;
                    if (!sameSearch) {
                        creation = null;
                        creationProof = null;
                        process = null;
                        probe = null;
                    }
                    phase = sameSearch ? 4 : 3;
                }
                if (phase == 3) {
                    while (boundCursor < constraints.size()) {
                        if (!budget.take()) return yielded();
                        int nextBound = constraints.get(boundCursor++).upperBound(host.owner(), recipe, bound);
                        if (nextBound < 0 || nextBound > bound)
                            throw new IllegalArgumentException("Invalid recipe parallelism bound");
                        bound = nextBound;
                    }
                    if (!budget.take()) return yielded();
                    search = new Search(Objects.requireNonNull(recipe), parentLimit, bound, monotonicStart.test(recipe), seeds.getAsLong(), ProbabilityTuning.defaultTuning());
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
                        if (!allowed.getAsBoolean()) continue;
                        finalBest = current.low > current.high;
                        amount = finalBest ? current.best : current.monotonic ? (int) (current.low + (current.high - current.low) / 2) : (int) current.high;
                        requireCandidateCodec(Objects.requireNonNull(recipe));
                        creation = Objects.requireNonNull(factory.beginCreation(host.owner(), recipe, current.seed, current.tuning));
                    }
                    if (creation != null) {
                        if (!allowed.getAsBoolean()) continue;
                        if (!creation.advance(budget)) {
                            if (!creation.current()) creation = null;
                            return yielded();
                        }
                        process = configureCandidate(creation.result(), recipe, amount, current);
                        creationProof = creation;
                        creation = null;
                        if (!allowed.getAsBoolean()) continue;
                        requireCandidateCodec(Objects.requireNonNull(recipe));
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
                        if (!allowed.getAsBoolean()) continue;
                        requireCandidateCodec(Objects.requireNonNull(recipe));
                        admission = mode == ControllerMode.LEGACY
                            ? host.beginAdmission(Objects.requireNonNull(slot).name(), Objects.requireNonNull(process), Objects.requireNonNull(selection))
                            : host.beginAdmission(Objects.requireNonNull(slot).name(), Objects.requireNonNull(process), Objects.requireNonNull(selection), Objects.requireNonNull(basis));
                    }
                    if (!admission.advance(budget, () -> {
                        if (!allowed.getAsBoolean()) return false;
                        requireCandidateCodec(Objects.requireNonNull(recipe));
                        return true;
                    })) return yielded();
                    boolean admitted = admission.admitted();
                    admission = null;
                    if (!admitted) {
                        search = null;
                        return finish(yielded());
                    }
                    lastAdmitted = Objects.requireNonNull(process);
                    if (mode == ControllerMode.LEGACY) {
                        resetPass();
                        return finish(new Result(Status.ADMITTED, lastAdmitted, examined, probes));
                    }
                    if (mode == ControllerMode.CROSS_RECIPE) {
                        crossRoundAdmitted = true;
                        crossRoundRecipe = process.recipe();
                        nextCandidate();
                        continue;
                    }
                    resetPass();
                    if (nextLogicalThread()) continue;
                    return finish(completed(Status.EXHAUSTED));
                }
            }
        }

        @Nullable
        private Result discoverCandidates(RecipeWorkBudget budget, BooleanSupplier allowed) {
            if (selection == null) {
                if (selectionCapture == null) {
                    if (!budget.take()) return yielded();
                    selectionCapture = catalog.beginSelection(host.owner());
                }
                if (!selectionCapture.advance(budget)) return yielded();
                selection = selectionCapture.result();
                selectionCapture = null;
            }
            if (!allowed.getAsBoolean()) return null;
            if (discovery != null && !discoveryValidated) {
                if (discoveryValidation == null) discoveryValidation = discovery.beginValidation();
                if (!discoveryValidation.advance(budget)) return yielded();
                if (!allowed.getAsBoolean()) return null;
                if (!discoveryValidation.current()) {
                    discovery.restartLiveIndexes();
                    candidates = List.of();
                    cursor = 0;
                    search = null;
                    discardPureCandidate();
                }
                discoveryValidation = null;
                discoveryValidated = true;
            }
            if (candidates.isEmpty()) {
                if (discovery == null)
                    discovery = catalog.beginCandidates(Objects.requireNonNull(selection), passGroups(), indexes, captureGeneration());
                if (!discovery.advance(budget)) {
                    if (!discovery.current()) discovery.restartLiveIndexes();
                    return yielded();
                }
                if (!allowed.getAsBoolean()) return null;
                candidates = discovery.result();
                discoveryValidated = true;
            }
            phase = 2;
            return null;
        }

        @Nullable
        private Result prepareThread(RecipeWorkBudget budget) {
            if (preflight.rosterRevision != host.owner().recipeRosterRevision())
                preflight = new Preflight(host.owner().recipeRosterRevision());
            while (preflight.cursor < host.slots().size()) {
                if (!budget.take()) return yielded();
                var captured = host.slots().get(preflight.cursor++).process();
                if (captured == null) continue;
                captured.requirePersistenceBoundary();
                if (captured.host() != host)
                    throw new IllegalStateException("Work slot lost its process ownership");
                preflight.running.add(captured.recipe().id());
                preflight.active++;
            }
            if (preflight.capacity != null && !preflight.capacity.current()) preflight.capacity = null;
            if (!host.dynamicRoster() && preflight.capacity == null)
                preflight.capacity = host.owner().attributes().beginValueCapture(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            if (!host.dynamicRoster() && !Objects.requireNonNull(preflight.capacity).advance(budget)) {
                if (!preflight.capacity.current()) preflight.capacity = null;
                return yielded();
            }
            int capacity = host.dynamicRoster() ? Integer.MAX_VALUE
                : Math.clamp((long) Objects.requireNonNull(preflight.capacity).result(), 0, host.slots().size());
            if (preflight.rosterRevision != host.owner().recipeRosterRevision()) {
                preflight = new Preflight(host.owner().recipeRosterRevision());
                return yielded();
            }
            if (preflight.active >= capacity) return finish(completed(Status.FULL));
            if (mode == ControllerMode.MULTITHREAD && !threadChosen) {
                while (logicalVisited < host.slots().size()) {
                    selectedThreadIndex = Math.floorMod(nextThreadIndex, host.slots().size());
                    var thread = host.slots().get(selectedThreadIndex);
                    // This exact roster's occupancy was already visited by the retained preflight.
                    if (thread.process() != null) {
                        nextThreadIndex = (selectedThreadIndex + 1) % host.slots().size();
                        logicalVisited++;
                        continue;
                    }
                    var savedState = threadStates.get(thread.name());
                    var progress = thread.name().equals(selectedThread) ? pureProgress
                        : savedState == null ? null : savedState.progress;
                    boolean observedVacant = progress != null && progress.rosterRevision == host.owner().recipeRosterRevision();
                    if (!observedVacant && !budget.take()) return yielded();
                    selectThread(thread.name());
                    threadChosen = true;
                    break;
                }
                if (!threadChosen) return finish(completed(Status.EXHAUSTED));
            }
            phase = 1;
            restorePureProgress();
            return null;
        }

        private MachineInstance hostOwner() {
            return host.owner();
        }

        private void restorePureProgress() {
            if (pureProgress == null) return;
            var progress = pureProgress;
            pureProgress = null;
            var saved = progress.saved;
            phase = saved.phase;
            recipe = saved.recipe;
            slot = saved.slot;
            checkingSlot = saved.checkingSlot;
            slotCursor = saved.slotCursor;
            slotGroups = saved.slotGroups;
            parentLimit = saved.parentLimit;
            parentValue = saved.parentValue;
            sharedCapture = saved.sharedCapture;
            basis = saved.basis;
            bound = saved.bound;
            boundCursor = saved.boundCursor;
            amount = saved.amount;
            finalBest = saved.finalBest;
            creation = saved.creation;
            process = saved.process;
            probe = saved.probe;
            creationProof = saved.creationProof;
            exhaustionValidation = saved.exhaustionValidation;
            exhaustionChecked = saved.exhaustionChecked;
            if (saved.phase >= 4 && search == null) {
                discardPureCandidate();
                phase = 2;
            }
            if (creationProof != null && !creationProof.current()) {
                discardPureCandidate();
                search = null;
                phase = 2;
            }
            if (phase >= 2 && (progress.rosterRevision != host.owner().recipeRosterRevision() || !progress.family.current())) {
                slot = null;
                checkingSlot = null;
                slotGroups = null;
                slotCursor = mode == ControllerMode.MULTITHREAD ? selectedThreadIndex : 0;
                parentValue = null;
                sharedCapture = null;
                basis = null;
                phase = 2;
            }
        }

        private void discardPureCandidate() {
            recipe = null;
            slot = null;
            checkingSlot = null;
            slotGroups = null;
            parentValue = null;
            sharedCapture = null;
            basis = null;
            creation = null;
            creationProof = null;
            process = null;
            probe = null;
            clearExhaustionProof();
        }

        private void clearExhaustionProof() {
            exhaustionValidation = null;
            exhaustionChecked = false;
        }

        private void nextCandidate() {
            next();
            recipe = null;
            process = null;
            slot = null;
            sharedCapture = null;
            basis = null;
            exhaustionValidation = null;
            exhaustionChecked = false;
            parentValue = null;
            phase = 2;
        }

        private boolean nextLogicalThread() {
            logicalVisited++;
            nextThreadIndex = (selectedThreadIndex + 1) % host.slots().size();
            threadChosen = false;
            recipe = null;
            process = null;
            slot = null;
            checkingSlot = null;
            slotGroups = null;
            sharedCapture = null;
            basis = null;
            phase = 0;
            discoveryValidation = null;
            discoveryValidated = false;
            clearExhaustionProof();
            return logicalVisited < host.slots().size();
        }

        private Result completed(Status status) {
            return new Result(lastAdmitted == null || status == Status.PAUSED ? status : Status.ADMITTED, lastAdmitted, examined, probes);
        }

        private Result yielded() {
            return result(Status.YIELDED, examined, probes);
        }

        private Result finish(Result result) {
            if (result.status() == Status.YIELDED && phase >= 1 && admission == null && pureProbe())
                pureProgress = new PureProgress(this);
            if (result.status() == Status.YIELDED && mode == ControllerMode.MULTITHREAD && threadChosen)
                nextThreadIndex = (selectedThreadIndex + 1) % host.slots().size();
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
            if (hasObligations())
                throw new IllegalStateException("Probe cleanup must finish before machine successors");
            finish(yielded());
        }

        public boolean hasObligations() {
            requireOwner();
            return !finished && (admission != null || !pureProbe());
        }

        private boolean pureProbe() {
            return probe == null || !probe.hasObligations() && probe.computation() == null
                && !probe.resolvingSources() && probe.sources().isEmpty();
        }

        public boolean finished() {
            requireOwner();
            return finished;
        }

        public void cancel() {
            requireOwner();
            stopping = true;
            if (probe != null) probe.cancel();
        }

        public @Nullable Throwable failure() {
            requireOwner();
            return failure;
        }

        public @Nullable RecipePureComputation computation() {
            requireOwner();
            return probe == null ? null : probe.computation();
        }

        public void finishComputation(RecipePureComputation expected) {
            requireOwner();
            if (probe == null) throw new IllegalArgumentException("No scan computation");
            probe.finishComputation(expected);
        }

        public List<ResourceBatch.Source> sources() {
            requireOwner();
            return probe == null ? List.of() : probe.sources();
        }

        public boolean resolvingSources() {
            requireOwner();
            return probe != null && probe.resolvingSources();
        }

        public void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            requireOwner();
            if (probe == null) throw new IllegalArgumentException("No scan source action");
            probe.finishSourceResolution(expected, resolved);
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner || executing || !finished && activeAttempt != this)
                throw new IllegalStateException("Scan requires its idle captured owner");
        }
    }
}
