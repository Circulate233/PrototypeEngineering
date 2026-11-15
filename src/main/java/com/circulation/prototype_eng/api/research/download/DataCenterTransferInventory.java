package com.circulation.prototype_eng.api.research.download;

import ae2.api.inventories.InternalInventory;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Physical database input/output slots and durable transfer evidence. Stack references are borrowed
 * read-only; the host persists this inventory in its own component payload. Pending effects lock the
 * slots until their world checkpoint permits cleanup. All access belongs to the host owner thread.
 */
public final class DataCenterTransferInventory implements InternalInventory {
    public static final int INPUT = 0, OUTPUT = 1;
    private static final ItemDeliveryFragmentNbt FRAGMENTS = new ItemDeliveryFragmentNbt(ResourceKeyNbt.INSTANCE);
    private final Runnable ownerCheck;
    private final Runnable changed;
    private final Runnable persist;
    private ItemStack input = ItemStack.EMPTY, output = ItemStack.EMPTY;
    private @Nullable ResearchDownloadMaterial material;
    private @Nullable ItemDeliveryFragment delivery;
    private boolean delivered;

    public DataCenterTransferInventory(Runnable ownerCheck, Runnable changed, Runnable persist) {
        this.ownerCheck = ownerCheck;
        this.changed = changed;
        this.persist = persist;
    }

    public Adapter forPlayer(UUID player) {
        ownerCheck.run();
        return new Adapter(player);
    }

    public boolean locked() {
        ownerCheck.run();
        return material != null || delivery != null;
    }

    @Override
    public int size() {
        return 2;
    }

    @Override
    public ItemStack getStackInSlot(int slotIndex) {
        ownerCheck.run();
        return slotIndex == INPUT ? input : output;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == INPUT && !locked();
    }

