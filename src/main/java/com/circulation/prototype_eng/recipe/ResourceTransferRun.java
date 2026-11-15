package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Internal fixed-resource migration prototype; not the extensible recipe processor. Actual receipts
 * and undelivered products stay owned by this run. This is not the planned transaction processor.
 */
public final class ResourceTransferRun {
    private final ResourceTransferPlan recipe;
    private final Runnable onChanged;
    private final long[] acquired;
    private final long[] remainingOutputs;
    private final ReferenceOpenHashSet<Object> visited = new ReferenceOpenHashSet<>();
    private int progress;
    private long energyCredit;

    public ResourceTransferRun(ResourceTransferPlan recipe, Runnable onChanged) {
        this.recipe = recipe;
        this.onChanged = onChanged;
        acquired = new long[recipe.inputs().size()];
        remainingOutputs = new long[recipe.outputs().size()];
        for (int i = 0; i < remainingOutputs.length; i++) {
            remainingOutputs[i] = recipe.outputs().get(i).amount();
        }
    }

    public ResourceTransferRun(Snapshot saved, Runnable onChanged) {
        recipe = saved.recipe();
        this.onChanged = onChanged;
        acquired = saved.acquired();
        remainingOutputs = saved.remainingOutputs();
        progress = saved.progress();
        energyCredit = saved.energyCredit();
        validateSavedState();
    }

    /**
     * Simulation chooses a candidate; only actual receipts advance it. Pool order is recipe priority.
     */
    @Nullable
    public static ResourceTransferPlan findPlan(List<ResourceTransferPlan> plans, List<? extends ResourceHatch> hatches) {
        var visited = new ReferenceOpenHashSet<>(plans.size());
        for (int index6 = 0; index6 < plans.size(); index6++) {
            ResourceTransferPlan recipe = plans.get(index6);
            boolean matches = true;
            var elements = recipe.inputs();
            for (int index5 = 0; index5 < elements.size(); index5++) {
                GenericStack required = elements.get(index5);
                long missing = required.amount();
                visited.clear();
                for (int index = 0; index < hatches.size(); index++) {
                    ResourceHatch hatch = hatches.get(index);
                    if (!hatch.isValid()) {
                        continue;
                    }
                    var inventory = hatch.input();
                    if (inventory == null || !visited.add(hatch.inputStorageIdentity())) {
                        continue;
                    }
                    missing -= checked(inventory.extract(required.what(), missing, Actionable.SIMULATE), missing);
                    if (missing == 0) {
                        break;
                    }
                }
                if (missing != 0) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return recipe;
            }
        }
        return null;
    }

    private static long checked(long transferred, long requested) {
        if (transferred < 0 || transferred > requested) {
            throw new IllegalStateException("Storage violated the transfer contract: " + transferred + " of " + requested);
        }
        return transferred;
    }

    /**
     * Called at most once per machine tick, only while the structure is valid and work is enabled.
     */
    public Status tick(List<? extends ResourceHatch> hatches, List<? extends EnergyInput> energy) {
        for (int i = 0; i < acquired.length; i++) {
            GenericStack required = recipe.inputs().get(i);
            long missing = required.amount() - acquired[i];
            visited.clear();
            for (int index3 = 0; index3 < hatches.size(); index3++) {
                ResourceHatch hatch = hatches.get(index3);
                if (missing == 0) {
                    break;
                }
                if (!hatch.isValid()) {
                    continue;
                }
                var inventory = hatch.input();
                if (inventory == null || !visited.add(hatch.inputStorageIdentity())) {
                    continue;
                }
                long received = checked(inventory.extract(required.what(), missing, Actionable.MODULATE), missing);
                if (received > 0) {
                    acquired[i] += received;
                    missing -= received;
                    onChanged.run();
                }
            }
        }
        if (!inputsComplete()) {
            return result(Status.COLLECTING_INPUTS);
        }
        if (progress < recipe.durationTicks()) {
            visited.clear();
            for (int index2 = 0; index2 < energy.size(); index2++) {
                EnergyInput input = energy.get(index2);
                long missing = recipe.energyPerTick() - energyCredit;
                if (missing == 0) {
                    break;
                }
                if (!input.isValid() || !visited.add(input.storageIdentity())) {
                    continue;
                }
                long received = checked(input.extractEnergy(missing, Actionable.MODULATE), missing);
                if (received > 0) {
                    energyCredit += received;
                    onChanged.run();
                }
            }
            if (energyCredit < recipe.energyPerTick()) {
                return result(Status.WAITING_FOR_ENERGY);
            }
            energyCredit = 0;
            progress++;
            onChanged.run();
            if (progress < recipe.durationTicks()) {
                return result(Status.WORKING);
            }
        }
        for (int i = 0; i < remainingOutputs.length; i++) {
            GenericStack product = recipe.outputs().get(i);
            visited.clear();
            for (int index4 = 0; index4 < hatches.size(); index4++) {
                ResourceHatch hatch = hatches.get(index4);
                if (remainingOutputs[i] == 0) {
                    break;
                }
                if (!hatch.isValid()) {
                    continue;
                }
                var inventory = hatch.output();
                if (inventory == null || !visited.add(hatch.outputStorageIdentity())) {
                    continue;
                }
                long delivered = checked(inventory.insert(product.what(), remainingOutputs[i], Actionable.MODULATE), remainingOutputs[i]);
                if (delivered > 0) {
                    remainingOutputs[i] -= delivered;
                    onChanged.run();
                }
            }
        }
        for (long remaining : remainingOutputs) {
            if (remaining != 0) {
                return result(Status.WAITING_FOR_OUTPUT);
            }
        }
        return result(Status.COMPLETE);
    }

    private Status result(Status status) {
        // Do not retain capability owners between ticks or across chunk unloads.
        visited.clear();
        return status;
    }

    private boolean inputsComplete() {
        for (int i = 0; i < acquired.length; i++) {
            if (acquired[i] != recipe.inputs().get(i).amount()) {
                return false;
            }
        }
        return true;
    }

    public ResourceTransferPlan recipe() {
        return recipe;
    }

    public int progress() {
        return progress;
    }

    public long energyCredit() {
        return energyCredit;
    }

    public Snapshot snapshot() {
        return new Snapshot(recipe, acquired, remainingOutputs, progress, energyCredit);
    }

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

    public enum Status {COLLECTING_INPUTS, WAITING_FOR_ENERGY, WORKING, WAITING_FOR_OUTPUT, COMPLETE}

    /**
     * Checkpoint arrays belong to the captured run and must not be modified by readers.
     * Restoring transfers them to the replacement run; the original run must no longer execute.
     */
    public record Snapshot(ResourceTransferPlan recipe, long[] acquired, long[] remainingOutputs, int progress,
                           long energyCredit) {
    }
}
