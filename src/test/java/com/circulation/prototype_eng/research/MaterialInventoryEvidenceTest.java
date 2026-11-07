package com.circulation.prototype_eng.research;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialInventoryEvidenceTest {
    private static final UUID PLAYER = new UUID(0, 31);
    private static final String STORE = "material_receipts";
    @TempDir
    Path directory;

    private static void verify(Path file, boolean host, NBTTagCompound inventory, NBTTagCompound receipt) throws IOException {
        MaterialInventoryEvidence.verify(PlayerDataPersistence.readPlayer(file, PLAYER, host), inventory, STORE, receipt);
    }

    private static NBTTagCompound receipt(String state) {
        var result = new NBTTagCompound();
        result.setString("operation", "00000000-0000-0000-0000-000000000051");
        result.setString("state", state);
        return result;
    }

    private static NBTTagCompound inventory(int count) {
        var stack = new NBTTagCompound();
        stack.setString("id", "test:material");
        stack.setByte("Count", (byte) count);
        stack.setByte("Slot", (byte) 0);
        var caps = new NBTTagCompound();
        caps.setInteger("stored", 7);
        stack.setTag("ForgeCaps", caps);
        var items = new NBTTagList();
        items.appendTag(stack);
        var result = new NBTTagCompound();
        result.setTag("inventory", items);
        result.setInteger("selectedSlot", 0);
        return result;
    }

    private static NBTTagCompound player(NBTTagCompound inventory, NBTTagCompound receipt) {
        var result = new NBTTagCompound();
        result.setUniqueId("UUID", PLAYER);
        result.setTag("Inventory", inventory.getTag("inventory").copy());
        result.setInteger("SelectedItemSlot", inventory.getInteger("selectedSlot"));
        var namespace = new NBTTagCompound();
        if (!receipt.isEmpty()) namespace.setTag(STORE, receipt.copy());
        var persisted = new NBTTagCompound();
        persisted.setTag("prototype_eng", namespace);
        var forge = new NBTTagCompound();
        forge.setTag("PlayerPersisted", persisted);
        result.setTag("ForgeData", forge);
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
    void bothPlayerAndHostFilesMustContainTheSameCommittedMaterialsAndReceipt() throws IOException {
        var receipt = receipt("COMMITTED");
        var inventory = inventory(1);
        var playerFile = directory.resolve("player.dat");
        var hostFile = directory.resolve("level.dat");
        var player = player(inventory, receipt);
        write(playerFile, player);
        write(hostFile, host(player));
        verify(playerFile, false, inventory, receipt);
        verify(hostFile, true, inventory, receipt);

        // The standalone player save succeeded, but the integrated host still contains its old inventory.
        write(hostFile, host(player(inventory(2), receipt)));
        verify(playerFile, false, inventory, receipt);
        assertThrows(IllegalStateException.class, () -> verify(hostFile, true, inventory, receipt));

        // Matching item counts alone do not prove which operation consumed them.
        write(hostFile, host(player(inventory, receipt("PREPARED"))));
        assertThrows(IllegalStateException.class, () -> verify(hostFile, true, inventory, receipt));
    }

    @Test
    void savedNativePayloadSelectedSlotAndReceiptRemovalAllParticipateInSettlement() throws IOException {
        var file = directory.resolve("player.dat");
        var inventory = inventory(1);
        var receipt = receipt("SETTLEMENT_CONFIRMED");
        var changed = inventory.copy();
        changed.getTagList("inventory", 10).getCompoundTagAt(0).getCompoundTag("ForgeCaps").setInteger("stored", 9);
        write(file, player(changed, receipt));
        assertThrows(IllegalStateException.class, () -> verify(file, false, inventory, receipt));
        changed = inventory.copy();
        changed.setInteger("selectedSlot", 2);
        write(file, player(changed, receipt));
        assertThrows(IllegalStateException.class, () -> verify(file, false, inventory, receipt));

        write(file, player(inventory, receipt));
        assertThrows(IllegalStateException.class, () -> verify(file, false, inventory, new NBTTagCompound()));
        write(file, player(inventory, new NBTTagCompound()));
        verify(file, false, inventory, new NBTTagCompound());
    }

    @Test
    void missingCorruptOrForeignPlayerFilesNeverBecomeConfirmation() throws IOException {
        var file = directory.resolve("player.dat");
        assertThrows(IOException.class, () -> PlayerDataPersistence.readPlayer(file, PLAYER, false));
        assertFalse(Files.exists(file));
        Files.writeString(file, "unfinished player save");
        assertThrows(IOException.class, () -> PlayerDataPersistence.readPlayer(file, PLAYER, false));
        var foreign = player(inventory(1), receipt("COMMITTED"));
        foreign.setUniqueId("UUID", new UUID(0, 99));
        write(file, foreign);
        assertThrows(IOException.class, () -> PlayerDataPersistence.readPlayer(file, PLAYER, false));
    }
}