    @Override
    public void setItemDirect(int slotIndex, ItemStack stack) {
        ownerCheck.run();
        if (locked()) throw new IllegalStateException("Database transfer slots have a pending operation");
        if (slotIndex == INPUT) input = stack;
        else output = stack;
        changed.run();
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!isItemValid(slot, stack) || stack.isEmpty()) return stack;
        int maximum = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
        if (!input.isEmpty() && (!ItemStack.areItemsEqual(input, stack) || !ItemStack.areItemStackTagsEqual(input, stack)))
            return stack;
        int accepted = Math.min(stack.getCount(), maximum - input.getCount());
        if (accepted <= 0) return stack;
        if (!simulate) {
            if (input.isEmpty()) {
                input = stack.copy();
                input.setCount(accepted);
            } else input.grow(accepted);
            changed.run();
        }
        if (accepted == stack.getCount()) return ItemStack.EMPTY;
        var remainder = stack.copy();
        remainder.shrink(accepted);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ownerCheck.run();
        if (locked() || amount <= 0) return ItemStack.EMPTY;
        var stack = getStackInSlot(slot);
        int extracted = Math.min(amount, stack.getCount());
        if (extracted == 0) return ItemStack.EMPTY;
        if (simulate) {
            var result = stack.copy();
            result.setCount(extracted);
            return result;
        }
        if (extracted == stack.getCount()) {
            if (slot == INPUT) input = ItemStack.EMPTY;
            else output = ItemStack.EMPTY;
            changed.run();
            return stack;
        }
        var result = stack.splitStack(extracted);
        changed.run();
        return result;
    }

    public NBTTagCompound encode() {
        ownerCheck.run();
        var tag = new NBTTagCompound();
        if (!input.isEmpty()) tag.setTag("Input", input.serializeNBT());
        if (!output.isEmpty()) tag.setTag("Output", output.serializeNBT());
        if (material != null) tag.setTag("Material", ResearchDownloadMaterial.CODEC.encode(material));
        if (delivery != null) {
            tag.setTag("Delivery", FRAGMENTS.encode(delivery));
            tag.setBoolean("Delivered", delivered);
        }
        return tag;
    }

    public void restore(NBTTagCompound tag) {
        input = tag.hasKey("Input") ? new ItemStack(tag.getCompoundTag("Input")) : ItemStack.EMPTY;
        output = tag.hasKey("Output") ? new ItemStack(tag.getCompoundTag("Output")) : ItemStack.EMPTY;
        material = tag.hasKey("Material") ? ResearchDownloadMaterial.CODEC.decode(tag.getCompoundTag("Material")) : null;
        delivery = tag.hasKey("Delivery") ? FRAGMENTS.decode(tag.getCompoundTag("Delivery")) : null;
        delivered = tag.getBoolean("Delivered");
    }

    private NBTTagCompound capture() {
        var tag = new NBTTagCompound();
        var entries = new NBTTagList();
        if (!input.isEmpty()) {
            var item = input.serializeNBT();
            item.setByte("Slot", (byte) INPUT);
            entries.appendTag(item);
        }
        if (!output.isEmpty()) {
            var item = output.serializeNBT();
            item.setByte("Slot", (byte) OUTPUT);
            entries.appendTag(item);
        }
        tag.setTag("inventory", entries);
        return tag;
    }

    /**
     * Actor-scoped operations over these physical slots. The same adapter supplies material evidence
     * and output delivery; neither operation uses the player's backpack as a substitute inventory.
     */
    public final class Adapter implements ResearchDownloadInventory, ItemDeliveryAdapter {
        private final UUID player;

        private Adapter(UUID player) {
            this.player = player;
        }

        @Override
        public UUID playerId() {
            return player;
        }

        @Override
        public boolean machineInventory() {
            return true;
        }

        @Override
        public boolean canOutput(NBTTagCompound nativeTemplate, long amount) {
            ownerCheck.run();
            var stack = new ItemStack(nativeTemplate);
            return !locked() && output.isEmpty() && !stack.isEmpty()
                && amount > 0 && amount <= Math.min(stack.getMaxStackSize(), getSlotLimit(OUTPUT));
        }

        @Override
        public @Nullable ResearchDownloadMaterial capture(UUID operationId, int slot) {
            ownerCheck.run();
            if (slot != INPUT || locked() || !output.isEmpty()) return null;
            return ResearchDownloadMaterial.prepare(player, operationId, INPUT, DataCenterTransferInventory.this.capture());
        }

        @Override
        public @Nullable ResearchDownloadMaterial find(UUID operationId) {
            ownerCheck.run();
            return material != null && material.operationId().equals(operationId) && material.playerId().equals(player) ? material : null;
        }

        @Override
        public ObjectList<ResearchDownloadMaterial> operations() {
            ownerCheck.run();
            return material != null && material.playerId().equals(player) ? ObjectList.of(material) : ObjectList.of();
        }

        @Override
        public boolean matches(NBTTagCompound inventory) {
            ownerCheck.run();
            return inventory.equals(DataCenterTransferInventory.this.capture());
        }

        @Override
        public void recordPrepared(ResearchDownloadMaterial receipt) {
            ownerCheck.run();
            if (!player.equals(receipt.playerId()) || material != null && !material.equals(receipt)
                || !matches(receipt.inventoryBefore()))
                throw new IllegalStateException("Database input preparation lost its original inventory");
            material = receipt;
            persist.run();
        }

        @Override
        public boolean consume(ResearchDownloadMaterial receipt) {
            ownerCheck.run();
            if (material == null || !material.sameOperation(receipt))
                throw new IllegalStateException("Database input consumption lost its receipt");
            if (material.status() == ResearchDownloadMaterial.Status.COMMITTED) return true;
            if (!matches(material.inventoryBefore()))
                throw new IllegalStateException("Database input changed before consumption");
            input.shrink(1);
            material = material.committed();
            persist.run();
            return true;
        }

        @Override
        public void persistCommitted(UUID operationId) {
            ownerCheck.run();
            if (material == null || !material.operationId().equals(operationId) || material.status() != ResearchDownloadMaterial.Status.COMMITTED)
                throw new IllegalStateException("Database input commit evidence is unavailable");
            persist.run();
        }

        @Override
        public void removePrepared(UUID operationId) {
            ownerCheck.run();
            if (material != null && (!material.operationId().equals(operationId) || material.status() != ResearchDownloadMaterial.Status.PREPARED
                || !matches(material.inventoryBefore())))
                throw new IllegalStateException("Database input abort lost its before proof");
            material = null;
            persist.run();
        }

        @Override
        public void removeCommitted(UUID operationId) {
            ownerCheck.run();
            if (material != null && (!material.operationId().equals(operationId) || material.status() != ResearchDownloadMaterial.Status.COMMITTED))
                throw new IllegalStateException("Database input cleanup lost its committed receipt");
            material = null;
            persist.run();
        }

        @Override
        public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, AEKey resource, long amount) {
            return plan(parent, ((AEItemKey) resource).toStack().serializeNBT(), amount);
        }

        @Override
        public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, NBTTagCompound nativeTemplate, long amount) {
            ownerCheck.run();
            var stack = new ItemStack(nativeTemplate);
            if (!player.equals(parent.playerUuid()) || amount < 1 || amount > Math.min(stack.getMaxStackSize(), getSlotLimit(OUTPUT)))
                throw new IllegalArgumentException("Database output must fit one physical output slot");
            var id = ItemDeliveryKeyNbt.fragmentId(parent, 0);
            return ObjectList.of(new ItemDeliveryFragment(id, parent, AEItemKey.of(stack), (int) amount,
                ItemDeliveryTemplates.taggedLeaf(nativeTemplate, (int) amount, parent, id), new ItemDeliveryFragment.Inventory(OUTPUT),
                null, ItemDeliveryFragment.Status.PLANNED));
        }

        @Override
        public Scan scan(ObjectList<ItemDeliveryFragment> delivering) {
            ownerCheck.run();
            var observed = new ObjectOpenHashSet<UUID>();
            var absent = new ObjectOpenHashSet<UUID>();
            for (int index = 0; index < delivering.size(); index++) {
                var fragment = delivering.get(index);
                if (delivery == null || !delivery.id().equals(fragment.id())) continue;
                delivery.requireTransitionTo(fragment);
                if (delivered) {
                    if (!output.serializeNBT().equals(fragment.taggedStackNbt()))
                        throw new IllegalStateException("Database delivered output differs from its receipt");
                    observed.add(fragment.id());
                } else if (output.isEmpty()) absent.add(fragment.id());
            }
            return new Scan(observed, Object2ObjectMaps.emptyMap(), absent);
        }

        @Override
        public boolean canDeliver(ItemDeliveryFragment fragment) {
            ownerCheck.run();
            return output.isEmpty() && (delivery == null || delivery.id().equals(fragment.id()));
        }

        @Override
        public void prepare(ItemDeliveryFragment fragment) {
            ownerCheck.run();
            if (delivery != null && !delivery.id().equals(fragment.id()))
                throw new IllegalStateException("Another operation owns the database output slot");
            delivery = fragment;
            delivered = false;
            persist.run();
        }

        @Override
        public Delivery deliver(ItemDeliveryFragment fragment) {
            ownerCheck.run();
            if (delivery == null || !delivery.id().equals(fragment.id()))
                throw new IllegalStateException("Database output has no prepared delivery evidence");
            if (!output.isEmpty()) return new Delivery(0, null);
            output = new ItemStack(fragment.taggedStackNbt());
            delivery = fragment;
            delivered = true;
            return new Delivery(fragment.count(), null);
        }

        @Override
        public void commit(ItemDeliveryFragment fragment, Delivery result) {
            ownerCheck.run();
            if (!delivered || delivery == null || !delivery.id().equals(fragment.id())
                || !output.serializeNBT().equals(fragment.taggedStackNbt()))
                throw new IllegalStateException("Database output commit lacks its exact delivery");
            persist.run();
        }

        @Override
        @SuppressWarnings("DataFlowIssue")
        public void release(ItemDeliveryFragment fragment) {
            ownerCheck.run();
            if (delivery != null) {
                if (!delivery.id().equals(fragment.id()) || !delivered || fragment.status() != ItemDeliveryFragment.Status.CONFIRMED)
                    throw new IllegalStateException("Database output release differs from its confirmed operation");
                var tag = output.getTagCompound();
                tag.removeTag(ItemDeliveryTemplates.DELIVERY_TAG);
                if (tag.isEmpty()) output.setTagCompound(null);
                delivery = null;
                delivered = false;
            }
            persist.run();
        }
    }
}
