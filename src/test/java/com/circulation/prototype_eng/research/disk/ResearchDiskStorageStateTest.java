package com.circulation.prototype_eng.research.disk;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import com.circulation.prototype_eng.research.ResearchEligibility;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchDiskStorageStateTest {
    private static final UUID ACTOR = new UUID(0, 1);
    private static final UUID DATABASE = new UUID(0, 2);
    private static final UUID OPERATION = new UUID(0, 3);
    private static final ResourceLocation LOOP = id("loop");
    private static final ResourceLocation OTHER = id("other");
    private static final ResourceLocation BLOCKER = id("blocker");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("disk_state_test", path);
    }

    private static ResearchRegistry registry() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        for (var id : Set.of(LOOP, OTHER, BLOCKER)) {
            var stage = ResearchLevelStage.builder(1, 10).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
            if (id.equals(BLOCKER)) stage.mutex(LOOP, 1);
            if (id.equals(LOOP)) stage.mutex(BLOCKER, 1);
            registry.register(ResearchDefinition.builder(id).maxLevel(10).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                .stage(stage.build()).build());
        }
        registry.freeze(resources);
        return registry;
    }

    private static TeamResearchView cloud(ResearchRegistry registry, PlayerResearchState player) {
        return new ResearchEligibility(registry).calculateStructural("team", Set.of(ACTOR), Map.of(ACTOR, player),
            Object2IntMaps.emptyMap(), Set.of());
    }

    private static ResearchDiskCommit prepare(ResearchDataCenter storage, ResearchRegistry registry, TeamResearchView cloud) {
        var data = new ResearchDiskData(registry, 1, Object2IntMaps.singleton(LOOP, 5));
        return Objects.requireNonNull(ResearchDiskImport.prepare(OPERATION, ACTOR, "team", data, storage.snapshot(), cloud).commit());
    }

    @Test
    void dirtyFailureRetainsOneAtomicContentsAndReceiptStateAndLaterDownloadsPreserveIt() {
        var registry = registry();
        var player = new PlayerResearchState(ACTOR);
        var failure = new AtomicReference<RuntimeException>(new IllegalStateException("dirty failed after publication"));
        var storage = new ResearchDataCenter(DATABASE, 1, 3, registry, () -> { }, () -> {
            if (failure.get() != null) throw failure.get();
        });
        var commit = prepare(storage, registry, cloud(registry, player));
        var before = storage.capture();
        assertSame(failure.get(), assertThrows(IllegalStateException.class, () -> storage.commitDisk(commit, cloud(registry, player), before.current())));
        assertFalse(before.current().getAsBoolean());
        assertEquals(5, storage.snapshot().storedResearch().getInt(LOOP));
        assertEquals(commit, storage.receipt(OPERATION));
        failure.set(null);
        player.completeAtLeast(LOOP, 7);
        player.completeAtLeast(OTHER, 1);
        assertEquals(DataCenterStorage.Result.UPDATED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertEquals(DataCenterStorage.Result.UPDATED, storage.downloadResearch(OTHER, 1, cloud(registry, player)));
        var restored = ResearchDataCenter.restore(DataCenterStorageNbt.decodeState(DataCenterStorageNbt.encodeState(storage.persistentState()), registry),
            registry, () -> { }, () -> { });
        assertEquals(commit, restored.receipt(OPERATION));
        assertEquals(7, restored.snapshot().storedResearch().getInt(LOOP));
        assertEquals(1, restored.snapshot().storedResearch().getInt(OTHER));
        var replayState = restored.persistentState();
        assertEquals(DataCenterStorage.Result.UNCHANGED, restored.commitDisk(commit, cloud(registry, player), () -> false));
        assertSame(replayState, restored.persistentState());
    }

    @Test
    void mutexAndFinalAuthorityRejectionsPublishNeitherBatchNorReceipt() {
        var registry = registry();
        var player = new PlayerResearchState(ACTOR);
        var storage = new ResearchDataCenter(DATABASE, 1, 3, registry, () -> { }, () -> { });
        var commit = prepare(storage, registry, cloud(registry, player));
        var before = storage.capture();
        player.completeAtLeast(BLOCKER, 1);
        assertEquals(DataCenterStorage.Result.MUTEX_BLOCKED, storage.commitDisk(commit, cloud(registry, player), before.current()));
        assertTrue(before.current().getAsBoolean());
        assertNull(storage.receipt(OPERATION));
        assertTrue(storage.snapshot().storedResearch().isEmpty());
        var empty = new PlayerResearchState(ACTOR);
        var checks = new AtomicInteger();
        assertEquals(DataCenterStorage.Result.STALE_CONTEXT, storage.commitDisk(commit, cloud(registry, empty), () -> checks.incrementAndGet() == 1));
        assertTrue(before.current().getAsBoolean());
        assertNull(storage.receipt(OPERATION));
    }

    @Test
    void unchangedContentsStillPublishReceiptAndRejectConflictingOperationIdentity() {
        var registry = registry();
        var player = new PlayerResearchState(ACTOR);
        var storage = new ResearchDataCenter(DATABASE, 1, 3, registry, () -> { }, () -> { });
        storage.storeFromDisk(Object2IntMaps.singleton(LOOP, 5), cloud(registry, player));
        var commit = prepare(storage, registry, cloud(registry, player));
        var before = storage.capture();
        assertEquals(DataCenterStorage.Result.UPDATED, storage.commitDisk(commit, cloud(registry, player), before.current()));
        assertFalse(before.current().getAsBoolean());
        var conflict = new ResearchDiskCommit(OPERATION, new UUID(0, 9), "team", commit.diskTier(), commit.entries(), commit.before(), commit.after());
        assertThrows(IllegalStateException.class, () -> storage.commitDisk(conflict, cloud(registry, player), () -> true));
        assertEquals(commit, storage.receipt(OPERATION));
        assertThrows(IllegalStateException.class, () -> storage.removeDiskReceipt(conflict, () -> true));
        assertEquals(commit, storage.receipt(OPERATION));
    }

    @Test
    void receiptCleanupFailureCannotRewindLaterResearchAndMissingCleanupIsIdempotent() {
        var registry = registry();
        var player = new PlayerResearchState(ACTOR);
        var failure = new AtomicReference<RuntimeException>();
        var storage = new ResearchDataCenter(DATABASE, 1, 3, registry, () -> { }, () -> {
            if (failure.get() != null) throw failure.get();
        });
        var commit = prepare(storage, registry, cloud(registry, player));
        storage.commitDisk(commit, cloud(registry, player), () -> true);
        var later = new Object2IntOpenHashMap<>(storage.snapshot().storedResearch());
        later.put(OTHER, 1);
        storage.storeFromDisk(later, cloud(registry, player));
        failure.set(new IllegalStateException("dirty failed after cleanup"));
        assertSame(failure.get(), assertThrows(IllegalStateException.class, () -> storage.removeDiskReceipt(commit, () -> true)));
        assertNull(storage.receipt(OPERATION));
        assertEquals(later, storage.snapshot().storedResearch());
        var state = storage.persistentState();
        failure.set(null);
        storage.removeDiskReceipt(commit, () -> true);
        assertSame(state, storage.persistentState());
    }
}
