package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.util.ResourceLocation;

import java.util.UUID;

/**
 * One immutable world checkpoint, including every offline player source needed for recovery.
 * Collections are completed snapshots borrowed unchanged for this checkpoint's lifetime, including
 * pending save retries. Producers finish populating them before handoff; consumers never mutate them.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players,
                                    ObjectList<Team> teams,
                                    ObjectList<ResearchDownloadOperation> downloads,
                                    ObjectList<ResearchDownloadReceipt> downloadReceipts,
                                    ObjectList<ResearchDiskOperation> diskImports) {
    public ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players, ObjectList<Team> teams) {
        this(serverTime, players, teams, ObjectList.of(), ObjectList.of(), ObjectList.of());
    }

    /**
     * Host checkpoint without disk obligations.
     */
    public ResearchWorldSnapshot(long serverTime, ObjectList<PlayerResearchState.Snapshot> players, ObjectList<Team> teams,
                                 ObjectList<ResearchDownloadOperation> downloads, ObjectList<ResearchDownloadReceipt> downloadReceipts) {
        this(serverTime, players, teams, downloads, downloadReceipts, ObjectList.of());
    }


    /**
     * knownMembers is retained history, so a former member may also be known by another team.
     */
    public record Team(String teamId, ObjectSet<UUID> knownMembers, ObjectSet<ResourceLocation> blacklist,
                       TeamOwnedResearch.Snapshot research, TeamResearchQueue.Snapshot queue,
                       ObjectList<ManualResearchSession> manualSessions) {

    }
}
