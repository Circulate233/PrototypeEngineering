package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchWorldPersistenceTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final UUID SESSION = new UUID(0, 3);
    private static final ResourceLocation MANUAL = id("manual");
    private static final ResourceLocation OWNED = id("owned");
    private static final ResourceLocation PERSONAL = id("personal");
    @TempDir
    Path directory;

    private static ResearchWorldSnapshot world(Fixture fixture, ObjectList<ManualResearchSession> sessions) {
        var team = new ResearchWorldSnapshot.Team("team", new ObjectOpenHashSet<>(ObjectList.of(PLAYER)), new ObjectOpenHashSet<>(ObjectList.of(PERSONAL)),
            fixture.research().snapshot(), fixture.queue().snapshot(), sessions);
        return new ResearchWorldSnapshot(10, ObjectList.of(player()), ObjectList.of(team));
    }

    private static ManualResearchSession active(Fixture fixture) {
        return ManualResearchSession.newActive(SESSION, "team", fixture.queue().activeHead().orElseThrow().key(), PLAYER, 1);
    }

    private static PlayerResearchState.Snapshot player() {
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        levels.put(PERSONAL, 1);
        return new PlayerResearchState.Snapshot(PLAYER, levels, ObjectList.of(RewardClaim.unclaimed(new RewardClaim.Key(PLAYER, PERSONAL, 1), 1)));
    }

    private static Fixture fixture() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(MANUAL).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.register(ResearchDefinition.builder(OWNED).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        var event = new RewardChild.Event(id("event"), (_, _) -> RewardEventAction.Result.ALREADY_APPLIED);
        var group = new RewardGroup(id("choice"), ObjectList.of(event));
        registry.register(ResearchDefinition.builder(PERSONAL).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).rewardGroup(1, group).build()).build());
        registry.freeze();
        var team = new TeamOwnedResearch("team", registry);
        var completion = team.prepareCompletion(OWNED, 1);
        completion.commit();
        completion.complete();
        var queue = new TeamResearchQueue(registry, "team");
        queue.enqueue(MANUAL, 1, PLAYER, 1);
        return new Fixture(new ResearchWorldNbt(registry, new ResourceKeyNbt(_ -> null)), team, queue);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    @Test
    void composedCodecRetainsPlayerTeamQueueBlacklistAndActiveSessionFacts() {
        var fixture = fixture();
        var snapshot = world(fixture, ObjectList.of(active(fixture)));
        var decoded = fixture.codec().decode(fixture.codec().encode(snapshot));
        assertEquals(snapshot, decoded);
        assertEquals(ManualResearchSession.Status.ACTIVE, decoded.teams().getFirst().manualSessions().getFirst().status());
        assertEquals(ManualResearchSession.Status.PAUSED, decoded.teams().getFirst().manualSessions().getFirst().restored().status());
        assertTrue(decoded.teams().getFirst().blacklist().contains(PERSONAL));
        assertEquals(1, decoded.players().getFirst().rewardClaims().size());
    }

    @Test
    void duplicatePlayersTeamsSessionsAndQueueIdsAreRejected() {
        var fixture = fixture();
        var team = world(fixture, ObjectList.of()).teams().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(10, ObjectList.of(player(), player()), ObjectList.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(10, ObjectList.of(player()), ObjectList.of(team, team)));
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(active(fixture), active(fixture))));
        var entry = fixture.queue().activeHead().orElseThrow();
        var queue = new TeamResearchQueue.Snapshot("team", ObjectList.of(entry, entry), team.queue().progress(), 2, 0, 1, -1, -1);
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot.Team("team", team.knownMembers(), team.blacklist(), team.research(), queue, ObjectList.of()));
    }

    @Test
    void teamOwnershipAndExactSessionQueueReferencesAreRequired() {
        var fixture = fixture();
        var team = world(fixture, ObjectList.of()).teams().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot.Team("other", team.knownMembers(), team.blacklist(), team.research(), team.queue(), ObjectList.of()));
        var changed = ManualResearchSession.newActive(SESSION, "team", new TeamResearchQueue.Key(OWNED, 1, 1), PLAYER, 1);
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(changed)));
        var wrongTeam = ManualResearchSession.newActive(SESSION, "other", active(fixture).queueKey(), PLAYER, 1);
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(wrongTeam)));
    }

    @Test
    void historicalMembershipMayOverlapButSessionAndOperationIdsAreWorldUnique() {
        var fixture = fixture();
        var original = world(fixture, ObjectList.of()).teams().getFirst();
        var otherQueue = new TeamResearchQueue.Snapshot("other", original.queue().entries(), original.queue().progress(),
            original.queue().nextEntryId(), original.queue().epoch(),
            original.queue().updatedAtWorldTime(), original.queue().lastGrantedEntryId(), original.queue().lastGrantedWorldTime());
        var otherResearch = new TeamOwnedResearch.Snapshot("other", original.research().levels());
        var other = new ResearchWorldSnapshot.Team("other", original.knownMembers(), original.blacklist(), otherResearch, otherQueue, ObjectList.of());
        assertEquals(2, new ResearchWorldSnapshot(10, ObjectList.of(player()), ObjectList.of(original, other)).teams().size());
        var duplicate = ManualResearchSession.newActive(SESSION, "other", active(fixture).queueKey(), PLAYER, 1);
        var otherSession = new ResearchWorldSnapshot.Team("other", other.knownMembers(), other.blacklist(), otherResearch, otherQueue, ObjectList.of(duplicate));
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(10, ObjectList.of(player()),
            ObjectList.of(world(fixture, ObjectList.of(active(fixture))).teams().getFirst(), otherSession)));
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>();
        amounts.put(new ManualMaterialKey(id("input"), 0), 1);
        var intent = new ManualResearchSession.MaterialIntent(new UUID(0, 4), PLAYER, 0, amounts, amounts, 1, 0);
        var first = active(fixture).beginMaterial(intent, 2, null);
        var second = ManualResearchSession.newActive(OTHER, "other", first.queueKey(), PLAYER, 1).beginMaterial(intent, 2, null);
        var pendingOther = new ResearchWorldSnapshot.Team("other", other.knownMembers(), other.blacklist(), otherResearch, otherQueue, ObjectList.of(second));
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(10, ObjectList.of(player()),
            ObjectList.of(world(fixture, ObjectList.of(first)).teams().getFirst(), pendingOther)));
    }

    @Test
    void offlineContributorAndFrozenMembersRequireGlobalPlayerSources() {
        var fixture = fixture();
        var baseline = new Object2IntOpenHashMap<UUID>();
        baseline.put(OTHER, 0);
        var complete = active(fixture).beginCompletion(new ManualResearchSession.CompletionIntent(2, baseline));
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(complete)));
        var current = ManualResearchSession.newActive(SESSION, "team", active(fixture).queueKey(), OTHER, 1);
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(current)));
        var key = new ManualMaterialKey(id("input"), 0);
        var pending = new Object2LongOpenHashMap<ManualMaterialKey>();
        pending.put(key, 1);
        var material = new ManualResearchSession.MaterialIntent(new UUID(0, 4), OTHER, 0, pending, pending, 1, 0);
        var preparing = new ManualResearchSession(SESSION, "team", active(fixture).queueKey(), null, 1,
            ManualResearchSession.Status.MATERIAL_COMMIT_PENDING, null, Object2LongMaps.emptyMap(), material, null);
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(preparing)));
    }

    @Test
    void completionStatesRequireHeadUntilRemovalThenAllowTheNextHead() {
        var fixture = fixture();
        var baselines = new Object2IntOpenHashMap<UUID>();
        baselines.put(PLAYER, 0);
        var complete = active(fixture).beginCompletion(new ManualResearchSession.CompletionIntent(2, baselines));
        assertEquals(world(fixture, ObjectList.of(complete)), fixture.codec().decode(fixture.codec().encode(world(fixture, ObjectList.of(complete)))));
        var propagated = complete.markCompletionPropagated();
        assertEquals(world(fixture, ObjectList.of(propagated)), fixture.codec().decode(fixture.codec().encode(world(fixture, ObjectList.of(propagated)))));
        fixture.queue().enqueue(OWNED, 1, PLAYER, 2);
        fixture.queue().removeHead(complete.queueKey());
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(propagated)));
        var removed = propagated.markQueueHeadRemoved();
        var terminal = world(fixture, ObjectList.of(removed));
        assertEquals(terminal, fixture.codec().decode(fixture.codec().encode(terminal)));
    }

    @Test
    void futureQueueSessionAndRewardEventsCannotEnterAWorldCheckpoint() {
        var fixture = fixture();
        var team = world(fixture, ObjectList.of(active(fixture))).teams().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(0, ObjectList.of(player()), ObjectList.of(team)));
        var future = active(fixture).resume(PLAYER, 11);
        assertThrows(IllegalArgumentException.class, () -> world(fixture, ObjectList.of(future)));
        var claims = ObjectList.of(RewardClaim.unclaimed(new RewardClaim.Key(PLAYER, PERSONAL, 1), 11));
        var player = new PlayerResearchState.Snapshot(PLAYER, Object2IntMaps.emptyMap(), claims);
        assertThrows(IllegalArgumentException.class, () -> new ResearchWorldSnapshot(10, ObjectList.of(player), ObjectList.of()));
    }

    @Test
    void strictTopLevelRejectsUnknownFieldsWrongTypesAndVersions() {
        var fixture = fixture();
        var codec = fixture.codec();
        var world = world(fixture, ObjectList.of());
        var unknown = codec.encode(world);
        unknown.setString("Extra", "field");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown));
        var version = codec.encode(world);
        version.setInteger("Version", 999);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(version));
        var time = codec.encode(world);
        time.setInteger("ServerTime", 10);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(time));
        var players = codec.encode(world);
        players.setTag("Players", new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(players));
    }

    @Test
    void strictTeamListsRejectDuplicateAndNoncanonicalMembersAndBlacklistIds() {
        var fixture = fixture();
        var codec = fixture.codec();
        var world = world(fixture, ObjectList.of());
        var members = codec.encode(world);
        members.getTagList("Teams", 10).getCompoundTagAt(0).getTagList("KnownMembers", 8).appendTag(new NBTTagString(PLAYER.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(members));
        var noncanonical = codec.encode(world);
        noncanonical.getTagList("Teams", 10).getCompoundTagAt(0).getTagList("KnownMembers", 8).set(0, new NBTTagString("0-0-0-0-1"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(noncanonical));
        var blacklist = codec.encode(world);
        blacklist.getTagList("Teams", 10).getCompoundTagAt(0).getTagList("Blacklist", 8).appendTag(new NBTTagString(PERSONAL.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(blacklist));
    }

    @Test
    void duplicatedGlobalPlayersAndMissingFrozenReferencesFailAfterNestedDecode() {
        var fixture = fixture();
        var codec = fixture.codec();
        var world = world(fixture, ObjectList.of(active(fixture)));
        var duplicate = codec.encode(world);
        var players = duplicate.getTagList("Players", 10);
        players.appendTag(players.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(duplicate));
        var missing = codec.encode(world);
        missing.getTagList("Players", 10).removeTag(0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(missing));
    }

    @Test
    void teamSourceAndBlacklistResolveAgainstTheFrozenRegistry() {
        var fixture = fixture();
        var codec = fixture.codec();
        var world = world(fixture, ObjectList.of());
        var blacklist = codec.encode(world);
        blacklist.getTagList("Teams", 10).getCompoundTagAt(0).getTagList("Blacklist", 8).set(0, new NBTTagString("test:unknown"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(blacklist));
        var source = codec.encode(world);
        source.getTagList("Teams", 10).getCompoundTagAt(0).getCompoundTag("Research").getTagList("Levels", 10).getCompoundTagAt(0).setString("Id", PERSONAL.toString());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(source));
    }

    @Test
    void realCompressedFileRoundTripsAndReplacesAnExistingCheckpoint() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("prototype_eng_research.dat");
        var file = new ResearchStateFile(target, fixture.codec());
        assertTrue(file.read().isEmpty());
        var first = world(fixture, ObjectList.of(active(fixture)));
        file.write(first);
        assertEquals(first, file.read().orElseThrow());
        var second = new ResearchWorldSnapshot(11, first.players(), first.teams());
        file.write(second);
        assertEquals(second, file.read().orElseThrow());
        try (var input = Files.newInputStream(target)) {
            assertEquals(11, CompressedStreamTools.readCompressed(input).getCompoundTag("data").getLong("ServerTime"));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void corruptExistingFilePropagatesItsErrorAndIsNeverTreatedAsEmpty() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("prototype_eng_research.dat");
        var file = new ResearchStateFile(target, fixture.codec());
        byte[] corrupt = {1, 2, 3, 4};
        Files.write(target, corrupt);
        assertThrows(IOException.class, file::read);
        assertArrayEquals(corrupt, Files.readAllBytes(target));
    }

    @Test
    void malformedCompressedDataPropagatesValidationFailure() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("prototype_eng_research.dat");
        var file = new ResearchStateFile(target, fixture.codec());
        try (var output = Files.newOutputStream(target)) {
            CompressedStreamTools.writeCompressed(new NBTTagCompound(), output);
        }
        var bytes = Files.readAllBytes(target);
        assertThrows(IllegalArgumentException.class, file::read);
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void failedEncodeLeavesThePreviousCheckpointUntouched() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("prototype_eng_research.dat");
        var file = new ResearchStateFile(target, fixture.codec());
        var valid = world(fixture, ObjectList.of());
        file.write(valid);
        var bytes = Files.readAllBytes(target);
        var original = valid.teams().getFirst();
        var invalid = new ResearchWorldSnapshot.Team(original.teamId(), original.knownMembers(), new ObjectOpenHashSet<>(ObjectList.of(id("unknown"))),
            original.research(), original.queue(), original.manualSessions());
        assertThrows(IllegalArgumentException.class, () -> file.write(new ResearchWorldSnapshot(10, valid.players(), ObjectList.of(invalid))));
        assertArrayEquals(bytes, Files.readAllBytes(target));
        assertEquals(valid, file.read().orElseThrow());
        assertNoTemporaryFiles();
    }

    @Test
    void atomicMoveFailureKeepsTheOriginalDestinationAndCleansTheTemporaryFile() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("blocked.dat");
        Files.createDirectory(target);
        var sentinel = target.resolve("keep.txt");
        Files.writeString(sentinel, "original");
        var file = new ResearchStateFile(target, fixture.codec());
        var error = assertThrows(IOException.class, () -> file.write(world(fixture, ObjectList.of())));
        assertEquals("original", Files.readString(sentinel));
        assertEquals(0, error.getSuppressed().length);
        assertNoTemporaryFiles();
        assertThrows(IOException.class, file::read);
    }

    @Test
    void missingParentAndNonregularExistingTargetsRemainErrors() throws IOException {
        var fixture = fixture();
        var missing = new ResearchStateFile(directory.resolve("missing").resolve("research.dat"), fixture.codec());
        assertThrows(IOException.class, () -> missing.write(world(fixture, ObjectList.of())));
        var parent = directory.resolve("file-parent");
        Files.writeString(parent, "preserve");
        var invalid = new ResearchStateFile(parent.resolve("research.dat"), fixture.codec());
        assertThrows(IOException.class, () -> invalid.write(world(fixture, ObjectList.of())));
        assertEquals("preserve", Files.readString(parent));
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private record Fixture(ResearchWorldNbt codec, TeamOwnedResearch research, TeamResearchQueue queue) {
    }
}
