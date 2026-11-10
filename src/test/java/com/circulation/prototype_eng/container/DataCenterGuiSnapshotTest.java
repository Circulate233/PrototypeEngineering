package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.view.DataCenterPlayerView;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.EMPTY;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.Entry;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.MAX_PACKET_BYTES;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.MAX_ROWS;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.Request;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.Selection;
import static com.circulation.prototype_eng.container.DataCenterGuiSnapshot.capture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DataCenterGuiSnapshotTest {
    private static final UUID DATABASE = new UUID(0, 41);

    private static void rejects(Consumer<ByteBuf> mutation) {
        var buffer = Unpooled.buffer();
        try {
            EMPTY.writeToPacket(buffer);
            mutation.accept(buffer);
            assertThrows(IllegalArgumentException.class, () -> new DataCenterGuiSnapshot(buffer));
        } finally {
            buffer.release();
        }
    }

    private static Fixture fixture() {
        var registry = new ResearchRegistry();
        var shared = new Object2IntOpenHashMap<ResourceLocation>();
        for (int i = 12; i >= 0; i--) {
            register(registry, ordinary(i), false, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
            shared.put(ordinary(i), 3);
        }
        register(registry, id("item"), false, ResearchDownloadDeliveryPolicy.ITEM_ONLY);
        register(registry, id("special"), true, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
        shared.put(id("item"), 2);
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        registry.freeze(resources);
        var cloud = new TeamResearchView(registry, "team", ObjectList.of(new UUID(0, 1)), shared,
            Object2IntMaps.emptyMap(), ObjectList.of());
        var stored = new Object2IntOpenHashMap<ResourceLocation>();
        stored.put(ordinary(2), 1);
        stored.put(id("item"), 2);
        stored.put(id("special"), 2);
        return new Fixture(registry, new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false,
            new DataCenterResearchSnapshot(DATABASE, 2, 16, 7, stored), cloud));
    }

    private static void register(ResearchRegistry registry, ResourceLocation id, boolean special, ResearchDownloadDeliveryPolicy policy) {
        registry.register(ResearchDefinition.builder(id).maxLevel(3).specialOnly(special).deliveryPolicy(policy)
                                            .startCondition(_ -> fail("Database display invoked a start condition"))
                                            .stage(ResearchLevelStage.builder(1, 3).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
    }

    private static ResourceLocation ordinary(int index) {
        return id("ordinary_" + (index < 10 ? "0" : "") + index);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test_database_gui", path);
    }

    @Test
    void unionIsSortedPagedAndPreservesOnlyAnExactVisibleSelection() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(0, null));
        assertEquals(15, first.total());
        assertEquals(ObjectList.of(id("item"), ordinary(0), ordinary(1), ordinary(2), ordinary(3), ordinary(4)),
            first.rows().stream().map(Entry::id).toList());
        var selection = new Selection(ordinary(6), 3);
        var second = capture(fixture.view(), fixture.registry(), new Request(8, selection));
        assertEquals(6, second.offset());
        assertEquals(selection, second.selected());
        var last = capture(fixture.view(), fixture.registry(), new Request(Integer.MAX_VALUE, selection));
        assertEquals(12, last.offset());
        assertEquals(3, last.rows().size());
        assertEquals(last.rows().getFirst().selection(), last.selected());
        var changedLevel = capture(fixture.view(), fixture.registry(), new Request(6, new Selection(ordinary(6), 2)));
        assertEquals(changedLevel.rows().getFirst().selection(), changedLevel.selected());
    }

    @Test
    void specialAndItemOnlyEntriesAppearOnlyWhenStoredAndNeverDownload() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(0, null));
        var item = first.find(new Selection(id("item"), 0));
        assertNotNull(item);
        assertEquals(2, item.cloudLevel());
        assertEquals(2, item.storedLevel());
        assertFalse(first.canDownload(item));
        var last = capture(fixture.view(), fixture.registry(), new Request(12, null));
        var special = last.find(new Selection(id("special"), 0));
        assertNotNull(special);
        assertEquals(0, special.cloudLevel());
        assertFalse(last.canDownload(special));
        var emptyDatabase = new DataCenterResearchSnapshot(DATABASE, 2, 16, 0, Object2IntMaps.emptyMap());
        var cloudOnly = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false,
            emptyDatabase, fixture.view().cloud()), fixture.registry(), new Request(0, null));
        assertEquals(13, cloudOnly.total());
        assertTrue(cloudOnly.rows().stream().noneMatch(row -> row.id().equals(id("item"))));
    }

    @Test
    void permissionsSavingAndOfflineStateDisableDownloadsWithoutHidingAllowedCloud() {
        var fixture = fixture();
        var request = new Request(0, new Selection(ordinary(0), 3));
        var allowed = capture(fixture.view(), fixture.registry(), request);
        assertTrue(allowed.canDownload(allowed.find(request.selection())));
        var noDownload = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.NOT_TEAM_MEMBER, false,
            fixture.view().dataCenter(), fixture.view().cloud()), fixture.registry(), request);
        assertEquals(allowed.rows(), noDownload.rows());
        assertFalse(noDownload.canDownload(noDownload.find(request.selection())));
        var saving = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, true,
            fixture.view().dataCenter(), fixture.view().cloud()), fixture.registry(), request);
        assertFalse(saving.canDownload(saving.find(request.selection())));
        var offline = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false,
            null, fixture.view().cloud()), fixture.registry(), request);
        assertEquals(13, offline.total());
        assertNull(offline.dataCenterId());
        assertFalse(offline.canDownload(offline.find(request.selection())));
        var denied = capture(new DataCenterPlayerView("team", Decision.NOT_TEAM_MEMBER, Decision.NOT_TEAM_MEMBER,
            false, null, null), fixture.registry(), request);
        assertEquals(0, denied.total());
        assertNull(denied.selected());
    }

    @Test
    void capacityTierAndAlreadyStoredLevelsControlAdmission() {
        var fixture = fixture();
        var database = Objects.requireNonNull(fixture.view().dataCenter());
        var full = new DataCenterResearchSnapshot(DATABASE, 2, 3, 0, database.storedResearch());
        var page = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false, full,
            fixture.view().cloud()), fixture.registry(), new Request(0, null));
        assertFalse(page.canDownload(page.find(new Selection(ordinary(0), 3))));
        assertTrue(page.canDownload(page.find(new Selection(ordinary(2), 3))));
        var tierZero = new DataCenterResearchSnapshot(DATABASE, 0, 16, 0, Object2IntMaps.emptyMap());
        var low = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false, tierZero,
            fixture.view().cloud()), fixture.registry(), new Request(0, null));
        assertFalse(low.canDownload(low.rows().getFirst()));
        var levels = new Object2IntOpenHashMap<>(database.storedResearch());
        levels.put(ordinary(0), 3);
        var unchanged = capture(new DataCenterPlayerView("team", Decision.ALLOWED, Decision.ALLOWED, false,
            new DataCenterResearchSnapshot(DATABASE, 2, 16, 1, levels), fixture.view().cloud()), fixture.registry(), new Request(0, null));
        assertFalse(unchanged.canDownload(unchanged.find(new Selection(ordinary(0), 3))));
    }

    @Test
    void lastDrawnIdentityRejectsReplacementDatabaseTeamLevelAndDisappearingRows() {
        var fixture = fixture();
        var page = capture(fixture.view(), fixture.registry(), new Request(0, null));
        var selection = new Selection(ordinary(0), 3);
        assertTrue(page.contains("team", DATABASE, selection));
        assertFalse(page.contains("other", DATABASE, selection));
        assertFalse(page.contains("team", new UUID(0, 42), selection));
        assertFalse(page.contains("team", DATABASE, new Selection(ordinary(0), 2)));
        var next = capture(fixture.view(), fixture.registry(), new Request(6, null));
        assertFalse(next.contains("team", DATABASE, selection));
    }

    @Test
    void roundTripPreservesIdentityMetadataAndFollowingGuiSyncField() {
        var fixture = fixture();
        var page = capture(fixture.view(), fixture.registry(), new Request(6, null));
        var buffer = Unpooled.buffer();
        try {
            page.writeToPacket(buffer);
            buffer.writeInt(123456);
            assertEquals(page, new DataCenterGuiSnapshot(buffer));
            assertEquals(123456, buffer.readInt());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
        assertEquals(DATABASE, page.dataCenterId());
        assertEquals(2, page.level());
        assertEquals(16, page.capacity());
        assertEquals(3, page.storedCount());
    }

    @Test
    void emptySnapshotRoundTripsWithoutInventingAnActiveDatabase() {
        var buffer = Unpooled.buffer();
        try {
            EMPTY.writeToPacket(buffer);
            assertEquals(EMPTY, new DataCenterGuiSnapshot(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void packetRejectsInvalidVersionBooleanPermissionLengthAndTrailingBody() {
        rejects(buffer -> buffer.setByte(4, 2));
        rejects(buffer -> buffer.setByte(5, 2));
        rejects(buffer -> buffer.setByte(8, 255)); // EMPTY has a zero-length team string at byte 7.
        rejects(buffer -> buffer.setByte(10, 2));
        rejects(buffer -> buffer.setInt(23, 1));
        rejects(buffer -> buffer.setInt(27, 6));
        rejects(buffer -> buffer.setByte(31, MAX_ROWS + 1));
        rejects(buffer -> buffer.setInt(0, MAX_PACKET_BYTES + 1));
        rejects(buffer -> buffer.writerIndex(buffer.writerIndex() - 1));
        rejects(buffer -> {
            buffer.writeByte(0);
            buffer.setInt(0, buffer.getInt(0) + 1);
        });
    }

    private record Fixture(ResearchRegistry registry, DataCenterPlayerView view) {
    }
}
