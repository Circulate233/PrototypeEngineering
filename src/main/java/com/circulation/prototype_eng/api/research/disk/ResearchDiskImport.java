package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pure rules precheck against a detached physical database snapshot. It does not authorize a cloud
 * view, write a live backend, consume the disk or prove a durable transfer. The caller must keep
 * database and item ownership current and jointly persist their eventual transfer.
 */
@SuppressWarnings("unused") // Public preflight boundary for later disk import consumers.
public final class ResearchDiskImport {
    private ResearchDiskImport() {
    }

    public static Result precheck(ResearchDiskData data, DataCenterResearchSnapshot target, TeamResearchView cloud) {
        Objects.requireNonNull(data, "Research disk data");
        Objects.requireNonNull(target, "Target database snapshot");
        data.requireRegistry(Objects.requireNonNull(cloud, "Research cloud").registry());
        if (data.tier() > target.level()) {
            return new Result(Status.DISK_TIER_TOO_HIGH, null);
        }
        // Reuse the actual backend's whole-batch capacity and mutex rules without publishing to it.
        var candidate = ResearchDataCenter.restore(target, cloud.registry(), () -> {
        }, () -> {
        });
        var result = candidate.storeFromDisk(data.entries(), cloud);
        return new Result(result.accepted() ? Status.ACCEPTED : Status.DATA_CENTER_REJECTED, result);
    }

    public enum Status {ACCEPTED, DISK_TIER_TOO_HIGH, DATA_CENTER_REJECTED}

    /**
     * Backend details are absent only when disk tier alone prevents the database precheck.
     */
    public record Result(Status status, @Nullable DataCenterStorage.Result storageResult) {
        public Result {
            Objects.requireNonNull(status);
            if (status == Status.DISK_TIER_TOO_HIGH ? storageResult != null
                : storageResult == null || (status == Status.ACCEPTED) != storageResult.accepted()) {
                throw new IllegalArgumentException("Disk precheck status differs from its database result");
            }
        }

        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }
}
