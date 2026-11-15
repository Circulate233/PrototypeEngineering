package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchDiskTest {
    private static ResearchDefinition definition(String name, int max, int tier) {
        return ResearchDefinition.builder(id(name)).maxLevel(max).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                 .stage(stage(1, max, tier)).build();
    }

    private static ResearchLevelStage stage(int first, int last, int tier) {
        return ResearchLevelStage.builder(first, last).durationTicks(1).researchTier(tier)
                                 .resourceFormula(ResearchResourceFormula.builder().build()).build();
    }

    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var registry = new ResearchRegistry();
        for (var definition : definitions) {
            registry.register(definition);
        }
        registry.freeze();
        return registry;
    }

    private static TeamResearchView cloud(ResearchRegistry registry) {
        return new TeamResearchView(registry, "team", Set.of(new UUID(0, 1)), Object2IntMaps.emptyMap(),
            Object2IntMaps.emptyMap(), Set.of());
    }

    private static ResearchDataCenter storage(ResearchRegistry registry, int tier, int capacity) {
        return new ResearchDataCenter(new UUID(0, 2), tier, capacity, registry, () -> {
        }, () -> {
        });
    }

    private static Object2IntMap<ResourceLocation> levels(String first, int firstLevel, String second, int secondLevel) {
        var entries = new Object2IntOpenHashMap<ResourceLocation>();
        entries.put(id(first), firstLevel);
        entries.put(id(second), secondLevel);
        return entries;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("test", name);
    }

    @Test
    void completedLevelIsPreservedWhileTheDiskAndDatabaseCompareResearchTier() {
        var registry = registry(definition("loop", 1_000, 2), definition("special", 1, 1));
        var entries = levels("loop", 999, "special", 1);
        var data = new ResearchDiskData(registry, 2, entries);
        var decoded = ResearchDiskNbtCodec.decode(ResearchDiskNbtCodec.encode(data), registry);
        var storage = storage(registry, 2, 2);
        var before = storage.capture();
        var cloud = cloud(registry);
        assertTrue(ResearchDiskImport.precheck(decoded, before.snapshot(), cloud).accepted());
        assertTrue(before.current().getAsBoolean(), "Precheck must not publish into the live database");
        assertEquals(DataCenterStorage.Result.UPDATED, storage.storeFromDisk(decoded.entries(), cloud));
        assertEquals(entries, storage.snapshot().storedResearch(), "The entries retain completed levels, not research tiers");
    }

    @Test
    void diskTierMustFitTheTargetEvenWhenItsResearchAlreadyFits() {
        var registry = registry(definition("low", 200, 1));
        var data = new ResearchDiskData(registry, 3, Object2IntMaps.singleton(id("low"), 100));
        var lowDatabase = storage(registry, 2, 2);
        var before = lowDatabase.capture();
        var rejected = ResearchDiskImport.precheck(data, before.snapshot(), cloud(registry));
        assertEquals(ResearchDiskImport.Status.DISK_TIER_TOO_HIGH, rejected.status());
        assertTrue(before.current().getAsBoolean());
        var matchingDatabase = storage(registry, 3, 2);
        assertTrue(ResearchDiskImport.precheck(data, matchingDatabase.snapshot(), cloud(registry)).accepted());
    }

    @Test
    void eachCompletedLevelUsesItsOwnStageTierAndTierZeroRemainsUsable() {
        var loop = ResearchDefinition.builder(id("loop")).maxLevel(10).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                     .stage(stage(1, 9, 0)).stage(stage(10, 10, 2)).build();
        var registry = registry(loop);
        var zeroDisk = new ResearchDiskData(registry, 0, Object2IntMaps.singleton(loop.id(), 9));
        var zeroDatabase = storage(registry, 0, 1);
        assertTrue(ResearchDiskImport.precheck(zeroDisk, zeroDatabase.snapshot(), cloud(registry)).accepted());
        assertThrows(IllegalArgumentException.class,
            () -> new ResearchDiskData(registry, 0, Object2IntMaps.singleton(loop.id(), 10)));
        var raised = new ResearchDiskData(registry, 2, Object2IntMaps.singleton(loop.id(), 10));
        assertEquals(ResearchDiskImport.Status.DISK_TIER_TOO_HIGH,
            ResearchDiskImport.precheck(raised, zeroDatabase.snapshot(), cloud(registry)).status());
        assertTrue(ResearchDiskImport.precheck(raised, storage(registry, 2, 1).snapshot(), cloud(registry)).accepted());
    }

    @Test
    void laterBatchCapacityFailureLeavesTheRealDatabaseAndItsEarlierContentsUntouched() {
        var registry = registry(definition("existing", 1, 1), definition("first", 1, 1), definition("last", 1, 1));
        var storage = storage(registry, 1, 2);
        var cloud = cloud(registry);
        storage.storeFromDisk(Object2IntMaps.singleton(id("existing"), 1), cloud);
        var before = storage.capture();
        var batch = new ResearchDiskData(registry, 1, levels("first", 1, "last", 1));
        var result = ResearchDiskImport.precheck(batch, before.snapshot(), cloud);
        assertEquals(ResearchDiskImport.Status.DATA_CENTER_REJECTED, result.status());
        assertEquals(DataCenterStorage.Result.CAPACITY_FULL, result.storageResult());
        assertTrue(before.current().getAsBoolean());
        assertEquals(Object2IntMaps.singleton(id("existing"), 1), storage.snapshot().storedResearch());
    }

    @Test
    void batchMutualExclusionIsPrecheckedAgainstOtherDiskEntriesAndExistingStorage() {
        var a = ResearchDefinition.builder(id("a")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                  .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build())
                                                           .mutex(id("b"), 1).build()).build();
        var b = ResearchDefinition.builder(id("b")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                  .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build())
                                                           .mutex(id("a"), 1).build()).build();
        var registry = registry(a, b, definition("other", 1, 1));
        var storage = storage(registry, 1, 3);
        var cloud = cloud(registry);
        var before = storage.capture();
        var conflicting = new ResearchDiskData(registry, 1, levels("a", 1, "b", 1));
        assertEquals(DataCenterStorage.Result.MUTEX_BLOCKED,
            ResearchDiskImport.precheck(conflicting, before.snapshot(), cloud).storageResult());
        assertTrue(before.current().getAsBoolean());
        storage.storeFromDisk(Object2IntMaps.singleton(a.id(), 1), cloud);
        before = storage.capture();
        conflicting = new ResearchDiskData(registry, 1, levels("other", 1, "b", 1));
        assertEquals(DataCenterStorage.Result.MUTEX_BLOCKED,
            ResearchDiskImport.precheck(conflicting, before.snapshot(), cloud).storageResult());
        assertTrue(before.current().getAsBoolean());
        assertEquals(Object2IntMaps.singleton(a.id(), 1), storage.snapshot().storedResearch());
    }

    @Test
    void sourceMapsAndPayloadMutationCannotChangeTheValidatedBatchImportedLater() {
        var registry = registry(definition("first", 10, 1), definition("second", 10, 1));
        var source = levels("first", 5, "second", 7);
        var frozen = new ResearchDiskData(registry, 1, source);
        var encoded = ResearchDiskNbtCodec.encode(frozen);
        var decoded = ResearchDiskNbtCodec.decode(encoded, registry);
        source.clear();
        source.put(id("foreign"), 99);
        encoded.getTagList("entries", 10).getCompoundTagAt(0).setInteger("level", 99);
        var storage = storage(registry, 1, 2);
        var cloud = cloud(registry);
        assertTrue(ResearchDiskImport.precheck(decoded, storage.snapshot(), cloud).accepted());
        storage.storeFromDisk(decoded.entries(), cloud);
        assertEquals(levels("first", 5, "second", 7), storage.snapshot().storedResearch());
    }

    @Test
    void decodingRejectsUnknownResearchAndOutOfRangeLevels() {
        var registry = registry(definition("first", 10, 1), definition("second", 10, 1));
        var data = new ResearchDiskData(registry, 1, levels("first", 5, "second", 7));
        var payload = ResearchDiskNbtCodec.encode(data);
        var last = payload.getTagList("entries", 10).getCompoundTagAt(1);
        last.setString("researchId", "test:unknown");
        assertThrows(IllegalArgumentException.class, () -> ResearchDiskNbtCodec.decode(payload, registry));
        last.setString("researchId", id("second").toString());
        last.setInteger("level", 11);
        assertThrows(IllegalArgumentException.class, () -> ResearchDiskNbtCodec.decode(payload, registry));
        assertTrue(ResearchDiskImport.precheck(data, storage(registry, 1, 2).snapshot(), cloud(registry)).accepted());
    }
}
