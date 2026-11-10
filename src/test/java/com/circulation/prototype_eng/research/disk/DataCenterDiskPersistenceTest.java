package com.circulation.prototype_eng.research.disk;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCommit;
import com.circulation.prototype_eng.research.DataCenterStorageNbt;
import com.circulation.prototype_eng.research.ResearchDataCenter;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.storage.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Actual native Anvil file readback without bootstrapping a server, tile registry or player.
 */
class DataCenterDiskPersistenceTest {
    private static final ResourceLocation MACHINE = id("machine");
    private static final ResourceLocation STORAGE = id("storage");
    private static final ResourceLocation OWNERSHIP = id("ownership");
    private static final ResourceLocation LOOP = id("loop");
    private static final MachineIdentity LOCATION = new MachineIdentity(MACHINE, -1, new BlockPos(-17, 65, 33));
    private static final PersistentMachineIdentity IDENTITY = new PersistentMachineIdentity(new UUID(0, 1), new UUID(0, 2));
    private static final String NATIVE_ID = "disk_file_test:controller";
    @TempDir
    Path directory;

    private static ResourceLocation id(String path) {
        return new ResourceLocation("disk_file_test", path);
    }

    private static ResearchDataCenter.State committed() {
        var before = new DataCenterResearchSnapshot(IDENTITY.machineId(), 1, 2, 0, Object2IntMaps.emptyMap());
        var after = new DataCenterResearchSnapshot(IDENTITY.machineId(), 1, 2, 0, Object2IntMaps.singleton(LOOP, 5));
        var receipt = new ResearchDiskCommit(new UUID(0, 3), IDENTITY.ownerId(), "team", 1, after.storedResearch(), before, after);
        return new ResearchDataCenter.State(after, Map.of(receipt.operationId(), receipt));
    }

    private static NBTTagCompound component(ResourceLocation type, NBTTagCompound data) {
        var component = new NBTTagCompound();
        component.setString("Type", type.toString());
        component.setTag("Data", data.copy());
        return component;
    }

    private static NBTTagCompound tile(NBTTagCompound storage) {
        var tile = new NBTTagCompound();
        tile.setString("id", NATIVE_ID);
        tile.setInteger("x", LOCATION.controllerPos().getX());
        tile.setInteger("y", LOCATION.controllerPos().getY());
        tile.setInteger("z", LOCATION.controllerPos().getZ());
        var components = new NBTTagList();
        components.appendTag(component(OWNERSHIP, IDENTITY.encode()));
        components.appendTag(component(STORAGE, storage));
        var runtime = new NBTTagCompound();
        runtime.setInteger("Version", 2);
        runtime.setString("Type", MACHINE.toString());
        runtime.setTag("Components", components);
        var envelope = new NBTTagCompound();
        envelope.setInteger("Version", 1);
        envelope.setString("Type", MACHINE.toString());
        envelope.setTag("Runtime", runtime);
        tile.setTag("Machine", envelope);
        return tile;
    }

    private void verify(NBTTagCompound expected) throws IOException {
        DataCenterDiskPersistence.verify(directory, LOCATION, IDENTITY, STORAGE, OWNERSHIP, NATIVE_ID, expected);
    }

    @Test
    void requiresCurrentContentsAndExactReceiptFromTheSameSavedTileBoundary() throws IOException {
        var state = committed();
        var original = DataCenterStorageNbt.encodeState(state);
        writeChunk(tile(original));
        verify(original);
        var later = new Object2IntOpenHashMap<>(state.contents().storedResearch());
        later.put(LOOP, 7);
        var current = DataCenterStorageNbt.encodeState(new ResearchDataCenter.State(new DataCenterResearchSnapshot(IDENTITY.machineId(), 1, 2, 1, later), state.receipts()));
        assertThrows(IOException.class, () -> verify(current));
        writeChunk(tile(current));
        verify(current);
        var withoutReceipt = DataCenterStorageNbt.encode(new DataCenterResearchSnapshot(IDENTITY.machineId(), 1, 2, 0, later));
        writeChunk(tile(withoutReceipt));
        assertThrows(IOException.class, () -> verify(current));
        verify(withoutReceipt);
    }

    @Test
    void rejectsChangedOwnershipNativeHostPositionAndComponentBinding() throws IOException {
        var expected = DataCenterStorageNbt.encodeState(committed());
        var wrongOwner = tile(expected);
        wrongOwner.getCompoundTag("Machine").getCompoundTag("Runtime").getTagList("Components", 10).getCompoundTagAt(0)
                  .setTag("Data", new PersistentMachineIdentity(IDENTITY.machineId(), new UUID(0, 9)).encode());
        writeChunk(wrongOwner);
        assertThrows(IOException.class, () -> verify(expected));
        var wrongNative = tile(expected);
        wrongNative.setString("id", "disk_file_test:replacement");
        writeChunk(wrongNative);
        assertThrows(IOException.class, () -> verify(expected));
        var moved = tile(expected);
        moved.setInteger("y", 66);
        writeChunk(moved);
        assertThrows(IOException.class, () -> verify(expected));
        var missingStorage = tile(expected);
        missingStorage.getCompoundTag("Machine").getCompoundTag("Runtime").getTagList("Components", 10).removeTag(1);
        writeChunk(missingStorage);
        assertThrows(IOException.class, () -> verify(expected));
    }

    @Test
    void rejectsCopiedPhysicalIdentityAndDuplicateStorageReceiptsInTheSameChunk() throws IOException {
        var expected = DataCenterStorageNbt.encodeState(committed());
        var copy = tile(expected);
        copy.setInteger("x", -18);
        writeChunk(tile(expected), copy);
        assertThrows(IOException.class, () -> verify(expected));
        var duplicate = tile(expected);
        duplicate.getCompoundTag("Machine").getCompoundTag("Runtime").getTagList("Components", 10)
                 .appendTag(component(STORAGE, expected));
        writeChunk(duplicate);
        assertThrows(IOException.class, () -> verify(expected));
    }

    @Test
    void missingWrongWorldAndMalformedRegionNeverBecomeDurableEvidence() throws IOException {
        var expected = DataCenterStorageNbt.encodeState(committed());
        assertThrows(IOException.class, () -> verify(expected));
        writeChunk(tile(expected));
        assertThrows(IOException.class, () -> DataCenterDiskPersistence.verify(directory.resolve("DIM1"), LOCATION, IDENTITY,
            STORAGE, OWNERSHIP, NATIVE_ID, expected));
        var file = directory.resolve("region/r.-1.0.mca");
        Files.writeString(file, "broken region");
        assertThrows(IOException.class, () -> verify(expected));
    }

    private void writeChunk(NBTTagCompound... tiles) throws IOException {
        int x = LOCATION.controllerPos().getX() >> 4;
        int z = LOCATION.controllerPos().getZ() >> 4;
        var level = new NBTTagCompound();
        level.setInteger("xPos", x);
        level.setInteger("zPos", z);
        var list = new NBTTagList();
        for (var tile : tiles) list.appendTag(tile.copy());
        level.setTag("TileEntities", list);
        var root = new NBTTagCompound();
        root.setTag("Level", level);
        var folder = directory.resolve("region");
        Files.createDirectories(folder);
        var region = new RegionFile(folder.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca").toFile());
        try {
            try (var output = Objects.requireNonNull(region.getChunkDataOutputStream(x & 31, z & 31))) {
                CompressedStreamTools.write(root, output);
            }
        } finally {
            region.close();
        }
    }
}
