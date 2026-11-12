package com.circulation.prototype_eng.research.manual;

import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.persistence.PlayerInventoryNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchInventory;
import com.circulation.prototype_eng.api.research.manual.ManualResearchMaterialReceipt;
import com.circulation.prototype_eng.api.research.manual.ManualResearchMaterialReceiptNbt;
import com.circulation.prototype_eng.research.MaterialInventoryEvidence;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Synchronous server-player material writes with PREPARED, COMMITTED and confirmed player receipts.
 * Vanilla writer completion is followed by exact player-file and integrated-host evidence checks.
 * An ambiguous inventory or player/world proof remains pending; it is never replayed.
 */
public final class MinecraftManualResearchInventory implements ManualResearchInventory {
    private static final String NAMESPACE = "prototype_eng";
    private static final String STORE = "manual_material_receipts";
    private static final int MAX_RECEIPTS = 1024;
    private final Thread owner = Thread.currentThread();
    private final EntityPlayerMP player;
    private final UUID playerId;
    private final InventoryPlayer inventory;
    private final MinecraftServer server;
    private final IntPredicate protectedSlot;

    public MinecraftManualResearchInventory(EntityPlayerMP player) {
        this(player, _ -> false);
    }

    /**
     * The caller may exclude slots from this research material request.
     */
    public MinecraftManualResearchInventory(EntityPlayerMP player, IntPredicate protectedSlot) {
        this.player = Objects.requireNonNull(player);
        Objects.requireNonNull(protectedSlot);
        this.protectedSlot = protectedSlot;
        playerId = Objects.requireNonNull(player.getUniqueID());
        inventory = Objects.requireNonNull(player.inventory);
        server = Objects.requireNonNull(player.getServerWorld().getMinecraftServer());
        requireOwner();
    }

    private static byte[] imageOf(List<ItemStack> slots, int selectedSlot) {
        var list = new NBTTagList();
        for (int slot = 0; slot < slots.size(); slot++) {
            var stack = slots.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var encoded = stack.writeToNBT(new NBTTagCompound());
            encoded.setByte("Slot", (byte) PlayerInventoryNbt.encodedSlot(slot));
            list.appendTag(encoded);
        }
        var image = new NBTTagCompound();
        image.setTag("inventory", list);
        image.setInteger("selectedSlot", selectedSlot);
        return ManualResearchMaterialReceiptNbt.encodeInventory(image);
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        // Full native tags also cover ForgeCaps; item/count/tag equality alone cannot authorize restoration.
        return left.writeToNBT(new NBTTagCompound()).equals(right.writeToNBT(new NBTTagCompound()));
    }

    @Override
    public UUID playerId() {
        requireOwner();
        return playerId;
    }

    @Override
    public @Nullable ManualResearchMaterialReceipt find(UUID operationId) {
        requireOwner();
        return readReceipts().get(Objects.requireNonNull(operationId));
    }

    @Override
    public @Nullable ManualResearchMaterialReceipt findBySession(UUID sessionId) {
        requireOwner();
        Objects.requireNonNull(sessionId);
        for (var receipt : readReceipts().values()) {
            if (receipt.sessionId().equals(sessionId)) {
                return receipt;
            }
        }
        return null;
    }

    @Override
    public byte[] snapshotInventory() {
        requireOwner();
        return ManualResearchMaterialReceiptNbt.encodeInventory(PlayerInventoryNbt.capture(inventory));
    }

    @Override
    public boolean matches(byte[] image) {
        requireOwner();
        return ManualResearchMaterialReceiptNbt.decodeInventory(image)
                                               .equals(ManualResearchMaterialReceiptNbt.decodeInventory(snapshotInventory()));
    }

    @Override
    public boolean hasMaterials(AEKey2LongMap quantities) {
        requireOwner();
        return planMaterials(captureSlots(), quantities) != null;
    }

    @Override
    public void recordPrepared(ManualResearchMaterialReceipt receipt) {
        requireOwner();
        requirePlayer(receipt);
        if (receipt.status() != ManualResearchMaterialReceipt.Status.PREPARED || !matches(receipt.inventoryBefore())) {
            throw new IllegalStateException("PREPARED receipt must match the complete current inventory");
        }
        var receipts = readReceipts();
        var existing = receipts.get(receipt.operationId());
        if (existing != null) {
            if (!existing.equals(receipt)) {
                throw new IllegalStateException("Material operation already has different evidence");
            }
        } else {
            for (var other : receipts.values()) {
                if (other.sessionId().equals(receipt.sessionId())) {
                    throw new IllegalStateException("Manual session already owns a material receipt");
                }
            }
            if (receipts.size() >= MAX_RECEIPTS) {
                throw new IllegalStateException("Too many outstanding material receipts");
            }
            receipts.put(receipt.operationId(), receipt);
        }
        publishAndSave(receipts);
    }

