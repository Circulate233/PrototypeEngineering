package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.machine.MachineControllerBlockHost;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.machine.render.ControllerRenderChunks;
import com.circulation.prototype_eng.machine.data.MachineDataSnapshot;
import com.circulation.prototype_eng.machine.data.MachineDataSnapshots;
import com.circulation.prototype_eng.machine.render.ControllerRenderSnapshots;
import com.circulation.prototype_eng.machine.render.ControllerRenderSync;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Empty construction/read never assembles. The next server update observes the final placement/NBT
 * after Forge's cancellation window. A reattached dormant tile owns a new controller generation.
 */
@SuppressWarnings("resource") // FML owns the shared server scheduler, never an individual tile.
public class MachineControllerBlockEntity extends AEBaseTile implements MachineControllerHost {
    @Nullable
    private ControllerData data;
    @Nullable
    private MachineController controller;
    @Nullable
    private ControllerLifecycleHost host;
    @Nullable
    private ControllerStructureUpdates structures;
    @Nullable
    private Placement placement;
    @Nullable
    private ControllerDisplayData display;
    @Nullable
    private ControllerDisplayData serverDisplay;
    @Nullable
    private MachineRenderSnapshot serverRender;
    private List<ControllerRenderChunks.Part> renderParts = List.of();
    private List<ControllerRenderChunks.Part> dataParts = List.of();
    private final UUID dataSession = UUID.randomUUID();
    private final MachineDataSnapshots dataSnapshots = new MachineDataSnapshots();
    @Nullable
    private MachineDataSnapshot serverDataSnapshot;
    @Nullable
    private ControllerDisplayData serverDataDisplay;
    @Nullable
    private MachineDataSnapshot clientDataSnapshot;
    @Nullable
    private ControllerDisplayData clientDataDisplay;
    private long clientDataRevision;
    private long clientDataGeneration;
    @Nullable
    private Object clientDataConnection;
    private ControllerRenderChunks.Assembler dataAssembler = new ControllerRenderChunks.Assembler();
    @Nullable
    private MachineInstance renderMachine;
    @Nullable
    private StructureConfiguration renderConfiguration;
    @Nullable
    private StructureMatch renderMatch;
    @Nullable
    private MachineAnimationState.Snapshot renderAnimation;
    @Nullable
    private StructureCatalogView renderCatalog;
    private List<ResourceLocation> renderComponents = List.of();
    private boolean wantsLoad;
    private boolean queuedLoad;
    private boolean replacementData;
    private Object attachmentEpoch = new Object();
    private boolean changingState;
    private boolean controllerDestroyed;
    private boolean updated;
    private long lastTick;
    private long displayGeneration;
    private long displaySequence;
    @Nullable
    private BlockPos attachmentPosition;
    @SuppressWarnings("unused") // Forge's registered TileEntity factory invokes the public no-arg constructor.
    public MachineControllerBlockEntity() {
    }
    public MachineControllerBlockEntity(ResourceLocation type, BlockOrientation orientation) {
        data = new ControllerData(type, orientation, null);
    }

    private static void requireType(MachineType type, ResourceLocation saved) {
        if (!type.id().equals(saved) || MachineTypeRegistry.INSTANCE.get(saved) != type) {
            throw new IllegalArgumentException("Controller does not reference its canonical block type");
        }
    }

    @Override
    public @NotNull AxisAlignedBB getRenderBoundingBox() {
        return PrototypeEngineering.proxy.controllerRenderBounds(this);
    }

    @Override
    public double getMaxRenderDistanceSquared() {
        var bounds = getRenderBoundingBox();
        double x = Math.max(Math.abs(bounds.minX - pos.getX()), Math.abs(bounds.maxX - pos.getX()));
        double y = Math.max(Math.abs(bounds.minY - pos.getY()), Math.abs(bounds.maxY - pos.getY()));
        double z = Math.max(Math.abs(bounds.minZ - pos.getZ()), Math.abs(bounds.maxZ - pos.getZ()));
        double distance = 64 + Math.sqrt(x * x + y * y + z * z);
        return distance * distance;
    }

    /**
     * Supplies only immutable construction data; assembly remains deferred to the owner boundary.
     */
    protected void initializeControllerConfiguration(ResourceLocation type, BlockOrientation orientation) {
        if (data == null) {
            data = new ControllerData(Objects.requireNonNull(type), Objects.requireNonNull(orientation), null);
        }
    }

