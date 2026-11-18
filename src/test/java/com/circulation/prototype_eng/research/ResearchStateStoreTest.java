package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchLevelResult;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadSources;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStateStoreTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final UUID DATABASE = new UUID(0, 3);
    private static final ResourceLocation RESEARCH = new ResourceLocation("test", "research");
    private static final FileTime SENTINEL = FileTime.fromMillis(1_600_000_000_000L);
    @TempDir
    Path directory;

    @Test
    void completedItemResearchSurvivesReopenForEveryMemberAndFollowsThemToNewTeams() throws IOException {
        var registry = itemRegistry();
        var codec = new ResearchWorldNbt(registry, new ResourceKeyNbt(_ -> null));
        var store = new ResearchStateStore(legacy(), codec);
        var world = world(registry, store);
        var members = ObjectSet.of(FIRST, SECOND);
        var original = OwnerTeamResolution.resolved(FIRST, "1234", OwnerTeamType.PLAYER, members, FIRST);
        var op = new ResearchPermissionContext(FIRST, "1234", null, "1234", true, false, true);
        assertEquals(ResearchLevelResult.APPLIED, world.completeForEditor(original, op, RESEARCH, 1, 1, Set.of(FIRST), _ -> null, () -> true));
        world.saveChanged(2);
        world.close(3);
        var reopened = new ResearchStateStore(legacy(), codec);
        var saved = reopened.read();
        assertNotNull(saved);
        assertTrue(saved.teams().getFirst().research().levels().isEmpty());
        assertEquals(2, saved.players().size());
        var restored = world(registry, reopened);
        restored.restore(saved);
        for (var player : members) {
            var personal = OwnerTeamResolution.resolved(player, player.toString(), OwnerTeamType.PLAYER, ObjectSet.of(player), player);
            var cloud = restored.captureCloud(personal, () -> true);
            assertNotNull(cloud);
            assertNotNull(cloud.view());
            assertEquals(1, cloud.view().completedLevel(RESEARCH));
        }
        var newcomer = new UUID(0, 99);
        var oldTeam = OwnerTeamResolution.resolved(newcomer, "1234", OwnerTeamType.PLAYER, ObjectSet.of(newcomer), newcomer);
        var view = restored.captureCloud(oldTeam, () -> true);
        assertNotNull(view);
        assertNotNull(view.view());
        assertEquals(0, view.view().completedLevel(RESEARCH));
        restored.close(4);
    }

    @Test
    void knownDefaultTeamMigratesWithoutMergingAnUnidentifiedTeamAndPrunesEmptyShards() throws IOException {
        var registry = itemRegistry();
        var codec = new ResearchWorldNbt(registry, new ResourceKeyNbt(_ -> null));
        var store = new ResearchStateStore(legacy(), codec);
        var direct = new Object2IntOpenHashMap<ResourceLocation>();
        direct.put(RESEARCH, 1);
        var queue = new TeamResearchQueue(registry, "ABCD");
        var entry = queue.enqueue(RESEARCH, 1, FIRST, 1);
        var main = new ResearchWorldSnapshot.Team("ABCD", ObjectSet.of(FIRST), ObjectSet.of(),
            new TeamOwnedResearch.Snapshot("ABCD", direct), queue.snapshot(), ObjectList.of());
        var unrelated = new ResearchWorldSnapshot.Team("CDEF", ObjectSet.of(FIRST), ObjectSet.of(),
            new TeamOwnedResearch.Snapshot("CDEF", direct), new TeamResearchQueue(registry, "CDEF").snapshot(), ObjectList.of());
        var empty = new ResearchWorldSnapshot.Team("8743", ObjectSet.of(), ObjectSet.of(),
            new TeamOwnedResearch("8743", registry).snapshot(), new TeamResearchQueue(registry, "8743").snapshot(), ObjectList.of());
        var saved = new ResearchWorldSnapshot(5, ObjectList.of(playerSnapshot(FIRST, false)), ObjectList.of(main, unrelated, empty));
        store.write(saved);
        var migrated = LegacyResearchTeams.migrate(saved, FIRST, "ABCD", ObjectSet.of("CDEF"));
        store.write(migrated);
        var actual = new ResearchStateStore(legacy(), codec).read();
        assertNotNull(actual);
        assertEquals(1, actual.players().getFirst().completedLevels().getInt(RESEARCH));
        assertEquals(2, actual.teams().size());
        assertEquals(unrelated, actual.teams().getFirst());
        var personal = actual.teams().getLast();
        assertEquals(FIRST.toString(), personal.teamId());
        assertEquals(entry, personal.queue().entries().getFirst());
        assertTrue(personal.research().levels().isEmpty());
        assertEquals(actual, LegacyResearchTeams.migrate(actual, FIRST, "ABCD", ObjectSet.of("CDEF")));
        try (var files = Files.list(root().resolve("teams"))) {
            assertEquals(2, files.count());
        }
    }

    private static ResearchWorld world(ResearchRegistry registry, ResearchStateStore store) {
        return new ResearchWorld(registry, ResearchPermissionPolicy.DEFAULT, snapshot -> {
            try {
                store.write(snapshot);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    private static ResearchRegistry itemRegistry() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(0)
                .resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        return registry;
    }

    @Test
    void roundTripKeepsOwnersOperationsAndIndexOrderingInSeparateFiles() throws IOException {
        var fixture = fixture();
        var expected = fixture.world(10, false);
        var store = store(fixture);
        assertNull(store.read());
        store.write(expected);
        assertEquals(expected, store(fixture).read());
        assertTrue(Files.isRegularFile(player(FIRST)));
        assertTrue(Files.isRegularFile(team()));
        assertTrue(Files.isRegularFile(root().resolve("downloads").resolve(new UUID(0, 10) + ".dat")));
        assertTrue(Files.isRegularFile(root().resolve("receipts").resolve(new UUID(0, 20) + ".dat")));
        assertTrue(Files.isRegularFile(root().resolve("imports").resolve(new UUID(0, 30) + ".dat")));
        var meta = readTag(root().resolve("meta.dat"));
        var players = meta.getTagList("Players", 10);
        assertEquals(SECOND.toString(), players.getCompoundTagAt(0).getString("Id"));
        assertEquals(FIRST.toString(), players.getCompoundTagAt(1).getString("Id"));
        assertFalse(meta.getTagList("Downloads", 10).getCompoundTagAt(0).hasKey("Payload"));
        assertFalse(Files.exists(root().resolve("pending.dat")));
        assertNoTemporaryFiles();
    }

    @Test
    void changingOnePlayerLeavesOtherOwnersAndEveryOperationByteAndTimestampUntouched() throws IOException {
        var fixture = fixture();
        var store = store(fixture);
        store.write(fixture.world(10, false));
        var untouched = ObjectList.of(player(SECOND), team(),
            root().resolve("downloads").resolve(new UUID(0, 10) + ".dat"),
            root().resolve("receipts").resolve(new UUID(0, 20) + ".dat"),
            root().resolve("imports").resolve(new UUID(0, 30) + ".dat"));
        var previous = new byte[untouched.size()][];
        for (int index = 0; index < untouched.size(); index++) {
            previous[index] = Files.readAllBytes(untouched.get(index));
            Files.setLastModifiedTime(untouched.get(index), SENTINEL);
        }
        var next = fixture.world(11, true);
        store.write(next);
        for (int index = 0; index < untouched.size(); index++) {
            assertArrayEquals(previous[index], Files.readAllBytes(untouched.get(index)));
            assertEquals(SENTINEL, Files.getLastModifiedTime(untouched.get(index)));
        }
        assertEquals(next, store(fixture).read());
    }

    @Test
    void noOpWriteDoesNotTouchAnyFileEvenAfterReopen() throws IOException {
        var fixture = fixture();
        var snapshot = fixture.world(10, false);
        store(fixture).write(snapshot);
        try (var files = Files.walk(root())) {
            for (var path : files.filter(Files::isRegularFile).toList()) Files.setLastModifiedTime(path, SENTINEL);
        }
        var reopened = store(fixture);
        assertEquals(snapshot, reopened.read());
        reopened.write(snapshot);
        try (var files = Files.walk(root())) {
            for (var path : files.filter(Files::isRegularFile).toList())
                assertEquals(SENTINEL, Files.getLastModifiedTime(path));
        }
    }

    @Test
    void partialShardInstallationRetainsJournalAndReopenCompletesTheWholeCheckpoint() throws IOException {
        var fixture = fixture();
        var before = fixture.world(10, false);
        var withoutTeam = new ResearchWorldSnapshot(10, before.players(), ObjectList.of());
        var store = store(fixture);
        store.write(withoutTeam);
        var oldMeta = Files.readAllBytes(root().resolve("meta.dat"));
        Files.createDirectory(root().resolve("teams"));
        Files.createDirectory(team());
        var expected = fixture.world(11, true);
        assertThrows(IOException.class, () -> store.write(expected));
        assertArrayEquals(oldMeta, Files.readAllBytes(root().resolve("meta.dat")));
        assertTrue(Files.isRegularFile(root().resolve("pending.dat")));
        Files.delete(team());
        assertEquals(expected, store(fixture).read());
        assertFalse(Files.exists(root().resolve("pending.dat")));
        assertNoTemporaryFiles();
    }

    @Test
    void nextWriteSettlesPriorRedoAndUsesItsCommittedBaselineForTheNextDelta() throws IOException {
        var fixture = fixture();
        var first = fixture.world(10, false);
        var store = store(fixture);
        store.write(new ResearchWorldSnapshot(10, first.players(), ObjectList.of()));
        Files.createDirectory(root().resolve("teams"));
        Files.createDirectory(team());
        assertThrows(IOException.class, () -> store.write(fixture.world(11, true)));
        Files.delete(team());
        // Reverting this player differs from the failed transaction, but matches the old baseline.
        var expected = fixture.world(12, false);
        store.write(expected);
        assertEquals(expected, store(fixture).read());
        assertFalse(Files.exists(root().resolve("pending.dat")));
    }

    @Test
    void committedJournalRepairsPostimagesBeforeRetryingFailedDeletion() throws IOException {
        var fixture = fixture();
        var store = store(fixture);
        store.write(fixture.world(10, false));
        Files.delete(player(SECOND));
        Files.createDirectory(player(SECOND));
        var full = fixture.world(11, true);
        var expected = new ResearchWorldSnapshot(11, ObjectList.of(playerSnapshot(FIRST, true)), full.teams(),
            full.downloads(), full.downloadReceipts(), full.diskImports());
        assertThrows(IOException.class, () -> store.write(expected));
        var pending = readTag(root().resolve("pending.dat"));
        assertEquals(pending.getCompoundTag("Meta").getString("Commit"), readTag(root().resolve("meta.dat")).getString("Commit"));
        Files.write(player(FIRST), new byte[]{1, 2, 3});
        Files.delete(player(SECOND));
        assertEquals(expected, store(fixture).read());
        assertFalse(Files.exists(player(SECOND)));
        assertFalse(Files.exists(root().resolve("pending.dat")));
    }

    @Test
    void removalsDeleteAllCategoriesAndDoNotKeepOrphanOperationsInMeta() throws IOException {
        var fixture = fixture();
        var store = store(fixture);
        store.write(fixture.world(10, false));
        var empty = new ResearchWorldSnapshot(11, ObjectList.of(), ObjectList.of());
        store.write(empty);
        assertEquals(empty, store(fixture).read());
        try (var files = Files.walk(root())) {
            assertEquals(ObjectList.of(root().resolve("meta.dat")), files.filter(Files::isRegularFile).toList());
        }
    }

    @Test
    void unrelatedJournalGenerationFailsBeforeChangingTheCommittedFiles() throws IOException {
        var fixture = fixture();
        var store = store(fixture);
        var before = fixture.world(10, false);
        store.write(new ResearchWorldSnapshot(10, before.players(), ObjectList.of()));
        Files.createDirectory(root().resolve("teams"));
        Files.createDirectory(team());
        assertThrows(IOException.class, () -> store.write(fixture.world(11, true)));
        var pendingPath = root().resolve("pending.dat");
        var pending = readTag(pendingPath);
        pending.setString("BaseCommit", UUID.randomUUID().toString());
        writeTag(pendingPath, pending);
        var meta = Files.readAllBytes(root().resolve("meta.dat"));
        assertThrows(IOException.class, () -> store(fixture).read());
        assertArrayEquals(meta, Files.readAllBytes(root().resolve("meta.dat")));
        assertTrue(Files.exists(pendingPath));
    }

    @Test
    void missingOrCorruptIndexedShardNeverLoadsTheRetainedLegacyCheckpoint() throws IOException {
        var fixture = fixture();
        var original = fixture.world(10, false);
        new ResearchStateFile(legacy(), fixture.codec()).write(original);
        assertEquals(original, store(fixture).read());
        var bytes = Files.readAllBytes(player(FIRST));
        Files.write(player(FIRST), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> store(fixture).read());
        Files.write(player(FIRST), bytes);
        Files.delete(player(FIRST));
        assertThrows(IOException.class, () -> store(fixture).read());
        assertEquals(original, new ResearchStateFile(legacy(), fixture.codec()).read());
    }

    @Test
    void migrationRetainsLegacyBytesAndEvidenceRejectsLostStoreRoot() throws IOException {
        var fixture = fixture();
        var expected = fixture.world(10, false);
        new ResearchStateFile(legacy(), fixture.codec()).write(expected);
        var bytes = Files.readAllBytes(legacy());
        Files.setLastModifiedTime(legacy(), SENTINEL);
        assertEquals(expected, store(fixture).read());
        assertArrayEquals(bytes, Files.readAllBytes(legacy()));
        assertEquals(SENTINEL, Files.getLastModifiedTime(legacy()));
        assertTrue(Files.isRegularFile(directory.resolve("prototype_eng_research.migrated")));
        Files.move(root(), directory.resolve("lost-store"));
        assertThrows(IOException.class, () -> store(fixture).read());
        assertArrayEquals(bytes, Files.readAllBytes(legacy()));
    }

    @Test
    void emptyInitializedDirectoryOrMissingMetadataIsAnExplicitError() throws IOException {
        var fixture = fixture();
        Files.createDirectory(root());
        assertThrows(IOException.class, () -> store(fixture).read());
        Files.delete(root());
        store(fixture).write(new ResearchWorldSnapshot(0, ObjectList.of(), ObjectList.of()));
        Files.delete(root().resolve("meta.dat"));
        assertThrows(IOException.class, () -> store(fixture).read());
    }

    @Test
    void unpublishedBootstrapResidueIsIgnoredWhileTheFirstVisibleRootHasACompleteCheckpoint() throws IOException {
        var fixture = fixture();
        var residue = Files.createDirectory(directory.resolve("prototype_eng_research-bootstrap-interrupted"));
        var partial = residue.resolve("pending.dat");
        var evidence = new byte[]{1, 2, 3};
        Files.write(partial, evidence);
        var store = store(fixture);
        assertNull(store.read());
        var expected = fixture.world(10, false);
        store.write(expected);
        assertEquals(expected, store(fixture).read());
        assertArrayEquals(evidence, Files.readAllBytes(partial));
        assertFalse(Files.exists(root().resolve("pending.dat")));
    }

    @Test
    void corruptMetadataJournalAndLegacyEnvelopeFailWithoutReplacingEvidence() throws IOException {
        var fixture = fixture();
        var invalid = new NBTTagCompound();
        writeTag(legacy(), invalid);
        var bytes = Files.readAllBytes(legacy());
        assertThrows(IOException.class, () -> store(fixture).read());
        assertArrayEquals(bytes, Files.readAllBytes(legacy()));
        Files.delete(legacy());
        store(fixture).write(fixture.world(10, false));
        writeTag(root().resolve("meta.dat"), invalid);
        assertThrows(IOException.class, () -> store(fixture).read());
        Files.write(root().resolve("pending.dat"), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> store(fixture).read());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root().resolve("pending.dat")));
    }

    private ResearchStateStore store(Fixture fixture) {
        return new ResearchStateStore(legacy(), fixture.codec());
    }

    private Path legacy() {
        return directory.resolve("prototype_eng_research.dat");
    }

    private Path root() {
        return directory.resolve("prototype_eng_research");
    }

    private Path player(UUID id) {
        return root().resolve("players").resolve(id + ".dat");
    }

    private Path team() {
        try {
            return root().resolve("teams").resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                                                                         .digest("team / 中文".getBytes(StandardCharsets.UTF_8))) + ".dat");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static NBTTagCompound readTag(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static void writeTag(Path path, NBTTagCompound tag) throws IOException {
        try (var output = Files.newOutputStream(path)) {
            CompressedStreamTools.writeCompressed(tag, output);
        }
    }

    private static PlayerResearchState.Snapshot playerSnapshot(UUID id, boolean completed) {
        var state = new PlayerResearchState(id);
        if (completed) state.completeAtLeast(RESEARCH, 1);
        return state.snapshot();
    }

    private static Fixture fixture() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(0)
                                                                     .resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        var team = new TeamOwnedResearch("team / 中文", registry);
        var snapshot = new ResearchWorldSnapshot.Team(team.teamId(), new ObjectOpenHashSet<>(ObjectList.of(FIRST, SECOND)),
            new ObjectOpenHashSet<>(), team.snapshot(), new TeamResearchQueue(registry, team.teamId()).snapshot(), ObjectList.of());
        return new Fixture(new ResearchWorldNbt(registry, new ResourceKeyNbt(_ -> null)), snapshot);
    }

    private record Fixture(ResearchWorldNbt codec, ResearchWorldSnapshot.Team team) {
        ResearchWorldSnapshot world(long time, boolean completed) {
            var output = new NBTTagCompound();
            output.setString("id", "minecraft:paper");
            output.setByte("Count", (byte) 1);
            var material = new ResearchDownloadMaterial(FIRST, new UUID(0, 10), 0, output, new NBTTagCompound(),
                new NBTTagCompound(), ResearchDownloadMaterial.Status.PREPARED);
            var intent = new ResearchDownloadOperation.Intent(FIRST, new UUID(0, 11), DATABASE, team.teamId(), RESEARCH, 1, 0, false, null);
            var plan = new ResearchDownloadSources.Plan(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM, RESEARCH, 1, 0,
                team.teamId(), ObjectList.of(FIRST), ObjectList.of(), 0, 0);
            var operation = new ResearchDownloadOperation(new UUID(0, 10), intent, plan, material, output, 1, 1,
                ResearchDownloadOperation.Status.PREPARED, false, ObjectList.of());
            var receipt = new ResearchDownloadReceipt(new UUID(0, 20), intent, plan, output, 1, ResearchDownloadReceipt.Outcome.ABORTED, ObjectList.of());
            return new ResearchWorldSnapshot(time, ObjectList.of(playerSnapshot(SECOND, false), playerSnapshot(FIRST, completed)),
                ObjectList.of(team), ObjectList.of(operation), ObjectList.of(receipt), ObjectList.of(disk(output)));
        }

        private ResearchDiskOperation disk(NBTTagCompound output) {
            var database = new DataCenterResearchSnapshot(DATABASE, 1, 16, 0, new Object2IntOpenHashMap<>());
            var commit = new ResearchDiskCommit(new UUID(0, 30), FIRST, team.teamId(), 1, new Object2IntOpenHashMap<>(), database, database);
            var diskMaterial = new ResearchDownloadMaterial(FIRST, commit.operationId(), 0, output, new NBTTagCompound(),
                new NBTTagCompound(), ResearchDownloadMaterial.Status.PREPARED);
            var disk = new ResearchDiskOperation(new ResearchDiskOperation.Intent(FIRST, new UUID(0, 31), DATABASE, team.teamId(), 0),
                new ResearchDataCenters.Target(DATABASE, new UUID(0, 4), new MachineIdentity(new ResourceLocation("test", "database"), 0, BlockPos.ORIGIN)),
                commit, diskMaterial, 1, ResearchDiskOperation.Status.PREPARED, false, output, 1, ObjectList.of());
            disk.validateSaved();
            return disk;
        }
    }
}
