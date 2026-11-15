package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

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
        return evaluate(data, target, cloud).result();
    }

    private static Candidate evaluate(ResearchDiskData data, DataCenterResearchSnapshot target, TeamResearchView cloud) {
        data.requireRegistry(cloud.registry());
        if (data.tier() > target.level()) {
            return new Candidate(new Result(Status.DISK_TIER_TOO_HIGH, null), target);
        }
        var replacement = new Object2IntOpenHashMap<>(target.storedResearch());
        var result = ResearchDataCenter.storeFromDisk(data.entries(), target, cloud, replacement);
        var after = result == DataCenterStorage.Result.UPDATED
            ? new DataCenterResearchSnapshot(target.dataCenterId(), target.level(), target.capacity(), 0, replacement) : target;
        return new Candidate(new Result(result.accepted() ? Status.ACCEPTED : Status.DATA_CENTER_REJECTED, result), after);
    }

    /**
     * Freeze the exact before/after batch without writing a live database or issuing item effects.
     */
    public static Preparation prepare(UUID operationId, UUID actor, String teamId, ResearchDiskData data,
                                      DataCenterResearchSnapshot target, TeamResearchView cloud) {
        var candidate = evaluate(data, target, cloud);
        var result = candidate.result();
        if (!result.accepted()) return new Preparation(result, null);
        if (!teamId.equals(cloud.teamId()))
            throw new IllegalArgumentException("Disk import belongs to another team");
        return new Preparation(result, new ResearchDiskCommit(operationId, actor, teamId, data.tier(), data.entries(),
            savedContents(target), savedContents(candidate.contents())));
    }

    private static DataCenterResearchSnapshot savedContents(DataCenterResearchSnapshot contents) {
        return contents.revision() == 0 ? contents : new DataCenterResearchSnapshot(contents.dataCenterId(),
            contents.level(), contents.capacity(), 0, contents.storedResearch());
    }

    public enum Status {ACCEPTED, DISK_TIER_TOO_HIGH, DATA_CENTER_REJECTED}

    private record Candidate(Result result, DataCenterResearchSnapshot contents) {
    }

    public record Preparation(Result result, @Nullable ResearchDiskCommit commit) {
    }

    /**
     * Backend details are absent only when disk tier alone prevents the database precheck.
     */
    public record Result(Status status, @Nullable DataCenterStorage.Result storageResult) {
        public boolean accepted() {
            return status == Status.ACCEPTED;
        }
    }
}
