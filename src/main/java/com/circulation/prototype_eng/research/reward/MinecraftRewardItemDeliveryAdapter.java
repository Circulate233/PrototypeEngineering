package com.circulation.prototype_eng.research.reward;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardFragment;
import com.circulation.prototype_eng.api.research.reward.RewardItemDeliveryAdapter;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/** Actual server inventory/drop delivery and synchronous persistence, with conservative unknown recovery. */
@SuppressWarnings("unused") // Library runtime entry point; no built-in gameplay or packet is registered.
public final class MinecraftRewardItemDeliveryAdapter implements RewardItemDeliveryAdapter {
    public static final String DELIVERY_TAG = "PrototypeEngineeringRewardDelivery";
    private static final int MAX_FRAGMENTS = 4096;
    private final EntityPlayerMP player;
    private final AbsenceProof absenceProof;
    private final IntOpenHashSet reservedSlots = new IntOpenHashSet();

    public MinecraftRewardItemDeliveryAdapter(EntityPlayerMP player) { this(player, (_, _) -> false); }

    /** The proof owner must fence all movement/deletion of unconfirmed tags and inspect durable authoritative storage. */
    public MinecraftRewardItemDeliveryAdapter(EntityPlayerMP player, AbsenceProof absenceProof) {
        this.player = Objects.requireNonNull(player, "player");
        this.absenceProof = Objects.requireNonNull(absenceProof, "absenceProof");
        if (player instanceof FakePlayer) throw new IllegalArgumentException("Rewards require a real player");
        requireOwner();
    }

    @Override public ObjectList<RewardFragment> plan(RewardEventKey parent, RewardChild.Item reward) {
        requireOwner();
        if (!parent.playerUuid().equals(player.getUniqueID())) throw new IllegalArgumentException("Reward actor changed");
        if (!(reward.resource() instanceof AEItemKey key)) return ObjectList.of();
        var logical = key.toStack();
        if (logical.hasTagCompound() && Objects.requireNonNull(logical.getTagCompound()).hasKey(DELIVERY_TAG)) {
            throw new IllegalArgumentException("Business item contains reserved reward tag");
        }
        int maximum = Math.min(logical.getMaxStackSize(), player.inventory.getInventoryStackLimit());
        if (maximum < 1 || reward.amount() > (long) maximum * MAX_FRAGMENTS) return ObjectList.of();
        long remaining = reward.amount();
        var result = new ObjectArrayList<RewardFragment>();
        // Different immutable leaf tags cannot be merged into an existing business stack.
        for (int slot = 0; slot < player.inventory.mainInventory.size() && remaining > 0; slot++) {
            if (!player.inventory.mainInventory.get(slot).isEmpty() || reservedSlots.contains(slot)) continue;
            int count = (int) Math.min(remaining, maximum);
            result.add(leaf(parent, reward, result.size(), count, new RewardFragment.Inventory(slot)));
            reservedSlots.add(slot);
            remaining -= count;
        }
        var world = player.getServerWorld();
        var drop = new RewardFragment.WorldDrop(world.provider.getDimension(), chunk(player.posX), chunk(player.posZ), player.posX, player.posY + 0.5, player.posZ);
        while (remaining > 0) {
            int count = (int) Math.min(remaining, maximum);
            result.add(leaf(parent, reward, result.size(), count, drop));
            remaining -= count;
        }
        return ObjectLists.unmodifiable(result);
    }

    private static RewardFragment leaf(RewardEventKey parent, RewardChild.Item reward, int index, int count, RewardFragment.Target target) {
        // Length framing prevents slashes inside different resource IDs from colliding.
        var encoded = frame(parent.playerUuid().toString()) + frame(parent.researchId().toString())
                + frame(Integer.toString(parent.level())) + frame(parent.groupId().toString())
                + frame(parent.childId().toString()) + frame(Integer.toString(index));
        var id = UUID.nameUUIDFromBytes(encoded.getBytes(StandardCharsets.UTF_8));
        var stack = ((AEItemKey) reward.resource()).toStack(count);
        var root = stack.hasTagCompound() ? Objects.requireNonNull(stack.getTagCompound()).copy() : new NBTTagCompound();
        root.setTag(DELIVERY_TAG, identity(parent, id));
        stack.setTagCompound(root);
        return new RewardFragment(id, parent, reward.resource(), count, stack.writeToNBT(new NBTTagCompound()), target, Optional.empty(), RewardFragment.Status.PLANNED);
    }

