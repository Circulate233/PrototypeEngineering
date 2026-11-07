package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardFragment;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

/**
 * Detached, owner-thread journal of effect-entry receipts in a player's persisted ForgeData.
 * Publishing NBT does not prove durability. The host must save and verify ISSUING before effects,
 * and only a durably established RETRY_PLANNED authorizes resetting an earlier ISSUING receipt.
 */
final class RewardDeliveryJournal {
    static final int MAX_RECEIPTS = 4096;
    private static final String NAMESPACE = "prototype_eng";
    private static final String STORE = "reward_delivery_receipts";
    private final UUID player;
    private final Object2ObjectLinkedOpenHashMap<UUID, Receipt> receipts = new Object2ObjectLinkedOpenHashMap<>();

    private RewardDeliveryJournal(UUID player) { this.player = Objects.requireNonNull(player); }

    static RewardDeliveryJournal read(NBTTagCompound entityData, UUID player) {
        Objects.requireNonNull(entityData);
        var result = new RewardDeliveryJournal(player);
        if (!entityData.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) return result;
        var persisted = StrictNbt.requireCompound(entityData, EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(NAMESPACE)) return result;
        var namespace = StrictNbt.requireCompound(persisted, NAMESPACE);
        if (!namespace.hasKey(STORE)) return result;
        var store = StrictNbt.requireCompound(namespace, STORE);
        StrictNbt.requireKeys(store, "version", "receipts");
        if (StrictNbt.requireInt(store, "version") != 1) throw new IllegalArgumentException("Unsupported reward receipt version");
        var list = StrictNbt.requireList(store, "receipts", Constants.NBT.TAG_COMPOUND);
        if (list.tagCount() > MAX_RECEIPTS) throw new IllegalArgumentException("Too many reward delivery receipts");
        for (int index = 0; index < list.tagCount(); index++) {
            var receipt = decode(list.getCompoundTagAt(index));
            result.requirePlayer(receipt);
            if (result.receipts.putIfAbsent(receipt.id(), receipt) != null) throw new IllegalArgumentException("Duplicate reward receipt ID");
        }
        return result;
    }

    /**
     * Reconciles authoritative sources once at player load, before an ordinary save can erase an
     * older source's ISSUING. Missing evidence is also uncertain. Do not run this on every write:
     * the reward owner may subsequently establish and persist an explicit no-effect retry.
     */
    static RewardDeliveryJournal reconcile(RewardDeliveryJournal loaded, RewardDeliveryJournal playerSaved,
                                           @Nullable RewardDeliveryJournal hostSaved) {
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(playerSaved);
        if (!loaded.player.equals(playerSaved.player) || hostSaved != null && !loaded.player.equals(hostSaved.player)) {
            throw new IllegalArgumentException("Reward receipt sources belong to different players");
        }
        var union = new Object2ObjectLinkedOpenHashMap<UUID, Receipt>();
        collectReceipts(union, loaded);
        collectReceipts(union, playerSaved);
        if (hostSaved != null) collectReceipts(union, hostSaved);
        // All identities and the complete union limit have passed before creating any result.
        var result = new RewardDeliveryJournal(loaded.player);
        for (var receipt : union.values()) {
            boolean prepared = isPrepared(loaded, receipt.id()) && isPrepared(playerSaved, receipt.id())
                && (hostSaved == null || isPrepared(hostSaved, receipt.id()));
            result.receipts.put(receipt.id(), new Receipt(receipt.id(), receipt.parent(), receipt.count(), receipt.stack(),
                receipt.target(), prepared ? State.PREPARED : State.ISSUING));
        }
        return result;
    }

    private static void collectReceipts(Object2ObjectLinkedOpenHashMap<UUID, Receipt> union, RewardDeliveryJournal source) {
        for (var receipt : source.receipts.values()) {
            var previous = union.get(receipt.id());
            if (previous != null) requireSame(previous, receipt);
            else {
                if (union.size() >= MAX_RECEIPTS) throw new IllegalStateException("Too many recovered reward delivery receipts");
                union.put(receipt.id(), receipt);
            }
        }
    }

    private static boolean isPrepared(RewardDeliveryJournal source, UUID id) {
        var receipt = source.receipts.get(id);
        return receipt != null && receipt.state() == State.PREPARED;
    }

