package com.circulation.prototype_eng.research.download;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.research.MaterialInventoryEvidence;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Actual download store decoding and player/host file evidence without a game or server bootstrap.
 */
class ResearchDownloadInventoryEvidenceTest {
    private static final UUID PLAYER = new UUID(0, 61);
    private static final UUID OPERATION = new UUID(0, 62);
    @TempDir
    Path directory;

    private static void verify(Path file, boolean host, NBTTagCompound inventory, NBTTagCompound store) throws IOException {
        MaterialInventoryEvidence.verify(PlayerDataPersistence.readPlayer(file, PLAYER, host), inventory, MinecraftResearchDownloadInventory.STORE, store);
    }

    private static ResearchDownloadMaterial prepared() {
        var stack = new NBTTagCompound();
        stack.setString("id", "download_inventory:material");
        stack.setByte("Count", (byte) 3);
        stack.setShort("Damage", (short) 4);
        stack.setByte("Slot", (byte) PlayerInventoryNbt.encodedSlot(5));
        var caps = new NBTTagCompound();
        caps.setInteger("stored", 42);
        stack.setTag("ForgeCaps", caps);
        var list = new NBTTagList();
        list.appendTag(stack);
        var image = new NBTTagCompound();
        image.setTag("inventory", list);
        image.setInteger("selectedSlot", 5);
        return Objects.requireNonNull(ResearchDownloadMaterial.prepare(PLAYER, OPERATION, 5, image));
    }

    private static NBTTagCompound store(ResearchDownloadMaterial receipt) {
        var result = new NBTTagCompound();
        result.setInteger("version", 1);
        var list = new NBTTagList();
        list.appendTag(ResearchDownloadMaterial.CODEC.encode(receipt));
        result.setTag("receipts", list);
        return result;
    }

    private static NBTTagCompound receiptStore(NBTTagCompound data) {
        return data.getCompoundTag("PlayerPersisted").getCompoundTag("prototype_eng").getCompoundTag(MinecraftResearchDownloadInventory.STORE);
    }

    private static NBTTagCompound player(NBTTagCompound inventory, NBTTagCompound store) {
        var result = new NBTTagCompound();
        result.setUniqueId("UUID", PLAYER);
        result.setTag("Inventory", inventory.getTag("inventory").copy());
        result.setInteger("SelectedItemSlot", inventory.getInteger("selectedSlot"));
        var namespace = new NBTTagCompound();
        if (!store.isEmpty()) namespace.setTag(MinecraftResearchDownloadInventory.STORE, store.copy());
        var persisted = new NBTTagCompound();
        persisted.setTag("prototype_eng", namespace);
        var data = new NBTTagCompound();
        data.setTag("PlayerPersisted", persisted);
        result.setTag("ForgeData", data);
        return result;
    }

    private static NBTTagCompound host(NBTTagCompound player) {
        var data = new NBTTagCompound();
        data.setTag("Player", player);
        var result = new NBTTagCompound();
        result.setTag("Data", data);
        return result;
    }

    private static void write(Path file, NBTTagCompound value) throws IOException {
        try (var output = Files.newOutputStream(file)) {
            CompressedStreamTools.writeCompressed(value, output);
        }
    }

    @Test
    void bothAuthorityFilesMustContainTheSamePaidSlotAndCommittedDownloadReceipt() throws IOException {
        var prepared = prepared();
        var committed = prepared.committed();
        var store = store(committed);
        var playerFile = directory.resolve("player.dat");
        var hostFile = directory.resolve("level.dat");
        write(playerFile, player(committed.inventoryAfter(), store));
        write(hostFile, host(player(committed.inventoryAfter(), store)));
        verify(playerFile, false, committed.inventoryAfter(), store);
        verify(hostFile, true, committed.inventoryAfter(), store);
        var saved = PlayerDataPersistence.readPlayer(playerFile, PLAYER, false);
        assertEquals(committed, MinecraftResearchDownloadInventory.readReceipts(saved.getCompoundTag("ForgeData"), PLAYER).get(OPERATION));

        write(hostFile, host(player(prepared.inventoryBefore(), store)));
        assertThrows(IllegalStateException.class, () -> verify(hostFile, true, committed.inventoryAfter(), store));
        write(hostFile, host(player(committed.inventoryAfter(), store(prepared))));
        assertThrows(IllegalStateException.class, () -> verify(hostFile, true, committed.inventoryAfter(), store));
    }

