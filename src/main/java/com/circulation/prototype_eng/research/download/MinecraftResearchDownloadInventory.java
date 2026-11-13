package com.circulation.prototype_eng.research.download;

import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadInventory;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadMaterial;
import com.circulation.prototype_eng.research.MaterialInventoryEvidence;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Real server-player single-slot material writes, backed by exact vanilla player-file evidence.
 */
public final class MinecraftResearchDownloadInventory implements ResearchDownloadInventory {
    static final String STORE = "download_material_receipts";
    private static final String NAMESPACE = "prototype_eng";
    private final EntityPlayerMP player;
    private final InventoryPlayer inventory;
    private final MinecraftServer server;

    public MinecraftResearchDownloadInventory(EntityPlayerMP player) {
        this.player = Objects.requireNonNull(player);
        inventory = Objects.requireNonNull(player.inventory);
        server = Objects.requireNonNull(player.getServerWorld().getMinecraftServer());
        requireOwner();
    }

    /**
     * A pending receipt prevents a second download from consuming the same operation.
     */
    public static boolean hasPending(EntityPlayerMP player) {
        var data = Objects.requireNonNull(player).getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) return false;
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, Constants.NBT.TAG_COMPOUND)) return true;
        var persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(NAMESPACE)) return false;
        if (!persisted.hasKey(NAMESPACE, Constants.NBT.TAG_COMPOUND)) return true;
        return persisted.getCompoundTag(NAMESPACE).hasKey(STORE);
    }

    static Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadMaterial> readReceipts(NBTTagCompound data, UUID playerId) {
        var receipts = new Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadMaterial>();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) return receipts;
        var persisted = StrictNbt.requireCompound(data, EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(NAMESPACE)) return receipts;
        var namespace = StrictNbt.requireCompound(persisted, NAMESPACE);
        if (!namespace.hasKey(STORE)) return receipts;
        var store = StrictNbt.requireCompound(namespace, STORE);
        StrictNbt.requireKeys(store, "version", "receipts");
        if (StrictNbt.requireInt(store, "version") != 1)
            throw new IllegalArgumentException("Unknown download material store version");
        var list = StrictNbt.requireList(store, "receipts", Constants.NBT.TAG_COMPOUND);
        if (list.tagCount() != 1)
            throw new IllegalArgumentException("Download material store must own exactly one player inventory operation");
        var receipt = ResearchDownloadMaterial.CODEC.decode(list.getCompoundTagAt(0));
        if (!Objects.requireNonNull(playerId).equals(receipt.playerId()))
            throw new IllegalArgumentException("Download material receipt belongs to another player");
        receipts.put(receipt.operationId(), receipt);
        return receipts;
    }

    private static void requireCommitted(ResearchDownloadMaterial receipt) {
        if (receipt.status() != ResearchDownloadMaterial.Status.COMMITTED) {
            throw new IllegalStateException("Download material receipt is not committed");
        }
    }

    @Override
    public UUID playerId() {
        requireOwner();
        return player.getUniqueID();
    }

    @Override
    public @Nullable ResearchDownloadMaterial capture(UUID operationId, int slot) {
        requireOwner();
        Objects.requireNonNull(operationId);
        PlayerInventoryNbt.encodedSlot(slot);
        if (hasPending(player)) return null;
        var before = PlayerInventoryNbt.capture(inventory);
        var captured = ResearchDownloadMaterial.prepare(player.getUniqueID(), operationId, slot, before);
        if (!matches(before)) throw new IllegalStateException("Download inventory changed during capture");
        return captured;
    }

    @Override
    public @Nullable ResearchDownloadMaterial find(UUID operationId) {
        requireOwner();
        return readReceipts(player.getEntityData(), player.getUniqueID()).get(Objects.requireNonNull(operationId));
    }

    @Override
    public ObjectList<ResearchDownloadMaterial> operations() {
        requireOwner();
        return ObjectLists.unmodifiable(new ObjectArrayList<>(readReceipts(player.getEntityData(), player.getUniqueID()).values()));
    }

    @Override
    public boolean matches(NBTTagCompound image) {
        requireOwner();
        PlayerInventoryNbt.validate(image);
        return image.equals(PlayerInventoryNbt.capture(inventory));
    }

    @Override
    public void recordPrepared(ResearchDownloadMaterial receipt) {
        requireOwner();
        requirePlayer(receipt);
        if (receipt.status() != ResearchDownloadMaterial.Status.PREPARED || !matches(receipt.inventoryBefore())) {
            throw new IllegalStateException("Download preparation lacks its exact before inventory proof");
        }
        var receipts = readReceipts(player.getEntityData(), player.getUniqueID());
        var existing = receipts.get(receipt.operationId());
        if (existing != null) {
            if (!existing.equals(receipt))
                throw new IllegalStateException("Download operation already has different material evidence");
        } else {
            if (!receipts.isEmpty())
                throw new IllegalStateException("Another download receipt already owns this player inventory");
            receipts.put(receipt.operationId(), receipt);
        }
        publishAndSave(receipts);
    }

    @Override
    public boolean consume(ResearchDownloadMaterial requested) {
        requireOwner();
        requirePlayer(requested);
        var receipts = readReceipts(player.getEntityData(), player.getUniqueID());
        var current = requireReceipt(receipts, requested.operationId());
        if (!current.sameOperation(requested))
            throw new IllegalStateException("Download consumption does not own the recorded material operation");
        if (current.status() == ResearchDownloadMaterial.Status.COMMITTED) {
            requireCommitted(current);
            publishAndSave(receipts);
            return true;
        }
        // Across calls/restarts, a PREPARED after image is only a candidate, never proof that we wrote it.
        if (requested.status() != ResearchDownloadMaterial.Status.PREPARED || !matches(current.inventoryBefore())) {
            throw new IllegalStateException("Prepared download inventory is unknown or has lost its exact before proof");
        }
        int slot = current.slot();
        var after = inventory.getStackInSlot(slot).copy();
        after.shrink(1);
        var nativeAfter = after.isEmpty() ? new NBTTagCompound() : after.writeToNBT(new NBTTagCompound());
        if (!nativeAfter.equals(PlayerInventoryNbt.stack(current.inventoryAfter(), slot))) {
            throw new IllegalStateException("Native material planning differs from the frozen download after proof");
        }
        if (!matches(current.inventoryBefore()))
            throw new IllegalStateException("Download inventory changed during material preflight");
        var committed = current.committed();
        requireOwner();
        try {
            inventory.setInventorySlotContents(slot, after);
            if (!matches(committed.inventoryAfter()))
                throw new IllegalStateException("Download material write differs from its complete after proof");
        } catch (RuntimeException | Error failure) {
            // Only this invocation has entered the exact slot write. Its complete after proof may
            // retain the already-paid obligation; other observed mutations remain unknown.
            retainAttemptedCommit(receipts, committed, failure);
            throw failure;
        }
        receipts.put(committed.operationId(), committed);
        // Publish COMMITTED before markDirty/save. A save failure never refunds or authorizes reconsumption.
        publishAndSave(receipts);
        return true;
    }

    @Override
    public void persistCommitted(UUID operationId) {
        requireOwner();
        var receipts = readReceipts(player.getEntityData(), player.getUniqueID());
        var receipt = requireReceipt(receipts, operationId);
        requireCommitted(receipt);
        publishAndSave(receipts);
    }

    @Override
    public void removePrepared(UUID operationId) {
        remove(operationId, ResearchDownloadMaterial.Status.PREPARED);
    }

    @Override
    public void removeCommitted(UUID operationId) {
        remove(operationId, ResearchDownloadMaterial.Status.COMMITTED);
    }

    private void remove(UUID operationId, ResearchDownloadMaterial.Status status) {
        requireOwner();
        Objects.requireNonNull(operationId);
        var receipts = readReceipts(player.getEntityData(), player.getUniqueID());
        var receipt = receipts.get(operationId);
        if (receipt != null) {
            if (receipt.status() != status)
                throw new IllegalStateException("Download receipt deletion has the wrong status");
            if (status == ResearchDownloadMaterial.Status.PREPARED && !matches(receipt.inventoryBefore())) {
                throw new IllegalStateException("Download abort has lost its exact before inventory proof");
            }
            receipts.remove(operationId);
        }
        // The world owns SOURCES_COMMITTED before committed cleanup; current inventory is saved as-is.
        // Missing retries persist the same deletion, including after a failed player/host write.
        publishAndSave(receipts);
    }

    private void retainAttemptedCommit(Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadMaterial> receipts,
                                       ResearchDownloadMaterial committed, Throwable original) {
        try {
            if (!matches(committed.inventoryAfter())) return;
            receipts.put(committed.operationId(), committed);
            publishAndSave(receipts);
        } catch (RuntimeException | Error recoveryFailure) {
            if (recoveryFailure != original) original.addSuppressed(recoveryFailure);
        }
    }

    private void publishAndSave(Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadMaterial> receipts) {
        var list = new NBTTagList();
        for (var receipt : receipts.values()) list.appendTag(ResearchDownloadMaterial.CODEC.encode(receipt));
        var store = new NBTTagCompound();
        store.setInteger("version", 1);
        store.setTag("receipts", list);
        var data = player.getEntityData();
        var persisted = data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)
            ? StrictNbt.requireCompound(data, EntityPlayer.PERSISTED_NBT_TAG).copy() : new NBTTagCompound();
        var namespace = persisted.hasKey(NAMESPACE) ? StrictNbt.requireCompound(persisted, NAMESPACE).copy() : new NBTTagCompound();
        if (receipts.isEmpty()) namespace.removeTag(STORE);
        else namespace.setTag(STORE, store);
        persisted.setTag(NAMESPACE, namespace);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        requireOwner();
        inventory.markDirty();
        var expectedInventory = PlayerInventoryNbt.capture(inventory);
        var expectedReceipts = receipts.isEmpty() ? new NBTTagCompound() : store;
        PlayerDataPersistence.persist(player, this::requireOwner,
            saved -> MaterialInventoryEvidence.verify(saved, expectedInventory, STORE, expectedReceipts));
        if (!expectedInventory.equals(PlayerInventoryNbt.capture(inventory)) || !receipts.equals(readReceipts(player.getEntityData(), player.getUniqueID()))) {
            throw new IllegalStateException("Download material evidence changed during its player persistence barrier");
        }
    }

    private ResearchDownloadMaterial requireReceipt(Object2ObjectLinkedOpenHashMap<UUID, ResearchDownloadMaterial> receipts, UUID operationId) {
        var receipt = receipts.get(Objects.requireNonNull(operationId));
        if (receipt == null) throw new IllegalStateException("Missing download material receipt");
        requirePlayer(receipt);
        return receipt;
    }

    private void requirePlayer(ResearchDownloadMaterial receipt) {
        if (!player.getUniqueID().equals(Objects.requireNonNull(receipt).playerId()))
            throw new IllegalArgumentException("Download receipt belongs to another player");
    }

    private void requireOwner() {
        var world = player.getServerWorld();
        if (player instanceof FakePlayer || world.getMinecraftServer() != server
            || !server.isCallingFromMinecraftThread() || player.inventory != inventory
            || server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {
            throw new IllegalStateException("Download materials require the exact real online player on its server owner thread");
        }
    }
}
