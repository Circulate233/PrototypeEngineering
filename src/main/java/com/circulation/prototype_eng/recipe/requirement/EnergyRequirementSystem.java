package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.List;
import java.util.Objects;

/** PM's independent FE start/tick/end IO, with each source counted once per operation. */
public final class EnergyRequirementSystem implements RecipeRequirementSystem<EnergyRequirement> {
    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, requirement.payload().input(), 0);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, requirement.payload().inputPerTick(), requirement.payload().outputPerTick());
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, 0, requirement.payload().output());
    }

    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement,
                                                   long input, long output) {
        if (input == 0 && output == 0) { return RequirementTransaction.SUCCESS; }
        var reservations = new ObjectArrayList<ResourceReservation>();
        try {
            var hatches = MachineHatches.get(process.owner());
            String id = requirement.id().toString();
            ProcessResult result = reserveInput(hatches, input, id, reservations);
            if (result == ProcessResult.SUCCESS) {
                result = reserveOutput(hatches, output, requirement.payload().voidExcessOutputs(), id, reservations);
            }
            return new ReservedRequirement(result, reservations);
        } catch (RuntimeException | Error failure) {
            ReservedRequirement.rollbackAfterFailure(reservations, failure);
            throw failure;
        }
    }

    private static ProcessResult reserveInput(MachineHatches hatches, long amount, String id,
                                               List<ResourceReservation> reservations) {
        if (amount == 0) { return ProcessResult.SUCCESS; }
        long remaining = amount;
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var source : hatches.energyInputs()) {
            var context = source.context();
            if (!source.isValid() || !visited.add(context)) { continue; }
            context.lock();
            try {
                if (!source.isValid()) { return RecipeText.StorageChanged.blocked(); }
                var snapshot = source.energySnapshot();
                long portion = Math.min(remaining, Math.min(snapshot.availableEnergy(), snapshot.maxExtract()));
                if (portion == 0) { continue; }
                var reserved = source.reserveExtractEnergy(snapshot, portion);
                if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                    return RecipeText.StorageChanged.blocked();
                }
                reservations.add(Objects.requireNonNull(reserved.reservation()));
                remaining -= portion;
            } finally {
                context.unlock();
            }
            if (remaining == 0) { return ProcessResult.SUCCESS; }
        }
        return visited.isEmpty() ? RecipeText.NoEnergyInput.blocked(id)
            : RecipeText.EnergyMissing.blocked(id, Long.toString(remaining));
    }

    private static ProcessResult reserveOutput(MachineHatches hatches, long amount, boolean voidExcess, String id,
                                                List<ResourceReservation> reservations) {
        if (amount == 0) { return ProcessResult.SUCCESS; }
        long remaining = amount;
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var target : hatches.energyOutputs()) {
            var context = target.context();
            if (!target.isValid() || !visited.add(context)) { continue; }
            context.lock();
            try {
                if (!target.isValid()) { return RecipeText.StorageChanged.blocked(); }
                var snapshot = target.energySnapshot();
                long portion = Math.min(remaining, Math.min(snapshot.availableCapacity(), snapshot.maxReceive()));
                if (portion == 0) { continue; }
                var reserved = target.reserveReceiveEnergy(snapshot, portion);
                if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                    return RecipeText.StorageChanged.blocked();
                }
                reservations.add(Objects.requireNonNull(reserved.reservation()));
                remaining -= portion;
            } finally {
                context.unlock();
            }
            if (remaining == 0) { return ProcessResult.SUCCESS; }
        }
        if (visited.isEmpty()) { return RecipeText.NoEnergyOutput.blocked(id); }
        return voidExcess ? ProcessResult.SUCCESS : RecipeText.EnergyOutputFull.blocked(id, Long.toString(remaining));
    }
}