    /** A repeated PREPARED publication is harmless; an uncertain attempt cannot restart itself. */
    boolean prepare(RewardFragment fragment) {
        if (fragment.status() != RewardFragment.Status.PLANNED && fragment.status() != RewardFragment.Status.RETRY_PLANNED) {
            throw new IllegalStateException("Only planned reward fragments may prepare a receipt");
        }
        var candidate = receipt(fragment, State.PREPARED);
        var previous = receipts.get(candidate.id());
        if (previous != null) {
            requireSame(previous, candidate);
            if (previous.state() == State.PREPARED) return false;
            if (fragment.status() != RewardFragment.Status.RETRY_PLANNED) {
                throw new IllegalStateException("An issuing reward receipt requires a proven no-effect retry");
            }
        } else if (receipts.size() >= MAX_RECEIPTS) {
            throw new IllegalStateException("Too many outstanding reward delivery receipts");
        }
        receipts.put(candidate.id(), candidate);
        return true;
    }

    /** Must be published and durably verified before entering the first inventory/spawn effect. */
    void issue(RewardFragment fragment) {
        if (fragment.status() != RewardFragment.Status.DELIVERING) throw new IllegalStateException("Reward fragment has not started");
        var candidate = receipt(fragment, State.ISSUING);
        var previous = receipts.get(candidate.id());
        if (previous == null) throw new IllegalStateException("Missing prepared reward receipt");
        requireSame(previous, candidate);
        if (previous.state() != State.PREPARED) throw new IllegalStateException("Reward receipt has already entered issuing");
        receipts.put(candidate.id(), candidate);
    }

    /** Confirmation is established by the reward owner; repeated cleanup does not invent receipts. */
    boolean remove(RewardFragment confirmed) {
        if (confirmed.status() != RewardFragment.Status.CONFIRMED) throw new IllegalStateException("Only confirmed reward receipts may be removed");
        var candidate = receipt(confirmed, State.ISSUING);
        var previous = receipts.get(candidate.id());
        if (previous == null) return false;
        requireSame(previous, candidate);
        receipts.remove(candidate.id());
        return true;
    }

    /** Missing receipts are unknown; conflicting evidence fails explicitly rather than matching. */
    boolean contains(RewardFragment fragment, State state) {
        var candidate = receipt(fragment, state);
        var previous = receipts.get(candidate.id());
        if (previous == null) return false;
        requireSame(previous, candidate);
        return previous.state() == state;
    }

    /** Canonical complete store for post-save comparison, stable even when the store is empty. */
    NBTTagCompound snapshot() {
        var list = new NBTTagList();
        for (var receipt : receipts.values()) list.appendTag(encode(receipt));
        var store = new NBTTagCompound();
        store.setInteger("version", 1);
        store.setTag("receipts", list);
        return store;
    }

