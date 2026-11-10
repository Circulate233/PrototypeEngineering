package com.circulation.prototype_eng.storage;

import ae2.api.AECapabilities;
import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import ae2.me.storage.ExternalStorageFacade;
import ae2.parts.automation.StackWorldBehaviors;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongUnaryOperator;

/** One tile's bounded automatic IO cursors. External capabilities are resolved anew for each tick. */
public final class HatchAutoTransfer {
    private static final int PROBES = 4;
    private static final int ENUMERATION_INTERVAL = 20;
    private static final EnumFacing[] DIRECTIONS = EnumFacing.values();
    private static final IActionSource SOURCE = IActionSource.empty();
    private final ResourceCursor[] cursors = new ResourceCursor[DIRECTIONS.length];
    private long tick;
    private long inputOrder = -1, outputOrder = -1;
    private int firstFace;
    private boolean enumeratedThisTick;

    public HatchAutoTransfer() {
        for (int i = 0; i < cursors.length; i++) cursors[i] = new ResourceCursor();
    }

    public void tickResources(ResourceHatchBlockEntity host) {
        if (!(host.getWorld() instanceof WorldServer world) || !current(world, host.getPos(), host)) return;
        if (!host.autoInput() && !host.autoOutput()) return;
        var hatch = host.storage();
        beginTick();
        for (int offset = 0; offset < DIRECTIONS.length; offset++) {
            int faceIndex = (firstFace + offset) % DIRECTIONS.length;
            var direction = DIRECTIONS[faceIndex];
            var position = host.getPos().offset(direction);
            if (!world.isBlockLoaded(position, false)) {
                cursors[faceIndex].clear();
                continue;
            }
            var neighbor = world.getTileEntity(position);
            observeNeighbor(faceIndex, neighbor);
            if (neighbor != null && neighbor.isInvalid()) continue;
            var face = direction.getOpposite();
            var resource = HatchCapabilities.findResource(neighbor, face);
            var peer = resourceEndpoint(world, position, face, neighbor, resource);
            if (peer.ports().isEmpty()) {
                cursors[faceIndex].clear();
                continue;
            }
            boolean input = host.autoInput();
            boolean output = host.autoOutput();
            long fluidInput = host.maxFluidInputRate(), fluidOutput = host.maxFluidOutputRate();
            resources(hatch, peer, faceIndex, host.mode().hasInput() && input, host.mode().hasOutput() && output, fluidInput, fluidOutput,
                () -> current(world, host.getPos(), host) && host.storage() == hatch
                    && current(world, position, neighbor) && (resource == null || resource.isValid())
                    && host.autoInput() == input && host.autoOutput() == output
                    && host.maxFluidInputRate() == fluidInput && host.maxFluidOutputRate() == fluidOutput);
        }
    }

    public void tickEnergy(EnergyHatchBlockEntity host) {
        if (!(host.getWorld() instanceof WorldServer world) || !current(world, host.getPos(), host)) return;
        if (!host.autoInput() && !host.autoOutput()) return;
        var input = host.inputStorage();
        var output = host.outputStorage();
        for (var direction : DIRECTIONS) {
            var position = host.getPos().offset(direction);
            if (!world.isBlockLoaded(position, false)) continue;
            var neighbor = world.getTileEntity(position);
            if (neighbor == null || neighbor.isInvalid()) continue;
            var face = direction.getOpposite();
            var remote = neighbor.getCapability(CapabilityEnergy.ENERGY, face);
            if (remote == null) continue;
            var contexts = peerContexts(neighbor, face, HatchCapabilities.findResource(neighbor, face));
            boolean inputEnabled = host.autoInput(), outputEnabled = host.autoOutput();
            energy(input, output, remote, contexts, host.mode().hasInput() && inputEnabled,
                host.mode().hasOutput() && outputEnabled,
                () -> current(world, host.getPos(), host) && current(world, position, neighbor)
                    && host.inputStorage() == input && host.outputStorage() == output
                    && host.autoInput() == inputEnabled && host.autoOutput() == outputEnabled);
        }
    }

    private static boolean current(WorldServer world, BlockPos position, @Nullable TileEntity tile) {
        return !world.isRemote && world.isCallingFromMinecraftThread() && world.isBlockLoaded(position, false)
            && world.getTileEntity(position) == tile && (tile == null || !tile.isInvalid() && tile.getWorld() == world);
    }

