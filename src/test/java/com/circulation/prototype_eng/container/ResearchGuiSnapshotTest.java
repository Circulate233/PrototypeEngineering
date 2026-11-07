package com.circulation.prototype_eng.container;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static com.circulation.prototype_eng.container.ResearchGuiSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchGuiSnapshotTest {
    private static final UUID ALICE = new UUID(0, 1);
    private static final UUID BOB = new UUID(0, 2);

    @Test void catalogIsSortedAndEachPageSelectsOnlyAnExactVisibleRow() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 0, new Selection(research(10), 2, 0), 6));
        assertEquals(13, first.total()); assertEquals(0, first.offset()); assertEquals(6, first.rows().size());
        assertEquals(ObjectList.of(research(0), research(1), research(2), research(3), research(4), research(5)),
            new ObjectArrayList<>(first.rows().stream().map(Entry::id).toList()));
        assertEquals(new Selection(research(0), 2, 0), first.selected()); assertEquals(0, first.detailOffset());
        var second = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 8, new Selection(research(8), 2, 0), 6));
        assertEquals(6, second.offset()); assertEquals(new Selection(research(8), 2, 0), second.selected());
        assertEquals(6, second.detailOffset()); assertEquals(3, second.details().size());
        var tail = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, Integer.MAX_VALUE, null, 0));
        assertEquals(12, tail.offset()); assertEquals(ObjectList.of(research(12)), new ObjectArrayList<>(tail.rows().stream().map(Entry::id).toList()));
        var wrongLevel = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 6, new Selection(research(8), 1, 0), 6));
        assertEquals(new Selection(research(6), 2, 0), wrongLevel.selected()); assertEquals(0, wrongLevel.detailOffset());
    }

    @Test void queueOrderAndEntryIdentityArePreservedAndOnlyTheHeadOwnsNumericProgress() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(Tab.QUEUE, 0, null, 0));
        assertEquals(ObjectList.of(research(5), research(1)), new ObjectArrayList<>(first.rows().stream().map(Entry::id).toList()));
        assertEquals(new Selection(research(5), 2, 41), first.selected());
        assertEquals(Long.MAX_VALUE - 1, first.details().getFirst().current());
        assertEquals(Long.MAX_VALUE, first.details().getFirst().required());
        assertEquals(9, first.detailTotal()); assertEquals(6, first.details().size());
        var second = capture(fixture.view(), fixture.registry(), new Request(Tab.QUEUE, 0, new Selection(research(1), 3, 7), 0));
        assertTrue(second.details().stream().allMatch(detail -> detail.current() == 0));
        var reused = capture(fixture.view(), fixture.registry(), new Request(Tab.QUEUE, 0, new Selection(research(1), 3, 99), 6));
        assertEquals(first.selected(), reused.selected()); assertEquals(0, reused.detailOffset());
    }

    @Test void numericDetailPagingRetainsEveryResourceAndClampsLargeOffsets() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(Tab.QUEUE, 0, null, 0));
        var last = capture(fixture.view(), fixture.registry(), new Request(Tab.QUEUE, 0, first.selected(), Integer.MAX_VALUE));
        var all = new ObjectArrayList<ResourceLocation>();
        first.details().forEach(detail -> all.add(detail.id())); last.details().forEach(detail -> all.add(detail.id()));
        assertEquals(9, all.size()); assertEquals(9, new ObjectOpenHashSet<>(all).size()); assertEquals(6, last.detailOffset());
        for (int i = 0; i < all.size(); i++) assertEquals(resource(i), all.get(i));
    }

    @Test void rewardChoicesAreFullyReachableAcrossDetailPagesAndSelectedChoiceSurvives() {
        var fixture = fixture();
        var first = capture(fixture.view(), fixture.registry(), new Request(Tab.REWARDS, 0, null, 0));
        assertEquals(research(1), first.rows().getFirst().id()); assertEquals(1, first.rows().getFirst().targetLevel());
        assertEquals(group(8), first.rows().getFirst().selectedGroup());
        assertEquals(9, first.detailTotal());
        var last = capture(fixture.view(), fixture.registry(), new Request(Tab.REWARDS, 0, first.selected(), 6));
        var choices = new ObjectArrayList<ResourceLocation>();
        first.details().forEach(detail -> choices.add(detail.id())); last.details().forEach(detail -> choices.add(detail.id()));
        assertEquals(fixture.groups(), choices); assertEquals(6, last.detailOffset());
        assertTrue(last.details().stream().allMatch(detail -> detail.kind() == Kind.REWARD_CHOICE));
        assertEquals(first.rows(), last.rows());
    }

    @Test void claimOrderingUsesResearchIdAndExactLevelAndStaleSelectionClearsOldDetails() {
        var fixture = fixture();
        var view = withClaims(fixture.view(), ObjectList.of(
            claim(research(5), 2, fixture.groups()), claim(research(1), 2, fixture.groups()), claim(research(1), 1, fixture.groups())));
        var page = capture(view, fixture.registry(), new Request(Tab.REWARDS, 0, new Selection(research(5), 1, 0), 6));
        assertEquals(ObjectList.of(new Selection(research(1), 1, 0), new Selection(research(1), 2, 0), new Selection(research(5), 2, 0)),
            new ObjectArrayList<>(page.rows().stream().map(Entry::selection).toList()));
        assertEquals(page.rows().getFirst().selection(), page.selected()); assertEquals(0, page.detailOffset());
        var empty = capture(withClaims(view, ObjectList.of()), fixture.registry(), new Request(Tab.REWARDS, 6, page.selected(), 6));
        assertEquals(0, empty.total()); assertEquals(0, empty.offset()); assertNull(empty.selected()); assertTrue(empty.details().isEmpty());
    }

    @Test void publicCatalogNeverReintroducesDeniedPlayerTeamQueueOrRewardData() {
        var fixture = fixture();
        var denied = Decision.NOT_TEAM_MEMBER;
        var team = new ResearchPlayerView.Team(ResearchPlayerView.TeamStatus.DENIED, "team", denied, denied, denied, denied,
            Object2IntMaps.emptyMap(), Object2IntMaps.emptyMap(), new ObjectOpenHashSet<>(), 0, ObjectList.of(), Object2LongMaps.emptyMap(), ObjectList.of());
        var view = new ResearchPlayerView(ALICE, 1, false, Decision.TARGET_PLAYER_MISMATCH, Object2IntMaps.emptyMap(), team, ObjectList.of());
        var catalog = capture(view, fixture.registry(), new Request(Tab.RESEARCH, 0, null, 0));
        assertEquals(13, catalog.total());
        assertTrue(catalog.rows().stream().allMatch(row -> row.personalLevel() == 0 && row.sharedLevel() == 0 && row.targetLevel() == 0 && !row.blacklisted()));
        assertTrue(catalog.details().isEmpty());
        for (var tab : ObjectList.of(Tab.QUEUE, Tab.REWARDS)) {
            var hidden = capture(view, fixture.registry(), new Request(tab, 0, null, 0));
            assertTrue(hidden.rows().isEmpty()); assertTrue(hidden.details().isEmpty()); assertNull(hidden.selected());
        }
    }

    @Test void staticMaterialDetailsKeepInputPositionsAndUseExactRequiredAndPaidLongAmounts() {
        var type = new TestResources.KeyType("gui_material");
        var item = new TestResources.Key(type, "item");
        var requirement = RecipeRequirements.RESOURCES.requirement(id("material"),
            new ResourceRequirement(ObjectList.of(new GenericStack(item, 500), new GenericStack(item, 5)), ObjectList.of()));
        var work = new Object2LongOpenHashMap<ManualMaterialKey>();
        work.put(new ManualMaterialKey(requirement.id(), 0), 3); work.put(new ManualMaterialKey(requirement.id(), 1), 4);
        var manual = new ResearchPlayerView.ManualRow(41, ManualResearchSession.Status.ACTIVE, null, ALICE, work);
        assertEquals(ObjectList.of(new Detail(Kind.MATERIAL, requirement.id(), 0, 3, 500), new Detail(Kind.MATERIAL, requirement.id(), 1, 4, 5)),
            materialDetails(ObjectList.of(requirement), manual));
        assertTrue(materialDetails(ObjectList.of(requirement), null).stream().allMatch(detail -> detail.current() == 0));
        var ticking = RecipeRequirements.RESOURCES.requirement(id("ticking"), new ResourceRequirement(ObjectList.of(),
            ObjectList.of(new GenericStack(item, 1)), ObjectList.of(), ObjectList.of(), false));
        assertThrows(IllegalArgumentException.class, () -> materialDetails(ObjectList.of(ticking), manual));
    }

    @Test void allTabsAndManualRowsRoundTripWithoutConsumingTheFollowingGuiSyncField() {
        var fixture = fixture();
        var snapshots = new ObjectArrayList<ResearchGuiSnapshot>(); snapshots.add(EMPTY);
        for (var tab : Tab.values()) snapshots.add(capture(fixture.view(), fixture.registry(), new Request(tab, 0, null, 0)));
        var entry = new Entry(research(1), 1, 1, 2, 0, false, 41, ManualResearchSession.Status.PAUSED,
            ManualResearchSession.PauseReason.CONTRIBUTOR_OFFLINE, null, null, null);
        snapshots.add(new ResearchGuiSnapshot(true, false, ResearchPlayerView.TeamStatus.AVAILABLE, Decision.ALLOWED, Decision.ALLOWED,
            Decision.ALLOWED, Decision.ALLOWED, Decision.NOT_TEAM_LEADER, Tab.QUEUE, 1, 0, ObjectList.of(entry), entry.selection(),
            1, 0, ObjectList.of(new Detail(Kind.MATERIAL, id("material"), 0, 3, 500))));
        for (var snapshot : snapshots) {
            var buffer = Unpooled.buffer();
            try {
                snapshot.writeToPacket(buffer); buffer.writeInt(12345);
                assertTrue(buffer.readableBytes() <= MAX_PACKET_BYTES + 8);
                assertEquals(snapshot, new ResearchGuiSnapshot(buffer));
                assertEquals(Integer.BYTES, buffer.readableBytes()); assertEquals(12345, buffer.readInt());
            } finally { buffer.release(); }
        }
    }

    @Test void packetLengthVersionFlagsEnumsAndRowCountsAreRejectedBeforeAllocatingPages() {
        malformed(EMPTY, buffer -> buffer.setInt(0, MAX_PACKET_BYTES + 1));
        malformed(EMPTY, buffer -> buffer.setInt(0, buffer.getInt(0) + 1));
        malformed(EMPTY, buffer -> buffer.setInt(0, 1));
        malformed(EMPTY, buffer -> buffer.setByte(4, 2)); // Protocol version.
        malformed(EMPTY, buffer -> buffer.setByte(5, 2)); // Boolean initialized flag.
        malformed(EMPTY, buffer -> buffer.setByte(7, 255)); // Team status.
        malformed(EMPTY, buffer -> buffer.setByte(8, 255)); // Personal permission.
        malformed(EMPTY, buffer -> buffer.setByte(13, 255)); // Tab.
        malformed(EMPTY, buffer -> buffer.setByte(22, MAX_ROWS + 1));
        malformed(EMPTY, buffer -> buffer.setByte(32, MAX_DETAILS + 1));
        var shortBuffer = Unpooled.buffer();
        try { shortBuffer.writeByte(0); assertThrows(IllegalArgumentException.class, () -> new ResearchGuiSnapshot(shortBuffer)); }
        finally { shortBuffer.release(); }
    }

    @Test void malformedCountersTruncatedBodiesAndTrailingBodyBytesCannotProduceASnapshot() {
        malformed(EMPTY, buffer -> buffer.setInt(14, -1)); // Main total.
        malformed(EMPTY, buffer -> buffer.setInt(18, 6)); // Main offset against an empty page.
        malformed(EMPTY, buffer -> buffer.setInt(24, -1)); // Detail total.
        malformed(EMPTY, buffer -> buffer.writerIndex(buffer.writerIndex() - 1));
        malformed(EMPTY, buffer -> { buffer.writeByte(0); buffer.setInt(0, buffer.getInt(0) + 1); });
        var fixture = fixture();
        var page = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 0, null, 0));
        malformed(page, buffer -> buffer.setInt(14, 1)); // Six rows cannot fit a declared one-row total.
    }

    @Test void requestsQuantitiesSelectionsAndIdsAreValidatedWithoutTruncatingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new Request(Tab.REWARDS, 0, new Selection(research(1), 0, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new Request(Tab.QUEUE, 0, new Selection(research(1), 1, 0), 0));
        assertThrows(IllegalArgumentException.class, () -> new Request(Tab.RESEARCH, -1, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Selection(id("a".repeat(300)), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Detail(Kind.NUMERIC, resource(0), 0, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> new Detail(Kind.MATERIAL, id("material"), 0, 6, 5));
        var fixture = fixture();
        var page = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResearchGuiSnapshot(page.initialized(), page.savePending(), page.teamStatus(),
            page.personalPermission(), page.teamViewPermission(), page.queuePermission(), page.startPermission(), page.blacklistPermission(),
            page.tab(), page.total(), page.offset(), page.rows(), new Selection(research(12), 2, 0), page.detailTotal(), page.detailOffset(), page.details()));
        assertThrows(UnsupportedOperationException.class, () -> page.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> page.details().clear());
    }

    @Test void negativeWireLevelsAndLongProgressCannotPassConstructorValidation() {
        var fixture = fixture();
        var page = capture(fixture.view(), fixture.registry(), new Request(Tab.RESEARCH, 0, null, 0));
        // The bounded first ID is ASCII and uses a one-byte UTF length prefix.
        malformed(page, buffer -> buffer.setInt(24 + page.rows().getFirst().id().toString().length(), -1));
        // The final detail ends with the current and required signed long values.
        malformed(page, buffer -> buffer.setLong(buffer.writerIndex() - Long.BYTES * 2, -1));
        malformed(page, buffer -> buffer.setLong(buffer.writerIndex() - Long.BYTES, -1));
    }

    private static void malformed(ResearchGuiSnapshot snapshot, Consumer<ByteBuf> mutate) {
        var buffer = Unpooled.buffer();
        try {
            snapshot.writeToPacket(buffer); mutate.accept(buffer);
            assertThrows(IllegalArgumentException.class, () -> new ResearchGuiSnapshot(buffer));
        } finally { buffer.release(); }
    }

    private static ResearchPlayerView withClaims(ResearchPlayerView view, ObjectList<ResearchPlayerView.ClaimSummary> claims) {
        return new ResearchPlayerView(view.playerUuid(), view.serverTime(), view.savePending(), view.personalPermission(), view.personalLevels(), view.team(), claims);
    }
    private static ResearchPlayerView.ClaimSummary claim(ResourceLocation id, int level, ObjectList<ResourceLocation> groups) {
        return new ResearchPlayerView.ClaimSummary(id, level, RewardClaim.Status.CLAIMING, Optional.of(group(8)), groups);
    }

    private static Fixture fixture() {
        var resources = new NetworkResourceRegistry();
        var formula = ResearchResourceFormula.builder();
        var progress = new Object2LongOpenHashMap<ResourceLocation>();
        for (int i = 0; i < 9; i++) {
            var type = resources.register(new NetworkResourceType(resource(i), false, 0));
            long required = i == 0 ? Long.MAX_VALUE : 100 + i;
            formula.constant(type, required, 1); progress.put(type.id(), i == 0 ? Long.MAX_VALUE - 1 : i);
        }
        resources.freeze();
        var registry = new ResearchRegistry();
        var groups = new ObjectArrayList<ResourceLocation>();
        for (int i = 0; i < 9; i++) groups.add(group(i));
        var targets = new Object2IntOpenHashMap<ResourceLocation>();
        for (int i = 12; i >= 0; i--) {
            var stage = ResearchLevelStage.builder(1, 3).researchTier(1).resourceFormula(formula.build());
            for (var group : groups) stage.rewardGroup(1, new RewardGroup(group, ObjectList.of(new RewardChild.Event(id("event"), (_, _) -> RewardEventAction.Result.ALREADY_APPLIED))));
            registry.register(ResearchDefinition.builder(research(i)).maxLevel(3).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                .startCondition(_ -> fail("Display executed a start condition")).stage(stage.build()).build());
            targets.put(research(i), 2);
        }
        registry.freeze(resources);
        var personal = new Object2IntOpenHashMap<ResourceLocation>(); personal.put(research(5), 1);
        var shared = new Object2IntOpenHashMap<ResourceLocation>();
        for (int i = 0; i < 13; i++) shared.put(research(i), 1);
        var rows = ObjectList.of(new ResearchPlayerView.QueueRow(41, research(5), 2, ALICE, 1),
            new ResearchPlayerView.QueueRow(7, research(1), 3, BOB, 2));
        var team = new ResearchPlayerView.Team(ResearchPlayerView.TeamStatus.AVAILABLE, "team", Decision.ALLOWED, Decision.ALLOWED,
            Decision.ALLOWED, Decision.NOT_TEAM_LEADER, shared, targets, new ObjectOpenHashSet<>(), 1, rows, progress, ObjectList.of());
        var view = new ResearchPlayerView(ALICE, 100, true, Decision.ALLOWED, personal, team,
            ObjectList.of(claim(research(5), 1, groups), claim(research(1), 1, groups)));
        return new Fixture(registry, view, groups);
    }

    private static ResourceLocation research(int index) { return id("research_" + (index < 10 ? "0" : "") + index); }
    private static ResourceLocation resource(int index) { return id("compute_" + index); }
    private static ResourceLocation group(int index) { return id("group_" + index); }
    private static ResourceLocation id(String path) { return new ResourceLocation("test_gui", path); }
    private record Fixture(ResearchRegistry registry, ResearchPlayerView view, ObjectList<ResourceLocation> groups) { }
}
