package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Action;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.view.DataCenterPlayerView;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Physical database operations at an exclusive owner boundary, sharing the current tick's network budget.
 */
public final class DataCenterResearchAccess {
    private final ResearchWorld world;
    private final NetworkRuntime network;
    private final ResearchDataCenters centers;
    private boolean executing;

    public DataCenterResearchAccess(ResearchWorld world, NetworkRuntime network, ResearchDataCenters centers) {
        this.world = Objects.requireNonNull(world);
        this.network = Objects.requireNonNull(network);
        this.centers = Objects.requireNonNull(centers);
    }

    private static Result result(Status status) {
        return new Result(status, null);
    }

    public Result download(OwnerTeamResolution membership, ResearchPermissionContext permission,
                           UUID expectedDataCenterId, ResourceLocation researchId, int level, BooleanSupplier current) {
        Objects.requireNonNull(expectedDataCenterId);
        Objects.requireNonNull(researchId);
        Objects.requireNonNull(current);
        enter();
        try {
            if (world.savePending()) return result(Status.SAVE_PENDING);
            var cloud = world.captureCloud(membership, permission, Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, current);
            if (cloud == null) return result(Status.STALE_CONTEXT);
            if (cloud.decision() != Decision.ALLOWED) return result(Status.PERMISSION_DENIED);
            var physical = centers.capture(membership.teamId(), expectedDataCenterId);
            if (physical == null) return result(Status.NO_ACTIVE_DATA_CENTER);
            return publish(physical, () -> physical.storage().downloadResearch(researchId, level,
                Objects.requireNonNull(cloud.view()), () -> !world.savePending() && cloud.current().getAsBoolean() && physical.current()));
        } finally {
            executing = false;
        }
    }

    /**
     * No player permission or source mutation is involved in the automatic upgrade of stored research.
     */
    public Result synchronize(OwnerTeamResolution membership, BooleanSupplier current) {
        Objects.requireNonNull(current);
        enter();
        try {
            if (world.savePending()) return result(Status.SAVE_PENDING);
            var cloud = world.captureCloud(membership, current);
            if (cloud == null) return result(Status.STALE_CONTEXT);
            var physical = selected(membership.teamId());
            if (physical == null) return result(Status.NO_ACTIVE_DATA_CENTER);
            return publish(physical, () -> physical.storage().synchronizeLoopingResearch(Objects.requireNonNull(cloud.view()),
                () -> !world.savePending() && cloud.current().getAsBoolean() && physical.current()));
        } finally {
            executing = false;
        }
    }

    /**
     * Separate state and download permissions; viewing never invokes a research start condition or saves.
     */
    @Nullable
    public DataCenterPlayerView view(OwnerTeamResolution membership, ResearchPermissionContext permission,
                                     BooleanSupplier current) {
        enter();
        try {
            var cloud = world.captureCloud(membership, permission, Action.REQUEST_DATA_CENTER_STATE, current);
            if (cloud == null) return null;
            var download = world.captureCloud(membership, permission, Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, current);
            if (download == null) return null;
            if (cloud.decision() != Decision.ALLOWED) {
                return new DataCenterPlayerView(membership.teamId(), cloud.decision(), download.decision(), world.savePending(), null, null);
            }
            var physical = selected(membership.teamId());
            if (!cloud.current().getAsBoolean() || physical != null && !physical.current()) return null;
            return new DataCenterPlayerView(membership.teamId(), Decision.ALLOWED, download.decision(), world.savePending(),
                physical == null ? null : physical.snapshot(), cloud.view());
        } finally {
            executing = false;
        }
    }

    @Nullable
    private ResearchDataCenters.Capture selected(String teamId) {
        var plan = network.plan();
        var team = plan == null ? null : plan.teams().get(teamId);
        return team == null || team.dataCenterId() == null ? null : centers.capture(teamId, team.dataCenterId());
    }

    private Result publish(ResearchDataCenters.Capture physical, Supplier<DataCenterStorage.Result> operation) {
        try {
            var result = Objects.requireNonNull(operation.get());
            // A dirty callback can fail after contents changed. Never restore the old publication or database.
            if (result == DataCenterStorage.Result.UPDATED) network.refresh();
            else if (!physical.current()) network.revoke();
            return new Result(Status.COMPLETED, result);
        } catch (RuntimeException | Error failure) {
            network.revoke();
            throw failure;
        }
    }

    private void enter() {
        world.savePending(); // Verifies the world's owner thread before observing the local reentry guard.
        if (executing) throw new IllegalStateException("Database access cannot be reentered");
        executing = true;
    }

    public enum Status {COMPLETED, PERMISSION_DENIED, NO_ACTIVE_DATA_CENTER, SAVE_PENDING, STALE_CONTEXT}

    public record Result(Status status, @Nullable DataCenterStorage.Result storageResult) {
        public Result {
            Objects.requireNonNull(status);
            if ((status == Status.COMPLETED) == (storageResult == null)) {
                throw new IllegalArgumentException("Completed database operations require their storage result");
            }
        }
    }
}
