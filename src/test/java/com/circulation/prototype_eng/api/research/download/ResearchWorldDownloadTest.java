package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.research.ResearchWorldNbt;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Fault-injected world transactions use native NBT and real research sources without game startup. */
class ResearchWorldDownloadTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OFFLINE = new UUID(0, 2);
    private static final UUID OPERATION = new UUID(0, 3);
    private static final ResourceLocation RESEARCH = new ResourceLocation("world_download_test", "research");
    private static final TestResources.KeyType TYPE = new TestResources.KeyType("world_download_output");
    private static final TestResources.Key ITEM = new TestResources.Key(TYPE, "native_output");

    @Test void frozenDownloadChargesEveryOriginalMemberOnceAndSavesEachEffectBeforeDelivery() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        assertTrue(world.trackedPlayers().containsAll(ObjectList.of(PLAYER, OFFLINE)));
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> true).getFirst().status());
        assertEquals(2, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(1, world.player(OFFLINE).completedLevel(RESEARCH));
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(1, fixture.adapter.plans);
        assertEquals(1, fixture.adapter.deliveries);
        assertEquals(1, fixture.adapter.commits);
        assertTrue(world.pendingDownloadOperations(PLAYER).isEmpty());
        var terminal = world.downloadReceipts(PLAYER).getFirst();
        assertEquals(ItemDeliveryFragment.Status.CONFIRMED, terminal.fragments().getFirst().status());
        assertEquals(output(), terminal.output());
        assertEquals(42, terminal.fragments().getFirst().taggedStackNbt().getCompoundTag("ForgeCaps").getInteger("stored"));
        assertNull(fixture.inventory.receipt);
        assertEquals(world.snapshot(), fixture.codec.decode(fixture.codec.encode(world.snapshot())));
        assertTrue(fixture.saves.attempts.stream().anyMatch(saved -> !saved.downloads().isEmpty() && saved.downloads().getFirst().materialArmed()));
        assertTrue(fixture.saves.attempts.stream().anyMatch(saved -> !saved.downloads().isEmpty()
            && saved.downloads().getFirst().status() == ResearchDownloadOperation.Status.SOURCES_COMMITTED));
    }

    @Test void missingAuthorizationPreservesPreparedAndItsOriginalReservations() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        assertEquals(ResearchDownloadResult.Status.PENDING, fixture.recover(world, _ -> false).getFirst().status());
        assertEquals(0, fixture.inventory.consumptions);
        assertEquals(0, fixture.adapter.deliveries);
        assertFalse(world.player(PLAYER).completeAtLeast(RESEARCH, 4));
        assertFalse(world.player(OFFLINE).completeAtLeast(RESEARCH, 4));
        assertEquals(ResearchDownloadOperation.Status.PREPARED, world.pendingDownloadOperations(PLAYER).getFirst().status());
    }

    @Test void authorizationLostAfterMaterialCommitDoesNotEraseOriginalSourceObligation() {
        var fixture = new Fixture();
        var allowed = new AtomicBoolean(true);
        fixture.inventory.afterConsume = () -> allowed.set(false);
        var world = fixture.restore(fixture.initial());
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> allowed.get()).getFirst().status());
        assertEquals(2, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(1, world.player(OFFLINE).completedLevel(RESEARCH));
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test void sourceSaveFailureKeepsAfterValuesAndReservationUntilExactPendingWriteSucceeds() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        fixture.saves.reject = saved -> !saved.downloads().isEmpty()
            && saved.downloads().getFirst().status() == ResearchDownloadOperation.Status.SOURCES_COMMITTED;
        assertSame(fixture.saves.failure, assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true)));
        var pending = fixture.saves.attempts.getLast();
        assertEquals(2, source(pending, PLAYER).completedLevels().getInt(RESEARCH));
        assertEquals(1, source(pending, OFFLINE).completedLevels().getInt(RESEARCH));
        assertFalse(world.busy());
        assertTrue(world.savePending());
        assertEquals(0, fixture.adapter.plans);
        assertSame(fixture.saves.failure, assertThrows(IllegalStateException.class, () -> world.playerLogin(new UUID(0, 9), 2)));
        assertSame(pending, fixture.saves.attempts.getLast());
        fixture.saves.reject = _ -> false;
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> false).getFirst().status());
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(2, world.player(PLAYER).completedLevel(RESEARCH));
        assertTrue(world.player(PLAYER).completeAtLeast(RESEARCH, 4));
    }

    @Test void committedMaterialSurvivesWorldWriteFailureAndRestartWithoutRecheckingAuthorization() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        fixture.saves.reject = saved -> !saved.downloads().isEmpty()
            && saved.downloads().getFirst().status() == ResearchDownloadOperation.Status.MATERIAL_COMMITTED;
        assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true));
        assertEquals(ResearchDownloadMaterial.Status.COMMITTED, fixture.inventory.receipt.status());
        assertEquals(ResearchDownloadOperation.Status.PREPARED, fixture.saves.durable.downloads().getFirst().status());
        fixture.saves.reject = _ -> false;
        var restarted = fixture.restore(fixture.saves.durable);
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(restarted, _ -> false).getFirst().status());
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(2, restarted.player(PLAYER).completedLevel(RESEARCH));
    }

    @Test void sourceCommittedRecoveryDoesNotRequireCurrentLevelsEqualFrozenAfter() {
        var fixture = new Fixture();
        var original = fixture.restore(fixture.initial());
        fixture.inventory.failRemove = true;
        assertThrows(IllegalStateException.class, () -> fixture.recover(original, _ -> true));
        var saved = fixture.saves.durable;
        assertEquals(ResearchDownloadOperation.Status.SOURCES_COMMITTED, saved.downloads().getFirst().status());
        var advancedPlayers = new ObjectArrayList<PlayerResearchState.Snapshot>();
        for (var player : saved.players()) {
            var levels = new Object2IntOpenHashMap<>(player.completedLevels());
            levels.put(RESEARCH, 4);
            advancedPlayers.add(new PlayerResearchState.Snapshot(player.playerUuid(), levels, player.rewardClaims()));
        }
        var restarted = fixture.restore(new ResearchWorldSnapshot(saved.serverTime(), advancedPlayers, saved.teams(), saved.downloads(), saved.downloadReceipts()));
        fixture.inventory.failRemove = false;
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(restarted, _ -> false).getFirst().status());
        assertEquals(4, restarted.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(4, restarted.player(OFFLINE).completedLevel(RESEARCH));
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test void preparedAfterImageIsUncertainAndNeverProvesThisOperationConsumedMaterial() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        fixture.inventory.receipt = initial.downloads().getFirst().material();
        fixture.inventory.image = fixture.inventory.receipt.inventoryAfter();
        var world = fixture.restore(initial);
        assertEquals(ResearchDownloadResult.Status.RECOVERY_ERROR, fixture.recover(world, _ -> true).getFirst().status());
        assertEquals(3, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(2, world.player(OFFLINE).completedLevel(RESEARCH));
        assertEquals(0, fixture.inventory.consumptions);
        assertEquals(0, fixture.adapter.deliveries);
    }

    @Test void materialCommittedWithoutAnExactPlayerReceiptNeverChargesOrIssuesOutput() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        var prepared = initial.downloads().getFirst().armed();
        var committed = prepared.materialCommitted(prepared.material().committed());
        var world = fixture.restore(new ResearchWorldSnapshot(1, initial.players(), initial.teams(), ObjectList.of(committed), ObjectList.of()));
        assertEquals(ResearchDownloadResult.Status.RECOVERY_ERROR, fixture.recover(world, _ -> true).getFirst().status());
        assertEquals(3, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(0, fixture.adapter.deliveries);
    }

    @Test void completeOutputPlanMustBeDurableBeforeAnyAdapterPrepareOrEffect() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        fixture.saves.reject = saved -> !saved.downloads().isEmpty()
            && saved.downloads().getFirst().status() == ResearchDownloadOperation.Status.DELIVERING;
        assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true));
        assertEquals(1, fixture.adapter.plans);
        assertEquals(0, fixture.adapter.prepares);
        assertEquals(0, fixture.adapter.deliveries);
        fixture.saves.reject = _ -> false;
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> false).getFirst().status());
        assertEquals(1, fixture.adapter.plans);
        assertEquals(1, fixture.adapter.deliveries);
    }

    @Test void lostDeliveryCommitReplyRestartsFromObservationWithoutReplacingTheItem() {
        var fixture = new Fixture();
        fixture.adapter.failCommit = true;
        var world = fixture.restore(fixture.initial());
        assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true));
        assertEquals(ItemDeliveryFragment.Status.DELIVERING, fixture.saves.durable.downloads().getFirst().fragments().getFirst().status());
        var restarted = fixture.restore(fixture.saves.durable);
        fixture.adapter.failCommit = false;
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(restarted, _ -> false).getFirst().status());
        assertEquals(1, fixture.adapter.deliveries);
        assertEquals(2, fixture.adapter.commits);
        assertEquals(1, fixture.adapter.plans);
    }

    @Test void missingDeliveryEvidenceRemainsPendingAndDoesNotProveAbsence() {
        var fixture = new Fixture();
        fixture.adapter.failCommit = true;
        var world = fixture.restore(fixture.initial());
        assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true));
        fixture.adapter.physical.clear();
        fixture.adapter.proveAbsence = false;
        fixture.adapter.failCommit = false;
        assertEquals(ResearchDownloadResult.Status.PENDING, fixture.recover(world, _ -> false).getFirst().status());
        assertEquals(1, fixture.adapter.deliveries);
        assertEquals(ItemDeliveryFragment.Status.DELIVERING, world.pendingDownloadOperations(PLAYER).getFirst().fragments().getFirst().status());
    }

    @Test void terminalNonceRetryRepeatsCleanupAndRejectsDifferentIntentWithoutReevaluation() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        var intent = initial.downloads().getFirst().intent();
        var world = fixture.restore(initial);
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> true).getFirst().status());
        int releases = fixture.adapter.releases;
        var evaluations = new AtomicInteger();
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.request(world, intent, evaluations).status());
        assertEquals(releases + 1, fixture.adapter.releases);
        var different = new ResearchDownloadOperation.Intent(PLAYER, intent.nonce(), intent.databaseId(), intent.teamId(), RESEARCH, 3, intent.slot());
        assertEquals(ResearchDownloadResult.Status.NONCE_CONFLICT, fixture.request(world, different, evaluations).status());
        assertEquals(0, evaluations.get());
        assertEquals(1, fixture.inventory.consumptions);
        assertEquals(1, fixture.adapter.deliveries);
        fixture.recover(world, _ -> false);
        assertEquals(releases + 2, fixture.adapter.releases);
    }

    @Test void rejectedConversionChangesNeitherInventoryNorSourceAndCreatesNoPendingOperation() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        var world = fixture.restore(new ResearchWorldSnapshot(1, initial.players(), initial.teams()));
        var calls = new AtomicInteger();
        assertEquals(ResearchDownloadResult.Status.NO_EVENT, fixture.request(world, initial.downloads().getFirst().intent(), calls).status());
        assertEquals(1, calls.get());
        assertEquals(3, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(2, world.player(OFFLINE).completedLevel(RESEARCH));
        assertTrue(world.pendingDownloadOperations(PLAYER).isEmpty());
        assertNull(fixture.inventory.receipt);
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test void explicitAbortRequiresExactUnconsumedMaterialAndDurablyReleasesReservations() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        assertEquals(ResearchDownloadResult.Status.ABORTED, world.abortItemDownload(PLAYER, OPERATION, fixture.inventory, 1).status());
        assertTrue(world.pendingDownloadOperations(PLAYER).isEmpty());
        assertTrue(world.downloadReceipts(PLAYER).getFirst().fragments().isEmpty());
        assertTrue(world.player(PLAYER).completeAtLeast(RESEARCH, 4));
        assertEquals(0, fixture.adapter.deliveries);
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test void blacklistedOrdinaryResearchIsRejectedBeforeItsMaterialConversionCallback() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        var team = initial.teams().getFirst();
        var blocked = new ResearchWorldSnapshot.Team(team.teamId(), team.knownMembers(), new ObjectOpenHashSet<>(ObjectList.of(RESEARCH)),
            team.research(), team.queue(), team.manualSessions(), team.stationWork());
        var world = fixture.restore(new ResearchWorldSnapshot(1, initial.players(), ObjectList.of(blocked)));
        var calls = new AtomicInteger();
        assertEquals(ResearchDownloadResult.Status.SOURCE_REJECTED, fixture.request(world, initial.downloads().getFirst().intent(), calls).status());
        assertEquals(0, calls.get());
        assertEquals(0, fixture.inventory.consumptions);
        assertEquals(3, world.player(PLAYER).completedLevel(RESEARCH));
    }

    @Test void zeroCostDownloadCreatesEveryEmptyOriginalMemberSourceBeforeEvaluatingMaterial() {
        var fixture = new Fixture(0);
        var world = new ResearchWorld(fixture.registry, fixture.resources, ResearchPermissionPolicy.DEFAULT, fixture.saves);
        var intent = new ResearchDownloadOperation.Intent(PLAYER, new UUID(0, 4), new UUID(0, 5), "original_team", RESEARCH, 4, 5);
        var calls = new AtomicInteger();
        assertEquals(ResearchDownloadResult.Status.NO_EVENT, fixture.request(world, intent, calls).status());
        assertEquals(1, calls.get(), "A zero source cost does not require personal level to reach the encoded target");
        assertEquals(0, world.player(PLAYER).completedLevel(RESEARCH));
        assertEquals(0, world.player(OFFLINE).completedLevel(RESEARCH));
        assertEquals(2, world.snapshot().players().size());
        assertEquals(0, fixture.inventory.consumptions);
    }

    @Test void armedSaveFailurePreventsPlayerReceiptAndMaterialEffects() {
        var fixture = new Fixture();
        var world = fixture.restore(fixture.initial());
        fixture.saves.reject = saved -> !saved.downloads().isEmpty() && saved.downloads().getFirst().materialArmed();
        assertThrows(IllegalStateException.class, () -> fixture.recover(world, _ -> true));
        assertNull(fixture.inventory.receipt);
        assertEquals(0, fixture.inventory.consumptions);
        fixture.saves.reject = _ -> false;
        assertEquals(ResearchDownloadResult.Status.COMPLETED, fixture.recover(world, _ -> true).getFirst().status());
        assertEquals(1, fixture.inventory.consumptions);
    }

    @Test void strictWorldNbtRejectsDuplicateNoncesAndCorruptedNativeOutput() {
        var fixture = new Fixture();
        var initial = fixture.initial();
        var operation = initial.downloads().getFirst();
        var otherId = new UUID(0, 30);
        var material = ResearchDownloadMaterial.prepare(PLAYER, otherId, 5, fixture.inventory.image);
        assertNotNull(material);
        var duplicate = new ResearchDownloadOperation(otherId, operation.intent(), operation.sources(), material, operation.output(), operation.amount(), 1,
            ResearchDownloadOperation.Status.PREPARED, false, ObjectList.of());
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(1, initial.players(), initial.teams(), ObjectList.of(operation, duplicate), ObjectList.of()));
        var corrupted = fixture.codec.encode(initial);
        corrupted.getTagList("Downloads", 10).getCompoundTagAt(0).getCompoundTag("Output").setString("ForgeCaps", "unknown payload");
        assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(corrupted));
    }

    private static PlayerResearchState.Snapshot source(ResearchWorldSnapshot snapshot, UUID player) {
        return snapshot.players().stream().filter(value -> value.playerUuid().equals(player)).findFirst().orElseThrow();
    }
    private static NBTTagCompound output() {
        var result = new NBTTagCompound();
        result.setString("id", "world_download_test:output");
        result.setByte("Count", (byte) 1);
        result.setShort("Damage", (short) 7);
        var tag = new NBTTagCompound();
        tag.setString("research", RESEARCH.toString());
        tag.setInteger("level", 4);
        result.setTag("tag", tag);
        var caps = new NBTTagCompound();
        caps.setInteger("stored", 42);
        result.setTag("ForgeCaps", caps);
        return result;
    }
    private static NBTTagCompound inventory() {
        var stack = output();
        stack.setByte("Slot", (byte) PlayerInventoryNbt.encodedSlot(5));
        stack.setByte("Count", (byte) 3);
        var list = new NBTTagList();
        list.appendTag(stack);
        var result = new NBTTagCompound();
        result.setTag("inventory", list);
        result.setInteger("selectedSlot", 0);
        return result;
    }

    private static final class Fixture {
        final ResearchRegistry registry = new ResearchRegistry();
        final NetworkResourceRegistry resources = new NetworkResourceRegistry();
        final ResearchWorldNbt codec;
        final Saves saves = new Saves();
        final Inventory inventory = new Inventory();
        final Adapter adapter = new Adapter(saves);
        Fixture() {
            this(1);
        }
        Fixture(int cost) {
            resources.freeze();
            registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(4).itemDownloadLevelCost(cost)
                .deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                .stage(ResearchLevelStage.builder(1, 4).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.freeze(resources);
            codec = new ResearchWorldNbt(registry, resources, new ResourceKeyNbt(id -> TYPE.getId().equals(id) ? TYPE : null));
        }
        ResearchWorldSnapshot initial() {
            var first = new PlayerResearchState(PLAYER);
            first.completeAtLeast(RESEARCH, 3);
            var offline = new PlayerResearchState(OFFLINE);
            offline.completeAtLeast(RESEARCH, 2);
            var team = new TeamOwnedResearch("original_team", registry);
            var queue = new TeamResearchQueue(registry, resources, team.teamId());
            var members = new ObjectOpenHashSet<>(ObjectList.of(PLAYER, OFFLINE));
            var membership = OwnerTeamResolution.resolved(PLAYER, team.teamId(), OwnerTeamType.PLAYER, members, Optional.of(PLAYER));
            var players = new Object2ObjectOpenHashMap<UUID, PlayerResearchState>();
            players.put(PLAYER, first); players.put(OFFLINE, offline);
            var levels = new Object2IntOpenHashMap<ResourceLocation>();
            levels.put(RESEARCH, 4);
            var plan = new ResearchDownloadSources(registry).evaluate(registry.require(RESEARCH), 4, membership,
                new DataCenterResearchSnapshot(new UUID(0, 5), 1, 16, 0, levels), players, team).plan();
            var material = ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, inventory.image);
            var operation = new ResearchDownloadOperation(OPERATION, new ResearchDownloadOperation.Intent(PLAYER, new UUID(0, 4), new UUID(0, 5),
                team.teamId(), RESEARCH, 4, 5), Objects.requireNonNull(plan), Objects.requireNonNull(material), output(), 3, 1,
                ResearchDownloadOperation.Status.PREPARED, false, ObjectList.of());
            return new ResearchWorldSnapshot(1, ObjectList.of(first.snapshot(), offline.snapshot()), ObjectList.of(
                new ResearchWorldSnapshot.Team(team.teamId(), members, new ObjectOpenHashSet<>(), team.snapshot(), queue.snapshot(), ObjectList.of())),
                ObjectList.of(operation), ObjectList.of());
        }
        ResearchWorld restore(ResearchWorldSnapshot saved) {
            var world = new ResearchWorld(registry, resources, ResearchPermissionPolicy.DEFAULT, saves);
            var decoded = codec.decode(codec.encode(saved));
            world.restore(decoded);
            saves.durable = decoded;
            return world;
        }
        ObjectList<ResearchDownloadResult> recover(ResearchWorld world, Predicate<ResearchDownloadOperation> allowed) {
            return world.recoverItemDownloads(PLAYER, inventory, adapter, 1, () -> true, allowed);
        }
        ResearchDownloadResult request(ResearchWorld world, ResearchDownloadOperation.Intent intent, AtomicInteger evaluations) {
            var membership = OwnerTeamResolution.resolved(PLAYER, intent.teamId(), OwnerTeamType.PLAYER,
                new ObjectOpenHashSet<>(ObjectList.of(PLAYER, OFFLINE)), Optional.of(PLAYER));
            var permission = new ResearchPermissionContext(PLAYER, intent.teamId(), PLAYER, intent.teamId(), true, false, false);
            var levels = new Object2IntOpenHashMap<ResourceLocation>(); levels.put(RESEARCH, 4);
            return world.requestItemDownload(intent, membership, permission, new DataCenterResearchSnapshot(intent.databaseId(), 1, 16, 0, levels), inventory,
                _ -> { evaluations.incrementAndGet(); return ResearchItemDownloadEvaluation.noEvent(); }, 1, adapter, () -> true, () -> true);
        }
    }

    private static final class Saves implements Consumer<ResearchWorldSnapshot> {
        final ObjectArrayList<ResearchWorldSnapshot> attempts = new ObjectArrayList<>();
        final IllegalStateException failure = new IllegalStateException("world save failed");
        Predicate<ResearchWorldSnapshot> reject = _ -> false;
        ResearchWorldSnapshot durable;
        @Override public void accept(ResearchWorldSnapshot value) {
            attempts.add(value);
            if (reject.test(value)) throw failure;
            durable = value;
        }
    }

    private static final class Inventory implements ResearchDownloadInventory {
        NBTTagCompound image = inventory();
        ResearchDownloadMaterial receipt;
        int consumptions;
        boolean failRemove;
        Runnable afterConsume = () -> { };
        @Override public UUID playerId() { return PLAYER; }
        @Override public ResearchDownloadMaterial capture(UUID id, int slot) { return ResearchDownloadMaterial.prepare(PLAYER, id, slot, image); }
        @Override public ResearchDownloadMaterial find(UUID id) { return receipt != null && receipt.operationId().equals(id) ? receipt : null; }
        @Override public ObjectList<ResearchDownloadMaterial> operations() { return receipt == null ? ObjectList.of() : ObjectList.of(receipt); }
        @Override public boolean matches(NBTTagCompound expected) { return image.equals(expected); }
        @Override public void recordPrepared(ResearchDownloadMaterial prepared) {
            assertTrue(matches(prepared.inventoryBefore()));
            receipt = prepared;
        }
        @Override public boolean consume(ResearchDownloadMaterial prepared) {
            if (!matches(prepared.inventoryBefore())) return false;
            receipt = prepared.committed();
            image = prepared.inventoryAfter();
            consumptions++;
            afterConsume.run();
            return true;
        }
        @Override public void persistCommitted(UUID id) {
            assertNotNull(find(id));
            assertEquals(ResearchDownloadMaterial.Status.COMMITTED, receipt.status());
            assertTrue(matches(receipt.inventoryAfter()));
        }
        @Override public void removePrepared(UUID id) {
            if (receipt != null) assertTrue(matches(receipt.inventoryBefore()));
            receipt = null;
        }
        @Override public void removeCommitted(UUID id) {
            if (failRemove) throw new IllegalStateException("material cleanup failed");
            if (receipt != null) assertEquals(ResearchDownloadMaterial.Status.COMMITTED, receipt.status());
            receipt = null;
        }
    }

    private static final class Adapter implements ItemDeliveryAdapter {
        final Saves saves;
        final ObjectOpenHashSet<UUID> physical = new ObjectOpenHashSet<>();
        int plans;
        int prepares;
        int deliveries;
        int commits;
        int releases;
        boolean failCommit;
        boolean proveAbsence = true;
        Adapter(Saves saves) { this.saves = saves; }
        @Override public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, AEKey resource, long amount) {
            throw new AssertionError("Downloads must use their frozen native template");
        }
        @Override public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, NBTTagCompound template, long amount) {
            plans++;
            assertEquals(output(), template);
            var id = new UUID(0, 100);
            return ObjectList.of(new ItemDeliveryFragment(id, parent, ITEM, Math.toIntExact(amount),
                ItemDeliveryTemplates.taggedLeaf(template, Math.toIntExact(amount), parent, id), new ItemDeliveryFragment.Inventory(8),
                Optional.empty(), ItemDeliveryFragment.Status.PLANNED));
        }
        @Override public Scan scan(ObjectList<ItemDeliveryFragment> delivering) {
            var absent = new ObjectOpenHashSet<UUID>();
            if (proveAbsence) for (var fragment : delivering) if (!physical.contains(fragment.id())) absent.add(fragment.id());
            return new Scan(physical, new Object2ObjectOpenHashMap<>(), absent);
        }
        @Override public boolean canDeliver(ItemDeliveryFragment fragment) { return true; }
        @Override public void prepare(ItemDeliveryFragment fragment) {
            prepares++;
            assertEquals(ResearchDownloadOperation.Status.DELIVERING, saves.durable.downloads().getFirst().status());
            assertEquals(ItemDeliveryFragment.Status.PLANNED, saves.durable.downloads().getFirst().fragments().getFirst().status());
        }
        @Override public Delivery deliver(ItemDeliveryFragment fragment) {
            assertEquals(ItemDeliveryFragment.Status.DELIVERING, saves.durable.downloads().getFirst().fragments().getFirst().status());
            assertTrue(physical.add(fragment.id()), "A frozen output fragment must not be issued twice");
            deliveries++;
            return new Delivery(fragment.count(), Optional.empty());
        }
        @Override public void commit(ItemDeliveryFragment fragment, Delivery delivery) {
            commits++;
            if (failCommit) throw new IllegalStateException("delivery commit reply lost");
        }
        @Override public void release(ItemDeliveryFragment fragment) {
            assertEquals(ItemDeliveryFragment.Status.CONFIRMED, fragment.status());
            releases++;
        }
    }
}
