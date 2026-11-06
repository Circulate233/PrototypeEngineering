package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Internal fixed-resource migration prototype; not the extensible recipe processor. Actual receipts
 * and undelivered products stay owned by this run. This is not the planned transaction processor.
 */
public final class ResourceTransferRun {
    public enum Status { COLLECTING_INPUTS, WAITING_FOR_ENERGY, WORKING, WAITING_FOR_OUTPUT, COMPLETE }

    private final ResourceTransferPlan recipe;
    private final Runnable onChanged;
    private final long[] acquired;
    private final long[] remainingOutputs;
    private final ReferenceOpenHashSet<Object> visited = new ReferenceOpenHashSet<>();
    private int progress;
    private long energyCredit;

    public ResourceTransferRun(ResourceTransferPlan recipe, Runnable onChanged) {
        this.recipe = Objects.requireNonNull(recipe);
        this.onChanged = Objects.requireNonNull(onChanged);
        acquired = new long[recipe.inputs().size()];
        remainingOutputs = new long[recipe.outputs().size()];
        for (int i = 0; i < remainingOutputs.length; i++) { remainingOutputs[i] = recipe.outputs().get(i).amount(); }
    }

    public ResourceTransferRun(Snapshot saved, Runnable onChanged) {
        recipe = saved.recipe();
        this.onChanged = Objects.requireNonNull(onChanged);
        acquired = saved.acquired();
        remainingOutputs = saved.remainingOutputs();
        progress = saved.progress();
        energyCredit = saved.energyCredit();
        validateSavedState();
    }

    /** Simulation chooses a candidate; only actual receipts advance it. Pool order is recipe priority. */
    @Nullable
    public static ResourceTransferPlan findPlan(List<ResourceTransferPlan> plans, List<? extends ResourceHatch> hatches) {
        var visited = new ReferenceOpenHashSet<Object>();
        for (ResourceTransferPlan recipe : plans) {
            boolean matches = true;
            for (GenericStack required : recipe.inputs()) {
                long missing = required.amount();
                visited.clear();
                for (ResourceHatch hatch : hatches) {
                    if (!hatch.isValid()) { continue; }
                    var inventory = hatch.input();
                    if (inventory == null || !visited.add(Objects.requireNonNull(hatch.inputStorageIdentity()))) { continue; }
                    missing -= checked(inventory.extract(required.what(), missing, Actionable.SIMULATE), missing);
                    if (missing == 0) { break; }
                }
                if (missing != 0) { matches = false; break; }
            }
            if (matches) { return recipe; }
        }
        return null;
    }

    /** Called at most once per machine tick, only while the structure is valid and work is enabled. */
    public Status tick(List<? extends ResourceHatch> hatches, List<? extends EnergyInput> energy) {
        for (int i = 0; i < acquired.length; i++) {
            GenericStack required = recipe.inputs().get(i);
            long missing = required.amount() - acquired[i];
            visited.clear();
            for (ResourceHatch hatch : hatches) {
                if (missing == 0) { break; }
                if (!hatch.isValid()) { continue; }
                var inventory = hatch.input();
                if (inventory == null || !visited.add(Objects.requireNonNull(hatch.inputStorageIdentity()))) { continue; }
                long received = checked(inventory.extract(required.what(), missing, Actionable.MODULATE), missing);
                if (received > 0) {
                    acquired[i] += received;
                    missing -= received;
                    onChanged.run();
                }
            }
        }
        if (!inputsComplete()) { return result(Status.COLLECTING_INPUTS); }
        if (progress < recipe.durationTicks()) {
            visited.clear();
            for (EnergyInput input : energy) {
                long missing = recipe.energyPerTick() - energyCredit;
                if (missing == 0) { break; }
                if (!input.isValid() || !visited.add(Objects.requireNonNull(input.storageIdentity()))) { continue; }
                long received = checked(input.extractEnergy(missing, Actionable.MODULATE), missing);
                if (received > 0) { energyCredit += received; onChanged.run(); }
            }
            if (energyCredit < recipe.energyPerTick()) { return result(Status.WAITING_FOR_ENERGY); }
            energyCredit = 0;
            progress++;
            onChanged.run();
            if (progress < recipe.durationTicks()) { return result(Status.WORKING); }
        }
        for (int i = 0; i < remainingOutputs.length; i++) {
            GenericStack product = recipe.outputs().get(i);
            visited.clear();
            for (ResourceHatch hatch : hatches) {
                if (remainingOutputs[i] == 0) { break; }
                if (!hatch.isValid()) { continue; }
                var inventory = hatch.output();
                if (inventory == null || !visited.add(Objects.requireNonNull(hatch.outputStorageIdentity()))) { continue; }
                long delivered = checked(inventory.insert(product.what(), remainingOutputs[i], Actionable.MODULATE), remainingOutputs[i]);
                if (delivered > 0) { remainingOutputs[i] -= delivered; onChanged.run(); }
            }
        }
        for (long remaining : remainingOutputs) { if (remaining != 0) { return result(Status.WAITING_FOR_OUTPUT); } }
        return result(Status.COMPLETE);
    }

    private Status result(Status status) {
        // Do not retain capability owners between ticks or across chunk unloads.
        visited.clear();
        return status;
    }

    private static long checked(long transferred, long requested) {
        if (transferred < 0 || transferred > requested) {
            throw new IllegalStateException("Storage violated the transfer contract: " + transferred + " of " + requested);
        }
        return transferred;
    }

    private boolean inputsComplete() {
        for (int i = 0; i < acquired.length; i++) {
            if (acquired[i] != recipe.inputs().get(i).amount()) { return false; }
        }
        return true;
    }

    public ResourceTransferPlan recipe() { return recipe; }
    public int progress() { return progress; }
    public long energyCredit() { return energyCredit; }

    public Snapshot snapshot() { return new Snapshot(recipe, acquired, remainingOutputs, progress, energyCredit); }

    private void validateSavedState() {
        if (acquired.length != recipe.inputs().size() || remainingOutputs.length != recipe.outputs().size()
                || progress < 0 || progress > recipe.durationTicks() || energyCredit < 0 || energyCredit > recipe.energyPerTick()) {
            throw new IllegalArgumentException("Invalid saved recipe work state");
        }
        for (int i = 0; i < acquired.length; i++) {
            if (acquired[i] < 0 || acquired[i] > recipe.inputs().get(i).amount()) {
                throw new IllegalArgumentException("Invalid saved input receipt");
            }
        }
        for (int i = 0; i < remainingOutputs.length; i++) {
            long total = recipe.outputs().get(i).amount();
            if (remainingOutputs[i] < 0 || remainingOutputs[i] > total || progress < recipe.durationTicks() && remainingOutputs[i] != total) {
                throw new IllegalArgumentException("Invalid saved product remainder");
            }
        }
        if (!inputsComplete() && (progress != 0 || energyCredit != 0) || progress == recipe.durationTicks() && energyCredit != 0) {
            throw new IllegalArgumentException("Saved work progress disagrees with its receipts");
        }
    }

    /** A complete checkpoint including the recipe definition; later registry edits cannot reprice active work. */
    public record Snapshot(ResourceTransferPlan recipe, long[] acquired, long[] remainingOutputs, int progress, long energyCredit) {
        public Snapshot {
            Objects.requireNonNull(recipe);
            acquired = acquired.clone();
            remainingOutputs = remainingOutputs.clone();
        }
        @Override public long[] acquired() { return acquired.clone(); }
        @Override public long[] remainingOutputs() { return remainingOutputs.clone(); }
    }
}
