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
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.circulation.prototype_eng.api.research.manual.ManualResearchSession.PauseReason.EXPLICIT;
import static com.circulation.prototype_eng.api.research.manual.ManualResearchSession.PauseReason.RESEARCH_INVALIDATED;
import static com.circulation.prototype_eng.api.research.manual.ManualResearchSession.Status.ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManualResearchSessionTest {
    private static final UUID SESSION = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final UUID OPERATION = new UUID(0, 3);
    private static final ResourceLocation RESEARCH = new ResourceLocation("test", "manual");
    private static final ManualMaterialKey MATERIAL = new ManualMaterialKey(new ResourceLocation("test", "input"), 0);
    private static final TeamResearchQueue.Key KEY = new TeamResearchQueue.Key(RESEARCH, 1, 1);

    private static ManualResearchSession active() {
        return ManualResearchSession.newActive(SESSION, "team", KEY, PLAYER, 1);
    }

    private static ManualResearchSession.CompletionIntent completion() {
        var baselines = new Object2IntOpenHashMap<UUID>();
        baselines.put(PLAYER, 0);
        return new ManualResearchSession.CompletionIntent(2, baselines);
    }

    private static Object2LongOpenHashMap<ManualMaterialKey> amounts(long value) {
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>();
        amounts.put(MATERIAL, value);
        return amounts;
    }

    private static ManualResearchSessionNbt codec(int tier) {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).researchTier(tier).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze(resources);
        return new ManualResearchSessionNbt(registry);
    }

    @Test
    void fullCodecPreservesActivePausedAndCompletionStatesBeforeWorldRestore() {
        var codec = codec(0);
        var complete = active().beginCompletion(completion());
        for (var value : List.of(active(), active().pause(EXPLICIT, 2), active().pause(RESEARCH_INVALIDATED, 2), complete,
            complete.markCompletionPropagated(), complete.markCompletionPropagated().markQueueHeadRemoved())) {
            assertEquals(value, codec.decode(codec.encode(value)));
        }
        assertEquals(ACTIVE, codec.decode(codec.encode(active())).status());
    }

    @Test
    void strictNbtRejectsUnknownFieldsTypesVersionsAndNoncanonicalIdentity() {
        var codec = codec(0);
        var unknown = codec.encode(active());
        unknown.setBoolean("Unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown));
        var version = codec.encode(active());
        version.setInteger("Version", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(version));
        var type = codec.encode(active());
        type.setInteger("LastActivity", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(type));
        var id = codec.encode(active());
        id.setString("Id", "0-0-0-0-1");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(id));
        var status = codec.encode(active());
        status.setString("Status", "active");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(status));
        var key = codec.encode(active());
        key.getCompoundTag("QueueKey").setString("Research", "manual");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(key));
    }

    @Test
    void strictNbtRejectsDuplicateWorkAndCompletionMembers() {
        var work = ManualResearchSessionNbt.encodeWork(amounts(3));
        work.appendTag(work.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(work));
        var codec = codec(0);
        var completion = codec.encode(active().beginCompletion(completion()));
        var members = completion.getCompoundTag("Completion").getTagList("Baselines", 10);
        members.appendTag(members.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(completion));
    }

    @Test
    void strictNbtValidatesMaterialNestedFieldsAndListElements() {
        var wrongList = new NBTTagList();
        wrongList.appendTag(new NBTTagString("invalid"));
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(wrongList));
        var material = ManualResearchSessionNbt.encodeMaterialIntent(new ManualResearchSession.MaterialIntent(OPERATION, PLAYER, 1, amounts(3), amounts(5)));
        material.setTag("Expected", new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeMaterialIntent(material));
        var work = ManualResearchSessionNbt.encodeWork(amounts(3));
        work.getCompoundTagAt(0).setInteger("Amount", 3);
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(work));
        var unknown = ManualResearchSessionNbt.encodeWork(amounts(3));
        unknown.getCompoundTagAt(0).setString("Extra", "field");
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(unknown));
    }

    @Test
    void fullCodecRejectsWrongTierUnknownMaterialAndInvalidQueueLevel() {
        assertThrows(IllegalArgumentException.class, () -> codec(1).encode(active()));
        var codec = codec(0);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new ManualResearchSession(SESSION, "team", KEY, PLAYER, 1, ACTIVE, null, amounts(1), null, null)));
        var level = codec.encode(active());
        level.getCompoundTag("QueueKey").setInteger("Level", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(level));
        var unknown = codec.encode(active());
        unknown.getCompoundTag("QueueKey").setString("Research", "test:unknown");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown));
    }
}