    @Test
    void cleanupVerifiesCurrentInventoryAndReceiptRemovalWithoutRequiringOldAfterForever() throws IOException {
        var committed = prepared().committed();
        var currentInventory = committed.inventoryAfter().copy();
        currentInventory.getTagList("inventory", 10).getCompoundTagAt(0).getCompoundTag("ForgeCaps").setInteger("stored", 9);
        var file = directory.resolve("player.dat");
        write(file, player(currentInventory, new NBTTagCompound()));
        verify(file, false, currentInventory, new NBTTagCompound());
        // A missing-receipt retry confirms the same current inventory and absence again.
        verify(file, false, currentInventory, new NBTTagCompound());
        assertThrows(IllegalStateException.class, () -> verify(file, false, committed.inventoryAfter(), new NBTTagCompound()));
        write(file, player(currentInventory, store(committed)));
        assertThrows(IllegalStateException.class, () -> verify(file, false, currentInventory, new NBTTagCompound()));
    }

    @Test
    void malformedDuplicateAndForeignOperationStoresCannotBecomeMaterialProof() {
        var committed = prepared().committed();
        var saved = player(committed.inventoryAfter(), store(committed));
        var data = saved.getCompoundTag("ForgeData");
        assertThrows(IllegalArgumentException.class, () -> MinecraftResearchDownloadInventory.readReceipts(data, new UUID(0, 99)));
        var duplicate = data.copy();
        var duplicateStore = receiptStore(duplicate);
        duplicateStore.getTagList("receipts", 10).appendTag(ResearchDownloadMaterial.CODEC.encode(committed));
        assertThrows(IllegalArgumentException.class, () -> MinecraftResearchDownloadInventory.readReceipts(duplicate, PLAYER));
        var malformed = data.copy();
        receiptStore(malformed).setInteger("version", 2);
        assertThrows(IllegalArgumentException.class, () -> MinecraftResearchDownloadInventory.readReceipts(malformed, PLAYER));
        var missingPayload = data.copy();
        receiptStore(missingPayload).setTag("receipts", new NBTTagList());
        assertThrows(IllegalArgumentException.class, () -> MinecraftResearchDownloadInventory.readReceipts(missingPayload, PLAYER));
    }

    @Test
    void missingCorruptForeignOrChangedNativeEvidenceCannotConfirmDownloadConsumption() throws IOException {
        var committed = prepared().committed();
        var file = directory.resolve("player.dat");
        assertThrows(IOException.class, () -> PlayerDataPersistence.readPlayer(file, PLAYER, false));
        assertFalse(Files.exists(file));
        Files.writeString(file, "failed download player save");
        assertThrows(IOException.class, () -> PlayerDataPersistence.readPlayer(file, PLAYER, false));
        var foreign = player(committed.inventoryAfter(), store(committed));
        foreign.setUniqueId("UUID", new UUID(0, 99));
        write(file, foreign);
        assertThrows(IOException.class, () -> verify(file, false, committed.inventoryAfter(), store(committed)));
        var differentSlot = committed.inventoryAfter().copy();
        differentSlot.getTagList("inventory", 10).getCompoundTagAt(0).setByte("Slot", (byte) 8);
        write(file, player(differentSlot, store(committed)));
        assertThrows(IllegalStateException.class, () -> verify(file, false, committed.inventoryAfter(), store(committed)));
        var differentCaps = committed.inventoryAfter().copy();
        differentCaps.getTagList("inventory", 10).getCompoundTagAt(0).getCompoundTag("ForgeCaps").setInteger("stored", 0);
        write(file, player(differentCaps, store(committed)));
        assertThrows(IllegalStateException.class, () -> verify(file, false, committed.inventoryAfter(), store(committed)));
    }
}
