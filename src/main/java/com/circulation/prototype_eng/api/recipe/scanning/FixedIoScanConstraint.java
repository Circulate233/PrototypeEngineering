package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * PM's fixed TICK/END admission checks using the same batch acquisition as actual execution.
 * These are independent observations of current balances, not a future lifecycle simulation.
 * Dynamic requirements and process callbacks are left to the authoritative START and later ticks.
 * An earlier input may free a shared output context, so that later stage is not rejected based on
 * its present capacity. Normal mixed hatches have independent input/output buffers and are checked.
 */
public final class FixedIoScanConstraint implements RecipeParallelismConstraint {
    @Override
    public ProcessResult test(RecipeProcess process) {
        process.requirePersistenceBoundary();
        for (var binding : process.components().snapshotBindings()) {
            if (!binding.type().systems().isEmpty()) { return ProcessResult.SUCCESS; }
        }
        var resources = Io.EMPTY;
        var energy = Io.EMPTY;
        var overlays = process.components().get(RecipeOverlays.TYPE);
        for (var requirement : process.orderedRequirements()) {
            var effective = overlays == null ? requirement : overlays.resolve(requirement);
            if (requirement.type() == RecipeRequirements.RESOURCES && effective.payload() instanceof ResourceRequirement payload) {
                if (!payload.chance().equals(ResourceChance.ALWAYS) || payload.randomOutputs() != null
                    || !payload.fuzzyInputs().isEmpty() || !payload.dynamicInputs().isEmpty()) {
                    return ProcessResult.SUCCESS;
                }
                resources = resources.merge(new Io(!payload.inputs().isEmpty(), !payload.inputsPerTick().isEmpty(),
                    !payload.outputsPerTick().isEmpty(), !payload.outputs().isEmpty()));
            } else if (requirement.type() == RecipeRequirements.ENERGY && effective.payload() instanceof EnergyRequirement payload) {
                energy = energy.merge(new Io(payload.input() > 0, payload.inputPerTick() > 0,
                    payload.outputPerTick() > 0, payload.output() > 0));
            } else {
                // Earlier requirements may change later IO, attributes or process-local state.
                return ProcessResult.SUCCESS;
            }
        }
        boolean ticks = process.recipe().durationTicks() > 0;
        boolean checkTick = ticks && (resources.needsTick() || energy.needsTick());
        boolean checkEnd = resources.endOutput || energy.endOutput;
        if (!checkTick && !checkEnd) { return ProcessResult.SUCCESS; }
        var hatches = MachineHatches.get(process.owner());
        boolean sharedResources = resources.mayFreeOutput(ticks) && sharedResourceContext(hatches);
        boolean sharedEnergy = energy.mayFreeOutput(ticks) && sharedEnergyContext(hatches);
        if (checkTick && !resources.freesTickOutput(sharedResources) && !energy.freesTickOutput(sharedEnergy)) {
            var result = RecipeStageExecutor.probeStage(process, RecipeStage.TICK);
            if (result != ProcessResult.SUCCESS) { return result; }
        }
        if (checkEnd && !resources.freesEndOutput(sharedResources, ticks) && !energy.freesEndOutput(sharedEnergy, ticks)) {
            return RecipeStageExecutor.probeStage(process, RecipeStage.END);
        }
        return ProcessResult.SUCCESS;
    }

    private static boolean sharedResourceContext(MachineHatches hatches) {
        var sources = new ReferenceOpenHashSet<StorageContext>();
        for (var hatch : hatches.resources()) {
            if (!hatch.isValid()) { continue; }
            var input = hatch.input();
            if (input != null && input.isValid()) { sources.add(input.context()); }
        }
        for (var hatch : hatches.resources()) {
            if (!hatch.isValid()) { continue; }
            var output = hatch.output();
            // All AEKey types can compete for the same physical slots.
            if (output != null && output.isValid() && sources.contains(output.context())) { return true; }
        }
        return false;
    }

    private static boolean sharedEnergyContext(MachineHatches hatches) {
        var sources = new ReferenceOpenHashSet<StorageContext>();
        for (var input : hatches.energyInputs()) {
            if (input.isValid()) { sources.add(input.context()); }
        }
        for (var output : hatches.energyOutputs()) {
            if (output.isValid() && sources.contains(output.context())) { return true; }
        }
        return false;
    }

    private record Io(boolean startInput, boolean tickInput, boolean tickOutput, boolean endOutput) {
        private static final Io EMPTY = new Io(false, false, false, false);

        private Io merge(Io other) {
            return new Io(startInput || other.startInput, tickInput || other.tickInput,
                tickOutput || other.tickOutput, endOutput || other.endOutput);
        }

        private boolean needsTick() { return tickInput || tickOutput; }
        private boolean mayFreeOutput(boolean ticks) { return (startInput || ticks && tickInput) && (tickOutput || endOutput); }
        private boolean freesTickOutput(boolean shared) { return shared && startInput && tickOutput; }
        private boolean freesEndOutput(boolean shared, boolean ticks) { return shared && endOutput && (startInput || ticks && tickInput); }
    }
}
