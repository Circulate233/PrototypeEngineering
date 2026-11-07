package com.circulation.prototype_eng.research.disk;

import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.MachineDataCenterPersistence;
import com.circulation.prototype_eng.api.research.DataCenterStorage;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.ThreadedFileIOBase;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Default physical machine-assembly database adapter. Uses the existing exact Anvil chunk writer
 * and verifies its native tile/ownership/storage envelope. Other hosts supply their own adapter.
 */
public final class MinecraftDataCenterPersistence implements MachineDataCenterPersistence {
    public static final MinecraftDataCenterPersistence INSTANCE = new MinecraftDataCenterPersistence();

    private MinecraftDataCenterPersistence() {
    }

    public static boolean available(MachineInstance owner) {
        Objects.requireNonNull(owner.lifecycle()).host().requireServerThread();
        return target(owner) != null;
    }

    @Override
    public void persistAndVerify(MachineInstance owner, ResourceLocation storageType, ResourceLocation ownershipType,
                                 NBTTagCompound expected, BooleanSupplier physicalCurrent) {
        var lifecycle = Objects.requireNonNull(owner.lifecycle());
        lifecycle.host().requireServerThread();
        owner.requirePersistenceBoundary();
        var target = Objects.requireNonNull(target(owner), "Original Anvil database host is unavailable");
        var ownership = ownership(owner, ownershipType);
        var identity = ownership.identity();
        var registration = Objects.requireNonNull(ownership.registration());
        if (!registration.unique() || registration.runtimeIdentity() != owner || registration.worldIdentity() != target.world()
            || !registration.location().equals(target.location()) || !physicalCurrent.getAsBoolean())
            throw new IllegalStateException("Original database identity binding is stale");
        int storages = 0;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.type().id().equals(storageType) && binding.component() instanceof DataCenterStorage storage
                && storage.snapshot().dataCenterId().equals(identity.machineId())) storages++;
        }
        if (storages != 1) throw new IllegalStateException("Original database component binding is unavailable");
        var nativeTag = target.tile().writeToNBT(new NBTTagCompound());
        var nativeId = nativeTag.getString("id");
        try {
            DataCenterDiskPersistence.verifyTile(nativeTag, target.location(), identity, storageType, ownershipType, nativeId, expected);
            if (!physicalCurrent.getAsBoolean()) throw new IllegalStateException("Database changed before its chunk save");
            // Avoid a full world save, which would reenter the research world's save callback.
            target.loader().saveChunk(target.world(), target.chunk());
            ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
            lifecycle.host().requireServerThread();
            if (target.loader().getPendingSaveCount() != 0) throw new IllegalStateException("Database chunk save still has pending writes");
            DataCenterDiskPersistence.verify(target.loader().chunkSaveLocation.toPath(), target.location(), identity,
                storageType, ownershipType, nativeId, expected);
            var current = target(owner);
            if (!physicalCurrent.getAsBoolean() || current == null || current.world() != target.world()
                || current.tile() != target.tile() || current.controller() != target.controller() || current.chunk() != target.chunk()
                || current.loader() != target.loader() || !registration.unique() || ownership(owner, ownershipType) != ownership)
                throw new IllegalStateException("Original database changed during its persistence barrier");
        } catch (MinecraftException failure) {
            throw new IllegalStateException("Database chunk persistence failed", failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Database chunk persistence was interrupted", failure);
        }
    }

    private static MachineOwnership ownership(MachineInstance owner, ResourceLocation type) {
        MachineOwnership result = null;
        for (var binding : owner.components().assemblyBindings()) {
            if (binding.type().id().equals(type) && binding.component() instanceof MachineOwnership candidate) {
                if (result != null) throw new IllegalStateException("Database has duplicate ownership bindings");
                result = candidate;
            }
        }
        return Objects.requireNonNull(result, "Database has no physical ownership binding");
    }

    @Nullable
    private static Target target(MachineInstance owner) {
        var lifecycle = Objects.requireNonNull(owner.lifecycle());
        var location = lifecycle.host().identity();
        if (!(lifecycle.host().world() instanceof WorldServer world) || lifecycle.host().worldIdentity() != world
            || world.provider.getDimension() != location.dimension() || world.getMinecraftServer() == null
            || world.getMinecraftServer().getWorld(location.dimension()) != world
            || !(world.getChunkProvider().chunkLoader instanceof AnvilChunkLoader loader)) return null;
        var chunk = world.getChunkProvider().getLoadedChunk(location.controllerPos().getX() >> 4, location.controllerPos().getZ() >> 4);
        if (chunk == null) return null;
        var tile = world.getTileEntity(location.controllerPos());
        if (!(tile instanceof MachineControllerHost controller) || tile.isInvalid() || tile.getWorld() != world
            || !tile.getPos().equals(location.controllerPos()) || controller.blockEntity() != tile
            || controller.machine() != owner || controller.type() != owner.type()) return null;
        return new Target(world, tile, controller, chunk, loader, location);
    }

    private record Target(WorldServer world, TileEntity tile, MachineControllerHost controller, Chunk chunk,
                          AnvilChunkLoader loader, MachineIdentity location) {
    }
}
