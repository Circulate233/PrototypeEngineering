package com.circulation.prototype_eng.research;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.DataCenterResearchAccess;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelResult;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchServerConfiguration;
import com.circulation.prototype_eng.api.research.ResearchWorld;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKeyNbt;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImportResult;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskInteractions;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskRecovery;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskStorage;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadInventory;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadResult;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadPreview;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadEvaluation;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportPreview;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskImport;
import com.circulation.prototype_eng.api.research.manual.ManualResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.container.ResearchOverviewPacket;
import com.circulation.prototype_eng.container.ContainerResearchOverview;
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
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
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
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.List;

/**
 * Authoritative server research session; its incremental store has one writer outside MapStorage.
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
        requireThread(server);
        if (session != null) {
            throw new IllegalStateException("A research server session is already installed");
        }
        var overworld = server.getWorld(0);
        var registry = ResearchRegistry.INSTANCE;
        var file = new ResearchStateStore(overworld.getSaveHandler().getMapFileFromName("prototype_eng_research").toPath(),
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
            if (saved != null) {
                var migrated = migrateLocalTeam(server, overworld, saved);
                world.restore(migrated);
                if (migrated != saved) world.checkpoint(migrated.serverTime());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        session = new Session(server, overworld, world, permissions);
    }

    private static ResearchWorldSnapshot migrateLocalTeam(MinecraftServer server, WorldServer world, ResearchWorldSnapshot saved) {
        if (!server.isSinglePlayer()) return saved;
        var host = world.getWorldInfo().getPlayerNBTTagCompound();
        if (host == null || !host.hasUniqueId("UUID")) return saved;
        var hostId = host.getUniqueId("UUID");
        if (hostId == null) return saved;
        var directory = world.getSaveHandler().getWorldDirectory().toPath().resolve("data/ftb_lib/teams");
        var otherFtbIds = new ObjectOpenHashSet<String>();
        String uid = "0002";
        if (!Files.notExists(directory)) {
            try (var files = Files.newDirectoryStream(directory, "*.dat")) {
                for (var path : files) {
                    if (!Files.isRegularFile(path)) continue;
                    try (var input = Files.newInputStream(path)) {
                        var team = CompressedStreamTools.readCompressed(input);
                        if (!team.hasKey("UID", 2)) continue;
                        var code = HexFormat.of().withUpperCase().toHexDigits(team.getShort("UID"));
                        if (path.getFileName().toString().equals("singleplayer.dat") && "singleplayer".equals(team.getString("ID"))
                            && TeamType.SERVER.getName().equals(team.getString("Type"))) uid = code;
                        else otherFtbIds.add(code);
                    }
                }
            } catch (IOException failure) {
                PrototypeEngineering.LOGGER.warn("Cannot verify legacy research team identities in {}", directory, failure);
                return saved;
            }
        }
        return LegacyResearchTeams.migrate(saved, hostId, uid, otherFtbIds);
    }

    /**
     * Refresh at the scheduler's exclusive boundary, after research work and before ordinary work.
     */
    @SuppressWarnings({"resource", "DataFlowIssue"})
    public static void refreshDataCenters(MinecraftServer server) {
        requireThread(server);
        var current = session;
        if (current != null && (current.server != server || current.overworld != server.getWorld(0))) {
            throw new IllegalStateException("Download recovery changed its server identity");
        }
        if (current != null && !current.closing) {
            recoverMachineDiskImports(current);
            recoverMachineDownloads(current);
            recoverDownloads(current);
        }
        var network = ServerNetworkRuntime.forServer(server);
        if (!network.refresh()) return;
        if (current == null || current.closing) return;
        if (current.server != server || current.overworld != server.getWorld(0)) {
            throw new IllegalStateException("Database synchronization changed its server identity");
        }
        if (current.world.savePending()) return;
        var teams = network.plan().teams().keySet();
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
            var membership = captures.get(physical.ownerId());
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
        ResearchRegistry.INSTANCE.require(id).stageForLevel(level);
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
            var membership = captures.get(actor.getUniqueID());
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
            var membership = captures.get(actor.getUniqueID());
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

    @SuppressWarnings({"resource", "unused"})
    public static void requestDataCenterRemove(EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                               ResourceLocation researchId, BooleanSupplier ownerCurrent,
                                               Consumer<DataCenterResearchAccess.Result> result) {
        var current = actorSession(actor);
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
            var membership = captures.get(actor.getUniqueID());
            var permission = membership.accepted() && expectedTeam.equals(membership.teamId())
                ? permission(current, actor, membership) : null;
            if (permission == null) {
                result.accept(staleDownload());
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.database.remove(membership, permission, databaseId, researchId,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership))));
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Queries one material conversion without source, inventory or persistent writes. The returned
     * candidate owns the callback result used by the subsequent request, including its exact cost.
     */
    @SuppressWarnings({"resource", "unused"}) // Public request API retained while the research GUI is deferred.
    public static void requestItemDownloadPreview(EntityPlayerMP actor, String expectedTeam, UUID expectedDataCenterId,
                                                  ResourceLocation id, int slot, ResearchDownloadInventory inventory,
                                                  ItemDeliveryAdapter delivery, BooleanSupplier ownerCurrent,
                                                  Consumer<ResearchItemDownloadPreview> result) {
        var current = actorSession(actor);
        var definition = ResearchRegistry.INSTANCE.require(id);
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
            var membership = captures.get(actor.getUniqueID());
            if (!membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(null);
                return;
            }
            var permission = permission(current, actor, membership);
            if (permission == null) {
                result.accept(null);
                return;
            }
            var authority = proof(current, captures, online);
            var physical = current.dataCenters.capture(expectedTeam, expectedDataCenterId);
            if (physical == null || !membership.members().contains(physical.ownerId())) {
                result.accept(null);
                return;
            }
            var cloud = current.world.captureCloud(membership, permission, ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership)));
            if (cloud == null) {
                result.accept(null);
                return;
            }
            if (cloud.decision() != ResearchPermissionPolicy.Decision.ALLOWED) {
                result.accept(null);
                return;
            }
            if (!cloud.current().getAsBoolean()) {
                result.accept(null);
                return;
            }
            var material = inventory.capture(UUID.randomUUID(), slot);
            if (material == null) {
                result.accept(null);
                return;
            }
            var stack = new ItemStack(material.material().copy());
            var recipe = ResearchItemDownloadRegistry.INSTANCE.find(AEItemKey.of(stack), id);
            if (definition.specialOnly()) {
                result.accept(null);
                return;
            }
            var output = ResearchItemDownloadRegistry.INSTANCE.evaluate(recipe,
                new ResearchItemDownloadContext(recipe == null ? 1 : recipe.researchLevel(), stack, definition,
                    actor, current.server, actor.getServerWorld(), expectedDataCenterId, expectedTeam,
                    actor.getServerWorld().getTotalWorldTime(), material.operationId()));
            if (output.status() != ResearchItemDownloadEvaluation.Status.SUCCESS) {
                result.accept(null);
                return;
            }
            if (!delivery.canOutput(output.output().serializeNBT(), output.output().getCount())) {
                result.accept(null);
                return;
            }
            var sources = current.world.previewItemDownloadSources(definition, output.researchLevel(), output.levelCost(),
                membership, physical.snapshot(), cloud.view());
            result.accept(new ResearchItemDownloadPreview(id, material, output, sources,
                inventory.machineInventory() ? physical.target() : null,
                () -> authority.getAsBoolean() && physical.current() && cloud.current().getAsBoolean()
                    && inventory.matches(material.inventoryBefore()) && currentActor(current, actor)
                    && ownerCurrent.getAsBoolean() && permission.equals(permission(current, actor, membership))));
        }, ExecutionMode.MAIN_THREAD);
    }

    /**
     * Admits a server candidate after checking its original input and authority. The callback is
     * not invoked again; both material and output may belong to a physical database adapter.
     */
    @SuppressWarnings({"resource", "unused"})
    public static void requestItemDownload(EntityPlayerMP actor, String expectedTeam, UUID expectedDataCenterId,
                                           ResearchItemDownloadPreview preview, UUID nonce, ResearchDownloadInventory inventory,
                                           ItemDeliveryAdapter delivery, BooleanSupplier ownerCurrent,
                                           Consumer<ResearchDownloadResult> result) {
        var current = actorSession(actor);
        var intent = new ResearchDownloadOperation.Intent(actor.getUniqueID(), nonce, expectedDataCenterId, expectedTeam,
            preview.researchId(), preview.output().researchLevel(), preview.material().slot(), inventory.machineInventory(), preview.target());
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
            var membership = captures.get(actor.getUniqueID());
            if (!membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(staleItemDownload());
                return;
            }
            var permission = permission(current, actor, membership);
            var physical = current.dataCenters.capture(expectedTeam, expectedDataCenterId);
            if (permission == null || physical == null || !membership.members().contains(physical.ownerId())
                || !preview.ready()) {
                result.accept(staleItemDownload());
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.requestItemDownload(intent, membership, permission, physical.snapshot(), inventory,
                _ -> preview.output(), preview.sources().plan(), preview.material().operationId(), preview.current(), operationTime(current), delivery,
                () -> authority.getAsBoolean() && physical.current() && currentActor(current, actor)
                    && ownerCurrent.getAsBoolean() && permission.equals(permission(current, actor, membership)),
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
        var position = pos.toImmutable();
        var actorWorld = actor.getServerWorld();
        var tile = actorWorld.getTileEntity(position);
        int slot = hand == EnumHand.MAIN_HAND ? actor.inventory.currentItem : 40;
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
            var membership = captures.get(actor.getUniqueID());
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
        ServerTaskSchedulers.forServer(current.server).submit(() ->
            performDiskImport(current, actor, expectedTeam, expectedDataCenterId, slot, nonce, ownerCurrent, result), ExecutionMode.MAIN_THREAD);
    }

    private static void performDiskImport(Session current, EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                          int slot, UUID nonce, BooleanSupplier ownerCurrent, Consumer<ResearchDiskImportResult> result) {
        performDiskImport(current, actor, expectedTeam, databaseId, slot, nonce, false, ownerCurrent, result);
    }

    @SuppressWarnings({"resource", "unused"})
    public static void requestItemImportFromDataCenter(EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                                       UUID nonce, BooleanSupplier ownerCurrent, Consumer<ResearchDiskImportResult> result) {
        var current = actorSession(actor);
        ServerTaskSchedulers.forServer(current.server).submit(() ->
            performDiskImport(current, actor, expectedTeam, databaseId, 0, nonce, true, ownerCurrent, result), ExecutionMode.MAIN_THREAD);
    }

    /**
     * Pure candidate query; the material callback runs once and its result belongs to the returned
     * preview. Database, input, output and player research remain unchanged.
     */
    @SuppressWarnings({"resource", "unused"})
    public static void requestItemImportPreview(EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                                int slot, ResearchDownloadInventory inventory, ItemDeliveryAdapter delivery,
                                                BooleanSupplier ownerCurrent,
                                                Consumer<ResearchItemImportPreview> result) {
        var current = actorSession(actor);
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
            var membership = captures.get(actor.getUniqueID());
            var permission = membership.accepted() && expectedTeam.equals(membership.teamId())
                ? permission(current, actor, membership) : null;
            var physical = permission == null ? null : current.dataCenters.capture(expectedTeam, databaseId);
            if (physical == null || !membership.members().contains(physical.ownerId())
                || !(physical.storage() instanceof ResearchDiskStorage storage) || !storage.diskPersistenceAvailable()) {
                result.accept(null);
                return;
            }
            var authority = proof(current, captures, online);
            var cloud = current.world.captureCloud(membership, permission, ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER,
                () -> authority.getAsBoolean() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership)));
            if (cloud == null || cloud.decision() != ResearchPermissionPolicy.Decision.ALLOWED || !cloud.current().getAsBoolean()
                || current.world.savePending()) {
                result.accept(null);
                return;
            }
            var material = inventory.capture(UUID.randomUUID(), slot);
            if (material == null) {
                result.accept(null);
                return;
            }
            var evaluated = ResearchItemImportRegistry.INSTANCE.evaluate(new ResearchItemImportContext(ResearchRegistry.INSTANCE,
                new ItemStack(material.material().copy()), actor, databaseId, expectedTeam, material.operationId()));
            if (evaluated == null || evaluated.amount() < 1) {
                result.accept(null);
                return;
            }
            if (!delivery.canOutput(evaluated.output(), evaluated.amount())) {
                result.accept(null);
                return;
            }
            var preparation = ResearchDiskImport.prepare(material.operationId(), actor.getUniqueID(), expectedTeam,
                evaluated.data(), physical.snapshot(), cloud.view());
            result.accept(new ResearchItemImportPreview(material, evaluated, preparation, physical.target(), inventory.machineInventory(),
                () -> !current.world.savePending() && authority.getAsBoolean() && cloud.current().getAsBoolean()
                    && physical.current() && inventory.matches(material.inventoryBefore()) && currentActor(current, actor)
                    && ownerCurrent.getAsBoolean()));
        }, ExecutionMode.MAIN_THREAD);
    }

    @SuppressWarnings({"resource", "unused"})
    public static void requestItemImport(EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                         ResearchItemImportPreview preview, UUID nonce, ResearchDownloadInventory inventory,
                                         ItemDeliveryAdapter delivery, BooleanSupplier ownerCurrent,
                                         Consumer<ResearchDiskImportResult> result) {
        var current = actorSession(actor);
        var intent = new ResearchDiskOperation.Intent(actor.getUniqueID(), nonce, databaseId, expectedTeam,
            preview.material().slot(), inventory.machineInventory());
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            var known = current.world.diskImportNonce(actor.getUniqueID(), nonce);
            if (known != null && currentActor(current, actor) && ownerCurrent.getAsBoolean()) {
                var status = !known.intent().equals(intent) ? ResearchDiskImportResult.Status.NONCE_CONFLICT
                    : known.status() == ResearchDiskOperation.Status.ABORTED ? ResearchDiskImportResult.Status.ABORTED
                    : known.status() == ResearchDiskOperation.Status.RELEASED ? ResearchDiskImportResult.Status.COMPLETED
                    : ResearchDiskImportResult.Status.PENDING;
                result.accept(new ResearchDiskImportResult(status, known.id(), null));
                return;
            }
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean() || !preview.ready() || !preview.current().getAsBoolean()
                || preview.machineInventory() != inventory.machineInventory()) {
                result.accept(staleDiskImport());
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(staleDiskImport());
                return;
            }
            var membership = captures.get(actor.getUniqueID());
            var permission = membership.accepted() && expectedTeam.equals(membership.teamId())
                ? permission(current, actor, membership) : null;
            var physical = permission == null ? null : current.dataCenters.capture(expectedTeam, databaseId);
            if (physical == null || !physical.target().equals(preview.target())) {
                result.accept(staleDiskImport());
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.requestDiskImport(intent, membership, permission, physical.target(), physical.storage(), inventory,
                _ -> preview.output(), preview.material(), delivery, operationTime(current),
                () -> authority.getAsBoolean() && physical.current() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                    && permission.equals(permission(current, actor, membership)), physical::physicalCurrent,
                () -> currentActor(current, actor)));
        }, ExecutionMode.MAIN_THREAD);
    }

    private static void performDiskImport(Session current, EntityPlayerMP actor, String expectedTeam, UUID databaseId,
                                          int slot, UUID nonce, boolean machineInventory, BooleanSupplier ownerCurrent,
                                          Consumer<ResearchDiskImportResult> result) {
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
        var membership = captures.get(actor.getUniqueID());
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
        ResearchDownloadInventory inventory;
        ItemDeliveryAdapter delivery;
        if (machineInventory) {
            if (!(physical.storage() instanceof MachineDataCenterStorage storage)) {
                result.accept(new ResearchDiskImportResult(ResearchDiskImportResult.Status.UNSUPPORTED_STORAGE, null, null));
                return;
            }
            var slots = storage.transfers().forPlayer(actor.getUniqueID());
            inventory = slots;
            delivery = slots;
        } else {
            inventory = new MinecraftResearchDownloadInventory(actor);
            delivery = new MinecraftItemDeliveryAdapter(actor);
        }
        var intent = new ResearchDiskOperation.Intent(actor.getUniqueID(), nonce, databaseId, expectedTeam, slot, machineInventory);
        result.accept(current.world.requestDiskImport(intent, membership, permission, physical.target(), physical.storage(),
            inventory, material -> ResearchItemImportRegistry.INSTANCE.evaluate(new ResearchItemImportContext(ResearchRegistry.INSTANCE,
                new ItemStack(material.material().copy()), actor, databaseId, expectedTeam, material.operationId())), null, delivery, operationTime(current),
            () -> authority.getAsBoolean() && physical.current() && currentActor(current, actor) && ownerCurrent.getAsBoolean()
                && permission.equals(permission(current, actor, membership)), physical::physicalCurrent, () -> currentActor(current, actor)));
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
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor)) {
                result.accept(staleDiskImport());
                return;
            }
            var operation = current.world.diskImport(actor.getUniqueID(), operationId);
            if (operation == null) {
                result.accept(new ResearchDiskImportResult(ResearchDiskImportResult.Status.RECOVERY_ERROR, operationId, null));
                return;
            }
            ResearchDownloadInventory inventory;
            if (operation.intent().machineInventory()) {
                var original = current.dataCenters.captureOriginal(operation.target());
                if (original == null || !(original.storage() instanceof MachineDataCenterStorage storage)) {
                    result.accept(new ResearchDiskImportResult(ResearchDiskImportResult.Status.PENDING, operationId, null));
                    return;
                }
                inventory = storage.transfers().forPlayer(actor.getUniqueID());
            } else inventory = new MinecraftResearchDownloadInventory(actor);
            result.accept(current.world.abortDiskImport(actor.getUniqueID(), operationId, inventory, operationTime(current)));
        }, ExecutionMode.MAIN_THREAD);
    }

    private static void recoverDiskImports(Session current, EntityPlayerMP actor) {
        if (!currentActor(current, actor)) return;

        var elements2 = current.world.pendingDiskImports(actor.getUniqueID());
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var operation = elements2.get(index2);
            if (!currentActor(current, actor)) return;
            var original = current.dataCenters.captureOriginal(operation.target());
            if (original == null || !(original.storage() instanceof ResearchDiskStorage storage)) continue;
            if (operation.intent().machineInventory() && (operation.status() != ResearchDiskOperation.Status.PREPARED
                || storage.receipt(operation.id()) != null)) continue;
            ResearchDownloadInventory inventory;
            ItemDeliveryAdapter delivery;
            if (operation.intent().machineInventory()) {
                if (!(storage instanceof MachineDataCenterStorage machineStorage)) continue;
                var slots = machineStorage.transfers().forPlayer(actor.getUniqueID());
                inventory = slots;
                delivery = slots;
            } else {
                inventory = new MinecraftResearchDownloadInventory(actor);
                delivery = new MinecraftItemDeliveryAdapter(actor);
            }
            if (operation.status() == ResearchDiskOperation.Status.ABORTING) {
                current.world.abortDiskImport(actor.getUniqueID(), operation.id(), inventory, operationTime(current));
                continue;
            }
            OwnerTeamResolution membership = null;
            ResearchPermissionContext permission = null;
            BooleanSupplier authorized = () -> false;
            if (operation.status() == ResearchDiskOperation.Status.PREPARED && storage.receipt(operation.id()) == null) {
                var online = onlinePlayers(current);
                var captures = capture(current, Set.of(actor.getUniqueID()));
                if (!unavailable(captures)) {
                    var capturedMembership = captures.get(actor.getUniqueID());
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
            current.world.recoverDiskImport(actor.getUniqueID(), operation.id(), inventory, delivery,
                new ResearchDiskRecovery(storage, original.target(), original::current, membership, permission, authorized), operationTime(current),
                () -> currentActor(current, actor));
        }

    }

    /**
     * Committed physical database effects do not depend on an online player or an open container.
     * A request that has not entered its database effect still needs its captured player authority.
     */
    private static void recoverMachineDiskImports(Session current) {
        var operations = current.world.pendingMachineDiskImports();
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            var original = current.dataCenters.captureOriginal(operation.target());
            if (original == null || !(original.storage() instanceof MachineDataCenterStorage storage)) continue;
            if (operation.status() == ResearchDiskOperation.Status.PREPARED && storage.receipt(operation.id()) == null)
                continue;
            var actorId = operation.intent().playerId();
            var slots = storage.transfers().forPlayer(actorId);
            current.world.recoverDiskImport(actorId, operation.id(), slots, slots,
                new ResearchDiskRecovery(storage, original.target(), original::current, null, null, () -> false), operationTime(current),
                () -> session == current && !current.closing && original.current());
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
        current.world.recoverItemDownloads(actor.getUniqueID(), null, new MinecraftResearchDownloadInventory(actor),
            new MinecraftItemDeliveryAdapter(actor), operationTime(current), () -> currentActor(current, actor),
            operation -> downloadAuthorized(current, actor, operation));
    }

    private static void recoverMachineDownloads(Session current) {
        var actors = current.world.downloadInventoryActors().iterator();
        while (actors.hasNext()) {
            var actorId = actors.next();
            var targets = current.world.downloadInventoryTargets(actorId).iterator();
            while (targets.hasNext()) {
                var target = targets.next();
                var physical = current.dataCenters.captureOriginal(target);
                if (physical == null || !(physical.storage() instanceof MachineDataCenterStorage storage)) continue;
                if (!storage.transfers().locked() && !current.world.pendingDownloadInventory(actorId, target)) {
                    targets.remove();
                    continue;
                }
                var adapter = storage.transfers().forPlayer(actorId);
                current.world.recoverItemDownloads(actorId, target, adapter, adapter, operationTime(current),
                    physical::current, operation -> {
                        var actor = current.server.getPlayerList().getPlayerByUUID(actorId);
                        return actor != null && downloadAuthorized(current, actor, operation);
                    });
            }
            if (current.world.downloadInventoryTargets(actorId).isEmpty()) actors.remove();
        }
    }

    /**
     * Only a PREPARED operation may depend on fresh team/database authorization.
     */
    private static boolean downloadAuthorized(Session current, EntityPlayerMP actor, ResearchDownloadOperation operation) {
        if (!currentActor(current, actor)) return false;
        var online = onlinePlayers(current);
        var captures = capture(current, Set.of(actor.getUniqueID()));
        if (unavailable(captures)) return false;
        var membership = captures.get(actor.getUniqueID());
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
        requireThread(server);
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
        ResearchRegistry.INSTANCE.require(id).stageForLevel(target);
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
        ResearchRegistry.INSTANCE.require(id).stageForLevel(level);
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
        var membership = captures.get(actor.getUniqueID());
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
        var membership = captures.get(actor.getUniqueID());
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
        ResearchRegistry.INSTANCE.require(id);
        submitAction(current, () -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) return;
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            if (unavailable(captures)) {
                result.accept(ResearchPermissionPolicy.Decision.MISSING_ACTOR_TEAM);
                return;
            }
            var membership = captures.get(actor.getUniqueID());
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
        ResearchRegistry.INSTANCE.require(id);
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
        ResearchRegistry.INSTANCE.require(id);
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
        if (target.isBlank() || target.length() > 256 || !target.equals(target.trim()))
            throw new IllegalArgumentException("Invalid target team");
        ResearchRegistry.INSTANCE.require(id);
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
        var personalOwner = targetTeam == null ? null : FtbOwnerTeamResolver.personalOwner(Universe.get(), targetTeam);
        if (personalOwner != null) seeds.add(personalOwner.getId());
        else if (liveTeam != null) {
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
        var actorTeam = captures.get(actor.getUniqueID());
        if (!actorTeam.accepted() || expectedTeam != null && !expectedTeam.equals(actorTeam.teamId()))
            return ResearchLevelResult.STALE_CONTEXT;
        var actorPermission = permission(current, actor, actorTeam);
        if (actorPermission == null) return ResearchLevelResult.STALE_CONTEXT;
        OwnerTeamResolution membership = null;
        String teamId = targetTeam;
        var targetMembers = new ObjectOpenHashSet<UUID>();
        if (targetPlayer != null) {
            membership = captures.get(targetPlayer);
            if (!membership.accepted()) return ResearchLevelResult.STALE_CONTEXT;
            teamId = membership.teamId();
        } else if (personalOwner != null) {
            membership = captures.get(personalOwner.getId());
            if (!membership.accepted() || !targetTeam.equals(membership.teamId())) return ResearchLevelResult.STALE_CONTEXT;
            targetMembers.add(personalOwner.getId());
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
        var universe = Universe.get();
        var personalOwner = FtbOwnerTeamResolver.personalOwner(universe, id);
        if (personalOwner != null) return personalOwner.team;
        ForgeTeam found = null;
        for (var team : universe.getTeams()) {
            if (FtbOwnerTeamResolver.personalTeam(team) || !id.equals(team.getUIDCode())) continue;
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
        var personalOwner = FtbOwnerTeamResolver.personalOwner(Universe.get(), id);
        if (personalOwner != null) return members.equals(Set.of(personalOwner.getId()));
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
        submitAction(current, () -> result.accept(action.get()), finalized);
    }

    @SuppressWarnings("resource") // The server owns this shared scheduler.
    private static void submitAction(Session current, Runnable action, Runnable finalized) {
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
        ServerTaskSchedulers.forServer(current.server).submit(() -> {
            if (!currentActor(current, actor) || !ownerCurrent.getAsBoolean()) {
                result.accept(null);
                return;
            }
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = captures.get(actor.getUniqueID());
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
     * Capture the overview at a scheduler boundary; definitions and permissions remain server authoritative.
     */
    @SuppressWarnings({"resource", "DataFlowIssue"})
    public static void requestOverview(EntityPlayerMP actor, @Nullable MachineControllerHost picker,
                                       boolean editor, BooleanSupplier ownerCurrent, Consumer<Overview> result) {
        var current = actorSession(actor);
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
            var membership = captures.get(actor.getUniqueID());
            var permission = viewPermission(current, actor, membership);
            if (permission == null) {
                result.accept(null);
                return;
            }
            var authority = proof(current, captures, online);
            var playerView = current.world.project(actor.getUniqueID(), membership, permission,
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean());
            if (playerView == null) {
                result.accept(null);
                return;
            }
            var station = current.world.stationView(permission, current.stations, operationTime(current), id -> inventory(current, id));
            if (station == null) {
                result.accept(null);
                return;
            }
            boolean visible = playerView.team().viewPermission() == ResearchPermissionPolicy.Decision.ALLOWED;
            var databases = new ObjectArrayList<ResearchOverviewPacket.Database>();
            var admission = current.stations.admission(playerView.team().id()).environment();
            int highestStation = admission.highestResearchStationLevel();
            var identities = ServerMachineIdentities.forServer(current.server).uniqueRegistrations();
            for (int index = 0; index < identities.size(); index++) {
                var identity = identities.get(index);
                if (!membership.accepted() || !membership.members().contains(identity.identity().ownerId())
                    || !(identity.runtimeIdentity() instanceof MachineInstance machine) || !machine.lifecycle().formed())
                    continue;
                if (visible && machine instanceof DataCenter database) {
                    var snapshot = database.dataCenterStorage().snapshot();
                    var stored = new ObjectArrayList<ResearchOverviewPacket.Stored>();
                    for (var entry : snapshot.storedResearch().object2IntEntrySet())
                        stored.add(new ResearchOverviewPacket.Stored(entry.getKey(), entry.getIntValue()));
                    databases.add(new ResearchOverviewPacket.Database(snapshot.dataCenterId(), machine.type().displayName().text().getUnformattedText(),
                        snapshot.level(), snapshot.capacity(), stored));
                }
            }
            var plan = ServerNetworkRuntime.forServer(current.server).plan();
            var team = plan == null ? null : plan.teams().get(playerView.team().id());
            UUID activeId = team == null ? null : team.dataCenterId();
            ResearchOverviewPacket.Database active = null;
            for (int index = 0; index < databases.size(); index++)
                if (databases.get(index).id().equals(activeId)) active = databases.get(index);
            DataCenterView pickerView = picker == null ? null : dataCenterView(actor, picker);
            int pickerLevel = pickerView == null ? 0 : pickerView.contents().level();
            var queue = new ObjectArrayList<ResearchOverviewPacket.Queue>();
            for (int index = 0; index < playerView.team().queueRows().size(); index++) {
                var row = playerView.team().queueRows().get(index);
                queue.add(new ResearchOverviewPacket.Queue(row.entryId(), row.researchId(), row.targetLevel()));
            }
            var retained = current.world.retainedProgress(playerView.team().id());
            var progressByResearch = new Object2ObjectOpenHashMap<ResourceLocation, ResearchProgress.Snapshot>();
            for (int index = 0; index < retained.size(); index++)
                progressByResearch.put(retained.get(index).target().researchId(), retained.get(index));
            var states = new ObjectArrayList<ResearchOverviewPacket.State>();
            var definitions = ResearchRegistry.INSTANCE.sortedById();
            for (int index = 0; index < definitions.size(); index++) {
                var definition = definitions.get(index);
                var id = definition.id();
                int completed = playerView.team().sharedLevels().getInt(id), personal = playerView.personalLevels().getInt(id);
                int target = completed == definition.maxLevel() ? completed : completed + 1;
                var stage = definition.stageForLevel(target);
                int downloaded = 0;
                String downloadedTo = "";
                for (int databaseIndex = 0; databaseIndex < databases.size(); databaseIndex++) {
                    var database = databases.get(databaseIndex);
                    int level = database.level(id);
                    if (level > downloaded) {
                        downloaded = level;
                        downloadedTo = database.name();
                    }
                }
                int processable = 0;
                if (!definition.specialOnly() && completed > 0) {
                    for (int stageIndex = 0; stageIndex < definition.stages().size(); stageIndex++) {
                        var candidate = definition.stages().get(stageIndex);
                        if (candidate.firstLevel() > completed || candidate.researchTier() > pickerLevel) break;
                        processable = Math.min(completed, candidate.lastLevel());
                    }
                }
                boolean devices = stage.researchTier() == 0 || admission.networkCenterLevel() > 0 && active != null
                    && active.tier() >= stage.researchTier() && highestStation >= stage.researchTier();
                boolean canStart = visible && !playerView.savePending() && completed < definition.maxLevel() && !definition.specialOnly()
                    && playerView.team().startPermission() == ResearchPermissionPolicy.Decision.ALLOWED
                    && playerView.team().structuralTargets().containsKey(id) && queue.size() < 5 && devices;
                boolean canDownload = visible && !playerView.savePending() && active != null && completed > downloaded
                    && definition.deliveryPolicy() == ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM && !definition.specialOnly()
                    && definition.researchTierForLevel(completed) <= active.tier()
                    && (active.level(id) > 0 || active.remaining() > 0)
                    && current.permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission) == ResearchPermissionPolicy.Decision.ALLOWED;
                if (canDownload)
                    for (int ruleIndex = 0; ruleIndex < definition.mutexRulesForLevel(completed).size(); ruleIndex++) {
                        var rule = definition.mutexRulesForLevel(completed).get(ruleIndex);
                        if (Math.max(active.level(rule.researchId()), playerView.team().sharedLevels().getInt(rule.researchId())) >= rule.minLevel()) {
                            canDownload = false;
                            break;
                        }
                    }
                var claims = new ObjectArrayList<ResearchOverviewPacket.Claim>();
                for (int claimIndex = 0; claimIndex < playerView.claims().size(); claimIndex++) {
                    var claim = playerView.claims().get(claimIndex);
                    if (claim.researchId().equals(id))
                        claims.add(new ResearchOverviewPacket.Claim(claim.level(), claim.status(), claim.selectedGroupId()));
                }
                var saved = visible ? progressByResearch.get(id) : null;
                var progress = saved == null ? null : overviewProgress(definition, saved,
                    station.progress() != null && station.progress().target().equals(saved.target()) ? station : null);
                states.add(new ResearchOverviewPacket.State(id, personal, completed, playerView.team().structuralTargets().getInt(id),
                    downloaded, downloadedTo, canStart, canDownload,
                    !playerView.savePending() && personal > 0 && !definition.specialOnly() && playerView.forgetPermission() == ResearchPermissionPolicy.Decision.ALLOWED && current.world.canForget(actor.getUniqueID(), id),
                    pickerView != null && pickerView.visible() && !definition.specialOnly() && processable > 0, processable,
                    progress, claims, membership.accepted() ? current.world.otherMemberLevel(membership.members(), actor.getUniqueID(), id) : 0,
                    new ResearchOverviewPacket.Availability(playerView.team().startPermission(),
                        current.permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, permission),
                        playerView.forgetPermission(), current.permissions.can(ResearchPermissionPolicy.Action.MANAGE_RESEARCH_QUEUE, permission))));
            }
            ResearchOverviewPacket.Current activeResearch = null;
            if (!queue.isEmpty() && station.progress() != null) {
                var head = queue.getFirst();
                var definition = ResearchRegistry.INSTANCE.require(head.id());
                var progress = overviewProgress(definition, station.progress(), station);
                ResearchPlayerView.ManualRow manual = null;
                for (int index = 0; index < playerView.team().manualRows().size(); index++)
                    if (playerView.team().manualRows().get(index).entryId() == head.entryId()) {
                        manual = playerView.team().manualRows().get(index);
                        break;
                    }
                UUID contributor = manual == null ? null : manual.contributor();
                String contributorName = "";
                if (contributor != null) {
                    var profile = current.server.getPlayerProfileCache().getProfileByUUID(contributor);
                    contributorName = profile == null ? contributor.toString() : profile.getName();
                }
                activeResearch = new ResearchOverviewPacket.Current(head.id(), head.entryId(), head.targetLevel(), progress.elapsed(),
                    definition.stageForLevel(head.targetLevel()).durationTicks(), station.workingStations(), manual != null,
                    contributorName, contributor, progress.materials(), progress.resources());
            }
            var environment = new ResearchOverviewPacket.Environment(playerView.team().id(), membership.accepted() ? teamTitle(actor, membership.teamId()) : "",
                admission.networkCenterLevel(), highestStation, activeId, station.canManage(), playerView.savePending(),
                editor && editorAuthorized(actor), visible, pickerLevel,
                picker == null ? "" : picker.type().displayName().text().getUnformattedText(), pickerView == null ? 0 : pickerView.contents().storedResearch().size(),
                pickerView == null ? 0 : pickerView.contents().capacity(), actor.getUniqueID());
            if (!authority.getAsBoolean() || !ownerCurrent.getAsBoolean()) {
                result.accept(null);
                return;
            }
            result.accept(new Overview(states, queue, databases, activeResearch, environment));
        }, ExecutionMode.MAIN_THREAD);
    }

    public record Overview(List<ResearchOverviewPacket.State> states, List<ResearchOverviewPacket.Queue> queue,
                           List<ResearchOverviewPacket.Database> databases,
                           @Nullable ResearchOverviewPacket.Current current,
                           ResearchOverviewPacket.Environment environment) {
    }

    private static ResearchOverviewPacket.Progress overviewProgress(ResearchDefinition definition,
                                                                    ResearchProgress.Snapshot saved, @Nullable ResearchStationView station) {
        var stage = definition.stageForLevel(saved.target().level());
        var materials = new ObjectArrayList<ResearchOverviewPacket.MaterialProgress>();
        for (var entry : stage.materialTotals().object2LongEntrySet()) {
            long remaining = saved.remainingMaterials().getLong(entry.getKey());
            materials.add(new ResearchOverviewPacket.MaterialProgress(entry.getKey(), station == null ? 0
                : Math.min(remaining, station.availableMaterials().getLong(entry.getKey())), remaining, entry.getLongValue()));
        }
        var resources = new ObjectArrayList<ResearchOverviewPacket.ResourceProgress>();
        for (var entry : stage.resourceDemandForLevel(saved.target().level()).reference2DoubleEntrySet())
            resources.add(new ResearchOverviewPacket.ResourceProgress(entry.getKey(), station == null ? 0 : station.occupiedResources().getDouble(entry.getKey()),
                station == null ? 0 : station.capacity().getDouble(entry.getKey()), saved.resources().getDouble(entry.getKey()), entry.getDoubleValue()));
        return new ResearchOverviewPacket.Progress(saved.target().level(), saved.elapsedTicks(), materials, resources);
    }

    public static void requestPauseManual(EntityPlayerMP actor, String expectedTeam, long entryId,
                                          BooleanSupplier ownerCurrent, Consumer<ManualResearch.Status> result, Runnable finalized) {
        var current = actorSession(actor);
        submitAction(current, () -> {
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = captures.get(actor.getUniqueID());
            if (unavailable(captures) || !currentActor(current, actor) || !membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(ManualResearch.Status.STALE_CONTEXT);
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.pauseManual(membership, actor.getUniqueID(), entryId, operationTime(current), online.keySet(),
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()));
        }, finalized);
    }

    public static void requestEditorCompletion(EntityPlayerMP actor, String expectedTeam, ResourceLocation id, int target,
                                               BooleanSupplier ownerCurrent, Consumer<ResearchLevelResult> result, Runnable finalized) {
        var current = actorSession(actor);
        submitAction(current, () -> {
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = captures.get(actor.getUniqueID());
            if (unavailable(captures) || !currentActor(current, actor) || !membership.accepted() || !expectedTeam.equals(membership.teamId())) {
                result.accept(ResearchLevelResult.STALE_CONTEXT);
                return;
            }
            var permission = editorPermission(current, actor, membership);
            if (permission == null) {
                result.accept(ResearchLevelResult.PERMISSION_DENIED);
                return;
            }
            var authority = proof(current, captures, online);
            result.accept(current.world.completeForEditor(membership, permission, id, target, operationTime(current), online.keySet(),
                player -> inventory(current, player), () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean()
                    && permission.equals(editorPermission(current, actor, membership))));
        }, finalized);
    }

    @SuppressWarnings("resource")
    public static void requestEditorSpecial(EntityPlayerMP actor, String expectedTeam, @Nullable UUID databaseId,
                                            ResourceLocation id, BooleanSupplier ownerCurrent,
                                            Consumer<DataCenterStorage.Result> result, Runnable finalized) {
        var current = actorSession(actor);
        submitAction(current, () -> {
            var online = onlinePlayers(current);
            var captures = capture(current, Set.of(actor.getUniqueID()));
            var membership = captures.get(actor.getUniqueID());
            if (databaseId == null || unavailable(captures) || !currentActor(current, actor) || !membership.accepted()
                || !expectedTeam.equals(membership.teamId()) || !editorAuthorized(actor)) {
                result.accept(DataCenterStorage.Result.STALE_CONTEXT);
                return;
            }
            var definition = ResearchRegistry.INSTANCE.require(id);
            if (!definition.specialOnly()) {
                result.accept(DataCenterStorage.Result.SPECIAL_ONLY_REJECTED);
                return;
            }
            var physical = current.dataCenters.capture(expectedTeam, databaseId);
            if (physical == null) {
                result.accept(DataCenterStorage.Result.STALE_CONTEXT);
                return;
            }
            var authority = proof(current, captures, online);
            var cloud = current.world.captureCloud(membership,
                () -> authority.getAsBoolean() && ownerCurrent.getAsBoolean() && editorAuthorized(actor));
            if (cloud == null) {
                result.accept(DataCenterStorage.Result.STALE_CONTEXT);
                return;
            }
            int previous = physical.snapshot().storedResearch().getInt(id);
            if (previous == definition.maxLevel()) {
                result.accept(DataCenterStorage.Result.MAX_LEVEL_REACHED);
                return;
            }
            var entries = new Object2IntOpenHashMap<ResourceLocation>();
            entries.put(id, previous + 1);
            var written = physical.storage().storeFromDisk(entries, cloud.view(),
                () -> ownerCurrent.getAsBoolean() && cloud.current().getAsBoolean() && physical.current() && editorAuthorized(actor));
            if (written == DataCenterStorage.Result.UPDATED) ServerNetworkRuntime.forServer(current.server).refresh();
            result.accept(written);
        }, finalized);
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
        return current.world.stationView(target, current.stations, operationTime(current), id -> inventory(current, id));
    }

    /**
     * Reads the opened physical database at its owner boundary, including inactive databases.
     */
    public static @Nullable DataCenterView dataCenterView(EntityPlayerMP actor, MachineControllerHost host) {
        var current = actorSession(actor);
        if (current.world.busy()) return null;
        var machine = host.machine();
        if (!(machine instanceof DataCenter database)) return null;
        var own = viewPermission(current, actor, current.resolver.resolve(actor.getUniqueID()));
        if (own == null) return null;
        var identity = host.savedIdentity();
        var ownerTeam = identity == null ? null : current.resolver.resolve(identity.ownerId());
        String teamId = ownerTeam != null && ownerTeam.accepted() ? ownerTeam.teamId() : null;
        var target = new ResearchPermissionContext(actor.getUniqueID(), own.actorTeamId(), null, teamId,
            own.teamLeader(), own.teamModerator(), own.serverOperator());
        var device = machine.networkDevice();
        var network = device == null ? null : device.view();
        var state = network == null ? null : network.state();
        boolean visible = current.permissions.can(ResearchPermissionPolicy.Action.REQUEST_DATA_CENTER_STATE, target)
            == ResearchPermissionPolicy.Decision.ALLOWED;
        boolean editable = visible && !current.world.savePending() && teamId != null && teamId.equals(own.actorTeamId());
        boolean remove = editable && current.permissions.can(ResearchPermissionPolicy.Action.REMOVE_DATA_CENTER_RESEARCH, target)
            == ResearchPermissionPolicy.Decision.ALLOWED;
        boolean transfer = editable && current.permissions.can(ResearchPermissionPolicy.Action.DOWNLOAD_RESEARCH_TO_DATA_CENTER, target)
            == ResearchPermissionPolicy.Decision.ALLOWED;
        return new DataCenterView(teamId == null ? "" : teamId, database.dataCenterStorage().snapshot(), state,
            visible, remove, transfer, current.world.savePending());
    }

    public record DataCenterView(String teamId, DataCenterResearchSnapshot contents, @Nullable NetworkWorkState state,
                                 boolean visible, boolean canRemove, boolean canTransfer, boolean savePending) {
    }

    public static @Nullable String stationTeam(EntityPlayerMP actor, MachineControllerHost host) {
        var current = actorSession(actor);
        var identity = host.savedIdentity();
        if (identity == null) return null;
        var team = current.resolver.resolve(identity.ownerId());
        return team.accepted() ? team.teamId() : null;
    }

    /**
     * Display title of a team identity returned by {@link #stationTeam}; the identity must exist in this server's FTB universe.
     */
    public static String teamTitle(EntityPlayerMP actor, String teamId) {
        var current = actorSession(actor);
        if (Universe.loaded() && Universe.get().server == current.server) {
            var personalOwner = FtbOwnerTeamResolver.personalOwner(Universe.get(), teamId);
            if (personalOwner != null) return personalOwner.getName();
        }
        var team = levelTeam(current, teamId);
        if (team == null)
            throw new IllegalStateException("Research station team " + teamId + " is not a current FTB team");
        return team.getTitle().getUnformattedText();
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
        requireThread(server);
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
        current.world.saveChanged(operationTime(current));
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
        var tag = stack.getTagCompound();
        if (stack.isEmpty() || tag == null || !tag.hasKey(MinecraftItemDeliveryAdapter.DELIVERY_TAG)) {
            return;
        }
        requireThread(current.server);
        var identity = tag.getCompoundTag(MinecraftItemDeliveryAdapter.DELIVERY_TAG);
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
        requireThread(server);
        var current = session;
        if (current == null || current.server != server || current.closing || server.getWorld(0) != current.overworld) {
            throw new IllegalStateException("No active research session for this server");
        }
        return current;
    }

    private static Session actorSession(EntityPlayerMP actor) {
        var current = active(actor.getServerWorld().getMinecraftServer());
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
            result.put(player.getUniqueID(), player);
        }
        return result;
    }

    private static Map<UUID, OwnerTeamResolution> capture(Session current, Set<UUID> seeds) {
        requireThread(current.server);
        var result = new Object2ObjectOpenHashMap<UUID, OwnerTeamResolution>();
        var teams = new ObjectOpenHashSet<String>();
        var pending = new ObjectArrayList<>(seeds);
        var scheduled = new ObjectOpenHashSet<>(seeds);
        for (int index = 0; index < pending.size(); index++) {
            var id = pending.get(index);
            var resolution = current.resolver.resolve(id);
            result.put(id, resolution);
            if (resolution.status() == OwnerTeamResolutionStatus.UNIVERSE_UNAVAILABLE) {
                return result; // An unavailable provider is a normal pause, never evidence of team dissolution.
            }
            if (!resolution.accepted()) {
                continue;
            }
            if (!teams.add(resolution.teamId())) continue;
            for (var member : resolution.members()) {
                if (scheduled.add(member)) {
                    pending.add(member);
                }
            }
        }
        return result;
    }

    private static BooleanSupplier proof(Session current, Map<UUID, OwnerTeamResolution> captures,
                                         Map<UUID, EntityPlayerMP> online) {
        var representatives = new ObjectArrayList<OwnerTeamResolution>();
        var teams = new ObjectOpenHashSet<String>();
        for (var resolution : captures.values())
            if (!resolution.accepted() || teams.add(resolution.teamId())) representatives.add(resolution);
        return () -> {
            requireThread(current.server);
            if (session != current || current.closing || current.server.getWorld(0) != current.overworld) {
                return false;
            }
            var players = current.server.getPlayerList().getPlayers();
            if (players.size() != online.size()) return false;
            for (int index = 0; index < players.size(); index++) {
                var player = players.get(index);
                if (online.get(player.getUniqueID()) != player) return false;
            }
            for (int index = 0; index < representatives.size(); index++) {
                var captured = representatives.get(index);
                var now = current.resolver.resolve(captured.ownerId());
                if (!sameResolution(captured, now)) {
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

    private static boolean editorAuthorized(EntityPlayerMP actor) {
        return actor.openContainer instanceof ContainerResearchOverview container && container.getPlayer() == actor && container.editorAuthorized();
    }

    /**
     * A server-owned editor grant supplies privilege only to editor completion; ordinary projections retain the actor's actual roles.
     */
    private static @Nullable ResearchPermissionContext editorPermission(Session current, EntityPlayerMP actor, OwnerTeamResolution membership) {
        if (!editorAuthorized(actor)) return null;
        var permission = permission(current, actor, membership);
        return permission == null || permission.serverOperator() ? permission
            : new ResearchPermissionContext(permission.actorId(), permission.actorTeamId(), permission.targetPlayerId(), permission.targetTeamId(),
            permission.teamLeader(), permission.teamModerator(), true);
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
        var universe = Universe.get();
        var actorId = actor.getUniqueID();
        var player = universe.getPlayer(actorId);
        if (player == null) return null;
        boolean personal = FtbOwnerTeamResolver.personalOwner(universe, membership.teamId()) == player;
        var team = player.team;
        if (!personal && (team == null || team.universe != universe || !team.isValid() || FtbOwnerTeamResolver.personalTeam(team)
            || !membership.teamId().equals(team.getUIDCode()))) return null;
        return new ResearchPermissionContext(actorId, membership.teamId(), null, membership.teamId(),
            personal || team.isOwner(player), !personal && team.isModerator(player),
            current.server.getPlayerList().canSendCommands(actor.getGameProfile()));
    }

    private static void requireManual(ResourceLocation id, int target) {
        if (target < 1 || ResearchRegistry.INSTANCE.require(id).stageForLevel(target).researchTier() != 0) {
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