    void beginTick() {
        tick++;
        firstFace = (firstFace + 1) % DIRECTIONS.length;
        enumeratedThisTick = false;
    }

    void observeNeighbor(int face, @Nullable TileEntity neighbor) {
        var cursor = cursors[face];
        if (cursor.neighbor.get() != neighbor || neighbor != null && neighbor.isInvalid()) {
            cursor.clear();
            cursor.neighbor = new WeakReference<>(neighbor);
        }
    }

    @SuppressWarnings("UnstableApiUsage") // AE's registered type-specific external storage bridge is the intended extension point.
    private static ResourceEndpoint resourceEndpoint(WorldServer world, BlockPos position, EnumFacing face,
                                                      @Nullable TileEntity neighbor, @Nullable ResourceHatch resource) {
        var contexts = peerContexts(neighbor, face, resource);
        var output = resource != null && resource.output() instanceof SlottedResourceStorage slotted ? slotted : null;
        var direct = neighbor == null ? null : neighbor.getCapability(AECapabilities.ME_STORAGE, face);
        if (direct != null) return new ResourceEndpoint(List.of(new ResourcePort(null, direct)), output, contexts);
        var ports = new ObjectArrayList<ResourcePort>();
        for (var entry : StackWorldBehaviors.createExternalStorageStrategies(world, position, face).entrySet()) {
            var wrapper = entry.getValue().createWrapper(false, () -> {});
            if (wrapper != null) ports.add(new ResourcePort(entry.getKey(), wrapper));
        }
        return new ResourceEndpoint(ports, null, contexts);
    }

    private static List<StorageContext> peerContexts(@Nullable TileEntity neighbor, EnumFacing face,
                                                    @Nullable ResourceHatch resource) {
        var contexts = new ObjectArrayList<StorageContext>();
        if (resource != null) {
            var input = resource.input();
            var output = resource.output();
            if (input != null) addContext(contexts, input.context());
            if (output != null) addContext(contexts, output.context());
        }
        var energyInput = HatchCapabilities.findEnergyInput(neighbor, face);
        var energyOutput = HatchCapabilities.findEnergyOutput(neighbor, face);
        if (energyInput != null) addContext(contexts, energyInput.context());
        if (energyOutput != null) addContext(contexts, energyOutput.context());
        return contexts;
    }

    void resources(BufferedResourceHatch hatch, ResourceEndpoint peer, int face, boolean inputEnabled,
                   boolean outputEnabled, long fluidInputRate, long fluidOutputRate, BooleanSupplier current) {
        if (peer.ports().isEmpty()) return;
        long nextInputOrder = hatch.input() == null ? -1 : hatch.input().context().order();
        long nextOutputOrder = hatch.output() == null ? -1 : hatch.output().context().order();
        if (nextInputOrder != inputOrder || nextOutputOrder != outputOrder) {
            for (var cursor : cursors) cursor.clear();
            inputOrder = nextInputOrder;
            outputOrder = nextOutputOrder;
        }
        var contexts = new ObjectArrayList<StorageContext>();
        if (hatch.input() != null) addContext(contexts, hatch.input().context());
        if (hatch.output() != null) addContext(contexts, hatch.output().context());
        if (!includePeer(contexts, peer.contexts()) || !tryLocks(contexts)) return;
        try {
            if (!current.getAsBoolean() || contexts.stream().anyMatch(context -> !context.isValid())) return;
            var cursor = cursors[face];
            if (inputEnabled && hatch.input() instanceof SlottedResourceBuffer input)
                importResources(input, peer, cursor, fluidInputRate, current);
            if (outputEnabled && hatch.output() instanceof SlottedResourceBuffer output)
                exportResources(output, peer, cursor, fluidOutputRate, current);
        } finally {
            unlock(contexts);
        }
    }

