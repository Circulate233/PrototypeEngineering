package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeExecutor;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.machine.WorkSlotProcessingSystem;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * PM's ordered work-slot host. One execution owner controls admission, tick, transfer and persistence;
 * these methods are not synchronization primitives for workers. Scheduler joins must transfer ownership
 * before mutating the host. Concurrent-process attributes limit admission, never evict existing work.
 */
public final class MachineWorkSlots {
    public static final ComponentType<MachineInstance, MachineWorkSlots> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "work_slots"), owner -> new MachineWorkSlots(owner, List.of()),
        List.of(new WorkSlotProcessingSystem()));

    private final MachineInstance owner;
    private final List<WorkSlot> slots;
    private final Object2ObjectLinkedOpenHashMap<String, WorkSlot> byName = new Object2ObjectLinkedOpenHashMap<>();
    private final RecipeExecutor executor;
    private boolean executing;
    private boolean faulted;

    public MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions) {
        this(owner, definitions, Set.of(), RecipeProcess::tick);
    }

    /** An empty layout uses PM's implicit main slot and the supplied machine-level groups. */
    public MachineWorkSlots(MachineInstance owner, List<WorkSlotDefinition> definitions, Set<ResourceLocation> defaultGroups,
                            RecipeExecutor executor) {
        this.owner = Objects.requireNonNull(owner);
        this.executor = Objects.requireNonNull(executor);
        Objects.requireNonNull(defaultGroups);
        var declared = definitions.isEmpty() ? List.of(new WorkSlotDefinition("main", defaultGroups)) : definitions;
        var workSlots = new ObjectArrayList<WorkSlot>();
        for (var definition : declared) {
            var slot = new WorkSlot(Objects.requireNonNull(definition));
            if (byName.putIfAbsent(slot.name(), slot) != null) { throw new IllegalArgumentException("Duplicate work slot: " + slot.name()); }
            workSlots.add(slot);
        }
        slots = ObjectLists.unmodifiable(workSlots);
    }

    public MachineInstance owner() { return owner; }
    public List<WorkSlot> slots() { return slots; }

    public WorkSlot requireSlot(String name) {
        var slot = byName.get(Objects.requireNonNull(name));
        if (slot == null) { throw new IllegalArgumentException("Unknown work slot: " + name); }
        return slot;
    }

    /** Live admission limit; lowering it leaves every existing process attached and runnable. */
    public int capacity() {
        return Math.clamp((long) owner.attributes().value(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1), 0, slots.size());
    }

    public List<RecipeProcess> activeProcesses() {
        var processes = new ObjectArrayList<RecipeProcess>();
        for (var slot : slots) {
            if (slot.process() != null) { processes.add(slot.process()); }
        }
        return ObjectLists.unmodifiable(processes);
    }

    public boolean start(RecipeProcess process) {
        requireBoundary();
        validateUnattached(process);
        for (var slot : slots) {
            if (slot.process() == null && accepts(slot, process)) { return startIn(slot.name(), process); }
        }
        return false;
    }

    public boolean startIn(String name, RecipeProcess process) {
        requireBoundary();
        validateUnattached(process);
        var slot = requireSlot(name);
        if (slot.process() != null || activeCount() >= capacity() || !accepts(slot, process)) { return false; }
        process.attachTo(this);
        slot.setState(process, process.state().result());
        return true;
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

    public boolean cancel(RecipeProcess process) { return cancel(process, RecipeText.Cancelled.failure()); }

    /**
     * Cancels owned work and releases its slot only after cleanup succeeds. This is not detach or END.
     * A faulted host may still clean its other quiescent processes, but is never reactivated by cleanup.
     */
    public boolean cancel(RecipeProcess process, ProcessResult.Failure reason) {
        requireCancellationBoundary();
        Objects.requireNonNull(process);
        Objects.requireNonNull(reason);
        for (var slot : slots) {
            if (slot.process() != process) { continue; }
            process.requirePersistenceBoundary();
            if (process.host() != this) { throw new IllegalStateException("Work slot lost its cancellation ownership"); }
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

    public int cancelAll() { return cancelAll(RecipeText.Cancelled.failure()); }

    /** Attempts every occupied slot in reverse order; failures retain their process for diagnostics. */
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
                if (process == null) { continue; }
                try {
                    if (cancelSlot(slot, process, reason)) { cancelled++; }
                } catch (RuntimeException | Error cleanup) {
                    faulted = true;
                    if (failure == null) { failure = cleanup; }
                    else if (failure != cleanup) { failure.addSuppressed(cleanup); }
                }
            }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            return cancelled;
        } finally {
            executing = false;
        }
    }

    private boolean cancelSlot(WorkSlot slot, RecipeProcess process, ProcessResult.Failure reason) {
        if (process.host() != this) { throw new IllegalStateException("Work slot lost its cancellation ownership"); }
        boolean cancelled = process.cancelFrom(this, reason);
        process.requirePersistenceBoundary();
        process.detachFrom(this);
        slot.setState(null, process.state().result());
        return cancelled;
    }

    /** One synchronous executor invocation per occupied slot; expected failures do not stop other slots. */
    public void tickProcesses() {
        requireBoundary();
        executing = true;
        try {
            for (var slot : slots) {
                var process = slot.process();
                if (process == null) { continue; }
                if (process.host() != this) { throw new IllegalStateException("Work slot lost its process ownership"); }
                var before = process.state().lifecycle();
                if (!before.isTerminal()) { executor.tick(process); }
                process.requirePersistenceBoundary();
                if (process.host() != this) { throw new IllegalStateException("Executor changed process ownership"); }
                var state = process.state();
                if (state.lifecycle().isTerminal()) {
                    process.detachFrom(this);
                    slot.setState(null, state.result());
                } else {
                    slot.setState(process, state.result());
                }
            }
        } catch (RuntimeException | Error failure) {
            faulted = true;
            throw failure;
        } finally {
            executing = false;
        }
    }

    public List<SlotSnapshot> snapshot() {
        requireBoundary();
        var snapshot = new ObjectArrayList<SlotSnapshot>();
        for (var slot : slots) {
            var process = slot.process();
            if (process != null) {
                process.requirePersistenceBoundary();
                if (process.host() != this) { throw new IllegalStateException("Work slot lost its process ownership"); }
            }
            snapshot.add(new SlotSnapshot(slot.definition(), process, slot.result()));
        }
        return ObjectLists.unmodifiable(snapshot);
    }

    /** World cleanup can observe faulted slots, but cannot detach a live transaction or execution. */
    public void requireQuiescent() {
        if (executing) { throw new IllegalStateException("Work slots are executing"); }
        for (var slot : slots) {
            var process = slot.process();
            if (process == null) { continue; }
            if (process.host() != this) { throw new IllegalStateException("Work slot lost its process ownership"); }
            process.requireQuiescent();
        }
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
        if (replacement.size() != slots.size()) { throw new IllegalArgumentException("Work slot layout size changed"); }
        var processes = new ReferenceOpenHashSet<RecipeProcess>();
        var previousProcesses = new ReferenceOpenHashSet<RecipeProcess>();
        for (var old : previous) {
            if (old.process() != null) { previousProcesses.add(old.process()); }
        }
        for (int i = 0; i < replacement.size(); i++) {
            var saved = Objects.requireNonNull(replacement.get(i));
            var slot = slots.get(i);
            if (!slot.definition().equals(saved.definition())) { throw new IllegalArgumentException("Work slot layout changed: " + slot.name()); }
            var process = saved.process();
            if (process == null) { continue; }
            validateProcess(process);
            if (!processes.add(process) || !accepts(slot, process) || !saved.result().equals(process.state().result())
                || process.host() != null && (process.host() != this || !previousProcesses.contains(process))) {
                throw new IllegalArgumentException("Duplicate, incompatible or foreign process in restored work slots");
            }
        }
        // Every operation after validation is local assignment under the same exclusive owner.
        for (var old : previousProcesses) { old.detachFrom(this); }
        for (int i = 0; i < replacement.size(); i++) {
            var saved = replacement.get(i);
            if (saved.process() != null) { saved.process().attachTo(this); }
            slots.get(i).setState(saved.process(), saved.result());
        }
    }

    private int activeCount() {
        int count = 0;
        for (var slot : slots) {
            if (slot.process() != null) { count++; }
        }
        return count;
    }

    private void validateUnattached(RecipeProcess process) {
        validateProcess(process);
        if (process.host() != null) { throw new IllegalArgumentException("Process is already hosted"); }
        if (process.state().lifecycle().isTerminal()) {
            throw new IllegalArgumentException("Cannot admit a terminal process as new work");
        }
    }

    private void validateProcess(RecipeProcess process) {
        Objects.requireNonNull(process).requirePersistenceBoundary();
        if (process.owner() != owner) { throw new IllegalArgumentException("Process belongs to a different machine"); }
    }

    private static boolean accepts(WorkSlot slot, RecipeProcess process) {
        // Empty whitelist accepts explicit admission, as PM's implicit slot intended. Scanning still
        // requires an explicit bounded group scope and must not search a global unbounded catalogue.
        return slot.allowedRecipeGroups().isEmpty() || process.recipe().recipeGroups().stream().anyMatch(slot.allowedRecipeGroups()::contains);
    }

    private void requireBoundary() {
        if (executing || faulted) { throw new IllegalStateException("Work slots are executing or faulted"); }
    }

    private void requireCancellationBoundary() {
        if (executing) { throw new IllegalStateException("Work slots are executing"); }
    }

    public record SlotSnapshot(WorkSlotDefinition definition, @Nullable RecipeProcess process,
                               ProcessResult result) {
        public SlotSnapshot {
            Objects.requireNonNull(definition);
            Objects.requireNonNull(result);
        }
    }
}
