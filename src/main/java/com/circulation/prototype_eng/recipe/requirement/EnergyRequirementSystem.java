package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
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
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.recipe.PreparedRequirement;

/** PM's independent FE start/tick/end IO, with each source counted once per operation. */
public final class EnergyRequirementSystem implements RecipeRequirementSystem<EnergyRequirement> {
    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The owner binding intentionally retains its captured callback scope.
    public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement,
                                          RecipeStage stage) {
        var payload = requirement.payload();
        long input = switch (stage) {
            case START -> payload.input();
            case TICK -> payload.inputPerTick();
            case END -> 0;
        };
        long output = switch (stage) {
            case START -> 0;
            case TICK -> payload.outputPerTick();
            case END -> payload.output();
        };
        if (input == 0 && output == 0) {
            return null;
        }
        var hatches = process.hatches();
        var quantities = new EnergyQuantities(input, output, RecipeParallelism.effective(process));
        return new RequirementPreparation() {
            private int direction;
            private int index;
            private long remaining = -1;
            private ReferenceOpenHashSet<StorageContext> visited = new ReferenceOpenHashSet<>();

            @Override
            public boolean ownerWorkMetered() {
                return true;
            }

            @Override
            public RecipePureComputation computation() {
                return quantities;
            }

            private RequirementTransaction paused(ObjectArrayList<ResourceReservation> reservations) {
                return new PreparedRequirement(this) {
                    @Override
                    public List<ResourceReservation> reservations() {
                        return reservations;
                    }
                };
            }

            @Override
            public RequirementTransaction acquire() {
                var reservations = new ObjectArrayList<ResourceReservation>();
                if (!quantities.complete || process.hatches() != hatches) {
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), reservations);
                }
                var budget = process.preparationBudget();
                String id = requirement.id().toString();
                if (remaining < 0) {
                    remaining = quantities.input;
                }
                try {
                    while (direction < 2) {
                        int count = direction == 0 ? hatches.energyInputs().size() : hatches.energyOutputs().size();
                        while (remaining > 0 && index < count) {
                            if (!budget.take()) {
                                return paused(reservations);
                            }
                            int sourceIndex = index++;
                            var inputSource = direction == 0 ? hatches.energyInputs().get(sourceIndex) : null;
                            var outputSource = direction == 1 ? hatches.energyOutputs().get(sourceIndex) : null;
                            var context = inputSource != null ? inputSource.context() : Objects.requireNonNull(outputSource).context();
                            boolean valid = inputSource != null ? inputSource.isValid() : outputSource.isValid();
                            if (!valid || !visited.add(context)) {
                                continue;
                            }
                            context.lock();
                            try {
                                if (!(inputSource != null ? inputSource.isValid() : outputSource.isValid())) {
                                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), reservations);
                                }
                                var snapshot = inputSource != null ? inputSource.energySnapshot() : outputSource.energySnapshot();
                                long portion = Math.min(remaining, inputSource != null
                                    ? Math.min(snapshot.availableEnergy(), snapshot.maxExtract())
                                    : Math.min(snapshot.availableCapacity(), snapshot.maxReceive()));
                                if (portion == 0) {
                                    continue;
                                }
                                var reserved = inputSource != null ? inputSource.reserveExtractEnergy(snapshot, portion)
                                    : outputSource.reserveReceiveEnergy(snapshot, portion);
                                if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), reservations);
                                }
                                reservations.add(Objects.requireNonNull(reserved.reservation()));
                                remaining -= portion;
                            } finally {
                                context.unlock();
                            }
                        }
                        if (remaining > 0) {
                            ProcessResult result = direction == 0
                                ? visited.isEmpty() ? RecipeText.NoEnergyInput.blocked(id) : RecipeText.EnergyMissing.blocked(id, Long.toString(remaining))
                                : visited.isEmpty() ? RecipeText.NoEnergyOutput.blocked(id)
                                : payload.voidExcessOutputs() ? ProcessResult.SUCCESS : RecipeText.EnergyOutputFull.blocked(id, Long.toString(remaining));
                            return new ReservedRequirement(result, reservations);
                        }
                        direction++;
                        index = 0;
                        visited = new ReferenceOpenHashSet<>();
                        remaining = quantities.output;
                    }
                    return new ReservedRequirement(ProcessResult.SUCCESS, reservations);
                } catch (RuntimeException | Error failure) {
                    ReservedRequirement.rollbackAfterFailure(reservations, failure);
                    throw failure;
                }
            }
        };
    }

    private static class EnergyQuantities implements RecipePureComputation {
        private long input;
        private long output;
        private final int parallelism;
        private boolean complete;

        private EnergyQuantities(long input, long output, int parallelism) {
            this.input = input;
            this.output = output;
            this.parallelism = parallelism;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            if (!budget.take()) {
                return false;
            }
            input = RecipeParallelism.scaleCount(input, parallelism);
            output = RecipeParallelism.scaleCount(output, parallelism);
            complete = true;
            return true;
        }
    }
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
        int parallelism = RecipeParallelism.effective(process);
        input = RecipeParallelism.scaleCount(input, parallelism);
        output = RecipeParallelism.scaleCount(output, parallelism);
        var reservations = new ObjectArrayList<ResourceReservation>();
        try {
            var hatches = process.hatches();
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