    @Override
    public boolean consume(ManualResearchMaterialReceipt requested, AEKey2LongMap quantities, long worldTime) {
        requireOwner();
        requirePlayer(requested);
        var receipts = readReceipts();
        var current = receipts.get(requested.operationId());
        if (current == null || !current.sameOperation(requested)) {
            throw new IllegalStateException("Material consumption does not own the recorded operation");
        }
        if (current.status() == ManualResearchMaterialReceipt.Status.COMMITTED) {
            requireAfter(current);
            publishAndSave(receipts);
            return true;
        }
        if (current.status() != ManualResearchMaterialReceipt.Status.PREPARED || requested.status() != current.status()) {
            throw new IllegalStateException("Only PREPARED materials can be consumed");
        }
        if (worldTime < current.preparedAt()) {
            throw new IllegalArgumentException("Material commit time precedes preparation");
        }
        if (!matches(current.inventoryBefore())) {
            throw new IllegalStateException("Inventory differs from PREPARED material proof");
        }
        var before = captureSlots();
        var after = planMaterials(before, quantities);
        if (after == null) {
            return false;
        }
        int selectedSlot = inventory.currentItem;
        var afterImage = imageOf(after, selectedSlot);
        var committed = current.committed(afterImage, worldTime);
        // Recheck after planning, including protection predicates and item serialization callbacks.
        if (!matches(current.inventoryBefore())) {
            throw new IllegalStateException("Inventory changed during material preflight");
        }
        var attempted = new IntOpenHashSet();
        try {
            for (int slot = 0; slot < before.size(); slot++) {
                if (sameStack(before.get(slot), after.get(slot))) {
                    continue;
                }
                requireOwner();
                if (protectedSlot.test(slot)) {
                    throw new IllegalStateException("Material slot became protected before write: " + slot);
                }
                if (!sameStack(inventory.getStackInSlot(slot), before.get(slot))) {
                    throw new IllegalStateException("Material slot changed before write: " + slot);
                }
                attempted.add(slot);
                inventory.setInventorySlotContents(slot, after.get(slot).copy());
            }
            if (!matches(afterImage)) {
                throw new IllegalStateException("Material write did not produce the exact planned inventory");
            }
        } catch (RuntimeException | Error failure) {
            recoverFailedWrite(receipts, committed, before, after, selectedSlot, attempted, failure);
            throw failure;
        }
        receipts.put(committed.operationId(), committed);
        // Publish COMMITTED before markDirty/save; a thrown save keeps evidence and never refunds.
        publishAndSave(receipts);
        return true;
    }

    @Override
    public void persistCommitted(UUID operationId) {
        requireOwner();
        var receipts = readReceipts();
        var receipt = requireReceipt(receipts, operationId);
        if (receipt.status() != ManualResearchMaterialReceipt.Status.COMMITTED) {
            throw new IllegalStateException("Only COMMITTED material receipts can be persisted");
        }
        requireAfter(receipt);
        publishAndSave(receipts);
    }

    @Override
    public void confirmSettlement(UUID operationId, long worldTime) {
        requireOwner();
        var receipts = readReceipts();
        var receipt = requireReceipt(receipts, operationId);
        if (receipt.status() == ManualResearchMaterialReceipt.Status.SETTLEMENT_CONFIRMED) {
            requireAfter(receipt);
            publishAndSave(receipts);
            return;
        }
        requireAfter(receipt);
        receipts.put(operationId, receipt.confirmed(worldTime));
        publishAndSave(receipts);
    }

    @Override
    public void removePrepared(UUID operationId) {
        remove(operationId, ManualResearchMaterialReceipt.Status.PREPARED);
    }

    @Override
    public void removeConfirmed(UUID operationId) {
        remove(operationId, ManualResearchMaterialReceipt.Status.SETTLEMENT_CONFIRMED);
    }

