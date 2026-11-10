package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.UUID;

import static com.circulation.prototype_eng.api.research.ResearchDataCentersTest.FIRST;
import static com.circulation.prototype_eng.api.research.ResearchDataCentersTest.OWNER;
import static com.circulation.prototype_eng.api.research.ResearchDataCentersTest.SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DataCenterResearchAccessTest {
    @Test
    void consecutiveDownloadsRefreshPublicationAndAccumulateWithoutRefillingTheTickBudget() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var world = fixture.researchWorld(ResearchPermissionPolicy.DEFAULT, _ -> fail("Download saved cloud sources"));
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            var before = world.snapshot();
            var physical = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var permit = Objects.requireNonNull(fixture.role(machine).workPermit());
            var reservation = Objects.requireNonNull(permit.reserve(new ResourceLocation("test_data_access", "earlier_work"),
                Object2LongMaps.singleton(NetworkResources.COMPUTE, 30)));
            assertTrue(reservation.commit());
            long firstEpoch = Objects.requireNonNull(fixture.network.plan()).epoch();
            var first = access.download(fixture.membership(OWNER), fixture.permission(), fixture.machineId(machine), FIRST, 1, () -> true);
            assertEquals(DataCenterResearchAccess.Status.COMPLETED, first.status());
            assertEquals(DataCenterStorage.Result.UPDATED, first.storageResult());
            assertFalse(physical.current());
            assertTrue(Objects.requireNonNull(fixture.network.plan()).epoch() > firstEpoch);
            var second = access.download(fixture.membership(OWNER), fixture.permission(), fixture.machineId(machine), SECOND, 1, () -> true);
            assertEquals(DataCenterStorage.Result.UPDATED, second.storageResult());
            var fresh = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            assertEquals(1, fresh.snapshot().storedResearch().getInt(FIRST));
            assertEquals(1, fresh.snapshot().storedResearch().getInt(SECOND));
            var freshPermit = Objects.requireNonNull(fixture.role(machine).workPermit());
            assertEquals(0, freshPermit.tick());
            assertEquals(70, freshPermit.availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(100, freshPermit.numericResources().getLong(NetworkResources.COMPUTE));
            assertEquals(2, fixture.dirtyWrites);
            assertEquals(before, world.snapshot());
        }
    }

    @Test
    void permissionDenialAndWrongPhysicalTargetLeaveStorageAndPublicationUntouched() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var actions = new ObjectArrayList<ResearchPermissionPolicy.Action>();
            var world = fixture.researchWorld((action, _) -> {
                actions.add(action);
                return ResearchPermissionPolicy.Decision.NOT_SERVER_OPERATOR;
            }, _ -> fail("Denied download saved cloud sources"));
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            var publication = fixture.network.plan();
            var denied = access.download(fixture.membership(OWNER), fixture.permission(), fixture.machineId(machine), FIRST, 1, () -> true);
            assertEquals(DataCenterResearchAccess.Status.PERMISSION_DENIED, denied.status());
            assertEquals(ObjectList.of(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER), actions);
            assertTrue(fixture.storage(machine).snapshot().storedResearch().isEmpty());
            assertEquals(0, fixture.dirtyWrites);
            assertSame(publication, fixture.network.plan());
            var allowedWorld = fixture.researchWorld(ResearchPermissionPolicy.DEFAULT, _ -> fail("Wrong target saved sources"));
            var allowedAccess = new DataCenterResearchAccess(allowedWorld, fixture.network, fixture.selector);
            var wrong = allowedAccess.download(fixture.membership(OWNER), fixture.permission(), new UUID(0, 99), FIRST, 1, () -> true);
            assertEquals(DataCenterResearchAccess.Status.NO_ACTIVE_DATA_CENTER, wrong.status());
            assertEquals(0, fixture.dirtyWrites);
            assertSame(publication, fixture.network.plan());
        }
    }

    @Test
    void deniedStateViewReportsIndependentDownloadPermissionWithoutExposingItsCloudOrDatabase() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var world = fixture.researchWorld((action, _) -> action == ResearchPermissionPolicy.Action.REQUEST_DATA_CENTER_STATE
                    ? ResearchPermissionPolicy.Decision.NOT_SERVER_OPERATOR : ResearchPermissionPolicy.Decision.ALLOWED,
                _ -> fail("State view saved cloud sources"));
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            var before = world.snapshot();
            var view = Objects.requireNonNull(access.view(fixture.membership(OWNER), fixture.permission(), () -> true));
            assertEquals(ResearchPermissionPolicy.Decision.NOT_SERVER_OPERATOR, view.viewPermission());
            assertEquals(ResearchPermissionPolicy.Decision.ALLOWED, view.downloadPermission());
            assertNull(view.dataCenter());
            assertNull(view.cloud());
            assertEquals(before, world.snapshot());
            var download = access.download(fixture.membership(OWNER), fixture.permission(), fixture.machineId(machine), FIRST, 1, () -> true);
            assertEquals(DataCenterResearchAccess.Status.COMPLETED, download.status());
            assertEquals(DataCenterStorage.Result.UPDATED, download.storageResult());
            assertEquals(1, fixture.storage(machine).snapshot().storedResearch().getInt(FIRST));
        }
    }

    @Test
    void sameTickCloudCompletionSynchronizesStoredLoopWithoutPlayerPolicyOrAnotherBudget() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var actions = new ObjectArrayList<ResearchPermissionPolicy.Action>();
            var world = fixture.researchWorld((action, _) -> {
                assertEquals(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, action);
                actions.add(action);
                return ResearchPermissionPolicy.Decision.ALLOWED;
            }, _ -> {
            });
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            assertEquals(DataCenterStorage.Result.UPDATED,
                access.download(fixture.membership(OWNER), fixture.permission(), fixture.machineId(machine), FIRST, 1, () -> true).storageResult());
            assertTrue(world.player(OWNER).completeAtLeast(FIRST, 3));
            world.checkpoint(1);
            var result = access.synchronize(fixture.membership(OWNER), () -> true);
            assertEquals(DataCenterStorage.Result.UPDATED, result.storageResult());
            assertEquals(3, fixture.storage(machine).snapshot().storedResearch().getInt(FIRST));
            assertFalse(fixture.storage(machine).snapshot().storedResearch().containsKey(SECOND));
            assertEquals(ObjectList.of(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER), actions);
            assertEquals(0, Objects.requireNonNull(fixture.role(machine).workPermit()).tick());
            var publication = fixture.network.plan();
            assertEquals(DataCenterStorage.Result.UNCHANGED, access.synchronize(fixture.membership(OWNER), () -> true).storageResult());
            assertSame(publication, fixture.network.plan());
        }
    }

    @Test
    void dirtyFailureAfterContentPublicationRevokesNetworkWithoutRollingBackTheWrittenResearch() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var world = fixture.researchWorld(ResearchPermissionPolicy.DEFAULT, _ -> fail("Download saved cloud sources"));
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            var physical = Objects.requireNonNull(fixture.selector.capture("team", fixture.machineId(machine)));
            var failure = new IllegalStateException("Tile dirty notification failed after the write");
            fixture.dirtyFailure = failure;
            assertSame(failure, assertThrows(IllegalStateException.class, () -> access.download(fixture.membership(OWNER),
                fixture.permission(), fixture.machineId(machine), FIRST, 1, () -> true)));
            assertEquals(1, fixture.storage(machine).snapshot().storedResearch().getInt(FIRST));
            assertFalse(physical.current());
            assertNull(fixture.network.plan());
            assertNull(fixture.role(machine).workPermit());
            assertEquals(1, fixture.dirtyWrites);
        }
    }

    @Test
    void staleMembershipProofAndPendingCloudSaveNeverStartPhysicalWrites() {
        try (var fixture = new ResearchDataCentersTest.Fixture()) {
            var machine = fixture.dataCenter();
            fixture.refresh();
            var failure = new IllegalStateException("Pending cloud save");
            var world = fixture.researchWorld((_, _) -> fail("Unavailable cloud checked permission"), _ -> {
                throw failure;
            });
            var access = new DataCenterResearchAccess(world, fixture.network, fixture.selector);
            var publication = fixture.network.plan();
            assertEquals(DataCenterResearchAccess.Status.STALE_CONTEXT, access.download(fixture.membership(OWNER), fixture.permission(),
                fixture.machineId(machine), FIRST, 1, () -> false).status());
            assertSame(failure, assertThrows(IllegalStateException.class, () -> world.playerLogin(OWNER, 1)));
            assertEquals(DataCenterResearchAccess.Status.SAVE_PENDING, access.download(fixture.membership(OWNER), fixture.permission(),
                fixture.machineId(machine), FIRST, 1, () -> true).status());
            assertEquals(DataCenterResearchAccess.Status.SAVE_PENDING, access.synchronize(fixture.membership(OWNER), () -> true).status());
            assertTrue(fixture.storage(machine).snapshot().storedResearch().isEmpty());
            assertEquals(0, fixture.dirtyWrites);
            assertSame(publication, fixture.network.plan());
        }
    }
}
