package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeExecutor;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.machine.WorkSlotProcessingSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * PM's ordered work-slot host. One execution owner controls admission, tick, transfer and persistence;
 * these methods are not synchronization primitives for workers. Scheduler joins must transfer ownership
 * before mutating the host. Concurrent-process attributes limit admission, never evict existing work.
 */
public final class MachineWorkSlots {
    public static final ComponentType<MachineInstance, MachineWorkSlots> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "work_slots"), MachineWorkSlots::fromDeclaration,
        List.of(new WorkSlotProcessingSystem()));
    private final MachineInstance owner;
    private final List<WorkSlot> slots;
    private final ObjectArrayList<WorkSlot> slotStorage;
    private final boolean dynamicRoster;
    private final int declaredSlotCount;
    private final Set<ResourceLocation> dynamicGroups;
    private final Object2ObjectLinkedOpenHashMap<String, WorkSlot> byName = new Object2ObjectLinkedOpenHashMap<>();
    private final RecipeExecutor executor;
    private long nextRuntimeSlot;
    private boolean executing;
    private boolean faulted;
    @Nullable
    private TickBatch activeBatch;
    @Nullable
    private Admission activeAdmission;

    public MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions) {
        this(owner, definitions, Set.of());
    }

    public MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions, Set<ResourceLocation> defaultGroups) {
        this(owner, definitions, defaultGroups, RecipeExecutor.DEFAULT);
    }

    /**
     * An empty layout uses PM's implicit main slot; empty machine-level groups add no group filter.
     */
    public MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions, Set<ResourceLocation> defaultGroups,
                            RecipeExecutor executor) {
        this(owner, definitions, defaultGroups, executor, false);
    }

    private MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions, Set<ResourceLocation> defaultGroups,
                             RecipeExecutor executor, boolean dynamicRoster) {
        this.owner = Objects.requireNonNull(owner);
        this.executor = Objects.requireNonNull(executor);
        Objects.requireNonNull(defaultGroups);
        this.dynamicRoster = dynamicRoster;
        dynamicGroups = ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(defaultGroups));
        var declared = !dynamicRoster && definitions.isEmpty() ? List.of(new WorkSlotDefinition("main", defaultGroups)) : definitions;
        declaredSlotCount = declared.size();
        var workSlots = new ObjectArrayList<WorkSlot>();
        for (var definition : declared) {
            var slot = new WorkSlot(Objects.requireNonNull(definition));
            if (dynamicRoster && slot.name().startsWith("pe_cross/"))
                throw new IllegalArgumentException("Declared slot uses the runtime slot namespace");
            if (byName.putIfAbsent(slot.name(), slot) != null) {
                throw new IllegalArgumentException("Duplicate work slot: " + slot.name());
            }
            workSlots.add(slot);
        }
        slotStorage = workSlots;
        slots = ObjectLists.unmodifiable(slotStorage);
        owner.registerRecipeHost(this);
    }

    /** Explicit cross-recipe binding; legacy public constructors keep their complete fixed layout. */
    public static MachineWorkSlots crossRecipe(MachineInstance owner, List<WorkSlotDefinition> definitions, Set<ResourceLocation> groups) {
        return new MachineWorkSlots(owner, definitions, groups, RecipeExecutor.DEFAULT, true);
    }

    private static MachineWorkSlots fromDeclaration(MachineInstance owner) {
        var type = owner.type();
        return type == null ? owner.createRecipeHost(List.of(), Set.of())
            : owner.createRecipeHost(type.workSlots(), type.recipeGroups());
    }

    /**
     * Captures every host before installing any lease or running any process callback.
     */
    public static TickGroup prepareTickGroup(List<MachineWorkSlots> hosts) {
        var ordered = new ObjectArrayList<>(Objects.requireNonNull(hosts));
        var unique = new ReferenceOpenHashSet<MachineWorkSlots>();
        var batches = new ObjectArrayList<TickBatch>();
        for (var host : ordered) {
            Objects.requireNonNull(host);
            if (!unique.add(host)) {
                throw new IllegalArgumentException("Duplicate work-slot host in tick group");
            }
            batches.add(host.captureTickBatch());
        }
        var group = new TickGroup(batches);
        for (var batch : batches) {
            batch.group = group;
            batch.host.activeBatch = batch;
        }
        return group;
    }

    private static boolean accepts(WorkSlot slot, RecipeProcess process) {
        return slot.definition().accepts(process.recipe());
    }

    public boolean dynamicRoster() {
        return dynamicRoster;
    }

    public Set<ResourceLocation> dynamicRecipeGroups() {
        return dynamicGroups;
    }

    public long nextRuntimeSlot() {
        return nextRuntimeSlot;
    }

    /** Allocate at most one vacant process slot during an admission search, never a JVM thread. */
    @Nullable
    public WorkSlot dynamicScanSlot(MachineRecipe recipe) {
        requireBoundary();
        if (!dynamicRoster) return null;
        var rule = new WorkSlotDefinition("runtime", dynamicGroups, false, WorkSlotRestartPolicy.LOCKED_RETRY);
        if (!rule.accepts(recipe)) return null;
        for (var slot : slots) if (slot.process() == null && slot.definition().accepts(recipe)) return slot;
        return appendRuntimeSlot(recipe);
    }

    /** Called after the scanner has budgeted its complete vacancy search. No roster traversal. */
    @Nullable
    public WorkSlot appendRuntimeSlot(MachineRecipe recipe) {
        requireBoundary();
        if (!dynamicRoster || !new WorkSlotDefinition("runtime", dynamicGroups, false, WorkSlotRestartPolicy.LOCKED_RETRY).accepts(recipe))
            return null;
        String name = "pe_cross/" + nextRuntimeSlot;
        long next = Math.incrementExact(nextRuntimeSlot);
        var slot = new WorkSlot(new WorkSlotDefinition(name, dynamicGroups, false, WorkSlotRestartPolicy.LOCKED_RETRY));
        owner.recordRecipeHostLayoutChange(this);
        nextRuntimeSlot = next;
        slotStorage.add(slot);
        byName.put(name, slot);
        return slot;
    }

    public boolean acceptsRestoredDefinition(int index, WorkSlotDefinition definition) {
        if (index < declaredSlotCount) return index < slots.size() && slots.get(index).definition().equals(definition);
        if (!dynamicRoster || !definition.name().startsWith("pe_cross/") || definition.isCore()
            || definition.restartPolicy() != WorkSlotRestartPolicy.LOCKED_RETRY || !definition.allowedRecipeGroups().equals(dynamicGroups))
            return false;
        var suffix = definition.name().substring("pe_cross/".length());
        if (suffix.isEmpty() || suffix.length() > 19) return false;
        long value = 0;
        for (int i = 0; i < suffix.length(); i++) {
            char digit = suffix.charAt(i);
            if (digit < '0' || digit > '9' || value > (Long.MAX_VALUE - (digit - '0')) / 10L) return false;
            value = value * 10L + digit - '0';
        }
        return definition.name().equals("pe_cross/" + value);
    }

    public void restoreRuntimeCounter(long next) {
        requireBoundary();
        if (!dynamicRoster || next < 0) throw new IllegalArgumentException("Invalid runtime slot counter");
        for (int i = declaredSlotCount; i < slots.size(); i++) {
            var name = slots.get(i).name();
            if (!acceptsRestoredDefinition(i, slots.get(i).definition()))
                throw new IllegalArgumentException("Invalid runtime slot");
            long id = 0;
            for (int c = "pe_cross/".length(); c < name.length(); c++) id = id * 10 + name.charAt(c) - '0';
            if (id >= next) throw new IllegalArgumentException("Runtime slot counter would reuse an identity");
        }
        nextRuntimeSlot = Math.max(nextRuntimeSlot, next);
    }

    /** No execution permission is granted; a later admission always rechecks the actual machine. */
    public SharedUsage sharedUsage(MachineRecipe recipe) {
        return Objects.requireNonNull(beginSharedUsage(recipe, null).run());
    }

    public UsageCapture beginSharedUsage(MachineRecipe recipe) {
        return beginSharedUsage(recipe, null);
    }

    private UsageCapture beginSharedUsage(MachineRecipe recipe, @Nullable Admission ignored) {
        Objects.requireNonNull(recipe);
        var hosts = owner.recipeHosts();
        return new UsageCapture() {
            private final long rosterRevision = owner.recipeRosterRevision();
            private final MachineAttributes.FamilyCapture family = owner.attributes().familyCapture();
            private final MachineAttributes.ValueCapture proof = owner.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            @Nullable
            private SharedUsage result;
            private int hostCursor;
            private int slotCursor;
            private long used;
            private int threads;
            private boolean pendingCounted;
            @Nullable
            private MachineAttributes.ValueCapture amount;
            @Nullable
            private RecipeProcess counting;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (!current()) return false;
                if (result != null) return true;
                while (hostCursor < hosts.size()) {
                    var host = hosts.get(hostCursor);
                    while (slotCursor < host.slots.size()) {
                        if (counting == null) {
                            if (!work.take()) return false;
                            counting = host.slots.get(slotCursor).process();
                            if (counting == null) {
                                slotCursor++;
                                continue;
                            }
                            amount = counting.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
                        }
                        if (!Objects.requireNonNull(amount).advance(work)) return false;
                        count(counting, Math.max(1, (int) Math.floor(amount.result())));
                        counting = null;
                        amount = null;
                        slotCursor++;
                    }
                    if (!pendingCounted) {
                        if (!work.take()) return false;
                        var pending = host.activeAdmission;
                        if (pending != null && pending != ignored && pending.reserved && pending.process.host() == null)
                            count(pending.process, RecipeParallelism.effective(pending.process));
                        pendingCounted = true;
                    }
                    hostCursor++;
                    slotCursor = 0;
                    pendingCounted = false;
                }
                if (!proof.advance(work)) return false;
                if (!work.take()) return false;
                result = new SharedUsage(used, Math.max(0L, (long) Math.floor(proof.result())), threads);
                return true;
            }

            @Override
            public SharedUsage result() {
                if (!current()) throw new IllegalStateException("Shared usage capture changed");
                return Objects.requireNonNull(result, "Shared usage capture is incomplete");
            }

            @Override
            public boolean current() {
                return rosterRevision == owner.recipeRosterRevision() && family.current() && proof.current();
            }

            private void count(RecipeProcess process, int parallelism) {
                if (process.owner() != owner) throw new IllegalStateException("Shared recipe host lost its machine");
                used = Math.addExact(used, parallelism);
                if (process.recipe().id().equals(recipe.id())) threads = Math.incrementExact(threads);
            }
        };
    }

    public MachineInstance owner() {
        return owner;
    }

    public List<WorkSlot> slots() {
        return slots;
    }

    public WorkSlot requireSlot(String name) {
        var slot = byName.get(Objects.requireNonNull(name));
        if (slot == null) {
            throw new IllegalArgumentException("Unknown work slot: " + name);
        }
        return slot;
    }

    /**
     * Live admission limit; lowering it leaves every existing process attached and runnable.
     */
    public int capacity() {
        return dynamicRoster ? Integer.MAX_VALUE
            : Math.clamp((long) owner.attributes().value(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1), 0, slots.size());
    }

    public List<RecipeProcess> activeProcesses() {
        var processes = new ObjectArrayList<RecipeProcess>();
        for (var slot : slots) {
            if (slot.process() != null) {
                processes.add(slot.process());
            }
        }
        return ObjectLists.unmodifiable(processes);
    }

    public boolean start(RecipeProcess process) {
        requireBoundary();
        validateUnattached(process);
        for (var slot : slots) {
            if (slot.process() == null && accepts(slot, process)) {
                return startIn(slot.name(), process);
            }
        }
        return false;
    }

    public boolean startIn(String name, RecipeProcess process) {
        requireBoundary();
        validateUnattached(process);
        var slot = requireSlot(name);
        if (workPaused() || slot.process() != null || activeCount() >= capacity() || !accepts(slot, process)
            || owner.recipeThreadCount(process.recipe().id()) >= process.recipe().maxConcurrentThreads()) {
            return false;
        }
        if (workPaused()) {
            return false;
        }
        process.attachTo(this);
        slot.setState(process, process.state().result());
        return true;
    }

    /**
     * The scanner's frozen pool scope supplements slot rules for this new admission only.
     */
    public boolean startIn(String name, RecipeProcess process, RecipeCatalog.Selection selection) {
        requireBoundary();
        validateUnattached(process);
        return Objects.requireNonNull(selection).accepts(owner, process.recipe()) && startIn(name, process);
    }

    public boolean startIn(String name, RecipeProcess process, RecipeCatalog.Selection selection, SharedUsage basis) {
        var current = sharedUsage(process.recipe());
        return current.occupied() == basis.occupied() && current.limit() == basis.limit()
            && startIn(name, process, selection);
    }

    @SuppressWarnings("ReturnOfInnerClass") // The retained admission cursor belongs to this exact work-slot host.
    public Admission beginAdmission(String name, RecipeProcess process, RecipeCatalog.Selection selection) {
        Objects.requireNonNull(selection);
        return createAdmission(name, process, selection);
    }

    @SuppressWarnings("ReturnOfInnerClass") // Carries the scanner's shared allowance into the exact host admission.
    public Admission beginAdmission(String name, RecipeProcess process, RecipeCatalog.Selection selection, SharedUsage basis) {
        var admission = beginAdmission(name, process, selection);
        admission.basis = Objects.requireNonNull(basis);
        return admission;
    }

    private Admission createAdmission(String name, RecipeProcess process, @Nullable RecipeCatalog.Selection selection) {
        requireBoundary();
        validateUnattached(process);
        var admission = new Admission(requireSlot(name), process);
        admission.selection = selection;
        activeAdmission = admission;
        return admission;
    }

    /**
     * Original admission rules with a retained local host gate while active-count/group visits yield.
     * No storage, lifecycle or scheduler authority is acquired; the caller already owns the machine.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public Admission beginAdmission(String name, RecipeProcess process) {
        return createAdmission(name, process, null);
    }

    /**
     * Transfers a quiescent process out of this host without executing END or refunding committed IO.
     * The caller owns its subsequent lifetime. Cancellation and planned compensation are separate.
     */
    public boolean detach(RecipeProcess process) {
        requireBoundary();
        Objects.requireNonNull(process);
        for (var slot : slots) {
            if (slot.process() == process) {
                process.requirePersistenceBoundary();
                process.detachFrom(this);
                slot.setState(null, process.state().result());
                return true;
            }
        }
        return false;
    }

    public boolean cancel(RecipeProcess process) {
        return cancel(process, RecipeText.Cancelled.failure());
    }

    /**
     * Cancels owned work and releases its slot only after cleanup succeeds. This is not detach or END.
     * A faulted host may still clean its other quiescent processes, but is never reactivated by cleanup.
     */
    public boolean cancel(RecipeProcess process, ProcessResult.Failure reason) {
        requireCancellationBoundary();
        Objects.requireNonNull(process);
        Objects.requireNonNull(reason);
        for (var slot : slots) {
            if (slot.process() != process) {
                continue;
            }
            process.requirePersistenceBoundary();
            if (process.host() != this) {
                throw new IllegalStateException("Work slot lost its cancellation ownership");
            }
            executing = true;
            try {
                return cancelSlot(slot, process, reason);
            } catch (RuntimeException | Error failure) {
                faulted = true;
                throw failure;
            } finally {
                executing = false;
            }
        }
        return false;
    }

    public int cancelAll() {
        return cancelAll(RecipeText.Cancelled.failure());
    }

    /**
     * Attempts every occupied slot in reverse order; failures retain their process for diagnostics.
     */
    public int cancelAll(ProcessResult.Failure reason) {
        requireCancellationBoundary();
        Objects.requireNonNull(reason);
        executing = true;
        Throwable failure = null;
        int cancelled = 0;
        try {
            for (int i = slots.size() - 1; i >= 0; i--) {
                var slot = slots.get(i);
                var process = slot.process();
                if (process == null) {
                    continue;
                }
                try {
                    if (cancelSlot(slot, process, reason)) {
                        cancelled++;
                    }
                } catch (RuntimeException | Error cleanup) {
                    faulted = true;
                    if (failure == null) {
                        failure = cleanup;
                    } else if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return cancelled;
        } finally {
            executing = false;
        }
    }

    private boolean cancelSlot(WorkSlot slot, RecipeProcess process, ProcessResult.Failure reason) {
        if (process.host() != this) {
            throw new IllegalStateException("Work slot lost its cancellation ownership");
        }
        boolean cancelled = process.cancelFrom(this, reason);
        process.requirePersistenceBoundary();
        process.detachFrom(this);
        slot.setState(null, process.state().result());
        return cancelled;
    }

    /**
     * One synchronous executor invocation per occupied slot; expected failures do not stop other slots.
     */
    public void tickProcesses() {
        requireBoundary();
        executing = true;
        try {
            for (var slot : slots) {
                if (workPaused()) {
                    break;
                }
                var process = slot.process();
                if (process == null) {
                    continue;
                }
                if (process.host() != this) {
                    throw new IllegalStateException("Work slot lost its process ownership");
                }
                var before = process.state().lifecycle();
                if (!before.isTerminal()) {
                    executor.tick(process);
                }
                publishSlot(slot, process);
            }
        } catch (RuntimeException | Error failure) {
            faulted = true;
            throw failure;
        } finally {
            executing = false;
        }
    }

    private void publishSlot(WorkSlot slot, RecipeProcess process) {
        process.requirePersistenceBoundary();
        if (slot.process() != process || process.host() != this) {
            throw new IllegalStateException("Work slot lost its process ownership");
        }
        var state = process.state();
        if (state.lifecycle().isTerminal()) {
            if (state.lifecycle() == RecipeProcess.Lifecycle.COMPLETED) slot.lastCompletedRecipe(process.recipe().id());
            process.detachFrom(this);
            slot.setState(null, state.result());
        } else {
            slot.setState(process, state.result());
        }
    }

    /**
     * Captures the real occupied slots without running callbacks. The host is leased immediately;
     * call begin, settle each current round, then tryFinish. Custom synchronous executors retain their
     * own contract and cannot be silently replaced by the built-in staged process lifecycle.
     */
    public TickBatch prepareTickBatch() {
        var batch = captureTickBatch();
        activeBatch = batch;
        return batch;
    }

    private TickBatch captureTickBatch() {
        requireBoundary();
        if (executor != RecipeExecutor.DEFAULT) {
            throw new IllegalStateException("Custom synchronous recipe executor does not provide staged execution");
        }
        var entries = new ObjectArrayList<TickBatch.Entry>();
        for (var slot : slots) {
            var process = slot.process();
            if (process == null) {
                continue;
            }
            process.requirePersistenceBoundary();
            if (process.host() != this) {
                throw new IllegalStateException("Work slot lost its process ownership");
            }
            entries.add(new TickBatch.Entry(slot, process));
        }
        return new TickBatch(this, entries);
    }

    /**
     * Recovery access when a batch callback threw; the same batch retains every cleanup obligation.
     */
    @Nullable
    public TickBatch pendingTickBatch() {
        return activeBatch;
    }

    /**
     * Process boundary hook: only this batch's exact owner operation can enter a captured process.
     */
    public void requireProcessBoundary(RecipeProcess process) {
        if (activeAdmission != null && (activeAdmission.thread != Thread.currentThread() || activeAdmission.permitted != process)) {
            throw new IllegalStateException("Process overlaps a captured admission");
        }
        if (activeBatch != null && (activeBatch.permitted != process || activeBatch.ownerThread != Thread.currentThread())) {
            throw new IllegalStateException("Process is owned by a work-slot tick batch");
        }
    }

    /**
     * Session/stage hook: a callback may not enter another process in the same captured batch.
     */
    public void requireProcessOperation(RecipeProcess process) {
        if (activeAdmission != null && (activeAdmission.thread != Thread.currentThread() || activeAdmission.permitted != process)) {
            throw new IllegalStateException("Recipe operation overlaps a captured admission");
        }
        if (activeBatch != null && activeBatch.group != null) {
            activeBatch.group.requireProcessOperation(process);
        }
        if (activeBatch != null && (activeBatch.ownerThread != Thread.currentThread()
            || executing && activeBatch.permitted != process)) {
            throw new IllegalStateException("Recipe operation overlaps a different work-slot callback");
        }
    }

    /**
     * Holds the batch callback scope across requirements, source notifications and nested cleanup.
     */
    public <T> T withProcessCallback(RecipeProcess process, Supplier<T> action) {
        Objects.requireNonNull(action);
        requireProcessOperation(process);
        var batch = activeBatch;
        if (batch == null) {
            return action.get();
        }
        boolean previousExecution = executing;
        var previousProcess = batch.permitted;
        var group = batch.group;
        var previousGroupProcess = group == null ? null : group.callbackProcess;
        executing = true;
        batch.permitted = process;
        if (group != null) {
            group.callbackProcess = process;
        }
        try {
            return action.get();
        } finally {
            if (group != null) {
                group.callbackProcess = previousGroupProcess;
            }
            batch.permitted = previousProcess;
            executing = previousExecution;
        }
    }

    private boolean workPaused() {
        var lifecycle = owner.lifecycle();
        return lifecycle != null && lifecycle.retired() || !owner.recipeWorkAllowed();
    }

    public List<SlotSnapshot> snapshot() {
        requireBoundary();
        var snapshot = new ObjectArrayList<SlotSnapshot>();
        for (var slot : slots) {
            var process = slot.process();
            if (process != null) {
                process.requirePersistenceBoundary();
                if (process.host() != this) {
                    throw new IllegalStateException("Work slot lost its process ownership");
                }
            }
            snapshot.add(new SlotSnapshot(slot.definition(), process, slot.result()));
        }
        return ObjectLists.unmodifiable(snapshot);
    }

    /**
     * World cleanup can observe faulted slots, but cannot detach a live transaction or execution.
     */
    public void requireQuiescent() {
        if (!quiescent()) {
            throw new IllegalStateException("Work slots retain execution or unsettled processes");
        }
    }

    /**
     * Owner-boundary query: pending execution or cleanup returns false without faulting the host.
     * This does not acquire ownership, release a captured batch, or grant persistence permission.
     */
    public boolean quiescent() {
        if (executing || activeBatch != null || activeAdmission != null) {
            return false;
        }
        boolean quiescent = true;
        for (var slot : slots) {
            var process = slot.process();
            if (process == null) {
                continue;
            }
            if (process.host() != this) {
                throw new IllegalStateException("Work slot lost its process ownership");
            }
            quiescent &= process.quiescent();
        }
        return quiescent;
    }

    /**
     * Validates the entire ordered layout before replacing any slot. New processes must be unattached
     * or already owned here; all old processes must be quiescent. No executor or world callback runs.
     * Current admission capacity is deliberately not a restore limit for already committed work.
     */
    public void restore(List<SlotSnapshot> snapshot) {
        requireBoundary();
        var previous = snapshot();
        var replacement = new ObjectArrayList<>(snapshot);
        if (!dynamicRoster && replacement.size() != slots.size() || dynamicRoster && replacement.size() < declaredSlotCount) {
            throw new IllegalArgumentException("Work slot layout size changed");
        }
        var processes = new ReferenceOpenHashSet<RecipeProcess>();
        var previousProcesses = new ReferenceOpenHashSet<RecipeProcess>();
        var names = new ObjectOpenHashSet<String>();
        long restoredNext = nextRuntimeSlot;
        for (var old : previous) {
            if (old.process() != null) {
                previousProcesses.add(old.process());
            }
        }
        for (int i = 0; i < replacement.size(); i++) {
            var saved = Objects.requireNonNull(replacement.get(i));
            if (!names.add(saved.definition().name()))
                throw new IllegalArgumentException("Duplicate restored slot name");
            var slot = dynamicRoster ? new WorkSlot(saved.definition()) : slots.get(i);
            if (!acceptsRestoredDefinition(i, saved.definition())) {
                throw new IllegalArgumentException("Work slot layout changed: " + saved.definition().name());
            }
            if (dynamicRoster && i >= declaredSlotCount) {
                long id = 0;
                var name = saved.definition().name();
                for (int c = "pe_cross/".length(); c < name.length(); c++) id = id * 10 + name.charAt(c) - '0';
                restoredNext = Math.max(restoredNext, Math.incrementExact(id));
            }
            var process = saved.process();
            if (process == null) {
                continue;
            }
            validateProcess(process);
            if (!processes.add(process) || !accepts(slot, process) || !saved.result().equals(process.state().result())
                || process.host() != null && (process.host() != this || !previousProcesses.contains(process))) {
                throw new IllegalArgumentException("Duplicate, incompatible or foreign process in restored work slots");
            }
        }
        // Every operation after validation is local assignment under the same exclusive owner.
        owner.recordRecipeHostLayoutChange(this);
        for (var old : previousProcesses) {
            old.detachFrom(this);
        }
        if (dynamicRoster) {
            slotStorage.clear();
            byName.clear();
            for (var saved : replacement) {
                var slot = new WorkSlot(saved.definition());
                slotStorage.add(slot);
                byName.put(slot.name(), slot);
            }
        }
        for (int i = 0; i < replacement.size(); i++) {
            var saved = replacement.get(i);
            if (saved.process() != null) {
                saved.process().attachTo(this);
            }
            slots.get(i).lastCompletedRecipe(null);
            slots.get(i).setState(saved.process(), saved.result());
        }
        nextRuntimeSlot = restoredNext;
    }

    /** Dynamic roster and its monotonic identity counter are validated before replacing any live slot. */
    public void restore(List<SlotSnapshot> snapshot, long nextRuntimeSlot) {
        requireBoundary();
        if (!dynamicRoster || nextRuntimeSlot < 0) throw new IllegalArgumentException("Invalid runtime roster save");
        for (int i = declaredSlotCount; i < snapshot.size(); i++) {
            var definition = snapshot.get(i).definition();
            if (!acceptsRestoredDefinition(i, definition)) throw new IllegalArgumentException("Invalid runtime slot");
            long id = 0;
            var name = definition.name();
            for (int c = "pe_cross/".length(); c < name.length(); c++) id = id * 10 + name.charAt(c) - '0';
            if (id >= nextRuntimeSlot)
                throw new IllegalArgumentException("Runtime slot counter would reuse an identity");
        }
        restore(snapshot);
        this.nextRuntimeSlot = Math.max(this.nextRuntimeSlot, nextRuntimeSlot);
    }

    private int activeCount() {
        int count = 0;
        for (var slot : slots) {
            if (slot.process() != null) {
                count++;
            }
        }
        return count;
    }

    private void validateUnattached(RecipeProcess process) {
        validateProcess(process);
        if (process.host() != null) {
            throw new IllegalArgumentException("Process is already hosted");
        }
        if (process.state().lifecycle().isTerminal()) {
            throw new IllegalArgumentException("Cannot admit a terminal process as new work");
        }
    }

    private void validateProcess(RecipeProcess process) {
        Objects.requireNonNull(process).requirePersistenceBoundary();
        if (process.owner() != owner) {
            throw new IllegalArgumentException("Process belongs to a different machine");
        }
    }

    private void requireBoundary() {
        if (executing || activeBatch != null || activeAdmission != null || faulted) {
            throw new IllegalStateException("Work slots are executing or faulted");
        }
    }

    private void requireCancellationBoundary() {
        if (executing || activeBatch != null || activeAdmission != null) {
            throw new IllegalStateException("Work slots are executing");
        }
    }

    public interface UsageCapture {
        boolean advance(RecipeWorkBudget work);

        SharedUsage result();

        boolean current();

        default SharedUsage run() {
            if (!advance(new RecipeWorkBudget(Long.MAX_VALUE)))
                throw new IllegalStateException("Shared usage capture is incomplete");
            return result();
        }
    }

    public record SharedUsage(long occupied, long limit, int recipeThreads) {
        public long available() {
            return Math.max(0L, limit - occupied);
        }
    }

    /**
     * One owner-thread set of host leases and rounds, retained until every host really finishes.
     */
    public static final class TickGroup {
        private final Thread ownerThread = Thread.currentThread();
        private final List<TickBatch> batches;
        private final ReferenceOpenHashSet<RecipeProcess> processes = new ReferenceOpenHashSet<>();
        @Nullable
        private RecipeProcess callbackProcess;
        @Nullable
        private TickBatch controlledBatch;
        private boolean executing;
        private boolean begun;
        private boolean stopped;
        private boolean finished;

        private TickGroup(List<TickBatch> batches) {
            this.batches = ObjectLists.unmodifiable(new ObjectArrayList<>(batches));
            for (var batch : batches) {
                for (var entry : batch.entries) {
                    processes.add(entry.process);
                }
            }
        }

        public void begin() {
            requireOwner();
            if (begun || stopped) {
                throw new IllegalStateException("Work-slot group cannot begin again");
            }
            begun = true;
            executing = true;
            try {
                for (var batch : batches) {
                    controlledBatch = batch;
                    batch.begin();
                }
            } catch (RuntimeException | Error failure) {
                recordFailureInternal(failure);
                throw failure;
            } finally {
                controlledBatch = null;
                executing = false;
            }
        }

        public List<RecipeProcess.Stage> nextRound() {
            requireOwner();
            if (!begun) {
                throw new IllegalStateException("Work-slot group has not begun");
            }
            if (!collectCurrentStages().isEmpty()) {
                throw new IllegalStateException("Previous recipe group round is unsettled");
            }
            if (stopped) {
                return List.of();
            }
            executing = true;
            var stages = new ObjectArrayList<RecipeProcess.Stage>();
            try {
                for (var batch : batches) {
                    controlledBatch = batch;
                    stages.addAll(batch.nextRound());
                }
                return ObjectLists.unmodifiable(stages);
            } catch (RuntimeException | Error failure) {
                recordFailureInternal(failure);
                throw failure;
            } finally {
                controlledBatch = null;
                executing = false;
            }
        }

        public List<RecipeProcess.Stage> currentStages() {
            requireOwner();
            return collectCurrentStages();
        }

        private List<RecipeProcess.Stage> collectCurrentStages() {
            var stages = new ObjectArrayList<RecipeProcess.Stage>();
            var previous = controlledBatch;
            try {
                for (var batch : batches) {
                    if (batch.finished) {
                        continue;
                    }
                    controlledBatch = batch;
                    stages.addAll(batch.currentStages());
                }
                return ObjectLists.unmodifiable(stages);
            } finally {
                controlledBatch = previous;
            }
        }

        public void stop() {
            requireOwner();
            stopped = true;
            var previous = controlledBatch;
            try {
                for (var batch : batches) {
                    if (batch.finished) {
                        continue;
                    }
                    controlledBatch = batch;
                    batch.stop();
                }
            } finally {
                controlledBatch = previous;
            }
        }

        public void recordFailure(Throwable failure) {
            requireOwner();
            recordFailureInternal(Objects.requireNonNull(failure));
        }

        private void recordFailureInternal(Throwable failure) {
            stopped = true;
            var previous = controlledBatch;
            try {
                for (var batch : batches) {
                    if (batch.finished) {
                        continue;
                    }
                    controlledBatch = batch;
                    if (batch.begun) {
                        batch.recordFailure(failure);
                    } else {
                        batch.stop();
                    }
                }
            } finally {
                controlledBatch = previous;
            }
        }

        /**
         * No host runs post while any group stage remains live; child leases release together.
         */
        public boolean tryFinish() {
            requireOwner();
            if (!collectCurrentStages().isEmpty()) {
                return false;
            }
            stopped = true;
            executing = true;
            Throwable failure = null;
            boolean pending = false;
            try {
                for (var batch : batches) {
                    if (batch.finished) {
                        continue;
                    }
                    controlledBatch = batch;
                    try {
                        if (!batch.tryFinish()) {
                            pending = true;
                        }
                    } catch (RuntimeException | Error cleanup) {
                        if (failure == null) {
                            failure = cleanup;
                        } else if (failure != cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        if (!batch.finished) {
                            pending = true;
                        }
                    }
                }
                if (!pending) {
                    for (var batch : batches) {
                        batch.host.activeBatch = null;
                    }
                    finished = true;
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                return finished;
            } finally {
                controlledBatch = null;
                executing = false;
            }
        }

        public boolean finished() {
            return finished;
        }

        private void requireProcessOperation(RecipeProcess process) {
            requireLive();
            var host = process.host();
            if (!processes.contains(process) || host == null || host.activeBatch == null || host.activeBatch.group != this) {
                throw new IllegalStateException("Work-slot group lost its exact process lease");
            }
            if (callbackProcess != null && callbackProcess != process) {
                throw new IllegalStateException("Recipe operation overlaps a different host callback");
            }
        }

        private void requireIdentity() {
            requireLive();
            for (var batch : batches) {
                if (batch.group != this || batch.host.activeBatch != batch) {
                    throw new IllegalStateException("Work-slot group lost its exact host lease");
                }
            }
        }

        private void requireLive() {
            if (Thread.currentThread() != ownerThread || finished) {
                throw new IllegalStateException("Stale work-slot group or incorrect owner");
            }
        }

        private void requireOwner() {
            requireIdentity();
            if (executing || callbackProcess != null) {
                throw new IllegalStateException("Work-slot group callback is executing");
            }
        }
    }

    /**
     * Owner-thread round driver; source execution and network authority remain with its coordinator.
     */
    public static final class TickBatch {
        private final MachineWorkSlots host;
        private final Thread ownerThread = Thread.currentThread();
        private final List<Entry> entries;
        @Nullable
        private TickGroup group;
        @Nullable
        private RecipeProcess permitted;
        private boolean begun;
        private boolean stopped;
        private boolean finished;

        private TickBatch(MachineWorkSlots host, List<Entry> entries) {
            this.host = host;
            this.entries = entries;
        }

        /**
         * Runs each actual process's pre/tick once in slot order; faults retain earlier sessions.
         */
        public void begin() {
            requireOwner();
            if (begun || stopped) {
                throw new IllegalStateException("Work-slot batch cannot begin again");
            }
            begun = true;
            host.executing = true;
            try {
                for (var entry : entries) {
                    if (host.workPaused()) {
                        stopped = true;
                        break;
                    }
                    entry.requireIdentity(host);
                    if (entry.process.state() != entry.initialState) {
                        throw new IllegalStateException("Captured process changed before batch begin");
                    }
                    permitted = entry.process;
                    entry.session = host.withProcessCallback(entry.process, entry.process::beginTick);
                    permitted = null;
                }
            } catch (RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            } finally {
                permitted = null;
                host.executing = false;
            }
        }

        /**
         * Creates at most one current stage per live process. A previous round must be fully settled;
         * a caller must never call process.tick after applying the returned stages' resources.
         */
        public List<RecipeProcess.Stage> nextRound() {
            requireOwner();
            if (!begun) {
                throw new IllegalStateException("Work-slot batch has not begun");
            }
            if (!currentStages().isEmpty()) {
                throw new IllegalStateException("Previous recipe round is unsettled");
            }
            if (stopped) {
                return List.of();
            }
            host.executing = true;
            var stages = new ObjectArrayList<RecipeProcess.Stage>();
            try {
                for (var entry : entries) {
                    entry.requireIdentity(host);
                    if (entry.session == null || entry.postAttempted) {
                        continue;
                    }
                    permitted = entry.process;
                    var stage = host.withProcessCallback(entry.process, entry.session::nextStage);
                    permitted = null;
                    if (stage != null) {
                        stages.add(stage);
                    }
                }
                return ObjectLists.unmodifiable(stages);
            } catch (RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            } finally {
                permitted = null;
                host.executing = false;
            }
        }

        /**
         * Partial nextRound acquisition is retained here, including after a later slot threw.
         */
        public List<RecipeProcess.Stage> currentStages() {
            requireOwner();
            var stages = new ObjectArrayList<RecipeProcess.Stage>();
            for (var entry : entries) {
                entry.requireIdentity(host);
                if (entry.session == null || entry.postAttempted) {
                    continue;
                }
                var stage = entry.session.currentStage();
                if (stage != null) {
                    stages.add(stage);
                }
            }
            return ObjectLists.unmodifiable(stages);
        }

        /**
         * Normal cancellation/retirement stops later rounds; current stages still need settlement.
         */
        public void stop() {
            requireOwner();
            stopped = true;
        }

        /**
         * Marks actual active sessions faulted without treating untouched later slots as executed.
         */
        public void recordFailure(Throwable failure) {
            requireIdentity();
            Objects.requireNonNull(failure);
            stopped = true;
            host.faulted = true;
            var previousProcess = permitted;
            try {
                for (var entry : entries) {
                    if (entry.session == null || entry.postAttempted) {
                        continue;
                    }
                    permitted = entry.process;
                    try {
                        entry.session.recordFailure(failure);
                    } catch (RuntimeException | Error recording) {
                        if (failure != recording) {
                            failure.addSuppressed(recording);
                        }
                    }
                }
            } finally {
                permitted = previousProcess;
            }
        }

        /**
         * False means an exact process/stage is still live; no post runs before the current round
         * settles. Every eligible post is attempted once, then healthy terminal slots are detached.
         * Faulted processes remain attached for diagnostics, while accurately quiescent batches close.
         */
        public boolean tryFinish() {
            requireOwner();
            if (!currentStages().isEmpty()) {
                return false;
            }
            stopped = true;
            host.executing = true;
            Throwable failure = null;
            boolean pending = false;
            try {
                for (var entry : entries) {
                    if (entry.published) {
                        continue;
                    }
                    try {
                        entry.requireIdentity(host);
                        permitted = entry.process;
                        var session = entry.session;
                        if (session != null && !entry.postAttempted) {
                            try {
                                host.withProcessCallback(entry.process, () -> {
                                    session.finish();
                                    return null;
                                });
                            } finally {
                                entry.postAttempted = session.postStarted();
                            }
                        }
                        if (!entry.process.quiescent()) {
                            pending = true;
                            continue;
                        }
                        if (entry.process.persistenceReady()) {
                            host.publishSlot(entry.slot, entry.process);
                        } else {
                            host.faulted = true;
                        }
                        entry.published = true;
                    } catch (RuntimeException | Error cleanup) {
                        host.faulted = true;
                        if (failure == null) {
                            failure = cleanup;
                        } else if (failure != cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        // A failed post may have completed its local boundary. Never replay it.
                        if (entry.process.quiescent()) {
                            entry.published = true;
                        } else {
                            pending = true;
                        }
                    } finally {
                        permitted = null;
                    }
                }
                if (!pending) {
                    finished = true;
                    if (group == null) {
                        host.activeBatch = null;
                    }
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                return finished;
            } finally {
                host.executing = false;
            }
        }

        /**
         * Actual closure, including when post reported a fault after every process became inactive.
         */
        public boolean finished() {
            return finished;
        }

        private void requireIdentity() {
            if (Thread.currentThread() != ownerThread || host.activeBatch != this || finished) {
                throw new IllegalStateException("Stale work-slot batch or incorrect owner");
            }
            if (group != null) {
                group.requireLive();
                if (group.controlledBatch != this || group.callbackProcess != null) {
                    throw new IllegalStateException("Grouped work-slot batch is controlled by its group");
                }
            }
        }

        private void requireOwner() {
            requireIdentity();
            if (host.executing) {
                throw new IllegalStateException("Work-slot batch callback is executing");
            }
        }

        private static final class Entry {
            private final WorkSlot slot;
            private final RecipeProcess process;
            private final RecipeProcess.State initialState;
            @Nullable
            private RecipeProcess.TickSession session;
            private boolean postAttempted;
            private boolean published;

            private Entry(WorkSlot slot, RecipeProcess process) {
                this.slot = slot;
                this.process = process;
                initialState = process.state();
            }

            private void requireIdentity(MachineWorkSlots host) {
                if (!published && (slot.process() != process || process.host() != host)) {
                    throw new IllegalStateException("Captured work slot lost its exact process");
                }
            }
        }
    }

    public record SlotSnapshot(WorkSlotDefinition definition, @Nullable RecipeProcess process,
                               ProcessResult result) {
        public SlotSnapshot {
            Objects.requireNonNull(definition);
            Objects.requireNonNull(result);
        }
    }

    public final class Admission {
        private final Thread thread = Thread.currentThread();
        private final WorkSlot slot;
        private final RecipeProcess process;
        private final Iterator<ResourceLocation> groups;
        private int cursor;
        private int active;
        private int phase;
        private boolean accepted;
        private boolean admitted;
        private boolean finished;
        private boolean advancing;
        private boolean reserved;
        @Nullable
        private SharedUsage basis;
        @Nullable
        private UsageCapture finalUsage;
        @Nullable
        private RecipeCatalog.Selection selection;
        @Nullable
        private RecipeProcess permitted;
        @Nullable
        private MachineAttributes.ValueCapture capacityValue;

        private Admission(WorkSlot slot, RecipeProcess process) {
            this.slot = slot;
            this.process = process;
            accepted = slot.allowedRecipeGroups().isEmpty();
            groups = process.recipe().recipeGroups().iterator();
        }

        public boolean advance(RecipeWorkBudget budget, BooleanSupplier authorization) {
            requireOwner();
            Objects.requireNonNull(budget);
            Objects.requireNonNull(authorization);
            if (finished) return true;
            advancing = true;
            try {
                if (workPaused() || !authorization.getAsBoolean()) return finish(false);
                if (phase == 0) {
                    while (cursor < slots.size()) {
                        if (!budget.take()) return false;
                        var captured = slots.get(cursor++).process();
                        if (captured == null) continue;
                        permitted = captured;
                        try {
                            captured.requirePersistenceBoundary();
                            if (captured.host() != MachineWorkSlots.this)
                                throw new IllegalStateException("Work slot lost its process ownership");
                        } finally {
                            permitted = null;
                        }
                        active++;
                    }
                    phase = 1;
                }
                if (phase == 1) {
                    while (!accepted && groups.hasNext()) {
                        if (!budget.take()) return false;
                        accepted = slot.allowedRecipeGroups().contains(groups.next());
                    }
                    phase = 2;
                }
                if (!dynamicRoster && capacityValue == null)
                    capacityValue = owner.attributes().beginValueCapture(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
                if (!dynamicRoster && !Objects.requireNonNull(capacityValue).advance(budget)) {
                    if (!capacityValue.current()) capacityValue = null;
                    return false;
                }
                if (basis != null) {
                    if (finalUsage == null) finalUsage = beginSharedUsage(process.recipe(), this);
                    if (!finalUsage.advance(budget)) {
                        if (!finalUsage.current()) finalUsage = null;
                        return false;
                    }
                    var current = finalUsage.result();
                    if (current.occupied() != basis.occupied() || current.limit() != basis.limit())
                        return finish(false);
                }
                if (!budget.take()) return false;
                validateUnattached(process);
                if (!accepted || selection != null && !selection.accepts(owner, process.recipe())
                    || slot.process() != null || workPaused()) return finish(false);
                validateUnattached(process);
                // Owner permission is also an extension callback. The scanner's authorization must
                // run after it so a callback cannot replace the definition after its final proof.
                if (workPaused() || !authorization.getAsBoolean()) return finish(false);
                if (!dynamicRoster && !Objects.requireNonNull(capacityValue).current()) {
                    capacityValue = null;
                    return false;
                }
                validateUnattached(process);
                if (!dynamicRoster && active >= Math.clamp((long) Objects.requireNonNull(capacityValue).result(), 0, slots.size())
                    || slot.process() != null
                    || owner.recipeThreadCount(process.recipe().id()) >= process.recipe().maxConcurrentThreads())
                    return finish(false);
                if (finalUsage != null && !finalUsage.current()) {
                    finalUsage = null;
                    return false;
                }
                // First completed owner claim wins. Final quantities may exceed the base allowance.
                owner.recordRecipeOccupant(process);
                reserved = true;
                process.attachTo(MachineWorkSlots.this);
                slot.setState(process, process.state().result());
                return finish(true);
            } finally {
                advancing = false;
            }
        }

        public void cancel() {
            requireOwner();
            if (!finished) finish(false);
        }

        public boolean finished() {
            requireOwner();
            return finished;
        }

        public boolean admitted() {
            requireOwner();
            if (!finished) throw new IllegalStateException("Admission is incomplete");
            return admitted;
        }

        private boolean finish(boolean accepted) {
            admitted = accepted;
            finished = true;
            activeAdmission = null;
            if (reserved && process.host() == null) owner.releaseRecipeOccupant(process);
            return true;
        }

        private void requireOwner() {
            if (Thread.currentThread() != thread || advancing || !finished && activeAdmission != this)
                throw new IllegalStateException("Admission requires its idle captured owner");
        }
    }
}
