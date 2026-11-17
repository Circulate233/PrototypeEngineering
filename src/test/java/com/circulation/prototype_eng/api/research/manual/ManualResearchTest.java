package com.circulation.prototype_eng.api.research.manual;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue") // Test setup establishes successful admission or capture before reading the result.
class ManualResearchTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final ResourceLocation ID = new ResourceLocation("test", "manual");
    private static final ManualResearchInventory INVENTORY = (ManualResearchInventory) Proxy.newProxyInstance(
        ManualResearchInventory.class.getClassLoader(), new Class<?>[]{ManualResearchInventory.class}, (_, method, args) -> switch (method.getName()) {
            case "playerId" -> PLAYER;
            case "findBySession" -> null;
            case "availableMaterials" -> {
                assertTrue(((AEKey2LongMap) args[0]).isEmpty());
                yield ImmutableResourceAmounts.empty();
            }
            default ->
                throw new AssertionError("Time-only research must not mutate player inventory: " + method.getName());
        });

    private static ManualResearch.Context context(long time) {
        return new ManualResearch.Context(time, Set.of(PLAYER), Set.of(PLAYER), () -> true);
    }

    @Test
    void cancellationAndReentryKeepElapsedWork() {
        var fixture = new Fixture(4);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(1));
        assertEquals(ManualResearch.Status.CANCELLED, fixture.runtime.cancelInvalidated(fixture.entry.key().queueEntryId(), context(2), INVENTORY));
        fixture.entry = fixture.queue.enqueue(ID, 1, PLAYER, 3);
        fixture.runtime.begin(fixture.entry, PLAYER, context(3));
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(4));
        assertEquals(2, fixture.queue.activeProgress().snapshot().elapsedTicks());
    }

    @Test
    void checkpointRecoveryDoesNotRepeatTheRecordedWorkTick() {
        var fixture = new Fixture(4);
        fixture.failSave = true;
        assertThrows(IllegalStateException.class, () -> fixture.advance(1));
        fixture.runtime.retryCheckpoint();
        fixture.reopen();
        assertEquals(ManualResearch.Status.RESUMED, fixture.runtime.takeover(fixture.entry.key().queueEntryId(), PLAYER, context(1)));
        assertEquals(ManualResearch.Status.WAITING_FOR_TURN, fixture.advance(1));
        assertEquals(1, fixture.queue.activeProgress().snapshot().elapsedTicks());
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2));
        assertEquals(2, fixture.queue.activeProgress().snapshot().elapsedTicks());
    }

    @Test
    void timeOnlyResearchStillRequiresEachWorkTick() {
        var fixture = new Fixture(3);
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(1));
        assertEquals(ManualResearch.Status.WAITING_FOR_TURN, fixture.advance(1));
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2));
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(3));
        assertEquals(1, fixture.team.completedLevel(ID));
    }

    @Test
    void ordinaryElapsedWorkCanDeferSavingButCompletionStillRequiresDurability() {
        var fixture = new Fixture(3, true);
        int initialSaves = fixture.durableSaves;
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(1));
        assertEquals(ManualResearch.Status.ADVANCED, fixture.advance(2));
        assertEquals(initialSaves, fixture.durableSaves);
        assertEquals(2, fixture.progressedSaves);
        assertEquals(0, fixture.saved.queue().progress().getFirst().elapsedTicks());
        assertEquals(ManualResearch.Status.COMPLETED, fixture.advance(3));
        assertTrue(fixture.durableSaves > initialSaves);
        assertEquals(2, fixture.progressedSaves);
        assertEquals(1, fixture.team.completedLevel(ID));
        assertEquals(0, fixture.saved.queue().entries().size());
    }

    @Test
    void failedDeferredNotificationRetriesItsExactProgressWithoutRepeatingTheTick() {
        var fixture = new Fixture(4, true);
        fixture.failProgress = true;
        assertThrows(IllegalStateException.class, () -> fixture.advance(1));
        assertTrue(fixture.runtime.checkpointPending());
        fixture.runtime.retryCheckpoint();
        assertFalse(fixture.runtime.checkpointPending());
        assertEquals(2, fixture.progressedSaves);
        assertEquals(ManualResearch.Status.WAITING_FOR_TURN, fixture.advance(1));
        assertEquals(1, fixture.queue.activeProgress().snapshot().elapsedTicks());
    }

    private static final class Fixture {
        final ResearchRegistry registry = new ResearchRegistry();
        final Map<UUID, PlayerResearchState> players = Map.of(PLAYER, new PlayerResearchState(PLAYER));
        TeamResearchQueue queue;
        TeamOwnedResearch team;
        TeamResearchQueue.Entry entry;
        ManualResearch runtime;
        ManualResearch.CheckpointState saved;
        boolean failSave, failProgress;
        final boolean deferProgress;
        int durableSaves, progressedSaves;

        Fixture(long duration) {
            this(duration, false);
        }

        Fixture(long duration, boolean deferProgress) {
            this.deferProgress = deferProgress;
            var stage = ResearchLevelStage.builder(1, 1).durationTicks(duration).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build());
            registry.register(ResearchDefinition.builder(ID).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(stage.build()).build());
            registry.freeze();
            queue = new TeamResearchQueue(registry, "team");
            team = new TeamOwnedResearch("team", registry);
            runtime = new ManualResearch(queue, team, players, checkpoint());
            entry = queue.enqueue(ID, 1, PLAYER, 0);
            runtime.begin(entry, PLAYER, context(0));
        }

        void save(ManualResearch.CheckpointState state) {
            if (failSave) {
                failSave = false;
                throw new IllegalStateException("Research checkpoint write failed");
            }
            durableSaves++;
            saved = state;
        }

        @SuppressWarnings("ReturnOfInnerClass")
            // The retained callback deliberately observes the owning fixture.
        ManualResearch.Checkpoint checkpoint() {
            if (!deferProgress) return this::save;
            return new ManualResearch.Checkpoint() {
                @Override
                public void persist(ManualResearch.CheckpointState state) {
                    save(state);
                }

                @Override
                public void progressed(ManualResearch.CheckpointState state) {
                    progressedSaves++;
                    if (failProgress) {
                        failProgress = false;
                        throw new IllegalStateException("Deferred progress notification failed");
                    }
                    assertEquals(queue.snapshot(), state.queue());
                    assertEquals(runtime.sessions(), state.sessions());
                }
            };
        }

        ManualResearch.Status advance(long time) {
            return runtime.advance(entry.key().queueEntryId(), context(time), INVENTORY);
        }

        void reopen() {
            queue = TeamResearchQueue.restore(registry, saved.queue());
            team = new TeamOwnedResearch("team", registry);
            team.restore(saved.team());
            var sessions = saved.sessions();
            runtime = new ManualResearch(queue, team, players, checkpoint());
            runtime.restore(sessions);
        }
    }
}
