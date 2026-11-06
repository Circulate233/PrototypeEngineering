package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlot;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
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

/**
 * Ordered, group-scoped PM recipe admission with bounded cooperative search. The scanner belongs to
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

    /** Safe default for arbitrary wrappers, probability, selectors and external attribute modifiers. */
    public RecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
        this(host, catalog, RecipeProcess::new, List.of(new FixedIoScanConstraint()), List.of(), _ -> false, seeds);
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
        for (var slot : host.slots()) { scope.addAll(slot.allowedRecipeGroups()); }
        groups = ObjectSets.unmodifiable(scope);
    }

    public MachineWorkSlots host() { return host; }

    /** At most one new process per call. Call at most once per scheduled machine tick. */
    public Result scan(ScanLimits limits) {
        Objects.requireNonNull(limits);
        if (scanning || faulted) { throw new IllegalStateException("Recipe scanner is executing or faulted"); }
        host.snapshot();
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

    private Result advance(ScanLimits limits) {
        var running = new ObjectOpenHashSet<ResourceLocation>();
        var active = host.activeProcesses();
        for (var process : active) { running.add(process.recipe().id()); }
        if (active.size() >= host.capacity()) { return result(Status.FULL, 0, 0); }
        if (groups.isEmpty()) { return result(Status.NO_GROUPS, 0, 0); }
        if (candidates.isEmpty()) { candidates = catalog.candidates(host.owner(), groups, indexes); }
        int examined = 0;
        int probes = 0;
        while (cursor < candidates.size() && examined < limits.candidates()) {
            var recipe = candidates.get(cursor);
            examined++;
            var slot = eligibleSlot(recipe);
            if (slot == null || running.contains(recipe.id())) { next(); continue; }
            int parentLimit = Math.max(1, (int) Math.floor(host.owner().attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1)));
            if (search == null || search.parentLimit != parentLimit) { search = begin(recipe, parentLimit); }
            while (search.low <= search.high && probes < limits.probes()) {
                int amount = search.monotonic ? (int) (search.low + (search.high - search.low) / 2) : (int) search.high;
                var process = candidate(recipe, amount, search);
                probes++;
                boolean accepted = canStart(process);
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
                probes++;
                if (canStart(process)) { return admit(slot, process, examined, probes); }
                // A previous success is not a lease. Re-observe on a later call if live storage changed.
                search = null;
                return result(Status.YIELDED, examined, probes);
            }
            next();
            if (probes >= limits.probes() && cursor < candidates.size()) { return result(Status.YIELDED, examined, probes); }
        }
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
            var result = Objects.requireNonNull(constraint.test(process));
            if (result != ProcessResult.SUCCESS) { return result; }
        }
        return RecipeStageExecutor.probeStart(process);
    }

    private Result admit(WorkSlot slot, RecipeProcess process, int examined, int probes) {
        if (!host.startIn(slot.name(), process)) {
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
            if (slot.process() == null && recipe.recipeGroups().stream().anyMatch(slot.allowedRecipeGroups()::contains)) { return slot; }
        }
        return null;
    }

    private void next() { cursor++; search = null; }
    private void resetPass() { candidates = List.of(); cursor = 0; search = null; }
    private static Result result(Status status, int examined, int probes) { return new Result(status, null, examined, probes); }

    private static <T> List<T> immutable(List<T> values) {
        var result = new ObjectArrayList<T>();
        for (var value : values) { result.add(Objects.requireNonNull(value)); }
        return ObjectLists.unmodifiable(result);
    }

    public enum Status { ADMITTED, YIELDED, EXHAUSTED, FULL, NO_GROUPS }

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
