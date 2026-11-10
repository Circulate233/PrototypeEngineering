package com.circulation.prototype_eng.research.disk;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraftforge.common.util.Constants;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Database-specific verification of the actual Anvil tile payload, never the loader's pending cache.
 */
final class DataCenterDiskPersistence {
    private DataCenterDiskPersistence() {
    }

    static void verify(Path directory, MachineIdentity location, PersistentMachineIdentity identity,
                       ResourceLocation storageType, ResourceLocation ownershipType, String nativeId,
                       NBTTagCompound expected) throws IOException {
        int x = location.controllerPos().getX() >> 4;
        int z = location.controllerPos().getZ() >> 4;
        var file = directory.resolve("region").resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!Files.isRegularFile(file) || Files.size(file) < 8192 || Files.size(file) % 4096 != 0)
            throw new IOException("Saved database region is missing or malformed: " + file);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        var region = new RegionFile(file.toFile());
        try {
            try (var input = region.getChunkDataInputStream(x & 31, z & 31)) {
                if (input == null) throw new IOException("Saved database chunk is missing or unreadable");
                var level = StrictNbt.requireCompound(CompressedStreamTools.read(input), "Level");
                if (StrictNbt.requireInt(level, "xPos") != x || StrictNbt.requireInt(level, "zPos") != z)
                    throw new IOException("Saved database chunk coordinates differ");
                var tiles = StrictNbt.requireList(level, "TileEntities", Constants.NBT.TAG_COMPOUND);
                int matches = 0;
                for (int index = 0; index < tiles.tagCount(); index++) {
                    var tile = tiles.getCompoundTagAt(index);
                    boolean position = tile.hasKey("x", Constants.NBT.TAG_INT) && tile.hasKey("y", Constants.NBT.TAG_INT)
                        && tile.hasKey("z", Constants.NBT.TAG_INT) && tile.getInteger("x") == location.controllerPos().getX()
                        && tile.getInteger("y") == location.controllerPos().getY() && tile.getInteger("z") == location.controllerPos().getZ();
                    if (position || hasDatabaseIdentity(tile, identity, ownershipType)) {
                        verifyTile(tile, location, identity, storageType, ownershipType, nativeId, expected);
                        matches++;
                    }
                }
                if (matches != 1)
                    throw new IOException("Saved database chunk requires exactly one original tile; found " + matches);
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Saved database envelope is malformed", malformed);
        } finally {
            region.close();
        }
    }

    static void verifyTile(NBTTagCompound tile, MachineIdentity location, PersistentMachineIdentity identity,
                           ResourceLocation storageType, ResourceLocation ownershipType, String nativeId,
                           NBTTagCompound expected) throws IOException {
        try {
            if (nativeId.isEmpty() || !StrictNbt.requireString(tile, "id").equals(nativeId)
                || StrictNbt.requireInt(tile, "x") != location.controllerPos().getX()
                || StrictNbt.requireInt(tile, "y") != location.controllerPos().getY()
                || StrictNbt.requireInt(tile, "z") != location.controllerPos().getZ())
                throw new IOException("Saved database tile type or coordinates differ");
            var envelope = StrictNbt.requireCompound(tile, "Machine");
            var runtime = StrictNbt.requireCompound(envelope, "Runtime");
            if (StrictNbt.requireInt(envelope, "Version") != 1 || !StrictNbt.requireId(envelope, "Type").equals(location.machineType())
                || StrictNbt.requireInt(runtime, "Version") != 2 || !StrictNbt.requireId(runtime, "Type").equals(location.machineType()))
                throw new IOException("Saved database machine declaration differs");
            var components = StrictNbt.requireList(runtime, "Components", Constants.NBT.TAG_COMPOUND);
            int ownership = 0;
            int storage = 0;
            for (int index = 0; index < components.tagCount(); index++) {
                var component = components.getCompoundTagAt(index);
                var type = StrictNbt.requireId(component, "Type");
                if (type.equals(ownershipType)) {
                    if (!PersistentMachineIdentity.decode(StrictNbt.requireCompound(component, "Data")).equals(identity))
                        throw new IOException("Saved database physical ownership differs");
                    ownership++;
                }
                if (type.equals(storageType)) {
                    if (!StrictNbt.requireCompound(component, "Data").equals(expected))
                        throw new IOException("Saved database contents or complete import receipts differ");
                    storage++;
                }
            }
            if (ownership != 1 || storage != 1)
                throw new IOException("Saved database requires one ownership and storage binding");
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Saved database tile envelope is malformed", malformed);
        }
    }

    private static boolean hasDatabaseIdentity(NBTTagCompound tile, PersistentMachineIdentity identity, ResourceLocation ownershipType) {
        var components = tile.getCompoundTag("Machine").getCompoundTag("Runtime").getTagList("Components", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < components.tagCount(); index++) {
            var component = components.getCompoundTagAt(index);
            if (!ownershipType.toString().equals(component.getString("Type"))) continue;
            var data = component.getCompoundTag("Data");
            if (data.hasKey("machineMost", Constants.NBT.TAG_LONG) && data.hasKey("machineLeast", Constants.NBT.TAG_LONG)
                && data.getLong("machineMost") == identity.machineId().getMostSignificantBits()
                && data.getLong("machineLeast") == identity.machineId().getLeastSignificantBits()) return true;
        }
        return false;
    }
}
