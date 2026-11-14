package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("DataFlowIssue") // Test setup establishes successful admission or capture before reading the result.
class ResearchStationDisplayTest {
    private static final ResourceLocation ID = new ResourceLocation("test", "research_display");

    private static ResearchStationView view(String team, TeamResearchQueue queue, double accumulated) {
        var work = queue.activeProgress();
        var progress = new ResearchProgress.Snapshot(work.target(), 1, ImmutableResourceAmounts.empty(),
            Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, accumulated));
        return new ResearchStationView(team, true, true, queue.queueSnapshot(), progress, work.stage().durationTicks(),
            ImmutableResourceAmounts.empty(), work.totals(), Reference2DoubleMaps.emptyMap(), Reference2DoubleMaps.emptyMap(), 0);
    }

    @Test
    void numericalChangesRoundTripWithoutResendingDefinitionsOrQueue() {
        var registry = new ResearchRegistry();
        registry.register(ResearchDefinition.builder(ID).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY)
                                            .stage(ResearchLevelStage.builder(1, 1).durationTicks(100).researchTier(1)
                                                                     .resourceFormula(ResearchResourceFormula.builder().perTick(ComputingResourceFixtures.COMPUTE, 10).build()).build()).build());
        registry.freeze();
        var queue = new TeamResearchQueue(registry, "team");
        queue.enqueue(ID, 1, new UUID(0, 1), 0);
        var publisher = new ResearchStationDisplay(registry);
        var client = new ResearchStationClientView();
        var full = publisher.capture(view("team", queue, 0));
        var fullBytes = Unpooled.buffer();
        var deltaBytes = Unpooled.buffer();
        try {
            full.writeToPacket(fullBytes);
            int fullSize = fullBytes.readableBytes();
            client.receive(new ResearchStationPacket(fullBytes));
            var changed = view("team", queue, 10);
            var delta = publisher.capture(changed);
            assertFalse(delta.reset());
            assertNull(delta.queue());
            assertEquals(ResearchStationPacket.THIRD, delta.resources().getFirst().fields());
            delta.writeToPacket(deltaBytes);
            assertTrue(deltaBytes.readableBytes() < fullSize);
            client.receive(new ResearchStationPacket(deltaBytes));
            assertEquals(10, client.resources.getFirst().accumulated);
            assertNull(publisher.capture(changed));
            assertTrue(publisher.capture(view("other", queue, 10)).reset());
        } finally {
            fullBytes.release();
            deltaBytes.release();
        }
    }

    @Test
    void materialDeltasChangeQuantitiesWithoutReplacingResourceIdentity() {
        var key = new TestResources.Key(new TestResources.KeyType("display_material"), "material");
        var client = new ResearchStationClientView();
        client.receive(new ResearchStationPacket(true, new ResearchStationPacket.Definition(1, "test", 1, 10, null, List.of(key), List.of()),
            List.of(), List.of(new ResearchStationPacket.MaterialChange(0, 3, 5, 5)), List.of()));
        var row = client.materials.getFirst();
        client.receive(new ResearchStationPacket(false, null, null, List.of(new ResearchStationPacket.MaterialChange(0, 3, 2, 3)), List.of()));
        assertSame(row, client.materials.getFirst());
        assertSame(key, row.key);
        assertEquals(2, row.available);
        assertEquals(3, row.remaining);
    }
}