    private void importResources(SlottedResourceBuffer input, ResourceEndpoint peer, ResourceCursor cursor,
                                 long fluidRate, BooleanSupplier current) {
        for (int probe = 0; probe < PROBES && current.getAsBoolean() && input.isValid(); probe++) {
            if (cursor.pending == null) {
                cursor.port = Math.floorMod(cursor.nextPort++, peer.ports().size());
                cursor.pending = candidate(peer, cursor, cursor.port);
                cursor.remainingTargets = input.size();
                if (cursor.pending == null || !input.isSupportedType(cursor.pending.what().getType())) {
                    cursor.pending = null;
                    continue;
                }
            }
            var key = cursor.pending.what();
            var port = peer.ports().get(Math.floorMod(cursor.port, peer.ports().size()));
            if (port.type() != null && port.type() != key.getType()) {
                cursor.pending = null;
                continue;
            }
            if (cursor.pendingSource != null && peer.slots() == null && !(port.storage() instanceof ExternalStorageFacade)) {
                refreshGeneric(port.storage(), cursor.pendingSource, cursor);
                if (cursor.pending == null) continue;
            }
            if (!current.getAsBoolean() || !input.isValid()) return;
            int slot = Math.floorMod(cursor.inputSlot++, input.size());
            long maximum = fluidLimit(key, cursor.pending.amount(), fluidRate);
            long offered = input.insert(slot, key, maximum, Actionable.SIMULATE);
            var reservation = offered > 0 ? input.reserveTransfer(slot, key, offered, true) : null;
            if (reservation != null) {
                settle(input.context(), reservation, amount -> port.storage().extract(key, amount, Actionable.MODULATE, SOURCE));
                cursor.pending = null;
            } else if (--cursor.remainingTargets == 0) {
                cursor.pending = null;
            }
        }
    }

    private @Nullable GenericStack candidate(ResourceEndpoint peer, ResourceCursor cursor, int portIndex) {
        var port = peer.ports().get(portIndex);
        var source = cursor.sources.computeIfAbsent(port.type(), ignored -> new SourceCursor());
        cursor.pendingSource = null;
        if (peer.slots() != null) {
            int count = peer.slots().size();
            return count == 0 ? null : peer.slots().getStack(Math.floorMod(source.slot++, count));
        }
        if (port.storage() instanceof ExternalStorageFacade facade) {
            int count = facade.getSlots();
            return count <= 0 ? null : facade.getStackInSlot(Math.floorMod(source.slot++, count));
        }
        refreshGeneric(port.storage(), source, cursor);
        if (source.queued.isEmpty()) return null;
        cursor.pendingSource = source;
        var key = source.queued.firstKey();
        return new GenericStack(key, source.queued.removeLong(key));
    }

    private void refreshGeneric(MEStorage storage, SourceCursor source, ResourceCursor cursor) {
        if (enumeratedThisTick || tick < source.nextEnumeration) return;
        var reported = KeyCounter.checked();
        storage.getAvailableStacks(reported);
        var fresh = new AEKey2LongMap.LinkdOpenHashMap();
        reported.forEach((key, amount) -> {
            if (amount > 0) fresh.addTo(key, amount);
        });
        // Preserve unvisited keys ahead of previously visited/new keys, even across frequent reports.
        var queued = source.queued.object2LongEntrySet().iterator();
        while (queued.hasNext()) {
            var entry = queued.next();
            long amount = fresh.getLong(entry.getKey());
            if (amount <= 0) queued.remove();
            else entry.setValue(amount);
        }
        fresh.forEachEntry((key, amount) -> {
            if (!source.queued.containsKey(key)) source.queued.addTo(key, amount);
        });
        if (cursor.pending != null && cursor.pendingSource == source) {
            var key = cursor.pending.what();
            long amount = fresh.getLong(key);
            if (amount <= 0) {
                cursor.pending = null;
                cursor.pendingSource = null;
                cursor.remainingTargets = 0;
            } else {
                cursor.pending = new GenericStack(key, amount);
                source.queued.removeLong(key);
            }
        }
        source.nextEnumeration = tick + ENUMERATION_INTERVAL;
        enumeratedThisTick = true;
    }

    private static void exportResources(SlottedResourceBuffer output, ResourceEndpoint peer, ResourceCursor cursor,
                                        long fluidRate, BooleanSupplier current) {
        for (int probe = 0; probe < PROBES && current.getAsBoolean() && output.isValid(); probe++) {
            int slot = Math.floorMod(cursor.outputSlot++, output.size());
            var stack = output.getStack(slot);
            if (stack == null) continue;
            for (var port : peer.ports()) {
                if (port.type() != null && port.type() != stack.what().getType()) continue;
                long offered = output.extract(slot, stack.what(), fluidLimit(stack.what(), stack.amount(), fluidRate), Actionable.SIMULATE);
                var reservation = offered > 0 ? output.reserveTransfer(slot, stack.what(), offered, false) : null;
                if (reservation != null)
                    settle(output.context(), reservation, amount -> port.storage().insert(stack.what(), amount, Actionable.MODULATE, SOURCE));
                break;
            }
        }
    }

