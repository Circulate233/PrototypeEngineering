package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.network.NetworkResourceState;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.machine.CrossRecipeBatch;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * PM's independent FE start/tick/end IO, with each source counted once per operation.
 */
public final class EnergyRequirementSystem implements RecipeRequirementSystem<EnergyRequirement> {
    private static RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement,
                                                  RecipeStage stage, long input, long output) {
        var timing = process.crossRecipeTiming();
        boolean batched = timing != null && timing.duration() > 0 && process.requirementPath().parent() == null;
        if (batched) {
            if (stage != RecipeStage.TICK) return RequirementTransaction.SUCCESS;
            var offsets = Objects.requireNonNull(timing.energyOffsets().get(process.requirementPath()));
            var payload = requirement.payload();
            input = timing.portion(CrossRecipeBatch.energyTotal(payload.input(), payload.inputPerTick(),
                process.recipe().durationTicks(), timing.parallelism()), offsets.input(), process.tickContext());
            output = timing.portion(CrossRecipeBatch.energyTotal(payload.output(), payload.outputPerTick(),
                process.recipe().durationTicks(), timing.parallelism()), offsets.output(), process.tickContext());
        }
        if (input == 0 && output == 0) {
            return RequirementTransaction.SUCCESS;
        }
        int parallelism = batched ? 1 : RecipeParallelism.effective(process);
        input = RecipeParallelism.scaleCount(input, parallelism);
        input = process.owner().networkEnergy(input);
        output = RecipeParallelism.scaleCount(output, parallelism);
        var reservations = new ObjectArrayList<ResourceReservation>(6);
        try {
            var hatches = process.hatches();
            String id = requirement.id().toString();
            ProcessResult result;
            if (input != 0 && requirement.payload().inputAllocation() != InputAllocation.SPLITTABLE) {
                var allocation = new EnergyAllocationCursor(hatches.energyInputs(), input, requirement.payload().inputAllocation(), stage, id);
                if (!allocation.advance(new RecipeWorkBudget(Long.MAX_VALUE), reservations))
                    throw new IllegalStateException("Unbounded energy allocation did not finish");
                result = allocation.result;
            } else result = reserveInput(hatches, input, id, reservations);
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
        if (amount == 0) {
            return ProcessResult.SUCCESS;
        }
        long remaining = amount;
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var source : hatches.energyInputs()) {
            var context = source.context();
            if (!source.isValid() || !visited.add(context)) {
                continue;
            }
            context.lock();
            try {
                if (!source.isValid()) {
                    return RecipeText.StorageChanged.blocked();
                }
                var snapshot = source.energySnapshot();
                long portion = Math.min(remaining, Math.min(snapshot.availableEnergy(), snapshot.maxExtract()));
                if (portion == 0) {
                    continue;
                }
                var reserved = source.reserveExtractEnergy(snapshot, portion);
                if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                    return RecipeText.StorageChanged.blocked();
                }
                reservations.add(Objects.requireNonNull(reserved.reservation()));
                remaining -= portion;
            } finally {
                context.unlock();
            }
            if (remaining == 0) {
                return ProcessResult.SUCCESS;
            }
        }
        return visited.isEmpty() ? RecipeText.NoEnergyInput.blocked(id)
            : RecipeText.EnergyMissing.blocked(id, Long.toString(remaining));
    }

    private static ProcessResult reserveOutput(MachineHatches hatches, long amount, boolean voidExcess, String id,
                                               List<ResourceReservation> reservations) {
        if (amount == 0) {
            return ProcessResult.SUCCESS;
        }
        long remaining = amount;
        var visited = new ReferenceOpenHashSet<StorageContext>();
        for (var target : hatches.energyOutputs()) {
            var context = target.context();
            if (!target.isValid() || !visited.add(context)) {
                continue;
            }
            context.lock();
            try {
                if (!target.isValid()) {
                    return RecipeText.StorageChanged.blocked();
                }
                var snapshot = target.energySnapshot();
                long portion = Math.min(remaining, Math.min(snapshot.availableCapacity(), snapshot.maxReceive()));
                if (portion == 0) {
                    continue;
                }
                var reserved = target.reserveReceiveEnergy(snapshot, portion);
                if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                    return RecipeText.StorageChanged.blocked();
                }
                reservations.add(Objects.requireNonNull(reserved.reservation()));
                remaining -= portion;
            } finally {
                context.unlock();
            }
            if (remaining == 0) {
                return ProcessResult.SUCCESS;
            }
        }
        if (visited.isEmpty()) {
            return RecipeText.NoEnergyOutput.blocked(id);
        }
        return voidExcess ? ProcessResult.SUCCESS : RecipeText.EnergyOutputFull.blocked(id, Long.toString(remaining));
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The owner binding intentionally retains its captured callback scope.
    public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement,
                                          RecipeStage stage) {
        var payload = requirement.payload();
        var timing = process.crossRecipeTiming();
        boolean batched = timing != null && timing.duration() > 0 && process.requirementPath().parent() == null;
        if (batched && stage != RecipeStage.TICK) return null;
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
        if (!batched && input == 0 && output == 0) {
            return null;
        }
        var hatches = process.hatches();
        var parallelCapture = process.attributes().beginValueCapture(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        var device = process.owner().networkDevice();
        double networkEnergy = device == null ? 1 : device.energyMultiplier();
        var offsets = batched ? Objects.requireNonNull(timing.energyOffsets().get(process.requirementPath())) : null;
        var tick = batched ? process.tickContext() : null;
        int originalDuration = process.recipe().durationTicks();
        return new RequirementPreparation() {
            @Nullable
            private EnergyQuantities quantities;
            private int direction;
            private int index;
            private long remaining = -1;
            private ReferenceOpenHashSet<StorageContext> visited = new ReferenceOpenHashSet<>();
            @Nullable
            private EnergyAllocationCursor allocation;

            @Override
            public boolean ownerWorkMetered() {
                return true;
            }

            @Override
            public @Nullable RecipePureComputation computation() {
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
                var reservations = new ObjectArrayList<ResourceReservation>(6);
                var budget = process.preparationBudget();
                if (!parallelCapture.current())
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), reservations);
                if (quantities == null) {
                    if (!parallelCapture.advance(budget)) return paused(reservations);
                    quantities = new EnergyQuantities(input, output, Math.max(1, (int) Math.floor(parallelCapture.result())), networkEnergy);
                    if (batched)
                        quantities.batch = new BatchEnergy(Objects.requireNonNull(timing), Objects.requireNonNull(offsets),
                            payload, originalDuration, Objects.requireNonNull(tick));
                    return paused(reservations);
                }
                if (!quantities.complete || process.hatches() != hatches) {
                    return new ReservedRequirement(RecipeText.StorageChanged.blocked(), reservations);
                }
                String id = requirement.id().toString();
                if (remaining < 0) {
                    remaining = quantities.input;
                }
                try {
                    while (direction < 2) {
                        if (direction == 0 && remaining > 0 && payload.inputAllocation() != InputAllocation.SPLITTABLE) {
                            if (allocation == null)
                                allocation = new EnergyAllocationCursor(hatches.energyInputs(), remaining, payload.inputAllocation(), stage, id);
                            if (!allocation.advance(budget, reservations)) return paused(reservations);
                            if (allocation.result != ProcessResult.SUCCESS)
                                return new ReservedRequirement(allocation.result, reservations);
                            remaining = 0;
                        }
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
                    if (process.incrementalAcquisition()) {
                        @SuppressWarnings("UnnecessaryLocalVariable") // The captured cleanup owns this exact reservation list.
                        var retained = reservations;
                        var cleanup = process.capturePreparedCleanup(new RequirementTransaction() {
                            @Override
                            public ProcessResult result() {
                                return ProcessResult.SUCCESS;
                            }

                            @Override
                            public List<ResourceReservation> reservations() {
                                return retained;
                            }

                            @Override
                            public void commit() {
                            }

                            @Override
                            public void rollback() {
                            }
                        });
                        cleanup.reject();
                        throw failure;
                    }
                    ReservedRequirement.rollbackAfterFailure(reservations, failure);
                    throw failure;
                }
            }
        };
    }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.START, requirement.payload().input(), 0);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.TICK, requirement.payload().inputPerTick(), requirement.payload().outputPerTick());
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<EnergyRequirement> requirement) {
        return acquire(process, requirement, RecipeStage.END, 0, requirement.payload().output());
    }

    /**
     * Source capture and exact reservation stay on the original owner and original stage.
     */
    private static final class EnergyAllocationCursor {
        private final List<? extends EnergyInput> candidates;
        private final long amount;
        private final InputAllocation allocation;
        private final RecipeStage stage;
        private final String id;
        private final ReferenceOpenHashSet<StorageContext> seen = new ReferenceOpenHashSet<>();
        private final ObjectArrayList<EnergyInput> sources = new ObjectArrayList<>();
        private final ObjectArrayList<EnergySnapshot> snapshots = new ObjectArrayList<>();
        private final ObjectArrayList<InputAllocation.Source> facts = new ObjectArrayList<>();
        private int candidate;
        private int verified;
        private int source;
        @Nullable
        private InputAllocationPlan plan;
        private ProcessResult result = ProcessResult.SUCCESS;

        private EnergyAllocationCursor(List<? extends EnergyInput> candidates, long amount, InputAllocation allocation,
                                       RecipeStage stage, String id) {
            this.candidates = candidates;
            this.amount = amount;
            this.allocation = allocation;
            this.stage = stage;
            this.id = id;
        }

        private boolean advance(RecipeWorkBudget budget, List<ResourceReservation> reservations) {
            while (candidate < candidates.size()) {
                if (!budget.take()) return false;
                var input = candidates.get(candidate++);
                var context = input.context();
                if (!input.isValid() || !seen.add(context)) continue;
                context.lock();
                try {
                    if (!input.isValid()) return changed();
                    var snapshot = input.energySnapshot();
                    if (snapshot.context() != context || !context.current(snapshot.revision())) return changed();
                    facts.add(new InputAllocation.Source(sources.size(), snapshot.revision(),
                        Math.min(snapshot.availableEnergy(), snapshot.maxExtract()), InputAllocationPlan.NO_RESOURCES,
                        snapshot.availableEnergy(), snapshot.maxExtract()));
                    sources.add(input);
                    snapshots.add(snapshot);
                } finally {
                    context.unlock();
                }
            }
            if (sources.isEmpty()) {
                result = RecipeText.NoEnergyInput.blocked(id);
                return true;
            }
            if (plan == null) plan = new InputAllocationPlan(allocation, stage, 0, null, amount, facts);
            if (!plan.advance(budget)) return false;
            if (plan.missing() != 0) {
                result = RecipeText.EnergyMissing.blocked(id, Long.toString(plan.missing()));
                return true;
            }
            while (verified < sources.size()) {
                if (!budget.take()) return false;
                var input = sources.get(verified);
                var snapshot = snapshots.get(verified++);
                var context = input.context();
                context.lock();
                try {
                    if (!input.isValid() || !context.current(snapshot.revision())) return changed();
                } finally {
                    context.unlock();
                }
            }
            while (source < sources.size()) {
                if (!budget.take()) return false;
                int index = source++;
                long assigned = plan.amount(index);
                if (assigned == 0) continue;
                var input = sources.get(index);
                var snapshot = snapshots.get(index);
                var context = input.context();
                context.lock();
                try {
                    if (!input.isValid() || !context.current(snapshot.revision())) return changed();
                    var reserved = input.reserveExtractEnergy(snapshot, assigned);
                    if (reserved.status() != ReservationResult.Status.ACCEPTED) return changed();
                    reservations.add(Objects.requireNonNull(reserved.reservation()));
                } finally {
                    context.unlock();
                }
            }
            return true;
        }

        private boolean changed() {
            result = RecipeText.StorageChanged.blocked();
            return true;
        }
    }

    private static class EnergyQuantities implements RecipePureComputation {
        private final double networkEnergy;
        private final int parallelism;
        private long input;
        private long output;
        private boolean complete;
        @Nullable
        private BatchEnergy batch;

        private EnergyQuantities(long input, long output, int parallelism, double networkEnergy) {
            this.networkEnergy = networkEnergy;
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
            if (batch == null) {
                input = RecipeParallelism.scaleCount(input, parallelism);
                output = RecipeParallelism.scaleCount(output, parallelism);
            } else {
                input = batch.timing.portion(CrossRecipeBatch.energyTotal(batch.payload.input(), batch.payload.inputPerTick(),
                    batch.originalDuration, batch.timing.parallelism()), batch.offsets.input(), batch.tick);
                output = batch.timing.portion(CrossRecipeBatch.energyTotal(batch.payload.output(), batch.payload.outputPerTick(),
                    batch.originalDuration, batch.timing.parallelism()), batch.offsets.output(), batch.tick);
            }
            input = NetworkResourceState.energy(input, networkEnergy);
            complete = true;
            return true;
        }
    }

    private record BatchEnergy(CrossRecipeBatch.Timing timing, CrossRecipeBatch.EnergyOffsets offsets,
                               EnergyRequirement payload, int originalDuration, RecipeProcess.Tick tick) {
    }
}
