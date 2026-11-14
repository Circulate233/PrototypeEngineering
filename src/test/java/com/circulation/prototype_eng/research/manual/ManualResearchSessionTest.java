package com.circulation.prototype_eng.research.manual;

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

    private static Object2LongOpenHashMap<ManualMaterialKey> amounts() {
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>();
        amounts.put(MATERIAL, 3);
        return amounts;
    }

    private static ManualResearchSessionNbt codec() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(RESEARCH).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        registry.freeze();
        return new ManualResearchSessionNbt(registry);
    }

    @Test
    void fullCodecPreservesActivePausedAndCompletionStatesBeforeWorldRestore() {
        var codec = codec();
        var complete = active().beginCompletion(completion());
        for (var value : List.of(active(), active().pause(EXPLICIT, 2), active().pause(RESEARCH_INVALIDATED, 2), complete,
            complete.markCompletionPropagated(), complete.markCompletionPropagated().markQueueHeadRemoved())) {
            assertEquals(value, codec.decode(codec.encode(value)));
        }
        assertEquals(ACTIVE, codec.decode(codec.encode(active())).status());
    }


    @Test
    void strictNbtRejectsDuplicateWorkAndCompletionMembers() {
        var work = ManualResearchSessionNbt.encodeWork(amounts());
        work.appendTag(work.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> ManualResearchSessionNbt.decodeWork(work));
        var codec = codec();
        var completion = codec.encode(active().beginCompletion(completion()));
        var members = completion.getCompoundTag("Completion").getTagList("Baselines", 10);
        members.appendTag(members.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(completion));
    }


}
