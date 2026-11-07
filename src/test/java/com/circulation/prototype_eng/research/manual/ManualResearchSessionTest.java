package com.circulation.prototype_eng.research.manual;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSessionNbt;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.circulation.prototype_eng.api.research.manual.ManualResearchSession.PauseReason.*;
import static com.circulation.prototype_eng.api.research.manual.ManualResearchSession.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualResearchSessionTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final UUID OPERATION = new UUID(0, 3);
    private static final ResourceLocation RESEARCH = new ResourceLocation("test", "manual");
    private static final ManualMaterialKey MATERIAL = new ManualMaterialKey(new ResourceLocation("test", "input"), 0);
    private static final TeamResearchQueue.Key KEY = new TeamResearchQueue.Key(RESEARCH, 1, 1);

    @Test void worldRestorePausesOnlyActiveSessionsAcrossAllNineStates() {
        var active = active();
        var pending = active.beginMaterial(intent(3, 3), 2, completion(2));
        var committed = pending.markMaterialCommitted(OPERATION);
        var settled = committed.settleMaterial(OPERATION, 3);
        var complete = settled.finishMaterialReceipt(OPERATION, 4);
        var states = List.of(active, active.pause(EXPLICIT, 2), pending, committed,
            pending.abortPrepared(OPERATION, CONTRIBUTOR_TEAM_CHANGED, 3), settled, complete,
            complete.markCompletionPropagated(), complete.markCompletionPropagated().markQueueHeadRemoved());
        assertEquals(9, states.stream().map(ManualResearchSession::status).distinct().count());
        for (var state : states) {
            if (state.status() == ACTIVE) {
                var restored = state.restored();
                assertEquals(PAUSED, restored.status());
                assertEquals(WORLD_RESTORED, restored.pauseReason());
                assertNull(restored.contributor());
                assertEquals(state.lastActivity(), restored.lastActivity());
            } else { assertSame(state, state.restored()); }
        }
    }

    @Test void settleSwitchesToFrozenExpectedOnceAndKeepsTheExactIntent() {
        var prior = session(ACTIVE, PLAYER, null, amounts(2), null, null);
        var intent = intent(3, 5);
        var pending = prior.beginMaterial(intent, 2, null);
        assertEquals(2, pending.work().getLong(MATERIAL));
        var committed = pending.markMaterialCommitted(OPERATION);
        assertEquals(2, committed.work().getLong(MATERIAL));
        var settled = committed.settleMaterial(OPERATION, 3);
        assertEquals(5, settled.work().getLong(MATERIAL));
        assertSame(intent, settled.materialIntent());
        assertThrows(IllegalStateException.class, () -> settled.settleMaterial(OPERATION, 4));
        assertThrows(IllegalStateException.class, () -> committed.markMaterialCommitted(OPERATION));
    }

    @Test void ordinaryReceiptRestoresContributorAfterRecovery() {
        var settled = active().beginMaterial(intent(3, 3), 2, null).markMaterialCommitted(OPERATION).settleMaterial(OPERATION, 3);
        var resumed = settled.restored().finishMaterialReceipt(OPERATION, 4);
        assertEquals(ACTIVE, resumed.status());
        assertEquals(PLAYER, resumed.contributor());
        assertNull(resumed.materialIntent());
        assertEquals(3, resumed.work().getLong(MATERIAL));
    }

    @Test void finalReceiptRetainsOriginalCompletionTimeAndFrozenMembers() {
        var members = new Object2IntOpenHashMap<UUID>();
        members.put(PLAYER, 0);
        var other = new UUID(0, 4);
        members.put(other, 2);
        var completion = new ManualResearchSession.CompletionIntent(2, members);
        var pending = active().beginMaterial(intent(3, 3), 2, completion);
        members.clear();
        var settled = pending.markMaterialCommitted(OPERATION).settleMaterial(OPERATION, 8).restored();
        var complete = settled.finishMaterialReceipt(OPERATION, 9);
        assertEquals(COMPLETION_PENDING, complete.status());
        assertSame(completion, complete.completionIntent());
        assertNotNull(complete.completionIntent());
        assertEquals(2, complete.completionIntent().worldTime());
        assertEquals(2, complete.completionIntent().baselines().getInt(other));
        assertEquals(-1, complete.completionIntent().baselines().getInt(new UUID(0, 99)));
        assertSame(completion, complete.markCompletionPropagated().markQueueHeadRemoved().completionIntent());
    }

    @Test void preparedTeamChangeAbortClearsCompletionWithoutAddingWork() {
        var pending = active().beginMaterial(intent(3, 3), 2, completion(2));
        var abort = pending.abortPrepared(OPERATION, CONTRIBUTOR_TEAM_CHANGED, 3);
        assertEquals(MATERIAL_ABORT_RECEIPT_PENDING, abort.status());
        assertNull(abort.completionIntent());
        assertTrue(abort.work().isEmpty());
        assertSame(pending.materialIntent(), abort.materialIntent());
        var paused = abort.restored().finishAbort(OPERATION, 4);
        assertEquals(PAUSED, paused.status());
        assertEquals(CONTRIBUTOR_TEAM_CHANGED, paused.pauseReason());
        assertTrue(paused.work().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> pending.abortPrepared(OPERATION, EXPLICIT, 3));
        assertThrows(IllegalStateException.class, () -> pending.markMaterialCommitted(OPERATION).abortPrepared(OPERATION, CONTRIBUTOR_TEAM_CHANGED, 3));
    }

    @Test void researchInvalidationAbortRetainsItsReasonAndPaidWorkThroughWorldRestoration() {
        var prior = session(ACTIVE, PLAYER, null, amounts(2), null, null);
        var pending = prior.beginMaterial(intent(1, 3), 2, completion(2));
        var abort = pending.abortPrepared(OPERATION, RESEARCH_INVALIDATED, 3);
        var saved = abort.restored();
        assertEquals(MATERIAL_ABORT_RECEIPT_PENDING, saved.status());
        assertEquals(RESEARCH_INVALIDATED, saved.pauseReason());
        assertNull(saved.completionIntent());
        assertEquals(2, saved.work().getLong(MATERIAL));
        var paused = saved.finishAbort(OPERATION, 4);
        assertEquals(PAUSED, paused.status());
        assertEquals(RESEARCH_INVALIDATED, paused.pauseReason());
        assertEquals(2, paused.work().getLong(MATERIAL));
        assertNull(paused.materialIntent());
    }

    @Test void pendingRecoveryRejectsResumeAndWrongOperationOrBackwardTime() {
        var pending = active().beginMaterial(intent(3, 3), 2, null);
        var wrong = new UUID(0, 99);
        assertThrows(IllegalStateException.class, () -> pending.resume(PLAYER, 3));
        assertThrows(IllegalArgumentException.class, () -> pending.markMaterialCommitted(wrong));
        assertThrows(IllegalArgumentException.class, () -> pending.abortPrepared(wrong, CONTRIBUTOR_TEAM_CHANGED, 3));
        var committed = pending.markMaterialCommitted(OPERATION);
        assertThrows(IllegalArgumentException.class, () -> committed.settleMaterial(wrong, 3));
        assertThrows(IllegalArgumentException.class, () -> committed.settleMaterial(OPERATION, 1));
        assertThrows(IllegalArgumentException.class, () -> active().pause(EXPLICIT, 0));
        assertThrows(IllegalArgumentException.class, () -> active().beginCompletion(completion(0)));
    }

    @Test void stateMatrixAndExpectedProjectionAreStrict() {
        assertEquals(COMPLETION_PENDING, session(COMPLETION_PENDING, null, null, amounts(0), null, completion(1)).status());
        assertThrows(IllegalArgumentException.class, () -> session(ACTIVE, null, null, amounts(0), null, null));
        assertThrows(IllegalArgumentException.class, () -> session(PAUSED, PLAYER, EXPLICIT, amounts(0), null, null));
        assertThrows(IllegalArgumentException.class, () -> session(MATERIAL_COMMITTED, null, null, amounts(2), intent(3, 3), null));
        assertThrows(IllegalArgumentException.class, () -> session(MATERIAL_SETTLED_RECEIPT_PENDING, null, null, amounts(2), intent(3, 3), null));
        assertThrows(IllegalArgumentException.class, () -> session(MATERIAL_ABORT_RECEIPT_PENDING, null, EXPLICIT, amounts(0), intent(3, 3), null));
        assertThrows(IllegalArgumentException.class, () -> session(COMPLETION_PENDING, null, null, amounts(0), null, null));
        assertThrows(IllegalArgumentException.class, () -> active().beginMaterial(intent(3, 4), 2, null));
        assertThrows(IllegalArgumentException.class, () -> active().beginMaterial(intent(3, 3), 2, completion(3)));
    }

    @Test void materialIntentRoundTripPreservesCompleteIdentityAndBothMaps() {
        var intent = intent(3, 5);
        var tag = ManualResearchSessionNbt.encodeMaterialIntent(intent);
        assertEquals(intent, ManualResearchSessionNbt.decodeMaterialIntent(tag));
        tag.getTagList("Expected", 10).getCompoundTagAt(0).setLong("Amount", 99);
        assertEquals(5, intent.expectedWork().getLong(MATERIAL));
    }

    @Test void snapshotsAreDeeplyReadOnlyIncludingEntryMutation() {
        var work = amounts(2);
        var session = session(ACTIVE, PLAYER, null, work, null, null);
        work.clear();
        assertEquals(2, session.work().getLong(MATERIAL));
        assertThrows(UnsupportedOperationException.class, () -> session.work().put(MATERIAL, 4));
        assertThrows(UnsupportedOperationException.class, () -> session.work().object2LongEntrySet().iterator().next().setValue(4L));
        assertThrows(UnsupportedOperationException.class, () -> session.work().defaultReturnValue(1));
        var completion = completion(2);
        assertThrows(UnsupportedOperationException.class, () -> completion.baselines().object2IntEntrySet().iterator().next().setValue(1));
        var intent = intent(3, 5);
        assertThrows(UnsupportedOperationException.class, () -> intent.pendingWork().object2LongEntrySet().iterator().next().setValue(1L));
        assertThrows(UnsupportedOperationException.class, () -> intent.expectedWork().object2LongEntrySet().iterator().next().setValue(1L));
    }

    @Test void negativeZeroAndOverflowAmountsAreRejectedBeforeTransitions() {
        assertThrows(IllegalArgumentException.class, () -> session(ACTIVE, PLAYER, null, amounts(-1), null, null));
        assertThrows(IllegalArgumentException.class, () -> intent(0, 1));
        assertThrows(IllegalArgumentException.class, () -> intent(3, 2));
        assertThrows(IllegalArgumentException.class, () -> new ManualMaterialKey(MATERIAL.requirementId(), -1));
        var nearLimit = session(ACTIVE, PLAYER, null, amounts(Long.MAX_VALUE), null, null);
        assertThrows(IllegalArgumentException.class, () -> nearLimit.beginMaterial(intent(1, Long.MAX_VALUE), 2, null));
        assertEquals(Long.MAX_VALUE, nearLimit.work().getLong(MATERIAL));
        var baseline = new Object2IntOpenHashMap<UUID>();
        baseline.put(PLAYER, -1);
        assertThrows(IllegalArgumentException.class, () -> new ManualResearchSession.CompletionIntent(1, baseline));
        assertThrows(IllegalArgumentException.class, () -> new ManualResearchSession.CompletionIntent(1, new Object2IntOpenHashMap<>()));
    }

    @Test void fullCodecPreservesActivePausedAndCompletionStatesBeforeWorldRestore() {
        var codec = codec(0);
        var complete = active().beginCompletion(completion(2));
        for (var value : List.of(active(), active().pause(EXPLICIT, 2), active().pause(RESEARCH_INVALIDATED, 2), complete,
            complete.markCompletionPropagated(), complete.markCompletionPropagated().markQueueHeadRemoved())) {
            assertEquals(value, codec.decode(codec.encode(value)));
        }
        assertEquals(ACTIVE, codec.decode(codec.encode(active())).status());
    }

    @Test void strictNbtRejectsUnknownFieldsTypesVersionsAndNoncanonicalIdentity() {
        var codec = codec(0);
        var unknown = codec.encode(active());
        unknown.setBoolean("Unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown));
        var version = codec.encode(active()); version.setInteger("Version", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(version));
        var type = codec.encode(active()); type.setInteger("LastActivity", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(type));
        var id = codec.encode(active()); id.setString("Id", "0-0-0-0-1");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(id));
        var status = codec.encode(active()); status.setString("Status", "active");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(status));
        var key = codec.encode(active()); key.getCompoundTag("QueueKey").setString("Research", "manual");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(key));
    }

    @Test void strictNbtRejectsDuplicateWorkAndCompletionMembers() {
        var work = ManualResearchSessionNbt.encodeWork(amounts(3));
        work.appendTag(work.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(work));
        var codec = codec(0);
        var completion = codec.encode(active().beginCompletion(completion(2)));
        var members = completion.getCompoundTag("Completion").getTagList("Baselines", 10);
        members.appendTag(members.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(completion));
    }

    @Test void strictNbtValidatesMaterialNestedFieldsAndListElements() {
        var wrongList = new NBTTagList(); wrongList.appendTag(new NBTTagString("invalid"));
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(wrongList));
        var material = ManualResearchSessionNbt.encodeMaterialIntent(intent(3, 5));
        material.setTag("Expected", new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeMaterialIntent(material));
        var work = ManualResearchSessionNbt.encodeWork(amounts(3));
        work.getCompoundTagAt(0).setInteger("Amount", 3);
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(work));
        var unknown = ManualResearchSessionNbt.encodeWork(amounts(3));
        unknown.getCompoundTagAt(0).setString("Extra", "field");
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(unknown));
    }

    @Test void fullCodecRejectsWrongTierUnknownMaterialAndInvalidQueueLevel() {
        assertThrows(IllegalArgumentException.class, () -> codec(1).encode(active()));
        var codec = codec(0);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(session(ACTIVE, PLAYER, null, amounts(1), null, null)));
        var level = codec.encode(active()); level.getCompoundTag("QueueKey").setInteger("Level", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(level));
        var unknown = codec.encode(active()); unknown.getCompoundTag("QueueKey").setString("Research", "test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown));
    }

    private static ManualResearchSession active() { return ManualResearchSession.newActive(SESSION, "team", KEY, PLAYER, 1); }
    private static ManualResearchSession session(ManualResearchSession.Status status, UUID player, ManualResearchSession.PauseReason reason,
        Object2LongMap<ManualMaterialKey> work, ManualResearchSession.MaterialIntent material, ManualResearchSession.CompletionIntent completion) {
        return new ManualResearchSession(SESSION, "team", KEY, player, 1, status, reason, work, material, completion);
    }
    private static ManualResearchSession.MaterialIntent intent(long pending, long expected) {
        return new ManualResearchSession.MaterialIntent(OPERATION, PLAYER, 1, amounts(pending), amounts(expected));
    }
    private static ManualResearchSession.CompletionIntent completion(long time) {
        var baselines = new Object2IntOpenHashMap<UUID>(); baselines.put(PLAYER, 0);
        return new ManualResearchSession.CompletionIntent(time, baselines);
    }
    private static Object2LongOpenHashMap<ManualMaterialKey> amounts(long value) {
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>(); amounts.put(MATERIAL, value); return amounts;
    }
    private static ManualResearchSessionNbt codec(int tier) {
        var resources = new NetworkResourceRegistry(); resources.freeze();
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
            .stage(ResearchLevelStage.builder(1, 1).researchTier(tier).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze(resources);
        return new ManualResearchSessionNbt(registry);
    }
}