    private void captureBlockConfiguration() {
        if (data == null && isAttached()) {
            var block = requireBlock();
            initializeControllerConfiguration(block.type().id(), block.getOrientation(world.getBlockState(pos)));
        }
    }

    @Override
    public void syncOrientationFromBlockState(@Nullable IBlockState state) {
        super.syncOrientationFromBlockState(state);
        // Chunk validates before adding the tile to its map, so the supplied state is the construction source.
        if (data == null && state != null && state.getBlock() instanceof MachineControllerBlockHost block) {
            initializeControllerConfiguration(block.type().id(), block.getOrientation(state));
        }
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
    }

    @Override
    public EnumFacing getForward() {
        return orientation().getSide(RelativeSide.FRONT);
    }

    @Override
    public EnumFacing getUp() {
        return orientation().getSide(RelativeSide.TOP);
    }

    @Override
    public BlockOrientation getOrientation() {
        return orientation();
    }

    @Override
    public IBlockState applyOrientationToBlockState(IBlockState state) {
        return state.getBlock() instanceof MachineControllerBlockHost block ? block.state(state, orientation()) : state;
    }

    @Override
    public void setOrientation(EnumFacing forward, EnumFacing up) {
        orient(BlockOrientation.get(forward, up));
    }

    @Nullable
    public MachineInstance machine() {
        return controller == null ? null : controller.machine();
    }

    @Override
    @Nullable
    public PersistentMachineIdentity savedIdentity() {
        requireServer();
        var current = machine();
        if (current != null && !replacementData) {
            for (var binding : current.components().assemblyBindings()) {
                if (binding.component() instanceof MachineOwnership ownership) {
                    return ownership.identity();
                }
            }
            return null;
        }
        var saved = controller != null && !replacementData ? controller.save() : data == null ? null : data.runtime();
        return saved == null ? null : MachineTypeRegistry.INSTANCE.assembly(type()).savedIdentity(saved);
    }

    public MachineType type() {
        return requireBlock().type();
    }

    /**
     * The callback runs on the server after accepted workers join; recheck the open container there.
     */
    public void readGuiAtBoundary(Runnable read) {
        scheduler().runAtBoundary(Objects.requireNonNull(read));
    }

    public BlockOrientation orientation() {
        return data == null ? BlockOrientation.NORTH_UP : data.orientation();
    }

