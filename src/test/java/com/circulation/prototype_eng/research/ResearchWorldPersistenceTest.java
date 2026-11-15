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
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchWorldPersistenceTest {
    private static final UUID PLAYER = new UUID(0, 1);
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
        var head = fixture.queue().activeHead();
        assertNotNull(head);
        return ManualResearchSession.newActive(SESSION, "team", head.key(), PLAYER, 1);
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
    void realCompressedFileRoundTripsAndReplacesAnExistingCheckpoint() throws IOException {
        var fixture = fixture();
        var target = directory.resolve("prototype_eng_research.dat");
        var file = new ResearchStateFile(target, fixture.codec());
        assertNull(file.read());
        var first = world(fixture, ObjectList.of(active(fixture)));
        file.write(first);
        assertEquals(first, file.read());
        var second = new ResearchWorldSnapshot(11, first.players(), first.teams());
        file.write(second);
        assertEquals(second, file.read());
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
