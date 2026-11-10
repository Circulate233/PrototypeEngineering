package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchDataCenterTest {
    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        for (var definition : definitions) {
            registry.register(definition);
        }
        registry.freeze(resources);
        return registry;
    }

    private static TeamResearchView cloud(ResearchRegistry registry, PlayerResearchState player) {
        return new ResearchEligibility(registry).calculateStructural("team", List.of(player.playerUuid()),
            Map.of(player.playerUuid(), player), Object2IntMaps.emptyMap(), Set.of());
    }

    private static ResearchDefinition ordinary(String name, int max, int tier) {
        return definition(name, max).stage(stage(max, tier).build()).build();
    }

    private static ResearchDefinition.Builder definition(String name, int max) {
        return ResearchDefinition.builder(id(name)).maxLevel(max).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
    }

    private static ResearchLevelStage.Builder stage(int last, int tier) {
        return ResearchLevelStage.builder(1, last).researchTier(tier).resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static Object2IntOpenHashMap<ResourceLocation> levels(String name) {
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        levels.put(id(name), 1);
        return levels;
    }

    private static Object2IntOpenHashMap<ResourceLocation> levels(String first, String second) {
        var levels = levels(first);
        levels.put(id(second), 1);
        return levels;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("data_center_test", name);
    }

    @Test
    void losingAuthorityAtPublicationLeavesDownloadsDiskWritesAndSyncUnchanged() {
        var registry = registry(ordinary("loop", 3, 1), ordinary("other", 1, 1));
        var player = new PlayerResearchState(new UUID(0, 1));
        player.completeAtLeast(id("loop"), 1);
        player.completeAtLeast(id("other"), 1);
        int[] dirty = {0};
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 3, registry, () -> {
        }, () -> dirty[0]++);
        storage.downloadResearch(id("loop"), 1, cloud(registry, player));
        player.completeAtLeast(id("loop"), 3);
        var view = cloud(registry, player);
        var before = storage.capture();
        int[] checks = {0};
        assertEquals(DataCenterStorage.Result.STALE_CONTEXT, storage.downloadResearch(id("other"), 1, view, () -> ++checks[0] == 1));
        checks[0] = 0;
        assertEquals(DataCenterStorage.Result.STALE_CONTEXT, storage.storeFromDisk(levels("other"), view, () -> ++checks[0] == 1));
        checks[0] = 0;
        assertEquals(DataCenterStorage.Result.STALE_CONTEXT, storage.synchronizeLoopingResearch(view, () -> ++checks[0] == 1));
        assertTrue(before.current().getAsBoolean());
        assertEquals(1, storage.snapshot().storedResearch().getInt(id("loop")));
        assertFalse(storage.snapshot().storedResearch().containsKey(id("other")));
        assertEquals(1, dirty[0]);
        var failure = new IllegalStateException("authority provider failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> storage.downloadResearch(id("other"), 1, view, () -> {
                throw failure;
            })));
        assertTrue(before.current().getAsBoolean());
    }

    @Test
    void consecutiveDownloadsNeedFreshContentProofAndPreserveEarlierCommits() {
        var registry = registry(ordinary("first", 1, 1), ordinary("second", 1, 1));
        var player = new PlayerResearchState(new UUID(0, 1));
        player.completeAtLeast(id("first"), 1);
        player.completeAtLeast(id("second"), 1);
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 2, registry, () -> {
        }, () -> {
        });
        var view = cloud(registry, player);
        var original = storage.capture();
        assertEquals(DataCenterStorage.Result.UPDATED, storage.downloadResearch(id("first"), 1, view, original.current()));
        assertEquals(DataCenterStorage.Result.STALE_CONTEXT, storage.downloadResearch(id("second"), 1, view, original.current()));
        var fresh = storage.capture();
        assertEquals(DataCenterStorage.Result.UPDATED, storage.downloadResearch(id("second"), 1, view, fresh.current()));
        assertEquals(levels("first", "second"), storage.snapshot().storedResearch());
    }

    @Test
    void cloudCompletionRequiresAnExplicitDownloadAndOnlyExistingLoopingResearchSynchronizes() {
        var registry = registry(ordinary("loop", 3, 1), ordinary("other", 1, 1), ordinary("too_high", 1, 2));
        var player = new PlayerResearchState(new UUID(0, 1));
        player.completeAtLeast(id("loop"), 1);
        player.completeAtLeast(id("other"), 1);
        player.completeAtLeast(id("too_high"), 1);
        var dirty = new AtomicInteger();
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 1, registry, () -> {
        }, dirty::incrementAndGet);
        var initial = storage.capture();
        assertEquals(DataCenterStorage.Result.UNCHANGED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertTrue(initial.current().getAsBoolean());
        assertEquals(DataCenterStorage.Result.DATA_CENTER_LEVEL_TOO_LOW, storage.downloadResearch(id("too_high"), 1, cloud(registry, player)));
        assertEquals(DataCenterStorage.Result.UPDATED, storage.downloadResearch(id("loop"), 3, cloud(registry, player)));
        assertEquals(1, storage.snapshot().storedResearch().getInt(id("loop")), "PN multi-level downloads use the actual shared level");
        assertFalse(initial.current().getAsBoolean());
        var downloaded = storage.capture();
        assertEquals(DataCenterStorage.Result.CAPACITY_FULL, storage.downloadResearch(id("other"), 1, cloud(registry, player)));
        assertTrue(downloaded.current().getAsBoolean());
        player.completeAtLeast(id("loop"), 3);
        assertEquals(DataCenterStorage.Result.UPDATED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertFalse(downloaded.current().getAsBoolean());
        assertEquals(3, storage.snapshot().storedResearch().getInt(id("loop")));
        var synced = storage.capture();
        assertEquals(DataCenterStorage.Result.UNCHANGED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertTrue(synced.current().getAsBoolean());
        assertEquals(2, dirty.get());
        assertEquals(2, storage.snapshot().revision());
    }

    @Test
    void diskWritesPreserveSpecialAndItemOnlyPathsAndRejectMutexOrCapacityWithoutPartialStorage() {
        var a = definition("a", 1).stage(stage(1, 1).mutex(id("b"), 1).build()).build();
        var b = definition("b", 1).stage(stage(1, 1).mutex(id("a"), 1).build()).build();
        var special = definition("special", 1).specialOnly().stage(stage(1, 1).build()).build();
        var itemOnly = ResearchDefinition.builder(id("item_only")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                         .stage(stage(1, 1).build()).build();
        var registry = registry(a, b, special, itemOnly);
        var player = new PlayerResearchState(new UUID(0, 1));
        var cloud = cloud(registry, player);
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 2, registry, () -> {
        }, () -> {
        });
        assertEquals(DataCenterStorage.Result.SPECIAL_ONLY_REJECTED, storage.downloadResearch(special.id(), 1, cloud));
        assertEquals(DataCenterStorage.Result.ITEM_ONLY_REJECTED, storage.downloadResearch(itemOnly.id(), 1, cloud));
        var old = storage.capture();
        assertEquals(DataCenterStorage.Result.MUTEX_BLOCKED, storage.storeFromDisk(levels("a", "b"), cloud));
        assertTrue(storage.snapshot().storedResearch().isEmpty());
        assertTrue(old.current().getAsBoolean());
        assertEquals(DataCenterStorage.Result.UPDATED, storage.storeFromDisk(levels("special", "item_only"), cloud));
        var saved = storage.capture();
        assertEquals(DataCenterStorage.Result.CAPACITY_FULL, storage.storeFromDisk(levels("a"), cloud));
        assertTrue(saved.current().getAsBoolean());
        assertEquals(Set.of(special.id(), itemOnly.id()), storage.snapshot().storedResearch().keySet());
    }

    @Test
    void automaticSynchronizationNeverUpgradesSpecialOrItemOnlyResearchImportedFromDisk() {
        var special = definition("special", 3).specialOnly().stage(stage(3, 1).build()).build();
        var itemOnly = ResearchDefinition.builder(id("item_only")).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                         .stage(stage(3, 1).build()).build();
        var registry = registry(special, itemOnly);
        var player = new PlayerResearchState(new UUID(0, 1));
        var cloud = new ResearchEligibility(registry).calculateStructural("team", List.of(player.playerUuid()),
            Map.of(player.playerUuid(), player), Object2IntMaps.singleton(itemOnly.id(), 3), Set.of());
        assertEquals(3, cloud.completedLevel(itemOnly.id()));
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 2, registry, () -> {
        }, () -> {
        });
        assertEquals(DataCenterStorage.Result.UPDATED, storage.storeFromDisk(levels("special", "item_only"), cloud));
        var stored = storage.capture();
        assertEquals(DataCenterStorage.Result.UNCHANGED, storage.synchronizeLoopingResearch(cloud));
        assertTrue(stored.current().getAsBoolean());
        assertEquals(levels("special", "item_only"), storage.snapshot().storedResearch());
    }

    @Test
    void savedContentsRestoreToAFreshProofAndNeitherFrozenEntriesNorBadPayloadsCanAlterTheLiveDatabase() {
        var registry = registry(ordinary("loop", 3, 1));
        var player = new PlayerResearchState(new UUID(0, 1));
        player.completeAtLeast(id("loop"), 2);
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 2, registry, () -> {
        }, () -> {
        });
        storage.downloadResearch(id("loop"), 2, cloud(registry, player));
        var captured = storage.capture();
        var payload = DataCenterStorageNbt.encode(captured.snapshot());
        var decoded = DataCenterStorageNbt.decode(payload, registry);
        var restored = ResearchDataCenter.restore(decoded, registry, () -> {
        }, () -> {
        });
        assertEquals(captured.snapshot().storedResearch(), restored.snapshot().storedResearch());
        assertFalse(restored.current(captured.snapshot()));
        assertThrows(UnsupportedOperationException.class,
            () -> decoded.storedResearch().object2IntEntrySet().iterator().next().setValue(3));
        payload.getTagList("Research", 10).getCompoundTagAt(0).setInteger("Level", 99);
        assertThrows(IllegalArgumentException.class, () -> DataCenterStorageNbt.decode(payload, registry));
        assertTrue(captured.current().getAsBoolean());
        assertEquals(2, storage.snapshot().storedResearch().getInt(id("loop")));
    }

    @Test
    void downloadedResearchDoesNotDisappearWhenTheCloudSourceIsForgotten() {
        var registry = registry(ordinary("loop", 3, 1));
        var player = new PlayerResearchState(new UUID(0, 1));
        player.completeAtLeast(id("loop"), 3);
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 2, registry, () -> {
        }, () -> {
        });
        storage.downloadResearch(id("loop"), 1, cloud(registry, player));
        var before = storage.capture();
        var forget = player.prepareSourceMutation(id("loop"), 3, 0);
        forget.commit();
        forget.complete();
        assertEquals(DataCenterStorage.Result.UNCHANGED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertTrue(before.current().getAsBoolean());
        assertEquals(3, storage.snapshot().storedResearch().getInt(id("loop")));
    }

    @Test
    void loopingUpgradeRejectsNewMutexWithoutPublishingAnUnrestorableOrPartialDatabase() {
        var second = ResearchResourceFormula.builder().build();
        var a = definition("a", 2).stage(stage(1, 1).build())
                                  .stage(ResearchLevelStage.builder(2, 2).researchTier(1).resourceFormula(second).mutex(id("b"), 2).build()).build();
        var b = definition("b", 2).stage(stage(1, 1).build())
                                  .stage(ResearchLevelStage.builder(2, 2).researchTier(1).resourceFormula(second).mutex(id("a"), 2).build()).build();
        var registry = registry(a, b, ordinary("other", 2, 1));
        var player = new PlayerResearchState(new UUID(0, 1));
        var storage = new ResearchDataCenter(new UUID(0, 2), 1, 3, registry, () -> {
        }, () -> {
        });
        var disk = levels("a", "other");
        disk.put(id("b"), 2);
        assertEquals(DataCenterStorage.Result.UPDATED, storage.storeFromDisk(disk, cloud(registry, player)));
        var before = storage.capture();
        player.completeAtLeast(id("a"), 2);
        player.completeAtLeast(id("other"), 2);
        assertEquals(DataCenterStorage.Result.MUTEX_BLOCKED, storage.synchronizeLoopingResearch(cloud(registry, player)));
        assertTrue(before.current().getAsBoolean());
        assertEquals(1, storage.snapshot().storedResearch().getInt(id("other")), "a rejected synchronization must not partially publish");
        var restored = ResearchDataCenter.restore(DataCenterStorageNbt.decode(DataCenterStorageNbt.encode(storage.snapshot()), registry),
            registry, () -> {
            }, () -> {
            });
        assertEquals(disk, restored.snapshot().storedResearch());
    }
}