    /** Preserves the current manual receipts and other namespaces without retaining borrowed tags. */
    void writeTo(NBTTagCompound entityData) {
        Objects.requireNonNull(entityData);
        var persisted = entityData.hasKey(EntityPlayer.PERSISTED_NBT_TAG)
            ? StrictNbt.requireCompound(entityData, EntityPlayer.PERSISTED_NBT_TAG).copy() : new NBTTagCompound();
        var namespace = persisted.hasKey(NAMESPACE) ? StrictNbt.requireCompound(persisted, NAMESPACE).copy() : new NBTTagCompound();
        if (receipts.isEmpty()) namespace.removeTag(STORE);
        else namespace.setTag(STORE, snapshot());
        persisted.setTag(NAMESPACE, namespace);
        entityData.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    private Receipt receipt(RewardFragment fragment, State state) {
        Objects.requireNonNull(fragment);
        var receipt = new Receipt(fragment.id(), fragment.parent(), fragment.count(), fragment.taggedStackNbt(), fragment.target(), state);
        requirePlayer(receipt);
        return receipt;
    }

    private void requirePlayer(Receipt receipt) {
        if (!player.equals(receipt.parent().playerUuid())) throw new IllegalArgumentException("Reward receipt belongs to another player");
    }

    private static void requireSame(Receipt previous, Receipt candidate) {
        if (!previous.id().equals(candidate.id()) || !previous.parent().equals(candidate.parent()) || previous.count() != candidate.count()
            || !previous.stack().equals(candidate.stack()) || !previous.target().equals(candidate.target())) {
            throw new IllegalStateException("Reward receipt identity, payload or target changed");
        }
    }

    private static NBTTagCompound encode(Receipt receipt) {
        var tag = new NBTTagCompound();
        tag.setString("fragment", receipt.id().toString());
        tag.setString("player", receipt.parent().playerUuid().toString());
        tag.setString("research", receipt.parent().researchId().toString());
        tag.setInteger("level", receipt.parent().level());
        tag.setString("group", receipt.parent().groupId().toString());
        tag.setString("child", receipt.parent().childId().toString());
        tag.setInteger("count", receipt.count());
        tag.setTag("stack", receipt.stack().copy());
        tag.setTag("target", encodeTarget(receipt.target()));
        tag.setString("state", receipt.state().name());
        return tag;
    }

    private static NBTTagCompound encodeTarget(RewardFragment.Target source) {
        var target = new NBTTagCompound();
        switch (source) {
            case RewardFragment.Inventory inventory -> {
                target.setString("type", "inventory");
                target.setInteger("slot", inventory.slot());
            }
            case RewardFragment.WorldDrop drop -> {
                target.setString("type", "drop");
                target.setInteger("dimension", drop.dimensionId());
                target.setInteger("chunkX", drop.chunkX());
                target.setInteger("chunkZ", drop.chunkZ());
                target.setDouble("x", drop.x());
                target.setDouble("y", drop.y());
                target.setDouble("z", drop.z());
            }
        }
        return target;
    }

    private static Receipt decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "fragment", "player", "research", "level", "group", "child", "count", "stack", "target", "state");
        var parent = new RewardEventKey(uuid(tag, "player"), StrictNbt.requireId(tag, "research"), StrictNbt.requireInt(tag, "level"),
            StrictNbt.requireId(tag, "group"), StrictNbt.requireId(tag, "child"));
        var target = StrictNbt.requireCompound(tag, "target");
        var decodedTarget = switch (StrictNbt.requireString(target, "type")) {
            case "inventory" -> {
                StrictNbt.requireKeys(target, "type", "slot");
                yield new RewardFragment.Inventory(StrictNbt.requireInt(target, "slot"));
            }
            case "drop" -> {
                StrictNbt.requireKeys(target, "type", "dimension", "chunkX", "chunkZ", "x", "y", "z");
                yield new RewardFragment.WorldDrop(StrictNbt.requireInt(target, "dimension"), StrictNbt.requireInt(target, "chunkX"),
                    StrictNbt.requireInt(target, "chunkZ"), StrictNbt.requireDouble(target, "x"), StrictNbt.requireDouble(target, "y"),
                    StrictNbt.requireDouble(target, "z"));
            }
            default -> throw new IllegalArgumentException("Unknown reward receipt target");
        };
        return new Receipt(uuid(tag, "fragment"), parent, StrictNbt.requireInt(tag, "count"), StrictNbt.requireCompound(tag, "stack"),
            decodedTarget, State.valueOf(StrictNbt.requireString(tag, "state")));
    }

    private static UUID uuid(NBTTagCompound tag, String key) {
        var raw = StrictNbt.requireString(tag, key);
        var parsed = UUID.fromString(raw);
        if (!parsed.toString().equals(raw)) throw new IllegalArgumentException("Non-canonical reward receipt UUID");
        return parsed;
    }

    enum State { PREPARED, ISSUING }

    private record Receipt(UUID id, RewardEventKey parent, int count, NBTTagCompound stack, RewardFragment.Target target, State state) {
        private Receipt {
            Objects.requireNonNull(id);
            Objects.requireNonNull(parent);
            Objects.requireNonNull(target);
            Objects.requireNonNull(state);
            stack = Objects.requireNonNull(stack).copy();
            if (count < 1 || count > Byte.MAX_VALUE || StrictNbt.requireByte(stack, "Count") != count) {
                throw new IllegalArgumentException("Invalid reward receipt item count");
            }
            if (target instanceof RewardFragment.Inventory(int slot) && slot >= 36) {
                throw new IllegalArgumentException("Reward receipt inventory target is outside main inventory");
            }
            StrictNbt.requireId(stack, "id");
            StrictNbt.requireShort(stack, "Damage");
            if (stack.hasKey("ForgeCaps")) StrictNbt.requireCompound(stack, "ForgeCaps");
            var identity = StrictNbt.requireCompound(StrictNbt.requireCompound(stack, "tag"), RewardItemProtection.DELIVERY_TAG);
            StrictNbt.requireKeys(identity, "Player", "Research", "Level", "Group", "Child", "Fragment");
            if (!uuid(identity, "Player").equals(parent.playerUuid()) || !uuid(identity, "Fragment").equals(id)
                || !StrictNbt.requireId(identity, "Research").equals(parent.researchId()) || StrictNbt.requireInt(identity, "Level") != parent.level()
                || !StrictNbt.requireId(identity, "Group").equals(parent.groupId()) || !StrictNbt.requireId(identity, "Child").equals(parent.childId())) {
                throw new IllegalArgumentException("Reward receipt stack identity differs");
            }
        }
    }
}
