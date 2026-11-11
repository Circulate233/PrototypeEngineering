package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.localization.RecipeText;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PN research gating and parallel numeric occupancy share the real stage's commit and compensation.
 */
public final class NetworkRecipeWorkSystem implements RecipeRequirementSystem<NetworkRecipeWork.Binding> {
    @Override
    public boolean requiresOwnerThread() {
        return true;
    }

    private static RequirementTransaction acquire(RecipeProcess process, NetworkRecipeWork.Binding binding,
                                                  boolean occupying, boolean checkCapacity) {
        var device = process.owner().components().get(binding.device());
        var permit = device == null ? null : device.workPermit();
        if (permit == null) {
            return rejected(RecipeText.NetworkUnavailable.blocked());
        }
        var dataCenter = permit.dataCenterResearch();
        for (var entry : binding.requirements().researchRequirements().object2IntEntrySet()) {
            if (dataCenter == null || dataCenter.storedResearch().getInt(entry.getKey()) < entry.getIntValue()) {
                return rejected(RecipeText.NetworkResearchMissing.blocked(entry.getKey().toString(), Integer.toString(entry.getIntValue())));
            }
        }
        if (!checkCapacity) {
            return new WorkTransaction(permit, null);
        }
        int parallelism = RecipeParallelism.effective(process);
        for (long amount : binding.requirements().resources().values()) {
            if (amount > Long.MAX_VALUE / parallelism) {
                return rejected(RecipeText.NetworkCapacityMissing.blocked());
            }
        }
        var required = binding.requirements().scaledResources(parallelism);
        // Probes may inspect every candidate/parallelism, but must not consume leases or operation ids.
        if (process.probing() || !occupying) {
            return permit.canReserve(required) ? new WorkTransaction(permit, null) : rejected(RecipeText.NetworkCapacityMissing.blocked());
        }
        var receipt = permit.reserve(binding.sourceId(), required);
        return receipt == null ? rejected(RecipeText.NetworkCapacityMissing.blocked()) : new WorkTransaction(permit, receipt);
    }

    private static RequirementTransaction rejected(ProcessResult result) {
        return new ReservedRequirement(result, List.of());
    }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<NetworkRecipeWork.Binding> requirement) {
        return acquire(process, requirement.payload(), process.recipe().durationTicks() == 0, true);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<NetworkRecipeWork.Binding> requirement) {
        return acquire(process, requirement.payload(), true, true);
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<NetworkRecipeWork.Binding> requirement) {
        return acquire(process, requirement.payload(), false, false);
    }

    private static final class WorkTransaction implements RequirementTransaction {
        private final NetworkRuntime.WorkPermit permit;
        @Nullable
        private final NetworkTeamBudget.Reservation receipt;
        private Resolution resolution = Resolution.REVERSIBLE;

        private WorkTransaction(NetworkRuntime.WorkPermit permit, @Nullable NetworkTeamBudget.Reservation receipt) {
            this.permit = permit;
            this.receipt = receipt;
        }

        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public boolean prepareCommit() {
            return receipt == null ? permit.authorized() : receipt.commit();
        }

        @Override
        public void commit() {
        }

        @Override
        public boolean validateCommit() {
            return permit.authorized();
        }

        @Override
        public void complete() {
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            if (receipt != null) {
                receipt.rollback();
            }
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }
}