    private static long fluidLimit(AEKey key, long amount, long maximum) {
        return key instanceof AEFluidKey && maximum > 0 ? Math.min(amount, maximum) : amount;
    }

    static void energy(@Nullable EnergyBuffer input, @Nullable EnergyBuffer output, IEnergyStorage remote,
                       List<StorageContext> peerContexts, boolean inputEnabled, boolean outputEnabled, BooleanSupplier current) {
        var contexts = new ObjectArrayList<StorageContext>();
        if (input != null) addContext(contexts, input.context());
        if (output != null) addContext(contexts, output.context());
        if (!includePeer(contexts, peerContexts) || !tryLocks(contexts)) return;
        try {
            if (!current.getAsBoolean() || contexts.stream().anyMatch(context -> !context.isValid())) return;
            if (inputEnabled && input != null && remote.canExtract()) {
                long offered = input.receiveEnergy(Integer.MAX_VALUE, Actionable.SIMULATE);
                var reservation = offered > 0 ? input.reserveTransfer(offered, true) : null;
                if (reservation != null)
                    settle(input.context(), reservation, amount -> remote.extractEnergy((int) amount, false));
            }
            if (outputEnabled && output != null && current.getAsBoolean() && output.isValid() && remote.canReceive()) {
                long offered = output.extractEnergy(Integer.MAX_VALUE, Actionable.SIMULATE);
                var reservation = offered > 0 ? output.reserveTransfer(offered, false) : null;
                if (reservation != null)
                    settle(output.context(), reservation, amount -> remote.receiveEnergy((int) amount, false));
            }
        } finally {
            unlock(contexts);
        }
    }

    private static void settle(StorageContext context, NativeTransferReservation reservation, LongUnaryOperator external) {
        try {
            long actual = external.applyAsLong(reservation.amount());
            if (actual < 0 || actual > reservation.amount())
                throw new IllegalStateException("External storage returned an unknown transfer amount");
            reservation.complete(actual);
        } catch (RuntimeException | Error failure) {
            // Unknown external effects cannot be refunded. Retain the native hold and stop this lifetime.
            try {
                context.invalidate();
            } catch (RuntimeException | Error invalidation) {
                failure.addSuppressed(invalidation);
            }
            throw failure;
        }
    }

    private static void addContext(List<StorageContext> contexts, StorageContext context) {
        if (!contexts.contains(context)) contexts.add(context);
    }

    private static boolean includePeer(List<StorageContext> local, List<StorageContext> peer) {
        for (var context : peer) if (local.contains(context)) return false;
        for (var context : peer) addContext(local, context);
        local.sort(Comparator.comparingLong(StorageContext::order));
        return true;
    }

    private static boolean tryLocks(List<StorageContext> contexts) {
        int acquired = 0;
        for (var context : contexts) {
            if (!context.tryOrdinaryLock()) {
                for (int i = acquired - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
                return false;
            }
            acquired++;
        }
        return true;
    }

    private static void unlock(List<StorageContext> contexts) {
        for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
        contexts.forEach(StorageContext::publishChanges);
    }

    record ResourcePort(@Nullable AEKeyType type, MEStorage storage) {}

    record ResourceEndpoint(List<ResourcePort> ports, @Nullable SlottedResourceStorage slots, List<StorageContext> contexts) {}

    private static final class ResourceCursor {
        private final Reference2ObjectOpenHashMap<AEKeyType, SourceCursor> sources = new Reference2ObjectOpenHashMap<>();
        private WeakReference<TileEntity> neighbor = new WeakReference<>(null);
        private int nextPort, port, inputSlot, outputSlot, remainingTargets;
        private @Nullable GenericStack pending;
        private @Nullable SourceCursor pendingSource;

        private void clear() {
            sources.clear();
            pending = null;
            pendingSource = null;
            nextPort = port = inputSlot = outputSlot = remainingTargets = 0;
        }
    }

    private static final class SourceCursor {
        private final AEKey2LongMap.LinkdOpenHashMap queued = new AEKey2LongMap.LinkdOpenHashMap();
        private int slot;
        private long nextEnumeration;
    }
}
