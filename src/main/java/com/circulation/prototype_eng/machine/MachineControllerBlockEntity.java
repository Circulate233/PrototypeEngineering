package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.block.MachineControllerBlock;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Empty construction/read never assembles. The next server update observes the final placement/NBT
 * after Forge's cancellation window. A reattached dormant tile owns a new controller generation.
 */
@SuppressWarnings("resource") // FML owns the shared server scheduler, never an individual tile.
public final class MachineControllerBlockEntity extends TileEntity implements ITickable {
    @Nullable private ControllerData data;
    @Nullable private MachineController controller;
    @Nullable private ControllerLifecycleHost host;
    @Nullable private ControllerStructureUpdates structures;
    @Nullable private Placement placement;
    @Nullable private ControllerDisplayData display;
    private boolean wantsLoad;
    private boolean queuedLoad;
    private boolean replacementData;
    private Object attachmentEpoch = new Object();
    private boolean changingState;
    private boolean updated;
    private long lastTick;
    private long displayGeneration;
    private long displaySequence;

    @SuppressWarnings("unused") // Forge's registered TileEntity factory invokes the public no-arg constructor.
    public MachineControllerBlockEntity() { }
    public MachineControllerBlockEntity(ResourceLocation type, BlockOrientation orientation) {
        data = new ControllerData(type, orientation, null);
    }

    @Nullable public MachineInstance machine() { return controller == null ? null : controller.machine(); }
    public BlockOrientation orientation() { return data == null ? BlockOrientation.NORTH_UP : data.orientation(); }
    public boolean formed() {
        if (world != null && world.isRemote) { return display != null && display.formed(); }
        var machine = machine();
        return machine != null && Objects.requireNonNull(machine.lifecycle()).formed();
    }

    @Override public void validate() { super.validate(); wantsLoad = true; }
    @Override public void onLoad() { super.onLoad(); wantsLoad = true; updated = false; }

    @Override public void update() {
        if (world == null || world.isRemote || !wantsLoad || !isAttached()) { return; }
        requireServer();
        long now = world.getTotalWorldTime();
        if (updated && lastTick == now) { return; }
        updated = true;
        lastTick = now;
        var machine = machine();
        if (controller == null || machine != null && Objects.requireNonNull(machine.lifecycle()).retired()) {
            if (!queuedLoad) {
                queuedLoad = true;
                scheduler().runAtBoundary(this::attachAtBoundary);
            }
        }
        machine = machine();
        if (structures != null && machine != null && !Objects.requireNonNull(machine.lifecycle()).retired()) { structures.tick(now); }
        // Components may mutate on scheduler workers; a server-side save mark covers their next pass.
        markDirty();
    }