    public boolean formed() {
        if (world != null && world.isRemote) {
            return display != null && display.formed();
        }
        var machine = machine();
        return machine != null && Objects.requireNonNull(machine.lifecycle()).formed();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (world != null) {
            attachmentPosition = pos.toImmutable();
        }
        wantsLoad = true;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && world.isRemote) resetClientData();
        captureBlockConfiguration();
        attachmentPosition = pos.toImmutable();
        wantsLoad = true;
        updated = false;
    }

    @Override
    public void update() {
        super.update();
        if (world == null || world.isRemote || !wantsLoad || !isAttached()) {
            return;
        }
        requireServer();
        long now = world.getTotalWorldTime();
        if (updated && lastTick == now) {
            return;
        }
        updated = true;
        lastTick = now;
        ServerTaskSchedulers.queueControllerDataDirty(Objects.requireNonNull(world.getMinecraftServer()), this);
        var machine = machine();
        if (controller == null || machine != null && Objects.requireNonNull(machine.lifecycle()).retired()) {
            if (!queuedLoad) {
                queuedLoad = true;
                scheduler().runAtBoundary(this::attachAtBoundary);
            }
        }
        machine = machine();
        // Observe typed state only after the scheduler joins this machine's workers.
        var observedEpoch = attachmentEpoch;
        Runnable observe = () -> {
            if (observedEpoch == attachmentEpoch && wantsLoad && isAttached()) observeRenderState();
        };
        if (structures != null && machine != null && !Objects.requireNonNull(machine.lifecycle()).retired()) {
            structures.enqueueTick(now, observe);
        } else scheduler().runAtBoundary(observe);
        // The queued native save mark follows this tick's actual scheduler worker join.
    }

    /** Native save eligibility only; this neither reads active components nor consumes their sync revisions. */
    public void markControllerDataDirty(boolean afterTick) {
        requireServer();
        if (afterTick && (!wantsLoad || !updated || lastTick != world.getTotalWorldTime())) return;
        if (isAttached()) markDirty();
    }

    /** Actual creation hook used by normal attachment and terminal cleanup. */
    protected ControllerLifecycleHost createLifecycleHost(ResourceLocation type, @Nullable UUID player,
                                                          @Nullable ResourceLocation blockId) {
        return ControllerLifecycleHost.nativeHost(type, this, player, blockId);
    }

    private void attachAtBoundary() {
        queuedLoad = false;
        if (!wantsLoad || !isAttached()) {
            return;
        }
        var block = requireBlock();
        var type = block.type();
        if (data == null) {
            data = new ControllerData(type.id(), block.getOrientation(world.getBlockState(pos)), null);
        }
        requireType(type, data.type());
        if (controller != null) {
            var previous = controller.machine();
            if (previous != null && !Objects.requireNonNull(previous.lifecycle()).retired()) {
                return;
            }
            if (!replacementData && previous != null) {
                data = new ControllerData(type.id(), orientation(), controller.save());
            }
            controller = null;
            structures = null;
            host = null;
            placement = null;
            displayGeneration = 0;
            serverDisplay = null;
            serverRender = null;
            renderParts = List.of();
            dataParts = List.of();
            serverDataSnapshot = null;
            dataSnapshots.clear();
            attachmentEpoch = new Object();
        }
        UUID actor = null;
        if (placement != null) {
            if (placement.world != world || !placement.position.equals(pos) || placement.type != type) {
                throw new IllegalStateException("Placement does not belong to this controller attachment");
            }
            actor = placement.player;
            placement = null;
        }
        var bound = createLifecycleHost(type.id(), actor, world.getBlockState(pos).getBlock().getRegistryName());
        var created = new MachineController(MachineTypeRegistry.INSTANCE.assembly(type), bound, scheduler());
        host = bound;
        controller = created;
        // load queues behind this owner action. Only its following boundary action may use the machine.
        created.load(data.runtime());
        scheduler().runAtBoundary(() -> {
            if (controller != created || !wantsLoad || !isAttached()) {
                created.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
                return;
            }
            var machine = created.machine();
            if (machine == null) {
                // A previously queued stop can cancel load while vanilla revalidates this same tile.
                controller = null;
                host = null;
                return;
            }
            if (Objects.requireNonNull(machine.lifecycle()).retired()) {
                return;
            }
            try {
                data = new ControllerData(type.id(), orientation(), null);
                replacementData = false;
                bound.configureStructure(MachineTypeRegistry.INSTANCE.structureCatalog(), MachineTypeRegistry.INSTANCE.structure(type), orientation());
                structures = new ControllerStructureUpdates(created, bound, scheduler(), this::changed);
                structures.firstTickAtBoundary(lastTick);
                applyState();
                changed();
            } catch (RuntimeException | Error failure) {
                wantsLoad = false;
                try {
                    created.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
                } catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        });
    }

    /**
     * Capture only the actual placer of this exact tile; no coordinate-wide pending actor cache.
     */
    public void placedBy(EntityLivingBase placer) {
        requireServer();
        if (controller != null || !isAttached()) {
            throw new IllegalStateException("Placement must precede controller assembly");
        }
        var type = requireBlock().type();
        if (data != null) {
            requireType(type, data.type());
        }
        if (placement != null) {
            throw new IllegalStateException("Controller placement was already recorded");
        }
        if (placer instanceof EntityPlayer player && !(player instanceof FakePlayer)) {
            placement = new Placement(world, pos.toImmutable(), type, player.getUniqueID());
        }
    }

    public void orient(BlockOrientation orientation) {
        Objects.requireNonNull(orientation);
        transformOrientation(_ -> orientation);
    }

    /**
     * Applies a pure relative transform at the owner boundary, using this attachment's latest direction.
     */
    public void transformOrientation(UnaryOperator<BlockOrientation> transformation) {
        Objects.requireNonNull(transformation);
        requireServer();
        var epoch = attachmentEpoch;
        scheduler().runAtBoundary(() -> {
            if (epoch == attachmentEpoch) {
                changeOrientation(transformation.apply(orientation()));
            }
        });
    }

    private void changeOrientation(BlockOrientation orientation) {
        if (!isAttached() || orientation() == orientation) {
            return;
        }
        var block = requireBlock();
        var old = Objects.requireNonNull(data, "Controller has no pending data");
        if (structures != null) {
            structures.invalidate();
        }
        data = new ControllerData(old.type(), orientation, old.runtime());
        var machine = machine();
        if (host != null && machine != null && !Objects.requireNonNull(machine.lifecycle()).retired()) {
            host.configureStructure(MachineTypeRegistry.INSTANCE.structureCatalog(), MachineTypeRegistry.INSTANCE.structure(block.type()), orientation);
        }
        applyState();
        changed();
    }

    private void applyState() {
        var block = requireBlock();
        var current = world.getBlockState(pos);
        var desired = block.state(current, orientation());
        if (desired == current) {
            return;
        }
        changingState = true;
        try {
            if (!world.setBlockState(pos, desired, 3)) {
                throw new IllegalStateException("Controller orientation could not be written");
            }
        } finally {
            changingState = false;
        }
    }

    @Override
    public boolean shouldRefresh(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState oldState, @NotNull IBlockState newState) {
        if (oldState.getBlock() != newState.getBlock()) {
            return true;
        }
        if (!world.isRemote && !changingState && newState.getBlock() instanceof MachineControllerBlockHost block) {
            var orientation = block.getOrientation(newState);
            // Record every state transition. Pending A -> original changes must not disappear just
            // because the first request has not updated tile data yet. FIFO actions update both sides.
            if (orientation != block.getOrientation(oldState)) {
                orient(orientation);
            }
        }
        return false;
    }

    @Override
    public void rotate(@NotNull Rotation rotation) {
        transformOrientation(current -> ControllerOrientation.rotate(current, rotation));
    }

    @Override
    public void mirror(@NotNull Mirror mirror) {
        transformOrientation(current -> ControllerOrientation.mirror(current, mirror));
    }

    @SuppressWarnings("unused") // Public downstream convenience entry point.
    public void rotateAround(EnumFacing axis) {
        transformOrientation(current -> current.rotateClockwiseAround(axis));
    }

    public void controllerBroken() {
        if (controllerDestroyed) {
            return;
        }
        var current = machine();
        boolean dormant = controller == null || replacementData
            || current != null && Objects.requireNonNull(current.lifecycle()).state() != MachineLifecycle.State.LOADED;
        if (dormant && data != null) {
            requireServer();
            var type = Objects.requireNonNull(MachineTypeRegistry.INSTANCE.get(data.type()), "Saved controller type is not registered");
            var assembly = MachineTypeRegistry.INSTANCE.assembly(type);
            var bound = createLifecycleHost(data.type(), null, null);
            var saved = controller != null && !replacementData ? controller.save() : data.runtime();
            if (saved != null) {
                scheduler().runAtBoundary(() -> {
                    var identity = assembly.savedIdentity(saved);
                    if (identity != null) {
                        MachineLifecycleRegistry.GLOBAL.unassembledControllerBroken(bound, identity);
                    }
                });
            }
        }
        controllerDestroyed = true;
        wantsLoad = false;
        placement = null;
        attachmentEpoch = new Object();
        if (controller != null && !replacementData) {
            controller.controllerBroken();
        }
    }

    private void stop(MachineLifecycleEvent.UnloadReason reason) {
        wantsLoad = false;
        dataParts = List.of();
        serverDataSnapshot = null;
        dataSnapshots.clear();
        if (controller != null) {
            placement = null;
            controller.unload(reason);
            attachmentEpoch = new Object();
        } else if (!isAttached()) {
            placement = null;
        }
    }

    @Override
    protected void setRemoved() {
        try {
            if (world != null && world.isRemote) {
                resetClientData();
                PrototypeEngineering.proxy.releaseControllerRender(this);
            }
            if (world != null && !world.isRemote) {
                stop(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            }
        } finally {
            super.setRemoved();
        }
    }

    @Override
    protected void onChunkUnloaded() {
        try {
            if (world != null && world.isRemote) {
                resetClientData();
                PrototypeEngineering.proxy.releaseControllerRender(this);
            }
            if (world != null && !world.isRemote) {
                stop(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        } finally {
            super.onChunkUnloaded();
        }
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        // AEBaseTile's final read updates vanilla coordinates before calling this hook.
        // Compare the original attachment while it still owns this tile, then restore on rejection.
        if (world != null && !isInvalid() && attachmentPosition != null && world.isBlockLoaded(attachmentPosition, false)
            && world.getTileEntity(attachmentPosition) == this && !attachmentPosition.equals(pos)) {
            pos = attachmentPosition;
            throw new IllegalArgumentException("Cannot move an attached controller through NBT");
        }
        var decoded = ControllerData.decode(StrictNbt.requireCompound(tag, "Machine"));
        if (data != null && !data.type().equals(decoded.type())) {
            throw new IllegalArgumentException("Controller item/save type mismatch");
        }
        if (world != null && isAttached()) {
            requireType(requireBlock().type(), decoded.type());
            if (StrictNbt.requireInt(tag, "x") != pos.getX() || StrictNbt.requireInt(tag, "y") != pos.getY() || StrictNbt.requireInt(tag, "z") != pos.getZ()) {
                throw new IllegalArgumentException("Cannot move an attached controller through NBT");
            }
        }
        if (controller != null) {
            requireServer();
            Objects.requireNonNull(controller.machine(), "Cannot read NBT during controller assembly").requirePersistenceBoundary();
            // Forge BlockSnapshot.restore also reads running tiles. Retire the quiescent old machine;
            // only a new controller can consume the replacement. Never overwrite live components.
            controller.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            attachmentEpoch = new Object();
            dataParts = List.of();
            serverDataSnapshot = null;
            dataSnapshots.clear();
            placement = null;
            updated = false;
        }
        super.loadTag(tag);
        replacementData = controller != null;
        data = decoded;
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        captureBlockConfiguration();
        var saved = Objects.requireNonNull(data, "Controller has no type or pending data");
        if (controller != null && !replacementData) {
            saved = new ControllerData(saved.type(), orientation(), controller.save());
        }
        super.saveAdditional(tag);
        tag.setTag("Machine", saved.encode());
    }

    private void changed() {
        requireServer();
        if (!isAttached()) {
            markDirty();
            return;
        }
        captureBlockConfiguration();
        if (displayGeneration == 0) displayGeneration = ControllerDisplayData.newGeneration();
        long previousSequence = displaySequence;
        long nextSequence = Math.incrementExact(previousSequence);
        var epoch = attachmentEpoch;
        var original = machine();
        var originalHost = host;
        var lifecycle = original == null ? null : Objects.requireNonNull(original.lifecycle());
        boolean loaded = lifecycle != null && lifecycle.state() == MachineLifecycle.State.LOADED && !lifecycle.retired();
        var configuration = loaded && originalHost != null ? originalHost.structureConfiguration() : null;
        var expectedCatalog = configuration == null ? MachineTypeRegistry.INSTANCE.structureCatalog() : configuration.catalog();
        var server = Objects.requireNonNull(world.getMinecraftServer());
        var view = expectedCatalog == null ? null : ServerStructureReloads.view(server, expectedCatalog);
        var metadata = new ControllerDisplayData(Objects.requireNonNull(data).type(), orientation(),
            loaded && configuration != null && lifecycle.formed(), displayGeneration, nextSequence);
        MachineRenderSnapshot snapshot = null;
        if (view != null && loaded && configuration != null) {
            snapshot = ControllerRenderSnapshots.capture(original, configuration, view, metadata, pos,
                () -> epoch == attachmentEpoch && isAttached() && machine() == original && host == originalHost &&
                    displaySequence == previousSequence && ServerStructureReloads.view(server, expectedCatalog) == view);
            if (snapshot == null) return;
        } else if (view != null) {
            var ids = new ObjectArrayList<ResourceLocation>();
            MachineAnimationState.Snapshot animation = null;
            if (loaded) {
                original.requirePersistenceBoundary();
                for (var binding : original.components().assemblyBindings()) ids.add(binding.type().id());
                var state = original.components().get(MachineAnimationState.TYPE);
                if (state != null) animation = state.snapshot();
            }
            snapshot = new MachineRenderSnapshot(metadata, new MachineRenderSnapshot.CatalogToken(view.session(), view.sequence()), pos,
                configuration == null ? null : configuration.definition().id(), List.of(), List.of(), ids, animation);
        }
        // Session publication can briefly be unavailable during reload; metadata conservatively revokes formation.
        if (view == null && metadata.formed())
            metadata = new ControllerDisplayData(metadata.type(), metadata.orientation(), false, metadata.generation(), metadata.sequence());
        var parts = snapshot == null ? List.<ControllerRenderChunks.Part>of() : ControllerRenderChunks.split(snapshot);
        var ordinary = dataSnapshots.observe(loaded ? original : null, metadata.generation());
        boolean dataChanged = ordinary != serverDataSnapshot;
        var ordinaryParts = dataChanged ? ControllerRenderChunks.split(ordinary, metadata, dataSession) : dataParts;
        var componentIds = new ObjectArrayList<ResourceLocation>();
        var animationState = loaded ? original.components().get(MachineAnimationState.TYPE) : null;
        if (loaded)
            for (var binding : original.components().assemblyBindings()) componentIds.add(binding.type().id());
        if (epoch != attachmentEpoch || !isAttached() || machine() != original || displaySequence != previousSequence)
            return;
        displaySequence = nextSequence;
        serverDisplay = metadata;
        serverRender = snapshot;
        renderParts = parts;
        serverDataSnapshot = ordinary;
        dataParts = ordinaryParts;
        if (dataChanged) serverDataDisplay = metadata;
        renderMachine = original;
        renderConfiguration = configuration;
        renderMatch = loaded ? lifecycle.structureMatch() : null;
        renderAnimation = animationState == null ? null : animationState.snapshot();
        renderComponents = List.copyOf(componentIds);
        renderCatalog = view;
        markDirty();
        var state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
        ControllerRenderSync.remaining(this, parts);
        if (dataChanged) ControllerRenderSync.remainingData(this, ordinaryParts);
    }

    private void observeRenderState() {
        var current = machine();
        if (current == null) return;
        var lifecycle = Objects.requireNonNull(current.lifecycle());
        if (lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired()) return;
        current.requirePersistenceBoundary();
        var configuration = host == null ? null : host.structureConfiguration();
        var animation = current.components().get(MachineAnimationState.TYPE);
        var state = animation == null ? null : animation.snapshot();
        var ids = new ObjectArrayList<ResourceLocation>();
        for (var binding : current.components().assemblyBindings()) ids.add(binding.type().id());
        if (displayGeneration == 0) displayGeneration = ControllerDisplayData.newGeneration();
        var ordinary = dataSnapshots.observe(current, displayGeneration);
        var catalog = configuration == null ? MachineTypeRegistry.INSTANCE.structureCatalog() : configuration.catalog();
        var view = catalog == null ? null : ServerStructureReloads.view(Objects.requireNonNull(world.getMinecraftServer()), catalog);
        if (ordinary != serverDataSnapshot || serverDisplay == null || current != renderMachine || configuration != renderConfiguration ||
            lifecycle.structureMatch() != renderMatch || state != renderAnimation || !ids.equals(renderComponents) || view != renderCatalog)
            changed();
    }

    /** Initial chunk reads use construction data only, never traverse an active machine. */
    private void freezeColdDisplay() {
        if (serverDisplay != null) return;
        captureBlockConfiguration();
        if (displayGeneration == 0) displayGeneration = ControllerDisplayData.newGeneration();
        serverDisplay = new ControllerDisplayData(Objects.requireNonNull(data).type(), orientation(), false, displayGeneration, displaySequence);
        // Never traverse a running machine from chunk serialization; the joined observer replaces this cold fact.
        serverDataSnapshot = dataSnapshots.observe(null, displayGeneration);
        serverDataDisplay = serverDisplay;
        dataParts = ControllerRenderChunks.split(serverDataSnapshot, serverDisplay, dataSession);
        var view = ServerStructureReloads.view(Objects.requireNonNull(world.getMinecraftServer()), MachineTypeRegistry.INSTANCE.structureCatalog());
        if (view != null) {
            serverRender = new MachineRenderSnapshot(serverDisplay, new MachineRenderSnapshot.CatalogToken(view.session(), view.sequence()),
                pos, null, List.of(), List.of(), List.of(), null);
            renderParts = ControllerRenderChunks.split(serverRender);
        }
    }

    public List<ControllerRenderChunks.Part> serverRenderParts() {
        requireServer();
        freezeColdDisplay();
        return renderParts;
    }

    public List<ControllerRenderChunks.Part> serverDataParts() {
        requireServer();
        freezeColdDisplay();
        return dataParts;
    }

    public @Nullable ControllerDisplayData serverDataDisplay() {
        requireServer();
        freezeColdDisplay();
        return serverDataDisplay;
    }

    /** Exact watch replay holds the joined server owner; serialization never advances this clock. */
    public @Nullable ControllerDisplayData refreshDataReplayAtBoundary() {
        requireServer();
        scheduler().requireOwnerBoundary();
        if (!isAttached() || dataParts.isEmpty() || serverDataDisplay == null) return null;
        var current = Objects.requireNonNull(serverDisplay);
        long next = Math.incrementExact(displaySequence);
        displaySequence = next;
        // A watch-local replay must not replace the canonical header seen by all other viewers.
        return new ControllerDisplayData(current.type(), current.orientation(), current.formed(), current.generation(), next);
    }

    /** Complete ordinary data only; a partial successor, unloaded tile or former connection revokes it. */
    @SuppressWarnings("unused") // Public read-only data entry for native consumers and addons.
    public @Nullable MachineDataSnapshot dataSnapshot() {
        var header = dataAssembler.header();
        if (world == null || !world.isRemote || !isAttached() ||
            clientDataConnection != PrototypeEngineering.proxy.controllerDataConnection(this, null) ||
            clientDataDisplay == null || header == null || !dataAssembler.current(clientDataDisplay, header.catalog()))
            return null;
        return clientDataSnapshot;
    }

    /** Only a complete current client view is visible to later renderer consumers. */
    @SuppressWarnings("unused") // Public read-only entry for the following renderer migration stage and addons.
    public @Nullable MachineRenderSnapshot renderSnapshot() {
        if (world == null || !world.isRemote) return null;
        return PrototypeEngineering.proxy.currentControllerRender(this);
    }

    @Override
    public @NotNull NBTTagCompound getUpdateTag() {
        requireServer();
        freezeColdDisplay();
        if (!renderParts.isEmpty()) {
            var tag = ControllerRenderSync.tag(this, renderParts.getFirst());
            if (!dataParts.isEmpty()) tag.setTag("Data", ControllerRenderSync.dataTag(dataParts.getFirst(), Objects.requireNonNull(serverDataDisplay)));
            return tag;
        }
        var tag = new NBTTagCompound();
        tag.setInteger("x", pos.getX());
        tag.setInteger("y", pos.getY());
        tag.setInteger("z", pos.getZ());
        tag.setTag("Display", Objects.requireNonNull(serverDisplay).encode());
        if (!dataParts.isEmpty()) tag.setTag("Data", ControllerRenderSync.dataTag(dataParts.getFirst(), Objects.requireNonNull(serverDataDisplay)));
        return tag;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void handleUpdateTag(@NotNull NBTTagCompound tag) {
        acceptClientTag(tag, null);
    }

    private void acceptClientTag(NBTTagCompound tag, @Nullable NetworkManager manager) {
        if (world == null || !world.isRemote) throw new IllegalStateException("Display data requires the client world");
        if (!isAttached() || !PrototypeEngineering.proxy.controllerDisplayConnectionCurrent(this, manager)) return;
        if (StrictNbt.requireInt(tag, "x") != pos.getX() || StrictNbt.requireInt(tag, "y") != pos.getY() || StrictNbt.requireInt(tag, "z") != pos.getZ())
            return;
        var payload = StrictNbt.requireCompound(tag, "Display");
        if (!requireBlock().type().id().equals(StrictNbt.requireId(payload, "Type"))) return;
        var incoming = ControllerDisplayData.decode(payload);
        observeClientDataGeneration(incoming.generation());
        if (tag.hasKey("Data")) {
            var ordinary = StrictNbt.requireCompound(tag, "Data");
            StrictNbt.requireKeys(ordinary, "Display", "Payload");
            var metadata = ControllerDisplayData.decode(StrictNbt.requireCompound(ordinary, "Display"));
            if (requireBlock().type().id().equals(metadata.type())) {
                observeClientDataGeneration(metadata.generation());
                acceptClientData(metadata, StrictNbt.requireCompound(ordinary, "Payload"), manager);
            }
        }
        if (tag.hasKey("DataOnly") && StrictNbt.requireBoolean(tag, "DataOnly")) return;
        // Includes the same-tile unload/reload ABA case: an old complete Display cannot seed a fresh assembler.
        if (!incoming.follows(display)) return;
        if (tag.hasKey("Render")) {
            var part = ControllerRenderChunks.Part.decode(incoming, StrictNbt.requireCompound(tag, "Render"));
            PrototypeEngineering.proxy.receiveControllerRender(this, manager, part, this::installClientRender);
        } else if (!incoming.formed() && incoming.follows(display)) {
            PrototypeEngineering.proxy.receiveControllerDisplay(this, manager, incoming, this::installClientRender);
            installClientDisplay(incoming);
        }
    }

    private void installClientRender(MachineRenderSnapshot snapshot) {
        if (!isAttached() || !snapshot.controller().equals(pos) || !snapshot.display().follows(display)) return;
        installClientDisplay(snapshot.display());
    }

    private void resetClientData() {
        var pending = dataAssembler.header();
        if (pending != null && pending.display().follows(clientDataDisplay)) clientDataDisplay = pending.display();
        dataAssembler.retire();
        dataAssembler = new ControllerRenderChunks.Assembler();
        if (clientDataGeneration > 0) dataAssembler.observeGeneration(clientDataGeneration);
        if (clientDataDisplay != null) dataAssembler.withdraw(clientDataDisplay);
        clientDataSnapshot = null;
        clientDataConnection = null;
    }

    private void observeClientDataGeneration(long generation) {
        // Data revisions belong to one server TE generation, unlike watch-local transport sequences.
        if (generation > clientDataGeneration) clientDataRevision = 0;
        clientDataGeneration = Math.max(clientDataGeneration, generation);
        dataAssembler.observeGeneration(clientDataGeneration);
        if (clientDataSnapshot != null && clientDataSnapshot.generation() < clientDataGeneration) clientDataSnapshot = null;
    }

    private void acceptClientData(ControllerDisplayData incoming, NBTTagCompound tag, @Nullable NetworkManager manager) {
        var connection = PrototypeEngineering.proxy.controllerDataConnection(this, manager);
        if (connection == null || incoming.generation() < clientDataGeneration ||
            clientDataDisplay != null && !incoming.follows(clientDataDisplay) && !incoming.equals(clientDataDisplay)) return;
        if (clientDataConnection != null && clientDataConnection != connection) resetClientData();
        clientDataConnection = connection;
        var part = ControllerRenderChunks.Part.decode(incoming, tag);
        if (part.catalog().sequence() != 0) throw new IllegalArgumentException("Invalid controller data transport token");
        try {
            var complete = dataAssembler.receiveData(part);
            if (complete != null) {
                if (clientDataDisplay != null && complete.generation() == clientDataDisplay.generation() && complete.revision() < clientDataRevision)
                    throw new IllegalArgumentException("Controller data revision moved backwards");
                clientDataDisplay = incoming;
                clientDataRevision = complete.revision();
                clientDataSnapshot = complete;
            }
        } finally {
            var header = dataAssembler.header();
            if (clientDataDisplay == null || header == null || !dataAssembler.current(clientDataDisplay, header.catalog())) clientDataSnapshot = null;
        }
    }

    private void installClientDisplay(ControllerDisplayData incoming) {
        display = incoming;
        data = new ControllerData(incoming.type(), incoming.orientation(), null);
        world.markBlockRangeForRenderUpdate(pos, pos);
    }

    @Override
    public void onDataPacket(@NotNull NetworkManager manager, @NotNull SPacketUpdateTileEntity packet) {
        if (packet.getPos().equals(pos) && packet.getTileEntityType() == 0)
            acceptClientTag(packet.getNbtCompound(), manager);
    }

    private boolean isAttached() {
        return world != null && !isInvalid() && world.isBlockLoaded(pos, false) && world.getTileEntity(pos) == this
            && world.getBlockState(pos).getBlock() instanceof MachineControllerBlockHost;
    }

    private MachineControllerBlockHost requireBlock() {
        if (!(world.getBlockState(pos).getBlock() instanceof MachineControllerBlockHost block)) {
            throw new IllegalStateException("Controller tile has no controller block");
        }
        return block;
    }

    private void requireServer() {
        if (world == null || world.isRemote || !Objects.requireNonNull(world.getMinecraftServer()).isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Controller operation requires its attached server thread");
        }
    }

    private TaskScheduler scheduler() {
        requireServer();
        return ServerTaskSchedulers.forServer(Objects.requireNonNull(world.getMinecraftServer()));
    }

    private record Placement(World world, BlockPos position, MachineType type, UUID player) {
    }
}
