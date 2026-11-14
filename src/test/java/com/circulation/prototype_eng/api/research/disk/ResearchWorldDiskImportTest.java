package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadInventory;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import com.circulation.prototype_eng.research.ResearchWorldNbt;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real world/database rules and native single-slot evidence; failures never start Minecraft.
 */
class ResearchWorldDiskImportTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID DATABASE = new UUID(0, 2);
    private static final UUID NONCE = new UUID(0, 3);
    private static final ResourceLocation RESEARCH = new ResourceLocation("disk_world_test", "special");
    private static final ResourceLocation LOOP = new ResourceLocation("disk_world_test", "loop");
    private static final int SLOT = 5;
    @TempDir
    Path directory;

    private static NBTTagCompound inventory(int count) {
        var stack = new NBTTagCompound();
        stack.setString("id", "disk_world_test:disk");
        stack.setShort("Damage", (short) 0);
        stack.setByte("Count", (byte) count);
        stack.setByte("Slot", (byte) PlayerInventoryNbt.encodedSlot(SLOT));
        var caps = new NBTTagCompound();
        caps.setInteger("stored", 42);
        stack.setTag("ForgeCaps", caps);
        var list = new NBTTagList();
        list.appendTag(stack);
        var image = new NBTTagCompound();
        image.setTag("inventory", list);
        image.setInteger("selectedSlot", 0);
        return image;
    }

    @Test
    void nativeDiskImportSavesEachEffectBeforeConsumingOneExactSlot() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.request(world).status());
        assertEquals(90, fixture.storage.snapshot().storedResearch().getInt(RESEARCH));
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(2, PlayerInventoryNbt.stack(fixture.inventory.image, SLOT).getByte("Count"));
        assertEquals(42, PlayerInventoryNbt.stack(fixture.inventory.image, SLOT).getCompoundTag("ForgeCaps").getInteger("stored"));
        assertEquals(0, world.player(PLAYER).completedLevel(RESEARCH));
        assertNull(fixture.inventory.receipt);
        assertNull(fixture.storage.receipt(fixture.saves.durable.diskImports().getFirst().id()));
        assertEquals(ResearchDiskOperation.Status.RELEASED, fixture.saves.durable.diskImports().getFirst().status());
        assertEquals(world.snapshot(), fixture.codec.decode(fixture.codec.encode(world.snapshot())));
        assertTrue(fixture.saves.attempts.stream().anyMatch(saved -> !saved.diskImports().isEmpty()
            && saved.diskImports().getFirst().status() == ResearchDiskOperation.Status.DATABASE_COMMITTED));
    }

    @Test
    void repeatedNonceReturnsOriginalResultWithoutAnotherDiskOrDatabaseEffect() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        var first = fixture.request(world);
        var duplicate = fixture.request(world);
        assertEquals(first, duplicate);
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(1, fixture.storage.commits);
        var different = new ResearchDiskOperation.Intent(PLAYER, NONCE, DATABASE, "team", 6);
        assertEquals(ResearchDiskImportResult.Status.NONCE_CONFLICT, fixture.request(world, different).status());
    }

    @Test
    void deniedPermissionNeverPreparesPlayerOrWritesDatabase() {
        var fixture = new Fixture(directory);
        var world = new ResearchWorld(fixture.registry, (_, _) -> ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER, fixture.saves);
        assertEquals(ResearchDiskImportResult.Status.PERMISSION_DENIED, fixture.request(world).status());
        assertNull(fixture.inventory.receipt);
        assertEquals(0, fixture.storage.commits);
        assertTrue(world.snapshot().diskImports().isEmpty());
    }

    @Test
    void foreignDatabaseOwnerIsRejectedBeforeCapturingOrConsumingDisk() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        var foreign = new ResearchDataCenters.Target(DATABASE, new UUID(0, 9), fixture.target.location());
        assertEquals(ResearchDiskImportResult.Status.PERMISSION_DENIED,
            world.requestDiskImport(fixture.intent, fixture.membership, fixture.permission, foreign, fixture.storage, fixture.inventory,
                _ -> fixture.data, 1, () -> true, () -> true, () -> true).status());
        assertEquals(0, fixture.storage.commits);
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test
    void higherDiskTierIsRejectedEvenWhenItsCompletedResearchFitsDatabase() {
        var fixture = new Fixture(directory);
        fixture.data = new ResearchDiskData(fixture.registry, 4, Object2IntMaps.singleton(RESEARCH, 90));
        var world = fixture.world();
        var result = fixture.request(world);
        assertEquals(ResearchDiskImportResult.Status.DISK_REJECTED, result.status());
        var precheck = result.precheck();
        assertNotNull(precheck);
        assertEquals(ResearchDiskImport.Status.DISK_TIER_TOO_HIGH, precheck.status());
        assertTrue(world.snapshot().diskImports().isEmpty());
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test
    void initialWorldSaveFailureHasNoPlayerReceiptOrDatabaseAttempt() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        fixture.saves.reject = saved -> !saved.diskImports().isEmpty();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertNull(fixture.inventory.receipt);
        assertEquals(0, fixture.storage.commits);
        assertTrue(world.savePending());
    }

    @Test
    void failedPlayerPreparationRemainsUnarmedAndCanCancelAfterTeamChange() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        fixture.inventory.failPrepared = true;
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        var operation = fixture.saves.durable.diskImports().getFirst();
        assertFalse(operation.databaseArmed());
        assertEquals(0, fixture.storage.commits);
        fixture.inventory.failPrepared = false;
        assertEquals(ResearchDiskImportResult.Status.ABORTED,
            world.abortDiskImport(PLAYER, operation.id(), fixture.inventory, 1).status());
        assertNull(fixture.inventory.receipt);
        assertEquals(3, PlayerInventoryNbt.stack(fixture.inventory.image, SLOT).getByte("Count"));
    }

    @Test
    void armedWorldSaveFailureDoesNotEnterDatabaseAndRestoredUnarmedCanCancel() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        fixture.saves.reject = saved -> !saved.diskImports().isEmpty() && saved.diskImports().getFirst().databaseArmed();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(0, fixture.storage.commits);
        assertFalse(fixture.saves.durable.diskImports().getFirst().databaseArmed());
        var saved = fixture.saves.durable;
        fixture.saves.reject = _ -> false;
        var restarted = fixture.restore(saved);
        assertEquals(ResearchDiskImportResult.Status.ABORTED,
            restarted.abortDiskImport(PLAYER, saved.diskImports().getFirst().id(), fixture.inventory, 1).status());
    }

    @Test
    void firstAttemptExplicitDatabaseRejectionSafelyAbortsPreparedMaterial() {
        var fixture = new Fixture(directory);
        fixture.storage.rejectCommit = true;
        var world = fixture.world();
        assertEquals(ResearchDiskImportResult.Status.ABORTED, fixture.request(world).status());
        assertNull(fixture.inventory.receipt);
        assertTrue(fixture.storage.snapshot().storedResearch().isEmpty());
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test
    void unknownArmedCallCannotCancelOrTreatRejectedRetryAsNoPreviousEffect() {
        var fixture = new Fixture(directory);
        fixture.storage.failBeforeCommit = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        var operation = fixture.saves.durable.diskImports().getFirst();
        assertTrue(operation.databaseArmed());
        assertEquals(ResearchDiskImportResult.Status.RECOVERY_ERROR, world.abortDiskImport(PLAYER, operation.id(), fixture.inventory, 1).status());
        fixture.storage.failBeforeCommit = false;
        fixture.storage.rejectCommit = true;
        assertEquals(ResearchDiskImportResult.Status.PENDING, fixture.recover(world, false).status());
        assertEquals(ResearchDiskImportResult.Status.PENDING, fixture.recover(world, true).status());
        assertEquals(0, fixture.inventory.consumptions);
        assertEquals(ResearchDiskOperation.Status.PREPARED, world.pendingDiskImports(PLAYER).getFirst().status());
    }

    @Test
    void matchingDatabaseAfterContentsWithoutReceiptNeverProveImportSuccess() {
        var fixture = new Fixture(directory);
        fixture.storage.failBeforeCommit = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        fixture.storage.failBeforeCommit = false;
        var cloud = new TeamResearchView(fixture.registry, "team", Set.of(PLAYER), Object2IntMaps.emptyMap(),
            Object2IntMaps.emptyMap(), Set.of());
        assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage.storeFromDisk(fixture.data.entries(), cloud, () -> true));
        assertEquals(ResearchDiskImportResult.Status.PENDING, fixture.recover(world, false).status());
        assertEquals(ResearchDiskImportResult.Status.PENDING, fixture.recover(world, true).status());
        assertEquals(0, fixture.inventory.consumptions);
        assertNotNull(fixture.inventory.receipt);
    }

    @Test
    void unknownArmedCallDoesNotRetryWhenBackendWouldNowAcceptAndBeforeStillMatches() {
        var fixture = new Fixture(directory);
        fixture.storage.failBeforeCommit = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(1, fixture.storage.attempts);
        var operation = fixture.saves.durable.diskImports().getFirst();
        assertTrue(operation.commit().matchesBefore(fixture.storage.snapshot()));
        fixture.storage.failBeforeCommit = false;
        var restarted = fixture.restore(fixture.saves.durable);
        assertEquals(ResearchDiskImportResult.Status.PENDING, fixture.recover(restarted, true).status());
        assertEquals(1, fixture.storage.attempts);
        assertEquals(0, fixture.storage.commits);
        assertEquals(0, fixture.inventory.consumptions);
        assertTrue(fixture.storage.snapshot().storedResearch().isEmpty());
        assertNotNull(fixture.inventory.receipt);
    }

    @Test
    void unavailablePersistenceIsRejectedBeforePlayerOrWorldPreparation() {
        var fixture = new Fixture(directory);
        fixture.storage.available = false;
        var world = fixture.world();
        assertEquals(ResearchDiskImportResult.Status.UNSUPPORTED_STORAGE, fixture.request(world).status());
        assertTrue(world.snapshot().diskImports().isEmpty());
        assertNull(fixture.inventory.receipt);
        assertEquals(0, fixture.storage.commits);
    }

    @Test
    void publishedDatabaseReceiptRecoversAfterCallbackFailureWithoutCurrentTeamAuthority() {
        var fixture = new Fixture(directory);
        fixture.storage.failAfterCommit = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(0, fixture.inventory.consumptions);
        fixture.storage.failAfterCommit = false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(1, fixture.storage.commits);
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void databaseFileBarrierFailurePreservesReceiptAndDoesNotConsumeUntilReadback() {
        var fixture = new Fixture(directory);
        fixture.storage.failPersist = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(0, fixture.inventory.consumptions);
        assertNotNull(fixture.storage.receipt(fixture.saves.durable.diskImports().getFirst().id()));
        fixture.storage.failPersist = false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void databaseCommittedWorldWriteFailureRestoresAndConsumesOnlyAfterItsExactCheckpoint() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        fixture.saves.reject = saved -> !saved.diskImports().isEmpty()
            && saved.diskImports().getFirst().status() == ResearchDiskOperation.Status.DATABASE_COMMITTED;
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(0, fixture.inventory.consumptions);
        var saved = fixture.saves.durable;
        fixture.saves.reject = _ -> false;
        fixture.storage.reload();
        var restarted = fixture.restore(saved);
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(restarted, false).status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void ordinaryDatabaseUpdatesKeepImportReceiptAndRecoveryPersistsCompleteCurrentContents() {
        var fixture = new Fixture(directory);
        fixture.storage.failPersist = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        var cloud = new TeamResearchView(fixture.registry, "team", Set.of(PLAYER),
            Object2IntMaps.singleton(LOOP, 80), Object2IntMaps.emptyMap(), Set.of());
        assertEquals(DataCenterStorage.Result.UPDATED, fixture.storage.downloadResearch(LOOP, 5, cloud, () -> true));
        fixture.storage.failPersist = false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(80, fixture.storage.snapshot().storedResearch().getInt(LOOP));
        assertEquals(90, fixture.storage.snapshot().storedResearch().getInt(RESEARCH));
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void recoveryRejectsAnotherPhysicalTargetEvenWithTheSameDatabaseUuid() {
        var fixture = new Fixture(directory);
        fixture.storage.failAfterCommit = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        var wrong = new ResearchDataCenters.Target(DATABASE, PLAYER,
            new MachineIdentity(fixture.target.location().machineType(), 0, new BlockPos(9, 9, 9)));
        var operation = world.pendingDiskImports(PLAYER).getFirst();
        fixture.storage.failAfterCommit = false;
        assertEquals(ResearchDiskImportResult.Status.RECOVERY_ERROR,
            world.recoverDiskImport(PLAYER, operation.id(), fixture.inventory,
                new ResearchDiskRecovery(fixture.storage, wrong, () -> true, null, null, () -> false), 1, () -> true).status());
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test
    void committedPlayerSaveFailureRetriesAfterImageWithoutConsumingAgain() {
        var fixture = new Fixture(directory);
        fixture.inventory.failCommitted = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(ResearchDownloadMaterial.Status.COMMITTED, fixture.inventory.receipt.status());
        fixture.inventory.failCommitted = false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void preparedPlayerAfterImageIsUnknownAndNeverAllowsAnotherConsumption() {
        var fixture = new Fixture(directory);
        fixture.inventory.failCommitted = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        fixture.inventory.receipt = fixture.saves.durable.diskImports().getFirst().material();
        fixture.inventory.failCommitted = false;
        assertEquals(ResearchDiskImportResult.Status.RECOVERY_ERROR, fixture.recover(world, false).status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void completedWorldWriteFailureKeepsBothReceiptsUntilWorldDurability() {
        var fixture = new Fixture(directory);
        var world = fixture.world();
        fixture.saves.reject = saved -> !saved.diskImports().isEmpty()
            && saved.diskImports().getFirst().status() == ResearchDiskOperation.Status.COMPLETED;
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertNotNull(fixture.inventory.receipt);
        assertNotNull(fixture.storage.receipt(fixture.saves.durable.diskImports().getFirst().id()));
        fixture.saves.reject = _ -> false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test
    void cleanupFailureAndLaterInventoryChangesDoNotReconsumeOrRequireOldAfterForever() {
        var fixture = new Fixture(directory);
        fixture.storage.failClear = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        assertNull(fixture.inventory.receipt);
        assertEquals(ResearchDiskOperation.Status.COMPLETED, fixture.saves.durable.diskImports().getFirst().status());
        fixture.inventory.image = inventory(7);
        fixture.storage.failClear = false;
        assertEquals(ResearchDiskImportResult.Status.COMPLETED, fixture.recover(world, false).status());
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(7, PlayerInventoryNbt.stack(fixture.inventory.image, SLOT).getByte("Count"));
    }

    @Test
    void abortingWorldSaveFailureDoesNotDeletePreparedPlayerEvidence() {
        var fixture = new Fixture(directory);
        fixture.inventory.failPrepared = true;
        var world = fixture.world();
        assertThrows(IllegalStateException.class, () -> fixture.request(world));
        fixture.inventory.failPrepared = false;
        fixture.saves.reject = saved -> !saved.diskImports().isEmpty()
            && saved.diskImports().getFirst().status() == ResearchDiskOperation.Status.ABORTING;
        var operation = fixture.saves.durable.diskImports().getFirst();
        assertThrows(IllegalStateException.class, () -> world.abortDiskImport(PLAYER, operation.id(), fixture.inventory, 1));
        assertNotNull(fixture.inventory.receipt);
        fixture.saves.reject = _ -> false;
        assertEquals(ResearchDiskImportResult.Status.ABORTED, world.abortDiskImport(PLAYER, operation.id(), fixture.inventory, 1).status());
        assertNull(fixture.inventory.receipt);
    }

    private static final class Fixture {
        final ResearchRegistry registry = new ResearchRegistry();
        final ResearchWorldNbt codec;
        final Saves saves = new Saves();
        final Inventory inventory = new Inventory(saves);
        final Storage storage;
        final ResearchDataCenters.Target target = new ResearchDataCenters.Target(DATABASE, PLAYER,
            new MachineIdentity(new ResourceLocation("disk_world_test", "center"), 0, new BlockPos(1, 2, 3)));
        final ResearchDiskOperation.Intent intent = new ResearchDiskOperation.Intent(PLAYER, NONCE, DATABASE, "team", SLOT);
        final OwnerTeamResolution membership = OwnerTeamResolution.resolved(PLAYER, "team", OwnerTeamType.PLAYER,
            new ObjectOpenHashSet<>(ObjectList.of(PLAYER)), PLAYER);
        final ResearchPermissionContext permission = new ResearchPermissionContext(PLAYER, "team", PLAYER, "team", false, false, false);
        ResearchDiskData data;

        Fixture(Path directory) {
            registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(100).specialOnly(true)
                                                .deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 100).durationTicks(1).researchTier(2).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.register(ResearchDefinition.builder(LOOP).maxLevel(100).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 100).durationTicks(1).researchTier(2).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.freeze();
            codec = new ResearchWorldNbt(registry, new ResourceKeyNbt(_ -> null));
            data = new ResearchDiskData(registry, 3, Object2IntMaps.singleton(RESEARCH, 90));
            storage = new Storage(registry, saves, directory);
        }

        ResearchWorld world() {
            return new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, saves);
        }

        ResearchWorld restore(ResearchWorldSnapshot saved) {
            var world = world();
            var decoded = codec.decode(codec.encode(saved));
            world.restore(decoded);
            saves.durable = decoded;
            return world;
        }

        ResearchDiskImportResult request(ResearchWorld world) {
            return request(world, intent);
        }

        ResearchDiskImportResult request(ResearchWorld world, ResearchDiskOperation.Intent request) {
            return world.requestDiskImport(request, membership, permission, target, storage, inventory, _ -> data, 1, () -> true, () -> true, () -> true);
        }

        ResearchDiskImportResult recover(ResearchWorld world, boolean allowed) {
            var operation = world.pendingDiskImports(PLAYER).getFirst();
            return world.recoverDiskImport(PLAYER, operation.id(), inventory,
                new ResearchDiskRecovery(storage, target, () -> true, allowed ? membership : null, allowed ? permission : null, () -> allowed),
                1, () -> true);
        }
    }

    private static final class Saves implements Consumer<ResearchWorldSnapshot> {
        final ObjectArrayList<ResearchWorldSnapshot> attempts = new ObjectArrayList<>();
        ResearchWorldSnapshot durable;
        Predicate<ResearchWorldSnapshot> reject = _ -> false;

        @Override
        public void accept(ResearchWorldSnapshot saved) {
            attempts.add(saved);
            if (reject.test(saved)) throw new IllegalStateException("world persistence failed");
            durable = saved;
        }
    }

    private static final class Inventory implements ResearchDownloadInventory {
        final Saves saves;
        NBTTagCompound image = inventory(3);
        ResearchDownloadMaterial receipt;
        int consumptions;
        boolean failPrepared;
        boolean failCommitted;

        Inventory(Saves saves) {
            this.saves = saves;
        }

        @Override
        public UUID playerId() {
            return PLAYER;
        }

        @Override
        public ResearchDownloadMaterial capture(UUID id, int slot) {
            return ResearchDownloadMaterial.prepare(PLAYER, id, slot, image);
        }

        @Override
        public ResearchDownloadMaterial find(UUID id) {
            return receipt != null && receipt.operationId().equals(id) ? receipt : null;
        }

        @Override
        public ObjectList<ResearchDownloadMaterial> operations() {
            return receipt == null ? ObjectList.of() : ObjectList.of(receipt);
        }

        @Override
        public boolean matches(NBTTagCompound expected) {
            return image.equals(expected);
        }

        @Override
        public void recordPrepared(ResearchDownloadMaterial prepared) {
            assertEquals(ResearchDiskOperation.Status.PREPARED, saves.durable.diskImports().getFirst().status());
            assertTrue(matches(prepared.inventoryBefore()));
            receipt = prepared;
            if (failPrepared) throw new IllegalStateException("prepared player save failed");
        }

        @Override
        public boolean consume(ResearchDownloadMaterial material) {
            assertEquals(ResearchDiskOperation.Status.DATABASE_COMMITTED, saves.durable.diskImports().getFirst().status());
            assertTrue(matches(material.inventoryBefore()));
            image = material.inventoryAfter();
            receipt = material.committed();
            consumptions++;
            persistCommitted(material.operationId());
            return true;
        }

        @Override
        public void persistCommitted(UUID id) {
            assertEquals(id, receipt.operationId());
            assertEquals(ResearchDownloadMaterial.Status.COMMITTED, receipt.status());
            assertTrue(matches(receipt.inventoryAfter()));
            if (failCommitted) throw new IllegalStateException("committed player save failed");
        }

        @Override
        public void removePrepared(UUID id) {
            assertEquals(ResearchDiskOperation.Status.ABORTING, saves.durable.diskImports().getFirst().status());
            if (receipt != null) {
                assertEquals(id, receipt.operationId());
                assertEquals(ResearchDownloadMaterial.Status.PREPARED, receipt.status());
                assertTrue(matches(receipt.inventoryBefore()));
            }
            receipt = null;
        }

        @Override
        public void removeCommitted(UUID id) {
            assertEquals(ResearchDiskOperation.Status.COMPLETED, saves.durable.diskImports().getFirst().status());
            if (receipt != null) {
                assertEquals(id, receipt.operationId());
                assertEquals(ResearchDownloadMaterial.Status.COMMITTED, receipt.status());
            }
            receipt = null;
        }
    }

    private static final class Storage implements ResearchDiskStorage {
        final ResearchRegistry registry;
        final Saves saves;
        final Path file;
        ResearchDataCenter delegate;
        int commits;
        int attempts;
        boolean rejectCommit;
        boolean failBeforeCommit;
        boolean failAfterCommit;
        boolean failPersist;
        boolean failClear;
        boolean available = true;

        Storage(ResearchRegistry registry, Saves saves, Path directory) {
            this.registry = registry;
            this.saves = saves;
            file = directory.resolve("database.dat");
            delegate = new ResearchDataCenter(DATABASE, 3, 8, registry, () -> {
            }, () -> {
            });
        }

        @Override
        public DataCenterResearchSnapshot snapshot() {
            return delegate.snapshot();
        }

        @Override
        public boolean current(DataCenterResearchSnapshot snapshot) {
            return delegate.current(snapshot);
        }

        @Override
        public boolean diskPersistenceAvailable() {
            return available;
        }

        @Override
        public Result downloadResearch(ResourceLocation id, int level, TeamResearchView cloud, BooleanSupplier current) {
            return delegate.downloadResearch(id, level, cloud, current);
        }

        @Override
        public Result storeFromDisk(Object2IntMap<ResourceLocation> entries, TeamResearchView cloud, BooleanSupplier current) {
            return delegate.storeFromDisk(entries, cloud, current);
        }

        @Override
        public Result synchronizeLoopingResearch(TeamResearchView cloud, BooleanSupplier current) {
            return delegate.synchronizeLoopingResearch(cloud, current);
        }

        @Override
        public ResearchDiskCommit receipt(UUID id) {
            return delegate.receipt(id);
        }

        @Override
        public Result commitDisk(ResearchDiskCommit commit, TeamResearchView cloud, BooleanSupplier current) {
            assertTrue(saves.durable.diskImports().getFirst().databaseArmed());
            attempts++;
            if (failBeforeCommit) throw new IllegalStateException("unknown database attempt");
            if (rejectCommit) return Result.CAPACITY_FULL;
            var existing = delegate.receipt(commit.operationId());
            var result = delegate.commitDisk(commit, cloud, current);
            if (existing == null && result.accepted()) commits++;
            if (failAfterCommit) throw new IllegalStateException("database callback failed after publishing");
            return result;
        }

        @Override
        public void persistDisk(ResearchDiskCommit commit, BooleanSupplier physicalCurrent) {
            assertEquals(commit, delegate.receipt(commit.operationId()));
            persist(physicalCurrent);
        }

        @Override
        public void clearDiskReceipt(ResearchDiskCommit commit, BooleanSupplier physicalCurrent) {
            assertEquals(ResearchDiskOperation.Status.COMPLETED, saves.durable.diskImports().getFirst().status());
            if (failClear) throw new IllegalStateException("database cleanup failed");
            delegate.removeDiskReceipt(commit, physicalCurrent);
            persist(physicalCurrent);
        }

        private void persist(BooleanSupplier physicalCurrent) {
            assertTrue(physicalCurrent.getAsBoolean());
            if (failPersist) throw new IllegalStateException("database native save failed");
            var encoded = DataCenterStorageNbt.encodeState(delegate.persistentState());
            try {
                try (var output = Files.newOutputStream(file)) {
                    CompressedStreamTools.writeCompressed(encoded, output);
                }
                NBTTagCompound read;
                try (var input = Files.newInputStream(file)) {
                    read = CompressedStreamTools.readCompressed(input);
                }
                assertEquals(encoded, DataCenterStorageNbt.encodeState(DataCenterStorageNbt.decodeState(read, registry)));
                assertEquals(encoded, read);
            } catch (IOException error) {
                throw new IllegalStateException("native database test persistence failed", error);
            }
        }

        void reload() {
            try (var input = Files.newInputStream(file)) {
                delegate = ResearchDataCenter.restore(DataCenterStorageNbt.decodeState(CompressedStreamTools.readCompressed(input), registry),
                    registry, () -> {
                    }, () -> {
                    });
            } catch (IOException error) {
                throw new IllegalStateException("native database test reload failed", error);
            }
        }
    }
}
