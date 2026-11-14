package com.circulation.prototype_eng.research;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchAccess;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelResult;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchServerConfiguration;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKeyNbt;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCarrier;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImportResult;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskInteractions;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskRecovery;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskStorage;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadResult;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import com.circulation.prototype_eng.api.research.view.DataCenterPlayerView;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.api.research.view.ResearchStationView;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.integration.ftb.FtbOwnerTeamResolver;
import com.circulation.prototype_eng.machine.ServerMachineIdentities;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import com.circulation.prototype_eng.research.download.MinecraftResearchDownloadInventory;
import com.circulation.prototype_eng.research.manual.MinecraftManualResearchInventory;
import com.circulation.prototype_eng.research.reward.MinecraftItemDeliveryAdapter;
import com.circulation.prototype_eng.research.reward.RewardDeliveryRecovery;
import com.circulation.prototype_eng.research.reward.RewardItemEntity;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.feed_the_beast.ftblib.lib.data.ForgeTeam;
import com.feed_the_beast.ftblib.lib.data.TeamType;
import com.feed_the_beast.ftblib.lib.data.Universe;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Authoritative server research session; its file has one writer and is never registered in MapStorage.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ServerResearch {
    @Nullable
    private static volatile Session session;

    private ServerResearch() {
    }

    /**
     * Carrier items are handled before a controller's normal right-click GUI activation.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onResearchDiskInteraction(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getUseItem() == Event.Result.DENY) return;
        var face = event.getFace();
        if (face == null) return;
        var result = ResearchDiskInteractions.use(event.getEntityPlayer(), event.getWorld(), event.getPos(), event.getHand(), face);
        if (result == EnumActionResult.PASS) return;
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    /**
     * FML serverStarting calls this after the overworld and frozen registries are available.
     */
    public static synchronized void start(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        if (session != null) {
            throw new IllegalStateException("A research server session is already installed");
        }
        var overworld = Objects.requireNonNull(server.getWorld(0), "Research startup requires the overworld");
        var registry = ResearchRegistry.INSTANCE;
        var file = new ResearchStateFile(overworld.getSaveHandler().getMapFileFromName("prototype_eng_research").toPath(),
            new ResearchWorldNbt(registry, ResourceKeyNbt.INSTANCE));
        var permissions = ResearchServerConfiguration.INSTANCE.permissions();
        var world = new ResearchWorld(registry, permissions, snapshot -> {
            try {
                file.write(snapshot);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        try {
            var saved = file.read();
            if (saved != null) world.restore(saved);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        session = new Session(server, overworld, world, permissions);
    }

    /**
     * Refresh at the scheduler's exclusive boundary, after research work and before ordinary work.
     */
    @SuppressWarnings("resource")
    public static void refreshDataCenters(MinecraftServer server) {
        requireThread(server);
        var current = session;
        if (current != null && (current.server != server || current.overworld != server.getWorld(0))) {
            throw new IllegalStateException("Download recovery changed its server identity");
        }
        if (current != null && !current.closing) recoverDownloads(current);
        var network = ServerNetworkRuntime.forServer(server);
        if (!network.refresh()) return;
        if (current == null || current.closing) return;
        if (current.server != server || current.overworld != server.getWorld(0)) {
            throw new IllegalStateException("Database synchronization changed its server identity");
        }
        if (current.world.savePending()) return;
        var teams = Objects.requireNonNull(network.plan()).teams().keySet();
        for (var teamId : teams) {
            var plan = network.plan();
            if (plan == null) return;
            var team = plan.teams().get(teamId);
            if (team == null || team.dataCenterId() == null) continue;
            var physical = current.dataCenters.capture(teamId, team.dataCenterId());
            if (physical == null) continue;
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(physical.ownerId()));
            if (unavailable(captures)) return;
            var membership = Objects.requireNonNull(captures.get(physical.ownerId()));
            if (!membership.accepted() || !teamId.equals(membership.teamId())) continue;
            var authority = proof(current, captures, online);
            var synchronizedResult = current.database.synchronize(membership, () -> authority.getAsBoolean() && physical.current());
            if (synchronizedResult.status() == DataCenterResearchAccess.Status.SAVE_PENDING
                || synchronizedResult.status() == DataCenterResearchAccess.Status.STALE_CONTEXT) return;
        }
    }

    /**
     * The request names the exact database shown by the server, never a replacement selected later.
     */
    @SuppressWarnings({"resource", "unused"}) // Public request API retained while the research GUI is deferred.
    public static void requestDataCenterDownload(EntityPlayerMP actor, String expectedTeam, UUID expectedDataCenterId,
                                                 ResourceLocation id, int level, BooleanSupplier ownerCurrent,
                                                 Consumer<DataCenterResearchAccess.Result> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(expectedTeam);
        Objects.requireNonNull(expectedDataCenterId);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(level);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(staleDownload());
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(staleDownload());
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(staleDownload());
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) {
                result.accept(staleDownload());
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.database.download(membership, permission, expectedDataCenterId, id, level,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, ExecutionMode.MAIN_THREAD);
    }

    @SuppressWarnings({"resource", "unused"}) // Public request API retained while the research GUI is deferred.
    public static void requestDataCenterView(EntityPlayerMP actor, BooleanSupplier ownerCurrent, Consumer<DataCenterPlayerView> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(null);
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(null);
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted()) {
                result.accept(new DataCenterPlayerView("", ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM,
                    ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM, current.world.savePending(), null, null));
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) {
                result.accept(null);
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.database.view(membership, permission,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, ExecutionMode.MAIN_THREAD);
    }

    private static DataCenterResearchAccess.Result staleDownload() {
        return new DataCenterResearchAccess.Result(DataCenterResearchAccess.Status.STALE_CONTEXT, null);
    }

    /**
     * An exact native inventory slot supplies one material; the server freezes event output once.
     */
    @SuppressWarnings({"resource", "unused"}) // Public request API retained while the research GUI is deferred.
    public static void requestItemDownload(EntityPlayerMP actor, String expectedTeam, UUID expectedDataCenterId,
                                           ResourceLocation id, int level, int slot, UUID nonce, BooleanSupplier ownerCurrent,
                                           Consumer<ResearchDownloadResult> result) {
        var current = actorSession(actor);
        var definition = ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        definition.stageForLevel(level);
        var intent = new ResearchDownloadOperation.Intent(actor.getUniqueID(), nonce, expectedDataCenterId, expectedTeam, id, level, slot);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(staleItemDownload());
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(staleItemDownload());
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(staleItemDownload());
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) {
                result.accept(staleItemDownload());
                return;
            }
            var authority = proof(current, captures, online);
            var physical = current.dataCenters.capture(expectedTeam, expectedDataCenterId);
            if (physical == null || !membership.members().contains(physical.ownerId())) {
                result.accept(staleItemDownload());
                return;
            }
            var cloud = current.world.captureCloud(membership, permission, ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership)));
            if (cloud == null) {
                result.accept(staleItemDownload());
                return;
            }
            if (cloud.decision() != ResearchPermissionPolicy.Decision.ALLOWED) {
                result.accept(new ResearchDownloadResult(ResearchDownloadResult.Status.PERMISSION_DENIED, null, null));
                return;
            }
            if (!cloud.current().getAsBoolean()) {
                result.accept(staleItemDownload());
                return;
            }
            result.accept(current.world.requestItemDownload(intent, membership, permission, physical.snapshot(),
                new MinecraftResearchDownloadInventory(actor), material -> ResearchItemDownloadRegistry.INSTANCE.evaluate(
                    new ResearchItemDownloadContext(ResearchRegistry.INSTANCE, level, new ItemStack(material.material()), definition,
                        actor, current.server, actor.getServerWorld(), expectedDataCenterId, expectedTeam,
                        actor.getServerWorld().getTotalWorldTime(), material.operationId())), operationTime(current),
                new MinecraftItemDeliveryAdapter(actor), () -> authority.getAsBoolean() && physical.current()
                    && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership)),
                () -> currentActor(current, actor) && ownerCurrent.getAsBoolean()));
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Physical right-click entry; captures the original tile, held slot and item identity before queueing.
     */
    @SuppressWarnings("resource")
    public static void requestDiskImport(EntityPlayerMP actor, BlockPos pos, EnumHand hand, EnumFacing facing,
                                         Consumer<ResearchDiskImportResult> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(result);
        var position = Objects.requireNonNull(pos).toImmutable();
        var actorWorld = actor.getServerWorld();
        var tile = actorWorld.getTileEntity(position);
        int slot = Objects.requireNonNull(hand) == EnumHand.MAIN_HAND ? actor.inventory.currentItem : 40;
        var key = AEItemKey.of(actor.inventory.getStackInSlot(slot));
        if (!(tile instanceof MachineControllerHost host) || key == null) {
            result.accept(staleDiskImport());
            return;
        }
        var nonce = UUID.randomUUID();
        BooleanSupplier ownerCurrent = () -> currentActor(current, actor) && actor.getServerWorld() == actorWorld
            && actorWorld.isBlockLoaded(position) && actorWorld.getTileEntity(position) == tile
            && key.equals(AEItemKey.of(actor.inventory.getStackInSlot(slot)))
            && actorWorld.isBlockModifiable(actor, position) && actor.canPlayerEdit(position, facing, actor.inventory.getStackInSlot(slot));
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!ownerCurrent.getAsBoolean()) {
                result.accept(staleDiskImport());
                return;
            }
            var machine = host.machine();
            if (machine == null) {
                result.accept(staleDiskImport());
                return;
            }
            DataCenterStorage clickedStorage = null;
            var elements6 = machine.components().assemblyBindings();
            for (int index6 = 0; index6 < elements6.size(); index6++) {
                var binding = elements6.get(index6);
                if (!(binding.component() instanceof DataCenterStorage storage)) continue;
                if (clickedStorage != null)
                    throw new IllegalStateException("Clicked machine has multiple data-center storage bindings");
                clickedStorage = storage;
            }
            if (clickedStorage == null) {
                result.accept(staleDiskImport());
                return;
            }
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(staleDiskImport());
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted()) {
                result.accept(new ResearchDiskImportResult(ResearchDiskImportResult.Status.PERMISSION_DENIED, null, null));
                return;
            }
            performDiskImport(current, actor, membership.teamId(), clickedStorage.snapshot().dataCenterId(), slot, nonce,
                ownerCurrent, result);
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Java hosts name their exact expected team, physical database and single native inventory slot.
     */
    @SuppressWarnings({"resource", "unused"}) // Public Java request for external item/host integrations.
    public static void requestDiskImport(EntityPlayerMP actor, String expectedTeam, UUID expectedDataCenterId, int slot,
                                         UUID nonce, BooleanSupplier ownerCurrent, Consumer<ResearchDiskImportResult> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(expectedTeam);
        Objects.requireNonNull(expectedDataCenterId);
        Objects.requireNonNull(nonce);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() ->
            performDiskImport(current, actor, expectedTeam, expectedDataCenterId, slot, nonce, ownerCurrent, result), ExecutionMode.MAIN_THREAD);
    }

    private static void performDiskImport(Session current, EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                          int slot, UUID nonce, BooleanSupplier ownerCurrent, Consumer<ResearchDiskImportResult> result) {
        if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
            result.accept(staleDiskImport());
            return;
        }
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) {
            result.accept(staleDiskImport());
            return;
        }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted() || !expectedTeam.equals(membership.teamId())) {
            result.accept(staleDiskImport());
            return;
        }
        var permission = permission(current, actor, membership);
        var physical = current.dataCenters.capture(expectedTeam, databaseId);
        if (permission == null || physical == null || !membership.members().contains(physical.ownerId())) {
            result.accept(staleDiskImport());
            return;
        }
        var authority = proof(current, captures, online);
        var intent = new ResearchDiskOperation.Intent(actor.getUniqueID(), nonce, databaseId, expectedTeam, slot);
        result.accept(current.world.requestDiskImport(intent, membership, permission, physical.target(), physical.storage(),
            new MinecraftResearchDownloadInventory(actor), material -> readDisk(material.material()), operationTime(current),
            () -> authority.getAsBoolean() && physical.current() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                && permission.equals(permission(current, actor, membership)), physical::physicalCurrent, () -> currentActor(current, actor)));
    }

    private static @Nullable ResearchDiskData readDisk(NBTTagCompound material) {
        var stack = new ItemStack(material);
        if (!(stack.getItem() instanceof ResearchDiskCarrier carrier)) return null;
        // Strict external NBT is an input boundary. A malformed disk is rejected before a world operation exists.
        try {
            var data = carrier.readResearchDisk(stack, ResearchRegistry.INSTANCE);
            if (data != null && data.tier() != carrier.researchDiskTier(stack)) return null;
            return data;
        } catch (IllegalArgumentException invalidDisk) {
            return null;
        }
    }

    private static ResearchDiskImportResult staleDiskImport() {
        return new ResearchDiskImportResult(ResearchDiskImportResult.Status.STALE_CONTEXT, null, null);
    }

    /**
     * Explicit cancellation remains available after team changes while the world proves no database attempt.
     */
    @SuppressWarnings({"resource", "unused"}) // Public Java cancellation for an original pending operation.
    public static void requestDiskImportCancel(EntityPlayerMP actor, UUID operationId, Consumer<ResearchDiskImportResult> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor)) {
                result.accept(staleDiskImport());
                return;
            }
            result.accept(current.world.abortDiskImport(actor.getUniqueID(), operationId, new MinecraftResearchDownloadInventory(actor), operationTime(current)));
        }, ExecutionMode.MAIN_THREAD);
    }

    private static void recoverDiskImports(Session current, EntityPlayerMP actor) {
        if (!currentActor(current, actor)) return;

        var elements2 = current.world.pendingDiskImports(actor.getUniqueID());
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var operation = elements2.get(index2);
            if (!currentActor(current, actor)) return;
            var inventory = new MinecraftResearchDownloadInventory(actor);
            if (operation.status() == ResearchDiskOperation.Status.ABORTING) {
                current.world.abortDiskImport(actor.getUniqueID(), operation.id(), inventory, operationTime(current));
                continue;
            }
            var original = current.dataCenters.captureOriginal(operation.target());
            if (original == null || !(original.storage() instanceof ResearchDiskStorage storage)) continue;
            OwnerTeamResolution membership = null;
            ResearchPermissionContext permission = null;
            BooleanSupplier authorized = () -> false;
            if (operation.status() == ResearchDiskOperation.Status.PREPARED && storage.receipt(operation.id()) == null) {
                var online = onlinePlayers(current);
                var captures = capture(current, Set.of(actor.getUniqueID()));
                if (!unavailable(captures)) {
                    var capturedMembership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
                    if (capturedMembership.accepted() && operation.intent().teamId().equals(capturedMembership.teamId())) {
                        var capturedPermission = permission(current, actor, capturedMembership);
                        var selected = current.dataCenters.capture(operation.intent().teamId(), operation.intent().databaseId());
                        if (capturedPermission != null && selected != null && operation.target().equals(selected.target())
                            && capturedMembership.members().contains(operation.target().ownerId())) {
                            membership = capturedMembership;
                            permission = capturedPermission;
                            var authority = proof(current, captures, online);
                            authorized = () -> authority.getAsBoolean() && selected.current() && currentActor(current, actor)
                                && capturedPermission.equals(permission(current, actor, capturedMembership));
                        }
                    } else if (capturedMembership.accepted() && !operation.databaseArmed()) {
                        current.world.abortDiskImport(actor.getUniqueID(), operation.id(), inventory, operationTime(current));
                        continue;
                    }
                }
            }
            current.world.recoverDiskImport(actor.getUniqueID(), operation.id(), inventory,
                new ResearchDiskRecovery(storage, original.target(), original::current, membership, permission, authorized), operationTime(current),
                () -> currentActor(current, actor));
        }

    }


    private static ResearchDownloadResult staleItemDownload() {
        return new ResearchDownloadResult(ResearchDownloadResult.Status.STALE_CONTEXT, null, null);
    }

    /**
     * Owner boundary retries frozen obligations; completed outputs require no current team or database.
     */
    private static void recoverDownloads(Session current) {
        for (var actor : onlinePlayers(current).values()) {
            if (current.world.pendingDownloadOperations(actor.getUniqueID()).isEmpty()
                && current.world.pendingDiskImports(actor.getUniqueID()).isEmpty()
                && !MinecraftResearchDownloadInventory.hasPending(actor)) continue;
            recoverDownloads(current, actor);
        }
    }

    private static void recoverDownloads(Session current, EntityPlayerMP actor) {
        if (!currentActor(current, actor)) return;
        recoverDiskImports(current, actor);
        current.world.recoverItemDownloads(actor.getUniqueID(), new MinecraftResearchDownloadInventory(actor),
            new MinecraftItemDeliveryAdapter(actor), operationTime(current), () -> currentActor(current, actor),
            operation -> downloadAuthorized(current, actor, operation));
    }

    /**
     * Only a PREPARED operation may depend on fresh team/database authorization.
     */
    private static boolean downloadAuthorized(Session current, EntityPlayerMP actor, ResearchDownloadOperation operation) {
        if (!currentActor(current, actor)) return false;
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) return false;
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        var intent = operation.intent();
        if (!membership.accepted() || !intent.teamId().equals(membership.teamId())
            || (membership.members().size() != operation.sources().members().size()
            || !membership.members().containsAll(operation.sources().members()))) return false;
        var permission = permission(current, actor, membership);
        if (permission == null) return false;
        var authority = proof(current, captures, online);
        if (current.permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission)
            != ResearchPermissionPolicy.Decision.ALLOWED) return false;
        var physical = current.dataCenters.capture(intent.teamId(), intent.databaseId());
        if (physical == null || !membership.members().contains(physical.ownerId())) return false;
        var definition = ResearchRegistry.INSTANCE.require(intent.researchId());
        if (definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM
            && (physical.snapshot().storedResearch().getInt(intent.researchId()) < intent.level()
            || definition.researchTierForLevel(intent.level()) > physical.snapshot().level())) return false;
        return authority.getAsBoolean() && physical.current() && currentActor(current, actor)
            && permission.equals(permission(current, actor, membership));
    }

    /**
     * Exact server identity rejects callbacks from an earlier integrated-server session.
     */
    @SuppressWarnings("unused") // Public Java research entry for server hosts and addons.
    public static ResearchWorld forServer(MinecraftServer server) {
        return active(server).world;
    }

    /**
     * Read-only capture; the scheduler takes ownership before research can execute or save.
     */
    @SuppressWarnings("resource") // The server lifecycle owns the borrowed network runtime.
    @Nullable
    public static PlannedExecution prepareTick(MinecraftServer server, PlannedDeadline deadline) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null || current.closing) {
            return null;
        }
        if (current.server != server || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("Research tick changed its server or overworld identity");
        }
        var online = onlinePlayers(current);
        var seeds = new ObjectOpenHashSet<>(current.world.trackedPlayers());
        seeds.addAll(online.keySet());
        var captures = capture(current, seeds);
        var proof = proof(current, captures, online);
        // A research checkpoint can reach disk before vanilla world time. Persisted research time
        // therefore advances independently once per server tick and never moves backwards on reload.
        long time = Math.max(Math.incrementExact(current.world.serverTime()), current.overworld.getTotalWorldTime());
        return current.world.prepareTick(time, captures, online.keySet(), id -> inventory(current, id), proof,
            current.stations, () -> ServerNetworkRuntime.forServer(server).refresh(), deadline);
    }

    /**
     * Only the actual actor can create a tier-zero session; this direct entry needs no network publication.
     */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static TeamResearchStart.Result startManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        return startCurrent(current, actor, id, target);
    }

    /**
     * Queue a player start through the existing server scheduler. Its MAIN action receives a fresh
     * network publication after old research/source work has settled. Actor and team authority are
     * captured again when executed; the caller supplies no device levels or resource vector.
     * The result callback runs once after the attempted admission and its world checkpoint succeed.
     * Unexecuted requests follow scheduler lifetime and are discarded during server shutdown.
     */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target,
                                    Consumer<TeamResearchStart.Result> result) {
        requestStart(actor, id, target, () -> true, result);
    }

    /**
     * A container can retain its exact open-window guard through admission callbacks.
     */
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target, BooleanSupplier ownerCurrent,
                                    Consumer<TeamResearchStart.Result> result) {
        requestStart(actor, id, target, null, ownerCurrent, result);
    }

    /**
     * Bind admission to the team displayed by the original server-owned container projection.
     */
    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                    BooleanSupplier ownerCurrent, Consumer<TeamResearchStart.Result> result) {
        requestStart(actor, id, target, expectedTeam, ownerCurrent, result, () -> {
        });
    }

    public static void requestStart(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                    BooleanSupplier ownerCurrent, Consumer<TeamResearchStart.Result> result, Runnable finalized) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(target);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        submitAction(current, () -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(staleStart());
                return;
            }
            result.accept(startCurrent(current, actor, id, target, expectedTeam, ownerCurrent));
        }, finalized);
    }

    /**
     * Claims the actual player's saved reward eligibility at the existing MAIN boundary. Selection
     * starts delivery, as in PN. Pending source work settles first; queued requests recheck the exact
     * server/player session and never accept a client-supplied recipient or item plan.
     */
    @SuppressWarnings("unused") // Public Java operation until packet/command adapters are attached.
    public static void requestReward(EntityPlayerMP actor, ResourceLocation id, int level, ResourceLocation group,
                                     Consumer<ResearchRewardClaimer.Result> result) {
        requestReward(actor, id, level, group, () -> true, result);
    }

    public static void requestReward(EntityPlayerMP actor, ResourceLocation id, int level, ResourceLocation group,
                                     BooleanSupplier ownerCurrent, Consumer<ResearchRewardClaimer.Result> result) {
        requestReward(actor, id, level, group, ownerCurrent, result, () -> {
        });
    }

    public static void requestReward(EntityPlayerMP actor, ResourceLocation id, int level, ResourceLocation group,
                                     BooleanSupplier ownerCurrent, Consumer<ResearchRewardClaimer.Result> result, Runnable finalized) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(level);
        Objects.requireNonNull(group);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        submitAction(current, () -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(new ResearchRewardClaimer.Result(ResearchRewardClaimer.Status.STALE_CONTEXT,
                    "Reward actor is no longer current", null));
                return;
            }
            result.accept(current.world.claimReward(actor.getUniqueID(), id, level, group, operationTime(current),
                new MinecraftItemDeliveryAdapter(actor), () -> currentActor(current, actor) && ownerCurrent.getAsBoolean()));
        }, finalized);
    }

    private static TeamResearchStart.Result startCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target) {
        return startCurrent(current, actor, id, target, null, () -> true);
    }

    @SuppressWarnings("resource")
    private static TeamResearchStart.Result startCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target,
                                                         @Nullable String expectedTeam, BooleanSupplier ownerCurrent) {
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) {
            return staleStart();
        }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) {
            return new TeamResearchStart.Result(TeamResearchStart.Status.PERMISSION_DENIED, null,
                ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
        }
        if (expectedTeam != null && !expectedTeam.equals(membership.teamId())) return staleStart();
        var permission = permission(current, actor, membership);
        if (permission == null) {
            return staleStart();
        }
        var capturedCurrent = proof(current, captures, online);
        BooleanSupplier actorCurrent = () -> capturedCurrent.getAsBoolean() && ownerCurrent.getAsBoolean()
            && permission.equals(permission(current, actor, membership));
        if (ResearchRegistry.INSTANCE.require(id).researchTierForLevel(target) == 0) {
            return current.world.start(membership, permission, id, target,
                new TeamResearchStart.Environment(0, 0, 0, Reference2DoubleMaps.emptyMap()), operationTime(current),
                online.keySet(), actorCurrent);
        }
        var network = ServerNetworkRuntime.forServer(current.server);
        var publication = network.plan();
        if (publication == null) {
            return staleStart();
        }
        var admission = current.stations.admission(membership.teamId());
        return current.world.start(membership, permission, id, target, admission.environment(), operationTime(current),
            online.keySet(), () -> actorCurrent.getAsBoolean() && network.plan() == publication && admission.current());
    }

    @SuppressWarnings("unused") // Public Java operation; login deliberately does not resume research.
    public static ManualResearch.Status takeoverManual(EntityPlayerMP actor, ResourceLocation id, int target) {
        var current = actorSession(actor);
        requireManual(id, target);
        return takeoverCurrent(current, actor, id, target, null, 0, () -> true);
    }

    private static ManualResearch.Status takeoverCurrent(Session current, EntityPlayerMP actor, ResourceLocation id, int target,
                                                         @Nullable String expectedTeam, long expectedEntryId, BooleanSupplier ownerCurrent) {
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) {
            return ManualResearch.Status.STALE_CONTEXT;
        }
        var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!membership.accepted()) {
            return ManualResearch.Status.CONTRIBUTOR_UNAVAILABLE;
        }
        if (expectedTeam != null && !expectedTeam.equals(membership.teamId()))
            return ManualResearch.Status.STALE_CONTEXT;
        var authority = proof(current, captures, online);
        return current.world.takeover(membership, actor.getUniqueID(), id, target, expectedEntryId,
            operationTime(current), online.keySet(), () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean());
    }

    @SuppressWarnings("unused") // Retained public Java operation for callers without a container projection.
    public static void requestTakeover(EntityPlayerMP actor, ResourceLocation id, int target, BooleanSupplier ownerCurrent,
                                       Consumer<ManualResearch.Status> result) {
        requestTakeover(actor, id, target, null, 0, ownerCurrent, result);
    }

    /**
     * Team and queue entry belong to the displayed session, not merely its research ID and level.
     */
    public static void requestTakeover(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                       long expectedEntryId, BooleanSupplier ownerCurrent, Consumer<ManualResearch.Status> result) {
        requestTakeover(actor, id, target, expectedTeam, expectedEntryId, ownerCurrent, result, () -> {
        });
    }

    public static void requestTakeover(EntityPlayerMP actor, ResourceLocation id, int target, @Nullable String expectedTeam,
                                       long expectedEntryId, BooleanSupplier ownerCurrent, Consumer<ManualResearch.Status> result, Runnable finalized) {
        var current = actorSession(actor);
        requireManual(id, target);
        if (expectedEntryId < 0) throw new IllegalArgumentException("Invalid expected research queue entry");
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        submitAction(current, () -> result.accept(currentActor(current, actor) && ownerCurrent.getAsBoolean()
                ? takeoverCurrent(current, actor, id, target, expectedTeam, expectedEntryId, ownerCurrent) : ManualResearch.Status.STALE_CONTEXT),
            finalized);
    }

    @SuppressWarnings("unused") // Retained public Java operation for callers without a container projection.
    public static void requestBlacklist(EntityPlayerMP actor, ResourceLocation id, boolean blacklisted, BooleanSupplier ownerCurrent,
                                        Consumer<ResearchPermissionPolicy.Decision> result) {
        requestBlacklist(actor, id, blacklisted, null, ownerCurrent, result);
    }

    public static void requestBlacklist(EntityPlayerMP actor, ResourceLocation id, boolean blacklisted, @Nullable String expectedTeam,
                                        BooleanSupplier ownerCurrent, Consumer<ResearchPermissionPolicy.Decision> result) {
        requestBlacklist(actor, id, blacklisted, expectedTeam, ownerCurrent, result, () -> {
        });
    }

    public static void requestBlacklist(EntityPlayerMP actor, ResourceLocation id, boolean blacklisted, @Nullable String expectedTeam,
                                        BooleanSupplier ownerCurrent, Consumer<ResearchPermissionPolicy.Decision> result, Runnable finalized) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        submitAction(current, () -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) return;
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
                return;
            }
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (!membership.accepted()) {
                result.accept(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
                return;
            }
            if (expectedTeam != null && !expectedTeam.equals(membership.teamId())) {
                result.accept(ResearchPermissionPolicy.Decision.TARGET_TEAM_MISMATCH);
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) {
                result.accept(ResearchPermissionPolicy.Decision.NOT_TEAM_MEMBER);
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.setBlacklisted(membership, permission, id, blacklisted, operationTime(current),
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, finalized);
    }

    @SuppressWarnings("unused") // Public Java callers need no container projection.
    public static void requestForget(EntityPlayerMP actor, ResourceLocation id, int forgottenLevel,
                                     BooleanSupplier ownerCurrent, Consumer<ResearchLevelResult> result) {
        requestForget(actor, id, forgottenLevel, null, -1, ownerCurrent, result, () -> {
        });
    }

    public static void requestForget(EntityPlayerMP actor, ResourceLocation id, int forgottenLevel,
                                     @Nullable String expectedTeam, int expectedBefore, BooleanSupplier ownerCurrent,
                                     Consumer<ResearchLevelResult> result, Runnable finalized) {
        var current = actorSession(actor);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        submitLevelAction(current, () -> levelCurrent(current, actor, expectedTeam, actor.getUniqueID(), null, ownerCurrent,
            (membership, permission, proof) -> current.world.forgetPersonal(membership, permission, id, forgottenLevel,
                expectedBefore, operationTime(current), proof)), result, finalized);
    }

    @SuppressWarnings("unused") // Public Java callers need no container projection.
    public static void requestAdminPlayerLevel(EntityPlayerMP actor, UUID target, ResourceLocation id, int level,
                                               BooleanSupplier ownerCurrent, Consumer<ResearchLevelResult> result) {
        requestAdminPlayerLevel(actor, target, id, level, null, ownerCurrent, result, () -> {
        });
    }

    public static void requestAdminPlayerLevel(EntityPlayerMP actor, UUID target, ResourceLocation id, int level,
                                               @Nullable String expectedTeam, BooleanSupplier ownerCurrent,
                                               Consumer<ResearchLevelResult> result, Runnable finalized) {
        var current = actorSession(actor);
        Objects.requireNonNull(target);
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        submitLevelAction(current, () -> levelCurrent(current, actor, expectedTeam, target, null, ownerCurrent,
            (membership, permission, proof) -> current.world.setPlayerResearchLevel(membership, permission, id, level,
                operationTime(current), proof)), result, finalized);
    }

    @SuppressWarnings("unused") // Public Java callers need no container projection.
    public static void requestAdminTeamLevel(EntityPlayerMP actor, String target, ResourceLocation id, int level,
                                             BooleanSupplier ownerCurrent, Consumer<ResearchLevelResult> result) {
        requestAdminTeamLevel(actor, target, id, level, null, ownerCurrent, result, () -> {
        });
    }

    public static void requestAdminTeamLevel(EntityPlayerMP actor, String target, ResourceLocation id, int level,
                                             @Nullable String expectedTeam, BooleanSupplier ownerCurrent,
                                             Consumer<ResearchLevelResult> result, Runnable finalized) {
        var current = actorSession(actor);
        Objects.requireNonNull(target);
        if (target.isBlank() || target.length() > 256 || !target.equals(target.trim()))
            throw new IllegalArgumentException("Invalid target team");
        ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id));
        submitLevelAction(current, () -> levelCurrent(current, actor, expectedTeam, null, target, ownerCurrent,
            (membership, permission, proof) -> current.world.setTeamResearchLevel(target,
                membership == null ? Set.of() : membership.members(), permission, id, level, operationTime(current), proof)), result, finalized);
    }

    private static ResearchLevelResult levelCurrent(Session current, EntityPlayerMP actor, @Nullable String expectedTeam,
                                                    @Nullable UUID targetPlayer, @Nullable String targetTeam,
                                                    BooleanSupplier ownerCurrent, LevelOperation operation) {
        if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
        if (!Universe.loaded() || Universe.get().server != current.server) return ResearchLevelResult.RECOVERY_PENDING;
        var online = onlinePlayers(current);
        var seeds = new ObjectOpenHashSet<UUID>();
        seeds.add(actor.getUniqueID());
        if (targetPlayer != null) seeds.add(targetPlayer);
        var liveTeam = targetTeam == null ? null : levelTeam(current, targetTeam);
        if (liveTeam != null) {
            var elements5 = liveTeam.getMembers();
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var member = elements5.get(index5);
                if (member == null || member.getId() == null)
                    throw new IllegalStateException("Invalid target team member");
                seeds.add(member.getId());
            }
        }
        var captures = capture(current, seeds);
        if (unavailable(captures)) return ResearchLevelResult.RECOVERY_PENDING;
        var actorTeam = Objects.requireNonNull(captures.get(actor.getUniqueID()));
        if (!actorTeam.accepted() || expectedTeam != null && !expectedTeam.equals(actorTeam.teamId()))
            return ResearchLevelResult.STALE_CONTEXT;
        var actorPermission = permission(current, actor, actorTeam);
        if (actorPermission == null) return ResearchLevelResult.STALE_CONTEXT;
        OwnerTeamResolution membership = null;
        String teamId = targetTeam;
        var targetMembers = new ObjectOpenHashSet<UUID>();
        if (targetPlayer != null) {
            membership = Objects.requireNonNull(captures.get(targetPlayer));
            if (!membership.accepted()) return ResearchLevelResult.STALE_CONTEXT;
            teamId = membership.teamId();
        } else if (liveTeam != null) {
            var elements10 = liveTeam.getMembers();
            for (int index10 = 0; index10 < elements10.size(); index10++) {
                var member = elements10.get(index10);
                var resolution = captures.get(member.getId());
                if (resolution == null || !resolution.accepted() || !targetTeam.equals(resolution.teamId()))
                    throw new IllegalStateException("Target team capture contradicts its member");
                if (membership != null && !sameTeam(membership, resolution))
                    throw new IllegalStateException("Conflicting target team member capture");
                membership = resolution;
                targetMembers.add(member.getId());
            }
            if (membership != null && !membership.members().equals(targetMembers))
                throw new IllegalStateException("Target team membership changed during capture");
        }
        var permission = new ResearchPermissionContext(actor.getUniqueID(), actorTeam.teamId(), targetPlayer, teamId,
            actorPermission.teamLeader(), actorPermission.teamModerator(), actorPermission.serverOperator());
        var authority = proof(current, captures, online);
        BooleanSupplier valid = () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
            && actorPermission.equals(permission(current, actor, actorTeam))
            && (targetTeam == null || currentLevelTeam(current, targetTeam, liveTeam, targetMembers));
        if (!valid.getAsBoolean()) return ResearchLevelResult.STALE_CONTEXT;
        return operation.apply(membership, permission, valid);
    }

    private static @Nullable ForgeTeam levelTeam(Session current, String id) {
        if (!Universe.loaded() || Universe.get().server != current.server) return null;
        ForgeTeam found = null;
        for (var team : Universe.get().getTeams()) {
            if (!id.equals(team.getUIDCode())) continue;
            if (found != null) throw new IllegalStateException("Duplicate target team identity");
            if (team.universe != Universe.get() || !team.isValid() || team.type != TeamType.PLAYER && team.type != TeamType.SERVER)
                throw new IllegalStateException("Invalid target team identity");
            found = team;
        }
        return found;
    }

    private static boolean currentLevelTeam(Session current, String id, @Nullable ForgeTeam captured, Set<UUID> members) {
        if (!Universe.loaded() || Universe.get().server != current.server || levelTeam(current, id) != captured)
            return false;
        var now = new ObjectOpenHashSet<UUID>();
        if (captured != null) {
            var elements4 = captured.getMembers();
            for (int index4 = 0; index4 < elements4.size(); index4++) {
                var member = elements4.get(index4);
                if (member == null || member.getId() == null)
                    throw new IllegalStateException("Invalid target team member");
                now.add(member.getId());
            }
        }
        return now.equals(members);
    }

    /**
     * Both enqueue failures and asynchronous operation/feedback failures release the original UI owner.
     */
    private static void submitLevelAction(Session current, Supplier<ResearchLevelResult> action,
                                          Consumer<ResearchLevelResult> result, Runnable finalized) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(result);
        submitAction(current, () -> result.accept(action.get()), finalized);
    }

    @SuppressWarnings("resource") // The server owns this shared scheduler.
    private static void submitAction(Session current, Runnable action, Runnable finalized) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(finalized);
        try {
            ServerTaskSchedulers.forServer(current.server).submit(() -> runFinalized(action, finalized), ExecutionMode.MAIN_THREAD);
        } catch (RuntimeException | Error failure) {
            try {
                finalized.run();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static void runFinalized(Runnable action, Runnable finalized) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            try {
                finalized.run();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        finalized.run();
    }

    /**
     * The callback receives null for a busy/stale read, never a replacement empty research state.
     */
    @SuppressWarnings({"resource", "unused"}) // Public request API retained while the research GUI is deferred.
    public static void requestView(EntityPlayerMP actor, BooleanSupplier ownerCurrent, Consumer<ResearchPlayerView> result) {
        var current = actorSession(actor);
        Objects.requireNonNull(ownerCurrent);
        Objects.requireNonNull(result);
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(null);
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = Objects.requireNonNull(captures.get(actor.getUniqueID()));
            if (unavailable(captures)) {
                membership = OwnerTeamResolution.rejected(actor.getUniqueID(), OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE,
                    "Research team capture is temporarily unavailable");
            }
            var team = membership;
            var permission = viewPermission(current, actor, team);
            if (permission == null) {
                result.accept(null);
                return;
            }
            var authority = proof(current, captures, online);
            var view = current.world.project(actor.getUniqueID(), team, permission,
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(viewPermission(current, actor, team)));
            result.accept(view);
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Reads display data at network tick end without creating teams or advancing research.
     */
    public static @Nullable ResearchStationView stationView(EntityPlayerMP actor, MachineControllerHost host) {
        var current = actorSession(actor);
        String expectedTeam = stationTeam(actor, host);
        if (expectedTeam == null) return ResearchStationView.unavailable();
        var membership = current.resolver.resolve(actor.getUniqueID());
        var ownPermission = viewPermission(current, actor, membership);
        if (ownPermission == null) return null;
        var target = new ResearchPermissionContext(actor.getUniqueID(), ownPermission.actorTeamId(), null, expectedTeam,
            ownPermission.teamLeader(), ownPermission.teamModerator(), ownPermission.serverOperator());
        return current.world.stationView(target, current.stations, operationTime(current));
    }

    public static @Nullable String stationTeam(EntityPlayerMP actor, MachineControllerHost host) {
        var current = actorSession(actor);
        var identity = host.savedIdentity();
        if (identity == null) return null;
        var team = current.resolver.resolve(identity.ownerId());
        return team.accepted() ? team.teamId() : null;
    }

    public static void requestQueueAction(EntityPlayerMP actor, String expectedTeam, long entryId, ResearchStationView.Action action,
                                          BooleanSupplier ownerCurrent, Consumer<Boolean> result, Runnable finalized) {
        var current = actorSession(actor);
        submitAction(current, () -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(false);
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(false);
                return;
            }
            var membership = captures.get(actor.getUniqueID());
            var permission = permission(current, actor, membership);
            if (permission == null || !expectedTeam.equals(membership.teamId())) {
                result.accept(false);
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.manageQueue(membership, permission, entryId, action, operationTime(current), online.keySet(),
                id -> inventory(current, id), () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, finalized);
    }

    /**
     * Mark closing before IO; a failed final checkpoint retains the exact session for retry.
     */
    public static synchronized void stop(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null) {
            return;
        }
        if (current.server != server) {
            throw new IllegalStateException("Research shutdown changed server identity");
        }
        current.closing = true;
        // The file writer retains its absolute path, so serverStopped can retry after world unload.
        current.world.close(operationTime(current));
        session = null;
    }

    /**
     * Idempotent serverStopped fallback for a retained failed-close session.
     */
    public static synchronized void stop() {
        var current = session;
        if (current != null) {
            stop(current.server);
        }
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save event) {
        var current = session;
        if (current == null || event.getWorld() != current.overworld) {
            return;
        }
        requireThread(current.server);
        current.world.checkpoint(operationTime(current));
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        var current = session;
        if (current != null && event.getWorld() == current.overworld) {
            stop(current.server);
        }
    }

    @SubscribeEvent
    @SuppressWarnings("resource") // The server owns the borrowed scheduler.
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player)
            || !belongsTo(current, player) || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {
            return;
        }
        requireThread(current.server);
        ServerTaskSchedulers.forServer(current.server).runAtBoundary(() -> {
            if (session == current && !current.closing && belongsTo(current, player)
                && current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player) {
                RewardDeliveryRecovery.reconcileDeliveryJournal(player);
                current.world.playerLogin(player.getUniqueID(), operationTime(current));
                releaseConfirmedRewards(current, player);
            }
        });
    }

    /**
     * A confirmed drop may load after its owner logged in; clean it after the join finishes.
     */
    @SubscribeEvent
    public static void onRewardEntityJoin(EntityJoinWorldEvent event) {
        var current = session;
        if (current == null || current.closing || event.getWorld().isRemote
            || event.getWorld().getMinecraftServer() != current.server || !(event.getEntity() instanceof EntityItem item)) {
            return;
        }
        var stack = item instanceof RewardItemEntity reward ? reward.deliveryStack() : item.getItem();
        if (!stack.hasTagCompound() || !Objects.requireNonNull(stack.getTagCompound()).hasKey(MinecraftItemDeliveryAdapter.DELIVERY_TAG)) {
            return;
        }
        requireThread(current.server);
        var identity = Objects.requireNonNull(stack.getTagCompound()).getCompoundTag(MinecraftItemDeliveryAdapter.DELIVERY_TAG);
        var parent = ItemDeliveryKeyNbt.readIdentity(identity);
        var id = parent.playerUuid();
        var player = current.server.getPlayerList().getPlayerByUUID(id);
        if (player != null) queueRewardCleanup(current, player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        cleanupCurrentPlayer(event.player);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        cleanupCurrentPlayer(event.player);
    }

    private static void cleanupCurrentPlayer(EntityPlayer actor) {
        var current = session;
        if (current != null && actor instanceof EntityPlayerMP player && !player.world.isRemote)
            queueRewardCleanup(current, player);
    }

    @SuppressWarnings("resource") // Cleanup uses the existing server-owned MAIN queue.
    private static void queueRewardCleanup(Session current, EntityPlayerMP player) {
        var id = player.getUniqueID();
        if (!currentActor(current, player) || !current.rewardCleanup.add(id)) return;
        try {
            ServerTaskSchedulers.forServer(current.server).submit(() -> {
                current.rewardCleanup.remove(id);
                if (currentActor(current, player)) {
                    releaseConfirmedRewards(current, player);
                }
            }, ExecutionMode.MAIN_THREAD);
        } catch (RuntimeException | Error failure) {
            current.rewardCleanup.remove(id);
            throw failure;
        }
    }

    @SubscribeEvent
    @SuppressWarnings("resource") // Logout follows retained source work on the existing scheduler boundary.
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var current = session;
        if (current == null || current.closing || !(event.player instanceof EntityPlayerMP player) || !belongsTo(current, player)) {
            return;
        }
        requireThread(current.server);
        var registered = current.server.getPlayerList().getPlayerByUUID(player.getUniqueID());
        if (registered != null && registered != player) {
            return;
        }
        ServerTaskSchedulers.forServer(current.server).runAtBoundary(() -> {
            if (session != current || current.closing) {
                return;
            }
            var now = current.server.getPlayerList().getPlayerByUUID(player.getUniqueID());
            if (now != null && now != player) {
                return;
            }
            var remaining = new ObjectOpenHashSet<>(onlinePlayers(current).keySet());
            remaining.remove(player.getUniqueID());
            current.world.playerLogout(player.getUniqueID(), operationTime(current), remaining);
        });
    }

    private static Session active(MinecraftServer server) {
        requireThread(Objects.requireNonNull(server));
        var current = session;
        if (current == null || current.server != server || current.closing || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("No active research session for this server");
        }
        return current;
    }

    private static Session actorSession(EntityPlayerMP actor) {
        Objects.requireNonNull(actor);
        var current = active(Objects.requireNonNull(actor.getServerWorld().getMinecraftServer()));
        if (!belongsTo(current, actor) || current.server.getPlayerList().getPlayerByUUID(actor.getUniqueID()) != actor) {
            throw new IllegalArgumentException("Research requires the actual online server player");
        }
        return current;
    }

    private static boolean belongsTo(Session current, EntityPlayerMP player) {
        return !(player instanceof FakePlayer) && !player.world.isRemote
            && player.getServerWorld().getMinecraftServer() == current.server;
    }

    private static boolean currentActor(Session current, EntityPlayerMP actor) {
        requireThread(current.server);
        return session == current && !current.closing && current.server.getWorld(0) == current.overworld
            && belongsTo(current, actor) && current.server.getPlayerList().getPlayerByUUID(actor.getUniqueID()) == actor;
    }

    /**
     * A crash after the research checkpoint may restore old delivery tags; remove only proven leaves.
     */
    private static void releaseConfirmedRewards(Session current, EntityPlayerMP player) {
        // A read-only snapshot may contain a CONFIRMED transition whose writer failed. Flush its
        // exact pending snapshot first; a nested world operation may only request a deferred save.
        current.world.checkpoint(operationTime(current));
        if (current.world.busy() || current.world.savePending()) return;
        recoverDownloads(current, player);
        if (current.world.busy() || current.world.savePending()) return;
        var delivery = new MinecraftItemDeliveryAdapter(player);
        var elements11 = current.world.snapshot().players();
        for (int index11 = 0; index11 < elements11.size(); index11++) {
            var saved = elements11.get(index11);
            if (!saved.playerUuid().equals(player.getUniqueID())) {
                continue;
            }
            var elements9 = saved.rewardClaims();
            for (int index9 = 0; index9 < elements9.size(); index9++) {
                var claim = elements9.get(index9);
                var elements8 = claim.children();
                for (int index8 = 0; index8 < elements8.size(); index8++) {
                    var child = elements8.get(index8);
                    for (var fragment : child.fragments().values()) {
                        if (!currentActor(current, player)) {
                            return;
                        }
                        if (fragment.status() == ItemDeliveryFragment.Status.CONFIRMED) {
                            delivery.release(fragment);
                        }
                    }
                }
            }
            break;
        }

        var elements = current.world.downloadReceipts(player.getUniqueID());
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var receipt = elements.get(index1);
            var elements3 = receipt.fragments();
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var fragment = elements3.get(index3);
                if (!currentActor(current, player)) return;
                delivery.release(fragment);
            }
        }

    }

    private static Object2ObjectOpenHashMap<UUID, EntityPlayerMP> onlinePlayers(Session current) {
        requireThread(current.server);
        var result = new Object2ObjectOpenHashMap<UUID, EntityPlayerMP>();
        var elements7 = current.server.getPlayerList().getPlayers();
        for (int index7 = 0; index7 < elements7.size(); index7++) {
            var player = elements7.get(index7);
            if (!belongsTo(current, player) || current.server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {
                continue;
            }
            if (result.putIfAbsent(player.getUniqueID(), player) != null) {
                throw new IllegalStateException("Duplicate actual online research player");
            }
        }
        return result;
    }

    private static Map<UUID, OwnerTeamResolution> capture(Session current, Set<UUID> seeds) {
        requireThread(current.server);
        var result = new Object2ObjectOpenHashMap<UUID, OwnerTeamResolution>();
        var teams = new Object2ObjectOpenHashMap<String, OwnerTeamResolution>();
        var pending = new ObjectArrayList<>(seeds);
        var scheduled = new ObjectOpenHashSet<>(seeds);
        for (int index = 0; index < pending.size(); index++) {
            var id = Objects.requireNonNull(pending.get(index));
            if (result.containsKey(id)) {
                continue;
            }
            var resolution = Objects.requireNonNull(current.resolver.resolve(id));
            if (!id.equals(resolution.ownerId())) {
                throw new IllegalStateException("Team resolver changed the queried player");
            }
            result.put(id, resolution);
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) {
                return result; // An unavailable provider is a normal pause, never evidence of team dissolution.
            }
            if (!resolution.accepted()) {
                continue;
            }
            var previous = teams.putIfAbsent(resolution.teamId(), resolution);
            if (previous != null && !sameTeam(previous, resolution)) {
                throw new IllegalStateException("Conflicting current research team captures");
            }
            for (var member : resolution.members()) {
                if (scheduled.add(member)) {
                    pending.add(member);
                }
            }
        }
        // Every member returned by a team must resolve back to that same team, including offline members.
        for (var resolution : teams.values()) {
            for (var member : resolution.members()) {
                var actual = result.get(member);
                if (actual == null || !actual.accepted() || !sameTeam(resolution, actual)) {
                    throw new IllegalStateException("Research team capture contradicts a member's current team");
                }
            }
        }
        return result;
    }

    private static BooleanSupplier proof(Session current, Map<UUID, OwnerTeamResolution> captures,
                                         Map<UUID, EntityPlayerMP> online) {
        return () -> {
            requireThread(current.server);
            if (session != current || current.closing || current.server.getWorld(0) != current.overworld) {
                return false;
            }
            var nowOnline = onlinePlayers(current);
            if (!nowOnline.keySet().equals(online.keySet())) {
                return false;
            }
            for (var entry : online.entrySet()) {
                if (nowOnline.get(entry.getKey()) != entry.getValue()) {
                    return false;
                }
            }
            for (var entry : captures.entrySet()) {
                var now = current.resolver.resolve(entry.getKey());
                if (now == null || !sameResolution(entry.getValue(), now)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static boolean sameResolution(OwnerTeamResolution left, OwnerTeamResolution right) {
        return left.ownerId().equals(right.ownerId()) && left.status() == right.status()
            && (!left.accepted() || sameTeam(left, right));
    }

    private static boolean sameTeam(OwnerTeamResolution left, OwnerTeamResolution right) {
        return left.teamId().equals(right.teamId()) && left.teamType() == right.teamType()
            && left.members().equals(right.members()) && Objects.equals(left.teamOwnerId(), right.teamOwnerId());
    }

    private static boolean unavailable(Map<UUID, OwnerTeamResolution> captures) {
        for (var resolution : captures.values()) {
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) {
                return true;
            }
        }
        return false;
    }

    private static TeamResearchStart.Result staleStart() {
        return new TeamResearchStart.Result(TeamResearchStart.Status.STALE_CONTEXT, null, null);
    }

    private static @Nullable ResearchPermissionContext viewPermission(Session current, EntityPlayerMP actor, OwnerTeamResolution membership) {
        return membership.accepted() ? permission(current, actor, membership)
            : new ResearchPermissionContext(actor.getUniqueID(), null, actor.getUniqueID(), null,
            false, false, current.server.getPlayerList().canSendCommands(actor.getGameProfile()));
    }

    private static @Nullable ResearchPermissionContext permission(Session current, EntityPlayerMP actor, OwnerTeamResolution membership) {
        if (!Universe.loaded() || Universe.get().server != current.server) {
            return null;
        }
        var player = Universe.get().getPlayer(actor.getUniqueID());
        if (player == null || player.team == null || !player.team.isValid() || !membership.teamId().equals(player.team.getUIDCode())) {
            return null;
        }
        return new ResearchPermissionContext(actor.getUniqueID(), membership.teamId(), null, membership.teamId(),
            player.team.isOwner(player), player.team.isModerator(player), current.server.getPlayerList().canSendCommands(actor.getGameProfile()));
    }

    private static void requireManual(ResourceLocation id, int target) {
        if (target < 1 || ResearchRegistry.INSTANCE.require(Objects.requireNonNull(id)).stageForLevel(target).researchTier() != 0) {
            throw new IllegalArgumentException("This server operation requires tier-zero research");
        }
    }

    private static @Nullable ManualResearchInventory inventory(Session current, UUID id) {
        requireThread(current.server);
        var player = current.server.getPlayerList().getPlayerByUUID(id);
        return player != null && belongsTo(current, player)
            ? new MinecraftManualResearchInventory(player, _ -> MinecraftResearchDownloadInventory.hasPending(player)) : null;
    }

    private static long operationTime(Session current) {
        return Math.max(current.world.serverTime(), current.overworld.getTotalWorldTime());
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Research operations require the authoritative server thread");
        }
    }

    @FunctionalInterface
    private interface LevelOperation {
        ResearchLevelResult apply(@Nullable OwnerTeamResolution membership, ResearchPermissionContext permission, BooleanSupplier current);
    }

    private static final class Session {
        private final MinecraftServer server;
        private final WorldServer overworld;
        private final ResearchWorld world;
        private final ResearchPermissionPolicy permissions;
        private final FtbOwnerTeamResolver resolver;
        private final ResearchStations stations;
        private final ResearchDataCenters dataCenters;
        private final DataCenterResearchAccess database;
        private final ObjectOpenHashSet<UUID> rewardCleanup = new ObjectOpenHashSet<>();
        private boolean closing;

        private Session(MinecraftServer server, WorldServer overworld, ResearchWorld world, ResearchPermissionPolicy permissions) {
            this.server = server;
            this.overworld = overworld;
            this.world = world;
            this.permissions = permissions;
            resolver = new FtbOwnerTeamResolver(server);
            stations = new ResearchStations(ServerMachineIdentities.forServer(server), () -> requireThread(server));
            dataCenters = new ResearchDataCenters(ServerMachineIdentities.forServer(server), () -> requireThread(server));
            database = new DataCenterResearchAccess(world, ServerNetworkRuntime.forServer(server), dataCenters);
        }
    }
}