    @Override public Scan scan(ObjectList<RewardFragment> fragments) {
        requireOwner();
        var expected = new Object2ObjectLinkedOpenHashMap<UUID, RewardFragment>();
        for (var fragment : fragments) {
            validate(fragment);
            if (fragment.status() != RewardFragment.Status.DELIVERING || expected.putIfAbsent(fragment.id(), fragment) != null) {
                throw new IllegalArgumentException("Invalid reward recovery plan");
            }
        }
        var observed = new ObjectOpenHashSet<UUID>();
        var entities = new Object2ObjectLinkedOpenHashMap<UUID, UUID>();
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) observe(player.inventory.getStackInSlot(slot), null, expected, observed, entities);
        for (var world : DimensionManager.getWorlds()) {
            for (var entity : world.loadedEntityList) {
                if (entity instanceof EntityItem item && !item.isDead) observe(item.getItem(), item.getUniqueID(), expected, observed, entities);
            }
        }
        var absent = new ObjectOpenHashSet<UUID>();
        for (var fragment : fragments) {
            // Loaded inventory/entities can observe an effect; they cannot prove global durable absence.
            if (!observed.contains(fragment.id()) && absenceProof.provenAbsent(player, fragment)) absent.add(fragment.id());
        }
        return new Scan(observed, entities, absent);
    }

    private static void observe(ItemStack actual, @Nullable UUID entity, Object2ObjectLinkedOpenHashMap<UUID, RewardFragment> expected,
            ObjectOpenHashSet<UUID> observed, Object2ObjectLinkedOpenHashMap<UUID, UUID> entities) {
        if (actual.isEmpty() || !actual.hasTagCompound() || !Objects.requireNonNull(actual.getTagCompound()).hasKey(DELIVERY_TAG)) return;
        var tag = StrictNbt.requireCompound(Objects.requireNonNull(actual.getTagCompound()), DELIVERY_TAG);
        StrictNbt.requireKeys(tag, "Player", "Research", "Level", "Group", "Child", "Fragment");
        var fragmentId = UUID.fromString(StrictNbt.requireString(tag, "Fragment"));
        var fragment = expected.get(fragmentId);
        // Validate all tagged identities before accepting or ignoring a different pending claim.
        var parent = new RewardEventKey(UUID.fromString(StrictNbt.requireString(tag, "Player")), StrictNbt.requireId(tag, "Research"), StrictNbt.requireInt(tag, "Level"),
                StrictNbt.requireId(tag, "Group"), StrictNbt.requireId(tag, "Child"));
        if (fragment == null) return;
        if (!parent.equals(fragment.parent()) || !exact(actual, new ItemStack(fragment.taggedStackNbt())) || !observed.add(fragmentId)) {
            throw new IllegalStateException("Reward fragment payload conflict or duplicate: " + fragmentId);
        }
        if (entity != null) entities.put(fragmentId, entity);
    }

    @Override public boolean canDeliver(RewardFragment fragment) {
        requireOwner(); validate(fragment);
        if (fragment.status() != RewardFragment.Status.PLANNED && fragment.status() != RewardFragment.Status.DELIVERING) return false;
        return switch (fragment.target()) {
            case RewardFragment.Inventory inventory -> inventory.slot() < player.inventory.mainInventory.size() && player.inventory.mainInventory.get(inventory.slot()).isEmpty();
            case RewardFragment.WorldDrop drop -> loaded(drop) != null;
        };
    }

    @Override public Delivery deliver(RewardFragment fragment) {
        requireOwner(); validate(fragment);
        if (fragment.status() != RewardFragment.Status.DELIVERING) throw new IllegalStateException("Reward fragment has not durably started");
        var stack = new ItemStack(fragment.taggedStackNbt());
        return switch (fragment.target()) {
            case RewardFragment.Inventory inventory -> {
                if (inventory.slot() >= player.inventory.mainInventory.size() || !player.inventory.mainInventory.get(inventory.slot()).isEmpty()) yield new Delivery(0, Optional.empty());
                player.inventory.setInventorySlotContents(inventory.slot(), stack);
                player.inventory.markDirty();
                if (!exact(player.inventory.mainInventory.get(inventory.slot()), stack)) throw new IllegalStateException("Reward insertion changed payload");
                if (player.inventoryContainer != null) player.inventoryContainer.detectAndSendChanges();
                yield new Delivery(fragment.count(), Optional.empty());
            }
            case RewardFragment.WorldDrop drop -> {
                var world = loaded(drop);
                if (world == null) yield new Delivery(0, Optional.empty());
                var entity = new EntityItem(world, drop.x(), drop.y(), drop.z(), stack);
                entity.setUniqueId(fragment.id());
                if (world.getEntityFromUuid(fragment.id()) != null) throw new IllegalStateException("Reward entity identity already exists");
                if (!world.spawnEntity(entity)) yield new Delivery(0, Optional.empty());
                if (!exact(entity.getItem(), stack)) throw new IllegalStateException("Reward spawn changed payload");
                yield new Delivery(fragment.count(), Optional.of(entity.getUniqueID()));
            }
        };
    }

    @Override public void commit(RewardFragment fragment, Delivery delivery) {
        requireOwner(); validate(fragment);
        if (fragment.status() != RewardFragment.Status.DELIVERING || delivery.acceptedCount() != fragment.count()) throw new IllegalArgumentException("Invalid reward commit");
        var expected = new ItemStack(fragment.taggedStackNbt());
        if (fragment.target() instanceof RewardFragment.Inventory) {
            if (delivery.entityUuid().isPresent()) throw new IllegalArgumentException("Inventory reward cannot have entity identity");
            int count = 0;
            for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) if (exact(player.inventory.getStackInSlot(slot), expected)) count++;
            if (count != 1) throw new IllegalStateException("Reward inventory persistence requires exactly one tagged leaf");
            Objects.requireNonNull(player.getServerWorld().getMinecraftServer()).getPlayerList().saveAllPlayerData();
            return;
        }
        var entityId = delivery.entityUuid().orElseThrow();
        var world = loaded((RewardFragment.WorldDrop) fragment.target());
        if (world == null || !(world.getEntityFromUuid(entityId) instanceof EntityItem entity) || entity.isDead
                || !exact(entity.getItem(), expected) || world.getChunkProvider().getLoadedChunk(chunk(entity.posX), chunk(entity.posZ)) == null) {
            throw new IllegalStateException("Reward drop persistence target is unavailable");
        }
        boolean disabled = world.disableLevelSaving;
        world.disableLevelSaving = false;
        try {
            world.saveAllChunks(true, null);
            world.flush();
        } catch (MinecraftException failure) {
            throw new IllegalStateException("Reward chunk persistence failed", failure);
        } finally { world.disableLevelSaving = disabled; }
    }

    private void validate(RewardFragment fragment) {
        if (!fragment.parent().playerUuid().equals(player.getUniqueID()) || !(fragment.resource() instanceof AEItemKey key)) throw new IllegalArgumentException("Invalid reward actor/resource");
        var stack = new ItemStack(fragment.taggedStackNbt());
        var root = Objects.requireNonNull(stack.getTagCompound(), "Reward tagged payload");
        if (stack.isEmpty() || stack.getCount() != fragment.count() || !StrictNbt.requireCompound(root, DELIVERY_TAG).equals(identity(fragment.parent(), fragment.id()))) {
            throw new IllegalArgumentException("Reward save differs from tagged payload");
        }
        root.removeTag(DELIVERY_TAG);
        if (root.isEmpty()) stack.setTagCompound(null);
        if (!key.equals(AEItemKey.of(stack))) throw new IllegalArgumentException("Reward business identity differs from payload");
    }

    private static NBTTagCompound identity(RewardEventKey key, UUID fragmentId) {
        var tag = new NBTTagCompound();
        tag.setString("Player", key.playerUuid().toString()); tag.setString("Research", key.researchId().toString());
        tag.setInteger("Level", key.level()); tag.setString("Group", key.groupId().toString());
        tag.setString("Child", key.childId().toString()); tag.setString("Fragment", fragmentId.toString());
        return tag;
    }
    @Nullable private WorldServer loaded(RewardFragment.WorldDrop drop) {
        var world = Objects.requireNonNull(player.getServerWorld().getMinecraftServer()).getWorld(drop.dimensionId());
        return world != null && world.getChunkProvider().getLoadedChunk(drop.chunkX(), drop.chunkZ()) != null ? world : null;
    }
    private void requireOwner() {
        var server = player.getServerWorld().getMinecraftServer();
        if (server == null || !server.isCallingFromMinecraftThread()) throw new IllegalStateException("Rewards require the authoritative server thread");
    }
    private static int chunk(double coordinate) { return (int) Math.floor(coordinate) >> 4; }
    private static String frame(String value) { return value.length() + ":" + value; }
    private static boolean exact(ItemStack first, ItemStack second) { return !first.isEmpty() && first.getCount() == second.getCount() && ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second); }

    /** Absence is an external durable-storage responsibility, never inferred from finite scans. */
    @FunctionalInterface
    public interface AbsenceProof { boolean provenAbsent(EntityPlayerMP player, RewardFragment fragment); }
}
