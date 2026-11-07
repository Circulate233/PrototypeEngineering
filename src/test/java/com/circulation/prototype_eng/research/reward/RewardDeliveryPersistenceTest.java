package com.circulation.prototype_eng.research.reward;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Objects;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Actual vanilla NBT/region IO, without creating players, item registry entries or a server. */
class RewardDeliveryPersistenceTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID ENTITY = new UUID(0, 2);
    @TempDir Path directory;

    @Test void verifiesTheSavedPlayerLeafIncludingSlotIndependentPayload() throws IOException {
        var expected = leaf();
        var entry = expected.copy();
        entry.setByte("Slot", (byte) 31);
        var file = savePlayer(PLAYER, entry);
        RewardDeliveryPersistence.verifyPlayer(file, PLAYER, expected);
    }

    @Test void rejectsMissingDuplicateAndChangedPlayerEvidence() {
        var expected = leaf();
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(savePlayer(PLAYER), PLAYER, expected));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(savePlayer(PLAYER, expected, expected), PLAYER, expected));
        var changed = expected.copy();
        changed.setByte("Count", (byte) 3);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(savePlayer(PLAYER, changed), PLAYER, expected));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(savePlayer(ENTITY, expected), PLAYER, expected));
    }

    @Test void playerReadFailureCannotTurnIntoConfirmedDelivery() throws IOException {
        var missing = directory.resolve("missing.dat");
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(missing, PLAYER, leaf()));
        assertFalse(Files.exists(missing));
        var invalid = directory.resolve("invalid.dat");
        Files.writeString(invalid, "broken compressed NBT");
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(invalid, PLAYER, leaf()));
    }

    @Test void rejectsARewardOverwrittenByAnUntaggedEntryAtTheSameVanillaSlot() throws IOException {
        var reward = leaf();
        reward.setByte("Slot", (byte) 31);
        var ordinary = leaf();
        ordinary.removeTag("tag");
        ordinary.setByte("Slot", (byte) 31);
        var file = savePlayer(PLAYER, reward, ordinary);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayer(file, PLAYER, leaf()));
    }

    @Test void verifiesIntegratedHostFromLevelDataPlayer() throws IOException {
        var file = saveHost(PLAYER, leaf());
        RewardDeliveryPersistence.verifyHost(file, PLAYER, leaf());
        // Host data has a distinct root; a correct UUID player file is not the host recovery source.
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyHost(savePlayer(PLAYER, leaf()), PLAYER, leaf()));
    }

    @Test void rejectsStaleOrMissingHostEvidenceDespiteCorrectPlayerFile() throws IOException {
        RewardDeliveryPersistence.verifyPlayer(savePlayer(PLAYER, leaf()), PLAYER, leaf());
        var host = saveHost(PLAYER);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyHost(host, PLAYER, leaf()));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyHost(saveHost(ENTITY, leaf()), PLAYER, leaf()));
    }

    @Test void verifiesActualRegionFileAtNegativeChunkCoordinates() throws IOException {
        writeChunk(-33, 65, entity(ENTITY, leaf()));
        RewardDeliveryPersistence.verifyDrop(directory, -33, 65, ENTITY, leaf(), protection());
    }

    @Test void rejectsWrongChunkEntityDuplicateAndPayload() throws IOException {
        writeChunk(2, 3, entity(ENTITY, leaf()), entity(ENTITY, leaf()));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
        writeChunk(2, 3, entity(PLAYER, leaf()));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
        var changed = leaf();
        changed.setShort("Damage", (short) 1);
        writeChunk(2, 3, entity(ENTITY, changed));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 3, 3, ENTITY, leaf(), protection()));
    }

    @Test void missingOrMalformedRegionVerificationDoesNotCreateStorage() throws IOException {
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 0, 0, ENTITY, leaf(), protection()));
        assertFalse(Files.exists(directory.resolve("region")));
        Files.createDirectories(directory.resolve("region"));
        var file = directory.resolve("region/r.0.0.mca");
        Files.writeString(file, "broken region");
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 0, 0, ENTITY, leaf(), protection()));
        assertEquals("broken region", Files.readString(file));
    }

    @Test void rejectsAnEntityThatCannotReloadAsAProtectedItem() throws IOException {
        var entity = entity(ENTITY, leaf());
        entity.setString("id", "minecraft:pig");
        writeChunk(2, 3, entity);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
        entity = entity(ENTITY, leaf());
        entity.removeTag("Age");
        writeChunk(2, 3, entity);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
        entity = entity(ENTITY, leaf());
        entity.removeTag("ForgeData");
        writeChunk(2, 3, entity);
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyDrop(directory, 2, 3, ENTITY, leaf(), protection()));
    }

    private Path savePlayer(UUID player, NBTTagCompound... stacks) throws IOException {
        var saved = playerData(player, stacks);
        var file = directory.resolve("player.dat");
        try (var output = Files.newOutputStream(file)) { CompressedStreamTools.writeCompressed(saved, output); }
        return file;
    }

    private Path saveHost(UUID player, NBTTagCompound... stacks) throws IOException {
        var data = new NBTTagCompound();
        data.setTag("Player", playerData(player, stacks));
        var saved = new NBTTagCompound();
        saved.setTag("Data", data);
        var file = directory.resolve("level.dat");
        try (var output = Files.newOutputStream(file)) { CompressedStreamTools.writeCompressed(saved, output); }
        return file;
    }

    private static NBTTagCompound playerData(UUID player, NBTTagCompound... stacks) {
        var saved = new NBTTagCompound();
        saved.setUniqueId("UUID", player);
        var inventory = new NBTTagList();
        for (int index = 0; index < stacks.length; index++) {
            var stack = stacks[index].copy();
            if (!stack.hasKey("Slot")) stack.setByte("Slot", (byte) index);
            inventory.appendTag(stack);
        }
        saved.setTag("Inventory", inventory);
        return saved;
    }

    private void writeChunk(int x, int z, NBTTagCompound... entities) throws IOException {
        var level = new NBTTagCompound();
        level.setInteger("xPos", x);
        level.setInteger("zPos", z);
        var list = new NBTTagList();
        for (var entity : entities) list.appendTag(entity.copy());
        level.setTag("Entities", list);
        var saved = new NBTTagCompound();
        saved.setTag("Level", level);
        var folder = directory.resolve("region");
        Files.createDirectories(folder);
        var region = new RegionFile(folder.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca").toFile());
        try {
            try (var output = Objects.requireNonNull(region.getChunkDataOutputStream(x & 31, z & 31))) { CompressedStreamTools.write(saved, output); }
        } finally { region.close(); }
    }

    private static NBTTagCompound entity(UUID id, NBTTagCompound item) {
        var entity = new NBTTagCompound();
        entity.setUniqueId("UUID", id);
        entity.setString("id", "minecraft:item");
        entity.setTag("Item", item.copy());
        entity.setShort("Age", Short.MIN_VALUE);
        entity.setShort("PickupDelay", Short.MAX_VALUE);
        entity.setBoolean("Invulnerable", true);
        var data = new NBTTagCompound();
        data.setTag("PrototypeEngineeringRewardProtection", protection());
        entity.setTag("ForgeData", data);
        return entity;
    }

    private static NBTTagCompound protection() {
        var value = new NBTTagCompound();
        value.setString("Fragment", ENTITY.toString());
        value.setShort("Age", (short) 0);
        value.setShort("PickupDelay", (short) 0);
        value.setBoolean("Invulnerable", false);
        return value;
    }

    private static NBTTagCompound leaf() {
        var stack = new NBTTagCompound();
        stack.setString("id", "test:reward");
        stack.setByte("Count", (byte) 2);
        stack.setShort("Damage", (short) 0);
        var identity = new NBTTagCompound();
        identity.setString("Fragment", ENTITY.toString());
        var business = new NBTTagCompound();
        business.setString("business", "preserved");
        business.setTag(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG, identity);
        stack.setTag("tag", business);
        return stack;
    }
}