    private void attachAtBoundary() {
        queuedLoad = false;
        if (!wantsLoad || !isAttached()) { return; }
        var block = requireBlock();
        var type = block.type();
        if (data == null) { data = new ControllerData(type.id(), block.getOrientation(world.getBlockState(pos)), null); }
        requireType(type, data.type());
        if (controller != null) {
            var previous = controller.machine();
            if (previous != null && !Objects.requireNonNull(previous.lifecycle()).retired()) { return; }
            if (!replacementData && previous != null) { data = new ControllerData(type.id(), orientation(), controller.save()); }
            controller = null;
            structures = null;
            host = null;
            placement = null;
            displayGeneration = 0;
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
        var bound = new ControllerLifecycleHost(type.id(), this, actor);
        var created = new MachineController(MachineTypeRegistry.INSTANCE.assembly(type), bound, scheduler());
        host = bound;
        controller = created;
        // load queues behind this owner action. Only its following boundary action may use the machine.
        created.load(data.runtime());
        scheduler().runAtBoundary(() -> {
            if (controller != created || !wantsLoad || !isAttached()) { created.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED); return; }
            var machine = created.machine();
            if (machine == null) {
                // A previously queued stop can cancel load while vanilla revalidates this same tile.
                controller = null;
                host = null;
                return;
            }
            if (Objects.requireNonNull(machine.lifecycle()).retired()) { return; }
            try {
                data = new ControllerData(type.id(), orientation(), null);
                replacementData = false;
                bound.configureStructure(MachineTypeRegistry.INSTANCE.structure(type), orientation());
                structures = new ControllerStructureUpdates(created, bound, scheduler(), this::changed);
                applyState();
                changed();
            } catch (RuntimeException | Error failure) {
                wantsLoad = false;
                try { created.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        });
    }

    /** Capture only the actual placer of this exact tile; no coordinate-wide pending actor cache. */
    public void placedBy(EntityLivingBase placer) {
        requireServer();
        if (controller != null || !isAttached()) { throw new IllegalStateException("Placement must precede controller assembly"); }
        var type = requireBlock().type();
        if (data != null) { requireType(type, data.type()); }
        if (placement != null) { throw new IllegalStateException("Controller placement was already recorded"); }
        if (placer instanceof EntityPlayer player) { placement = new Placement(world, pos.toImmutable(), type, player.getUniqueID()); }
    }

    public void orient(BlockOrientation orientation) {
        Objects.requireNonNull(orientation);
        transformOrientation(_ -> orientation);
    }

    private void transformOrientation(UnaryOperator<BlockOrientation> transformation) {
        requireServer();
        var epoch = attachmentEpoch;
        scheduler().runAtBoundary(() -> {
            if (epoch == attachmentEpoch) { changeOrientation(transformation.apply(orientation())); }
        });
    }

    private void changeOrientation(BlockOrientation orientation) {
        if (!isAttached() || orientation() == orientation) { return; }
        var block = requireBlock();
        var old = Objects.requireNonNull(data, "Controller has no pending data");
        if (structures != null) { structures.invalidate(); }
        data = new ControllerData(old.type(), orientation, old.runtime());
        var machine = machine();
        if (host != null && machine != null && !Objects.requireNonNull(machine.lifecycle()).retired()) {
            host.configureStructure(MachineTypeRegistry.INSTANCE.structure(block.type()), orientation);
        }
        applyState();
        changed();
    }

    private void applyState() {
        var block = requireBlock();
        var current = world.getBlockState(pos);
        var desired = block.state(current, orientation());
        if (desired == current) { return; }
        changingState = true;
        try {
            if (!world.setBlockState(pos, desired, 3)) { throw new IllegalStateException("Controller orientation could not be written"); }
        } finally { changingState = false; }
    }

    @Override public boolean shouldRefresh(@NotNull World world, @NotNull BlockPos pos, @NotNull IBlockState oldState, @NotNull IBlockState newState) {
        if (oldState.getBlock() != newState.getBlock()) { return true; }
        if (!world.isRemote && !changingState && newState.getBlock() instanceof MachineControllerBlock block) {
            var orientation = block.getOrientation(newState);
            // Record every state transition. Pending A -> original changes must not disappear just
            // because the first request has not updated tile data yet. FIFO actions update both sides.
            if (orientation != block.getOrientation(oldState)) { orient(orientation); }
        }
        return false;
    }
    @Override public void rotate(@NotNull Rotation rotation) { transformOrientation(current -> ControllerOrientation.rotate(current, rotation)); }
    @Override public void mirror(@NotNull Mirror mirror) { transformOrientation(current -> ControllerOrientation.mirror(current, mirror)); }
    public void rotateAround(EnumFacing axis) { transformOrientation(current -> current.rotateClockwiseAround(axis)); }

    public void controllerBroken() {
        wantsLoad = false;
        placement = null;
        attachmentEpoch = new Object();
        if (controller != null) { controller.controllerBroken(); }
    }
    private void stop(MachineLifecycleEvent.UnloadReason reason) {
        wantsLoad = false;
        if (controller != null) { placement = null; controller.unload(reason); attachmentEpoch = new Object(); }
        else if (!isAttached()) { placement = null; }
    }
    @Override public void invalidate() {
        try { if (world != null && !world.isRemote) { stop(MachineLifecycleEvent.UnloadReason.INVALIDATED); } }
        finally { super.invalidate(); }
    }
    @Override public void onChunkUnload() {
        try { if (world != null && !world.isRemote) { stop(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD); } }
        finally { super.onChunkUnload(); }
    }

    @Override public void readFromNBT(@NotNull NBTTagCompound tag) {
        var decoded = ControllerData.decode(StrictNbt.requireCompound(tag, "Machine"));
        if (data != null && !data.type().equals(decoded.type())) { throw new IllegalArgumentException("Controller item/save type mismatch"); }
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
            placement = null;
            updated = false;
        }
        super.readFromNBT(tag);
        replacementData = controller != null;
        data = decoded;
    }
    @Override public @NotNull NBTTagCompound writeToNBT(@NotNull NBTTagCompound tag) {
        var saved = Objects.requireNonNull(data, "Controller has no type or pending data");
        if (controller != null && !replacementData) { saved = new ControllerData(saved.type(), orientation(), controller.save()); }
        var result = super.writeToNBT(tag);
        result.setTag("Machine", saved.encode());
        return result;
    }

    private void changed() {
        requireServer();
        displaySequence = Math.incrementExact(displaySequence);
        markDirty();
        if (isAttached()) {
            var state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }
    @Override public @NotNull NBTTagCompound getUpdateTag() {
        requireServer();
        if (displayGeneration == 0) { displayGeneration = ControllerDisplayData.newGeneration(); }
        var tag = new NBTTagCompound();
        tag.setInteger("x", pos.getX()); tag.setInteger("y", pos.getY()); tag.setInteger("z", pos.getZ());
        tag.setTag("Display", new ControllerDisplayData(Objects.requireNonNull(data).type(), orientation(), formed(), displayGeneration, displaySequence).encode());
        return tag;
    }
    @Override public SPacketUpdateTileEntity getUpdatePacket() { return new SPacketUpdateTileEntity(pos, 0, getUpdateTag()); }
    @Override public void handleUpdateTag(@NotNull NBTTagCompound tag) {
        if (world == null || !world.isRemote) { throw new IllegalStateException("Display data requires the client world"); }
        if (StrictNbt.requireInt(tag, "x") != pos.getX() || StrictNbt.requireInt(tag, "y") != pos.getY() || StrictNbt.requireInt(tag, "z") != pos.getZ()) { return; }
        if (!isAttached()) { return; }
        var payload = StrictNbt.requireCompound(tag, "Display");
        if (!requireBlock().type().id().equals(StrictNbt.requireId(payload, "Type"))) { return; }
        var incoming = ControllerDisplayData.decode(payload);
        if (!incoming.follows(display)) { return; }
        display = incoming;
        data = new ControllerData(incoming.type(), incoming.orientation(), null);
        world.markBlockRangeForRenderUpdate(pos, pos);
    }
    @Override public void onDataPacket(@NotNull NetworkManager manager, @NotNull SPacketUpdateTileEntity packet) { handleUpdateTag(packet.getNbtCompound()); }

    private boolean isAttached() {
        return world != null && !isInvalid() && world.isBlockLoaded(pos, false) && world.getTileEntity(pos) == this
            && world.getBlockState(pos).getBlock() instanceof MachineControllerBlock;
    }
    private MachineControllerBlock requireBlock() {
        if (!(world.getBlockState(pos).getBlock() instanceof MachineControllerBlock block)) { throw new IllegalStateException("Controller tile has no controller block"); }
        return block;
    }
    private void requireServer() {
        if (world == null || world.isRemote || !Objects.requireNonNull(world.getMinecraftServer()).isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Controller operation requires its attached server thread");
        }
    }
    private TaskScheduler scheduler() { requireServer(); return ServerTaskSchedulers.forServer(Objects.requireNonNull(world.getMinecraftServer())); }
    private static void requireType(MachineType type, ResourceLocation saved) {
        if (!type.id().equals(saved) || MachineTypeRegistry.INSTANCE.get(saved) != type) { throw new IllegalArgumentException("Controller does not reference its canonical block type"); }
    }
    private record Placement(World world, BlockPos position, MachineType type, UUID player) { }
}
