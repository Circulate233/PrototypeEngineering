package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.List;
import java.util.Objects;

/** PM's ordered multi-container resource IO, reserving actual quantities instead of simulating twice. */
public final class ResourceRequirementSystem implements RecipeRequirementSystem<ResourceRequirement> {
    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, requirement.payload().inputs(), ObjectLists.emptyList());
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, requirement.payload().inputsPerTick(), requirement.payload().outputsPerTick());
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement) {
        return acquire(process, requirement, ObjectLists.emptyList(), requirement.payload().outputs());
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement,
                                                   List<GenericStack> inputs, List<GenericStack> outputs) {
        if (inputs.isEmpty() && outputs.isEmpty()) {
            return RequirementTransaction.SUCCESS;
        }
        var reservations = new ObjectArrayList<ResourceReservation>();
        try {
            var hatches = MachineHatches.get(process.owner()).resources();
            String id = requirement.id().toString();
            ProcessResult result = reserve(hatches, inputs, true, false, id, reservations);
            if (result == ProcessResult.SUCCESS) {
                result = reserve(hatches, outputs, false, requirement.payload().voidExcessOutputs(), id, reservations);
            }
            return new ReservedRequirement(result, reservations);
        } catch (RuntimeException | Error failure) {
            ReservedRequirement.rollbackAfterFailure(reservations, failure);
            throw failure;
        }
    }

    private static ProcessResult reserve(List<ResourceHatch> hatches, List<GenericStack> quantities, boolean input,
                                          boolean voidExcess, String id, List<ResourceReservation> reservations) {
        if (quantities.isEmpty()) {
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
        var totals = new Object2LongLinkedOpenHashMap<AEKey>();
        for (var stack : quantities) {
            totals.put(stack.what(), Math.addExact(totals.getLong(stack.what()), stack.amount()));
        }
        for (var entry : totals.object2LongEntrySet()) {
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