    private void remove(UUID operationId, ManualResearchMaterialReceipt.Status expectedStatus) {
        requireOwner();
        Objects.requireNonNull(operationId);
        var receipts = readReceipts();
        var current = receipts.get(operationId);
        if (current != null) {
            if (current.status() != expectedStatus) {
                throw new IllegalStateException("Material receipt deletion has the wrong status");
            }
            if (expectedStatus == ManualResearchMaterialReceipt.Status.PREPARED) {
                if (!matches(current.inventoryBefore())) {
                    throw new IllegalStateException("Aborted materials no longer match their complete before proof");
                }
            } else {
                requireAfter(current);
            }
            receipts.remove(operationId);
        }
        // Retain the deletion in memory on save failure; missing retries save the same current table.
        publishAndSave(receipts);
    }

    private @Nullable ObjectArrayList<ItemStack> planMaterials(List<ItemStack> before, AEKey2LongMap quantities) {
        Objects.requireNonNull(quantities);
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("Manual material quantities must not be empty");
        }
        var remaining = new AEKeyLinkedOpenHashMap(quantities.size());
        for (var entry : quantities.object2LongEntrySet()) {
            if (!(entry.getKey() instanceof AEItemKey) || entry.getLongValue() <= 0) {
                throw new IllegalArgumentException("Manual materials require positive AEItemKey quantities");
            }
            remaining.addTo(entry.getKey(), entry.getLongValue());
        }
        var after = new ObjectArrayList<ItemStack>(before.size());
        for (int slot = 0; slot < before.size(); slot++) {
            var stack = before.get(slot).copy();
            if (!stack.isEmpty() && !protectedSlot.test(slot)) {
                var key = Objects.requireNonNull(AEItemKey.of(stack));
                long needed = remaining.getLong(key);
                int taken = (int) Math.min(needed, stack.getCount());
                if (taken > 0) {
                    stack.shrink(taken);
                    remaining.addTo(key, -taken);
                    if (needed == taken) {
                        remaining.removeLong(key);
                    }
                }
            }
            after.add(stack);
        }
        return remaining.isEmpty() ? after : null;
    }

    private ObjectArrayList<ItemStack> captureSlots() {
        var slots = new ObjectArrayList<ItemStack>(inventory.getSizeInventory());
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            slots.add(inventory.getStackInSlot(slot).copy());
        }
        if (slots.size() != 41) {
            throw new IllegalStateException("Manual materials require the complete 1.12 player inventory");
        }
        return slots;
    }

    private void recoverFailedWrite(Object2ObjectLinkedOpenHashMap<UUID, ManualResearchMaterialReceipt> receipts,
                                    ManualResearchMaterialReceipt committed, List<ItemStack> before, List<ItemStack> after,
                                    int selectedSlot, IntOpenHashSet attempted, Throwable original) {
        try {
            if (matches(Objects.requireNonNull(committed.inventoryAfter()))) {
                receipts.put(committed.operationId(), committed);
                publishAndSave(receipts);
                return;
            }
            if (inventory.currentItem != selectedSlot) {
                throw new IllegalStateException("Material failure changed selected slot; outcome remains unknown");
            }
            var observed = captureSlots();
            for (int slot = 0; slot < observed.size(); slot++) {
                var actual = observed.get(slot);
                if (!sameStack(actual, before.get(slot)) && !(attempted.contains(slot) && sameStack(actual, after.get(slot)))) {
                    throw new IllegalStateException("Material failure has an unowned inventory mutation; outcome remains unknown");
                }
            }
            // Restore only exactly observed changes made by this call, checking all slots before each write.
            for (int slot : attempted) {
                if (sameStack(observed.get(slot), before.get(slot))) {
                    continue;
                }
                requireOwner();
                if (!matches(imageOf(observed, selectedSlot))) {
                    throw new IllegalStateException("Inventory changed during material compensation");
                }
                inventory.setInventorySlotContents(slot, before.get(slot).copy());
                observed.set(slot, before.get(slot).copy());
            }
            if (!matches(committed.inventoryBefore())) {
                throw new IllegalStateException("Material compensation lacks exact before proof");
            }
        } catch (RuntimeException | Error recoveryFailure) {
            if (recoveryFailure != original) {
                original.addSuppressed(recoveryFailure);
            }
        }
    }

    private Object2ObjectLinkedOpenHashMap<UUID, ManualResearchMaterialReceipt> readReceipts() {
        var receipts = new Object2ObjectLinkedOpenHashMap<UUID, ManualResearchMaterialReceipt>();
        var entity = player.getEntityData();
        if (!entity.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            return receipts;
        }
        var persisted = StrictNbt.requireCompound(entity, EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(NAMESPACE)) {
            return receipts;
        }
        var namespace = StrictNbt.requireCompound(persisted, NAMESPACE);
        if (!namespace.hasKey(STORE)) {
            return receipts;
        }
        var stored = StrictNbt.requireCompound(namespace, STORE);
        StrictNbt.requireKeys(stored, "version", "receipts");
        if (StrictNbt.requireInt(stored, "version") != 1) {
            throw new IllegalArgumentException("Unsupported manual material store version");
        }
        var list = StrictNbt.requireList(stored, "receipts", Constants.NBT.TAG_COMPOUND);
        if (list.tagCount() > MAX_RECEIPTS) {
            throw new IllegalArgumentException("Too many saved material receipts");
        }
        var sessions = new ObjectOpenHashSet<UUID>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var receipt = ManualResearchMaterialReceiptNbt.decode(list.getCompoundTagAt(index));
            requirePlayer(receipt);
            if (!sessions.add(receipt.sessionId()) || receipts.putIfAbsent(receipt.operationId(), receipt) != null) {
                throw new IllegalArgumentException("Duplicate material receipt operation or session");
            }
        }
        return receipts;
    }

    private void publishAndSave(Object2ObjectLinkedOpenHashMap<UUID, ManualResearchMaterialReceipt> receipts) {
        var list = new NBTTagList();
        for (var receipt : receipts.values()) {
            list.appendTag(ManualResearchMaterialReceiptNbt.encode(receipt));
        }
        var store = new NBTTagCompound();
        store.setInteger("version", 1);
        store.setTag("receipts", list);
        var entity = player.getEntityData();
        var persisted = entity.hasKey(EntityPlayer.PERSISTED_NBT_TAG)
            ? StrictNbt.requireCompound(entity, EntityPlayer.PERSISTED_NBT_TAG).copy() : new NBTTagCompound();
        var namespace = persisted.hasKey(NAMESPACE) ? StrictNbt.requireCompound(persisted, NAMESPACE).copy() : new NBTTagCompound();
        if (receipts.isEmpty()) {
            namespace.removeTag(STORE);
        } else {
            namespace.setTag(STORE, store);
        }
        persisted.setTag(NAMESPACE, namespace);
        entity.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        requireOwner();
        inventory.markDirty();
        var expectedInventory = ManualResearchMaterialReceiptNbt.decodeInventory(snapshotInventory());
        var expectedReceipts = receipts.isEmpty() ? new NBTTagCompound() : store;
        PlayerDataPersistence.persist(player, this::requireOwner,
            saved -> MaterialInventoryEvidence.verify(saved, expectedInventory, STORE, expectedReceipts));
        if (!expectedInventory.equals(ManualResearchMaterialReceiptNbt.decodeInventory(snapshotInventory()))
            || !receipts.equals(readReceipts())) {
            throw new IllegalStateException("Material evidence changed during its player persistence barrier");
        }
    }

    private ManualResearchMaterialReceipt requireReceipt(Object2ObjectLinkedOpenHashMap<UUID, ManualResearchMaterialReceipt> receipts,
                                                         UUID operationId) {
        var receipt = receipts.get(Objects.requireNonNull(operationId));
        if (receipt == null) {
            throw new IllegalStateException("Missing material receipt " + operationId);
        }
        requirePlayer(receipt);
        return receipt;
    }

    private void requirePlayer(ManualResearchMaterialReceipt receipt) {
        if (!playerId.equals(Objects.requireNonNull(receipt).playerId())) {
            throw new IllegalArgumentException("Material receipt belongs to another player");
        }
    }

    private void requireAfter(ManualResearchMaterialReceipt receipt) {
        if (receipt.status() == ManualResearchMaterialReceipt.Status.PREPARED
            || !matches(Objects.requireNonNull(receipt.inventoryAfter()))) {
            throw new IllegalStateException("Committed material inventory lacks its exact after proof");
        }
    }

    private void requireOwner() {
        var world = player.getServerWorld();
        if (Thread.currentThread() != owner || player instanceof FakePlayer || world.isRemote
            || world.getMinecraftServer() != server || !server.isCallingFromMinecraftThread()
            || !playerId.equals(player.getUniqueID()) || player.inventory != inventory
            || server.getPlayerList().getPlayerByUUID(playerId) != player) {
            throw new IllegalStateException("Manual materials require the exact real online player on its server owner thread");
        }
    }
}
