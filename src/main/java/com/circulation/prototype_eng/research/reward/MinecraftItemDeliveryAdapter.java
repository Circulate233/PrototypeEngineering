package com.circulation.prototype_eng.research.reward;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryAdapter;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Actual server inventory/drop delivery and synchronous persistence, with conservative unknown recovery.
 */
@SuppressWarnings("unused") // Library runtime entry point; no built-in gameplay or packet is registered.
public final class MinecraftItemDeliveryAdapter implements ItemDeliveryAdapter {
    public static final String DELIVERY_TAG = RewardItemProtection.DELIVERY_TAG;
    private static final String DROP_PROTECTION = "PrototypeEngineeringRewardProtection";
    private static final int MAX_FRAGMENTS = 4096;
    private final EntityPlayerMP player;
    private final MinecraftServer server;
    private final WorldServer overworld;
    private final AbsenceProof absenceProof;
    private final IntOpenHashSet reservedSlots = new IntOpenHashSet();

    public MinecraftItemDeliveryAdapter(EntityPlayerMP player) {
        this(player, (_, _) -> false);
    }

    /**
     * The proof owner must fence all movement/deletion of unconfirmed tags and inspect durable authoritative storage.
     */
    public MinecraftItemDeliveryAdapter(EntityPlayerMP player, AbsenceProof absenceProof) {
        this.player = Objects.requireNonNull(player, "player");
        server = Objects.requireNonNull(player.getServerWorld().getMinecraftServer(), "Reward server");
        overworld = Objects.requireNonNull(server.getWorld(0), "Reward overworld");
        this.absenceProof = Objects.requireNonNull(absenceProof, "absenceProof");
        if (player instanceof FakePlayer) throw new IllegalArgumentException("Rewards require a real player");
        requireOwner();
    }

    private static ItemDeliveryFragment leaf(ItemDeliveryKey parent, AEItemKey resource, NBTTagCompound template,
                                             int index, int count, ItemDeliveryFragment.Target target) {
        var id = ItemDeliveryKeyNbt.fragmentId(parent, index);
        return new ItemDeliveryFragment(id, parent, resource, count, ItemDeliveryTemplates.taggedLeaf(template, count, parent, id),
            target, Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
    }

    private static void observe(ItemStack actual, @Nullable EntityItem entity, Object2ObjectLinkedOpenHashMap<UUID, ItemDeliveryFragment> expected,
                                ObjectOpenHashSet<UUID> observed, Object2ObjectLinkedOpenHashMap<UUID, UUID> entities) {
        if (actual.isEmpty() || !actual.hasTagCompound() || !Objects.requireNonNull(actual.getTagCompound()).hasKey(DELIVERY_TAG))
            return;
        var tag = StrictNbt.requireCompound(Objects.requireNonNull(actual.getTagCompound()), DELIVERY_TAG);
        var parent = ItemDeliveryKeyNbt.readIdentity(tag);
        var fragmentId = ItemDeliveryKeyNbt.fragmentId(tag);
        var fragment = expected.get(fragmentId);
        // Validate all tagged identities before accepting or ignoring a different pending claim.
        if (fragment == null) return;
        if (!parent.equals(fragment.parent()) || !exact(actual, new ItemStack(fragment.taggedStackNbt())) || !observed.add(fragmentId)) {
            throw new IllegalStateException("Reward fragment payload conflict or duplicate: " + fragmentId);
        }
        if (entity != null) {
            entities.put(fragmentId, entity.getUniqueID());
            if (fragment.target() instanceof ItemDeliveryFragment.WorldDrop) protectDrop(entity, fragment);
        }
    }

    private static Path hostFile(SaveHandler handler) {
        return handler.getWorldDirectory().toPath().resolve("level.dat");
    }

    private static ItemStack releasedStack(ItemStack tagged) {
        var result = tagged.copy();
        var root = Objects.requireNonNull(result.getTagCompound());
        root.removeTag(DELIVERY_TAG);
        if (root.isEmpty()) result.setTagCompound(null);
        return result;
    }

    private static boolean releaseCandidate(ItemStack stack, ItemDeliveryFragment fragment, ItemStack expected) {
        if (stack.isEmpty() || !stack.hasTagCompound() || !Objects.requireNonNull(stack.getTagCompound()).hasKey(DELIVERY_TAG))
            return false;
        var tag = StrictNbt.requireCompound(Objects.requireNonNull(stack.getTagCompound()), DELIVERY_TAG);
        if (!fragment.id().toString().equals(StrictNbt.requireString(tag, "Fragment"))) return false;
        if (!exact(stack, expected))
            throw new IllegalStateException("Confirmed reward leaf payload changed: " + fragment.id());
        return true;
    }

    private static void protectDrop(EntityItem entity, ItemDeliveryFragment fragment) {
        var data = entity.getEntityData();
        var state = new NBTTagCompound();
        entity.writeEntityToNBT(state);
        if (data.hasKey(DROP_PROTECTION)) {
            requireDropProtection(data, fragment);
        } else {
            var retained = new NBTTagCompound();
            retained.setString("Fragment", fragment.id().toString());
            retained.setShort("Age", state.getShort("Age"));
            retained.setShort("PickupDelay", state.getShort("PickupDelay"));
            retained.setBoolean("Invulnerable", entity.getIsInvulnerable());
            data.setTag(DROP_PROTECTION, retained);
        }
        // Vanilla pauses age increment and merging for this sentinel; setNoDespawn only delays age.
        state.setShort("Age", Short.MIN_VALUE);
        entity.readEntityFromNBT(state);
        entity.setInfinitePickupDelay();
        entity.setEntityInvulnerable(true);
    }

    private static void restoreDrop(EntityItem entity, ItemDeliveryFragment fragment) {
        var data = entity.getEntityData();
        if (!data.hasKey(DROP_PROTECTION)) return;
        var retained = requireDropProtection(data, fragment);
        var state = new NBTTagCompound();
        entity.writeEntityToNBT(state);
        state.setTag("Age", retained.getTag("Age").copy());
        state.setTag("PickupDelay", retained.getTag("PickupDelay").copy());
        entity.readEntityFromNBT(state);
        entity.setEntityInvulnerable(retained.getBoolean("Invulnerable"));
        data.removeTag(DROP_PROTECTION);
    }

    private static NBTTagCompound requireDropProtection(NBTTagCompound data, ItemDeliveryFragment fragment) {
        var retained = StrictNbt.requireCompound(data, DROP_PROTECTION);
        StrictNbt.requireKeys(retained, "Fragment", "Age", "PickupDelay", "Invulnerable");
        if (!fragment.id().toString().equals(StrictNbt.requireString(retained, "Fragment")))
            throw new IllegalStateException("Reward drop protection identity changed");
        if (!retained.hasKey("Age", Constants.NBT.TAG_SHORT) || !retained.hasKey("PickupDelay", Constants.NBT.TAG_SHORT)
            || !retained.hasKey("Invulnerable", Constants.NBT.TAG_BYTE))
            throw new IllegalStateException("Reward drop protection state is malformed");
        return retained;
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }

    private static boolean exact(ItemStack first, ItemStack second) {
        return !first.isEmpty() && first.getCount() == second.getCount() && ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    @Override
    public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, AEKey resource, long amount) {
        requireOwner();
        if (!(resource instanceof AEItemKey key)) return ObjectList.of();
        return plan(parent, key.toStack().writeToNBT(new NBTTagCompound()), amount);
    }

    @Override
    public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, NBTTagCompound nativeTemplate, long amount) {
        requireOwner();
        if (!parent.playerUuid().equals(player.getUniqueID()))
            throw new IllegalArgumentException("Reward actor changed");
        if (amount < 1) throw new IllegalArgumentException("Item delivery amount must be positive");
        var template = ItemDeliveryTemplates.copy(nativeTemplate);
        requirePlayerSaveHandler();
        var logical = new ItemStack(template);
        var key = AEItemKey.of(logical);
        if (logical.isEmpty() || key == null)
            throw new IllegalArgumentException("Invalid native item delivery template");
        int maximum = Math.min(Byte.MAX_VALUE, Math.min(logical.getMaxStackSize(), player.inventory.getInventoryStackLimit()));
        if (maximum < 1 || amount > (long) maximum * MAX_FRAGMENTS) return ObjectList.of();
        long remaining = amount;
        var result = new ObjectArrayList<ItemDeliveryFragment>();
        // Different immutable leaf tags cannot be merged into an existing business stack.
        for (int slot = 0; slot < player.inventory.mainInventory.size() && remaining > 0; slot++) {
            if (!player.inventory.mainInventory.get(slot).isEmpty() || reservedSlots.contains(slot)) continue;
            int count = (int) Math.min(remaining, maximum);
            result.add(leaf(parent, key, template, result.size(), count, new ItemDeliveryFragment.Inventory(slot)));
            reservedSlots.add(slot);
            remaining -= count;
        }
        var world = player.getServerWorld();
        if (remaining > 0 && !(world.getChunkProvider().chunkLoader instanceof AnvilChunkLoader)) {
            throw new IllegalStateException("Reward persistence requires the authoritative Anvil chunk loader");
        }
        var drop = new ItemDeliveryFragment.WorldDrop(world.provider.getDimension(), chunk(player.posX), chunk(player.posZ), player.posX, player.posY + 0.5, player.posZ);
        while (remaining > 0) {
            int count = (int) Math.min(remaining, maximum);
            result.add(leaf(parent, key, template, result.size(), count, drop));
            remaining -= count;
        }
        return ObjectLists.unmodifiable(result);
    }

    @Override
    public Scan scan(ObjectList<ItemDeliveryFragment> fragments) {
        requireOwner();
        var expected = new Object2ObjectLinkedOpenHashMap<UUID, ItemDeliveryFragment>();
        for (var fragment : fragments) {
            validate(fragment);
            if (fragment.status() != ItemDeliveryFragment.Status.DELIVERING || expected.putIfAbsent(fragment.id(), fragment) != null) {
                throw new IllegalArgumentException("Invalid reward recovery plan");
            }
        }
        var observed = new ObjectOpenHashSet<UUID>();
        var entities = new Object2ObjectLinkedOpenHashMap<UUID, UUID>();
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++)
            observe(player.inventory.getStackInSlot(slot), null, expected, observed, entities);
        observe(player.inventory.getItemStack(), null, expected, observed, entities);
        for (var world : DimensionManager.getWorlds()) {
            if (world.getMinecraftServer() != server) continue;
            for (var entity : world.loadedEntityList) {
                if (entity instanceof EntityItem item && !item.isDead) {
                    observe(item.getItem(), item, expected, observed, entities);
                }
            }
        }
        var unobserved = new ObjectArrayList<ItemDeliveryFragment>();
        for (var fragment : fragments) if (!observed.contains(fragment.id())) unobserved.add(fragment);
        var handler = requirePlayerSaveHandler();
        ObjectSet<UUID> absent;
        try {
            // Read before any player save can replace evidence. A failed ISSUING save leaves the
            // live journal ISSUING, so an older PREPARED file cannot authorize another effect.
            absent = RewardDeliveryPersistence.preparedFragments(playerFile(handler), isHost() ? hostFile(handler) : null,
                player.getUniqueID(), readJournal(), unobserved);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        for (var fragment : unobserved) {
            if (!absent.contains(fragment.id()) && absenceProof.provenAbsent(player, fragment))
                absent.add(fragment.id());
        }
        requireOwner();
        return new Scan(observed, entities, absent);
    }

    @Override
    public boolean canDeliver(ItemDeliveryFragment fragment) {
        requireOwner();
        validate(fragment);
        if (fragment.status() != ItemDeliveryFragment.Status.PLANNED && fragment.status() != ItemDeliveryFragment.Status.RETRY_PLANNED
            && fragment.status() != ItemDeliveryFragment.Status.DELIVERING) return false;
        return switch (fragment.target()) {
            case ItemDeliveryFragment.Inventory inventory -> {
                requirePlayerSaveHandler();
                yield inventory.slot() < player.inventory.mainInventory.size() && player.inventory.mainInventory.get(inventory.slot()).isEmpty();
            }
            case ItemDeliveryFragment.WorldDrop drop -> {
                var world = loaded(drop);
                if (world != null && !(world.getChunkProvider().chunkLoader instanceof AnvilChunkLoader)) {
                    throw new IllegalStateException("Reward persistence requires the authoritative Anvil chunk loader");
                }
                yield world != null;
            }
        };
    }

    @Override
    public void prepare(ItemDeliveryFragment fragment) {
        requireOwner();
        validate(fragment);
        var journal = readJournal();
        journal.prepare(fragment);
        journal.writeTo(player.getEntityData());
        persistPlayer(null);
    }

    @Override
    public Delivery deliver(ItemDeliveryFragment fragment) {
        requireOwner();
        validate(fragment);
        if (fragment.status() != ItemDeliveryFragment.Status.DELIVERING)
            throw new IllegalStateException("Reward fragment has not durably started");
        var journal = readJournal();
        journal.issue(fragment);
        // Publish first so every intervening vanilla save carries ISSUING, including on failure.
        journal.writeTo(player.getEntityData());
        persistPlayer(null);
        var stack = new ItemStack(fragment.taggedStackNbt());
        return switch (fragment.target()) {
            case ItemDeliveryFragment.Inventory inventory -> {
                requirePlayerSaveHandler();
                if (inventory.slot() >= player.inventory.mainInventory.size() || !player.inventory.mainInventory.get(inventory.slot()).isEmpty())
                    yield new Delivery(0, Optional.empty());
                player.inventory.setInventorySlotContents(inventory.slot(), stack);
                player.inventory.markDirty();
                if (!exact(player.inventory.mainInventory.get(inventory.slot()), stack))
                    throw new IllegalStateException("Reward insertion changed payload");
                if (player.inventoryContainer != null) player.inventoryContainer.detectAndSendChanges();
                requireOwner();
                yield new Delivery(fragment.count(), Optional.empty());
            }
            case ItemDeliveryFragment.WorldDrop drop -> {
                var world = loaded(drop);
                if (world == null) yield new Delivery(0, Optional.empty());
                var entity = new EntityItem(world, drop.x(), drop.y(), drop.z(), stack);
                entity.setUniqueId(fragment.id());
                protectDrop(entity, fragment);
                if (world.getEntityFromUuid(fragment.id()) != null)
                    throw new IllegalStateException("Reward entity identity already exists");
                // Forge join callbacks have already seen the tagged entity. A cancelled spawn is
                // not proof that a callback left it untouched or did not transfer its contents.
                if (!world.spawnEntity(entity)) yield new Delivery(0, Optional.of(entity.getUniqueID()));
                requireOwner();
                if (!exact(entity.getItem(), stack)) throw new IllegalStateException("Reward spawn changed payload");
                yield new Delivery(fragment.count(), Optional.of(entity.getUniqueID()));
            }
        };
    }

    @Override
    public void commit(ItemDeliveryFragment fragment, Delivery delivery) {
        requireOwner();
        validate(fragment);
        if (fragment.status() != ItemDeliveryFragment.Status.DELIVERING || delivery.acceptedCount() != fragment.count())
            throw new IllegalArgumentException("Invalid reward commit");
        if (readJournal().contains(fragment, RewardDeliveryJournal.State.PREPARED)) {
            throw new IllegalStateException("Observed reward effect contradicts its prepared journal");
        }
        var expected = new ItemStack(fragment.taggedStackNbt());
        if (fragment.target() instanceof ItemDeliveryFragment.Inventory) {
            if (delivery.entityUuid().isPresent())
                throw new IllegalArgumentException("Inventory reward cannot have entity identity");
            requireInventoryLeaf(expected);
            persistPlayer(fragment.taggedStackNbt());
            requireInventoryLeaf(expected);
            return;
        }
        var entityId = delivery.entityUuid().orElseThrow();
        var world = loaded((ItemDeliveryFragment.WorldDrop) fragment.target());
        if (world == null || !(world.getEntityFromUuid(entityId) instanceof EntityItem entity) || entity.isDead
            || !exact(entity.getItem(), expected) || world.getChunkProvider().getLoadedChunk(chunk(entity.posX), chunk(entity.posZ)) == null) {
            throw new IllegalStateException("Reward drop persistence target is unavailable");
        }
        if (!(world.getChunkProvider().chunkLoader instanceof AnvilChunkLoader loader)) {
            throw new IllegalStateException("Reward persistence requires the authoritative Anvil chunk loader");
        }
        var savedChunk = world.getChunkProvider().getLoadedChunk(chunk(entity.posX), chunk(entity.posZ));
        protectDrop(entity, fragment);
        try {
            // Use the existing exact chunk writer. A full world save would reenter WorldEvent.Save.
            loader.saveChunk(world, Objects.requireNonNull(savedChunk));
            ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
            requireOwner();
            if (loader.getPendingSaveCount() != 0)
                throw new IllegalStateException("Reward chunk save still has pending writes");
            RewardDeliveryPersistence.verifyDrop(loader.chunkSaveLocation.toPath(), savedChunk.x, savedChunk.z, entityId, fragment.taggedStackNbt(),
                StrictNbt.requireCompound(entity.getEntityData(), DROP_PROTECTION));
            requireOwner();
            if (world.getEntityFromUuid(entityId) != entity || entity.world != world || entity.isDead || !exact(entity.getItem(), expected)
                || chunk(entity.posX) != savedChunk.x || chunk(entity.posZ) != savedChunk.z
                || world.getChunkProvider().getLoadedChunk(savedChunk.x, savedChunk.z) != savedChunk) {
                throw new IllegalStateException("Reward drop changed during its persistence barrier");
            }
        } catch (MinecraftException failure) {
            throw new IllegalStateException("Reward chunk persistence failed", failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reward chunk persistence was interrupted", failure);
        }
    }

    private void requireInventoryLeaf(ItemStack expected) {
        int count = exact(player.inventory.getItemStack(), expected) ? 1 : 0;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++)
            if (exact(player.inventory.getStackInSlot(slot), expected)) count++;
        if (count != 1)
            throw new IllegalStateException("Reward inventory persistence requires exactly one tagged leaf");
    }

    private NBTTagCompound protectedCursor() {
        var cursor = player.inventory.getItemStack();
        return RewardItemProtection.isProtected(cursor) ? cursor.writeToNBT(new NBTTagCompound()) : new NBTTagCompound();
    }

    private RewardDeliveryJournal readJournal() {
        return RewardDeliveryJournal.read(player.getEntityData(), player.getUniqueID());
    }

    /**
     * Uses vanilla's existing writers and verifies both integrated-host recovery sources.
     */
    private void persistPlayer(@Nullable NBTTagCompound leaf) {
        var journal = readJournal().snapshot();
        var cursor = protectedCursor();
        PlayerDataPersistence.persist(player, this::requireOwner,
            saved -> RewardDeliveryPersistence.verifySavedPlayerState(saved, player.getUniqueID(), journal, cursor, leaf));
        requireOwner();
        if (!journal.equals(readJournal().snapshot()) || !cursor.equals(protectedCursor())) {
            throw new IllegalStateException("Reward player evidence changed during its persistence barrier");
        }
    }

    private boolean isHost() {
        return server.isSinglePlayer() && player.getName().equals(server.getServerOwner());
    }

    private Path playerFile(SaveHandler handler) {
        return handler.getWorldDirectory().toPath().resolve("playerdata").resolve(player.getUniqueID() + ".dat");
    }

    private SaveHandler requirePlayerSaveHandler() {
        var handler = overworld.getSaveHandler();
        if (!(handler instanceof SaveHandler vanilla) || handler.getPlayerNBTManager() != handler) {
            throw new IllegalStateException("Reward persistence requires the authoritative vanilla player save handler");
        }
        return vanilla;
    }

    @Override
    public void release(ItemDeliveryFragment fragment) {
        requireOwner();
        validate(fragment);
        if (fragment.status() != ItemDeliveryFragment.Status.CONFIRMED)
            throw new IllegalArgumentException("Only durably confirmed reward fragments can be released");
        // Validate even when the already released item is gone; a reused receipt ID is not ours.
        readJournal().contains(fragment, RewardDeliveryJournal.State.ISSUING);
        var expected = new ItemStack(fragment.taggedStackNbt());
        var slots = new IntArrayList();
        var drops = new ObjectArrayList<EntityItem>();
        boolean cursor = releaseCandidate(player.inventory.getItemStack(), fragment, expected);
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            var stack = player.inventory.getStackInSlot(slot);
            if (releaseCandidate(stack, fragment, expected)) slots.add(slot);
        }
        for (var world : DimensionManager.getWorlds()) {
            if (world.getMinecraftServer() != server) continue;
            for (var value : world.loadedEntityList) {
                if (value instanceof EntityItem entity && !entity.isDead && releaseCandidate(entity.getItem(), fragment, expected)) {
                    drops.add(entity);
                }
            }
        }
        if (slots.size() + drops.size() + (cursor ? 1 : 0) > 1)
            throw new IllegalStateException("Duplicate confirmed reward leaf " + fragment.id());
        for (int slot : slots)
            player.inventory.setInventorySlotContents(slot, releasedStack(player.inventory.getStackInSlot(slot)));
        if (cursor) player.inventory.setItemStack(releasedStack(player.inventory.getItemStack()));
        for (var entity : drops) {
            restoreDrop(entity, fragment);
            entity.setItem(releasedStack(entity.getItem()));
            if (entity.world instanceof WorldServer world) {
                var loadedChunk = world.getChunkProvider().getLoadedChunk(chunk(entity.posX), chunk(entity.posZ));
                if (loadedChunk != null) loadedChunk.markDirty();
            }
        }
        if (!slots.isEmpty() || cursor) {
            player.inventory.markDirty();
            if (player.inventoryContainer != null) player.inventoryContainer.detectAndSendChanges();
            if (cursor) player.sendContainerToPlayer(player.openContainer);
        }
        requireOwner();
        var journal = readJournal();
        if (journal.remove(fragment)) {
            journal.writeTo(player.getEntityData());
            player.inventory.markDirty();
        }
        // Cleanup is repeatable after reload. Missing/previously released leaves never create items.
    }

    private void validate(ItemDeliveryFragment fragment) {
        if (!fragment.parent().playerUuid().equals(player.getUniqueID()) || !(fragment.resource() instanceof AEItemKey key))
            throw new IllegalArgumentException("Invalid reward actor/resource");
        var stack = new ItemStack(fragment.taggedStackNbt());
        var root = Objects.requireNonNull(stack.getTagCompound(), "Reward tagged payload");
        if (stack.isEmpty() || stack.getCount() != fragment.count() || !StrictNbt.requireCompound(root, DELIVERY_TAG).equals(ItemDeliveryKeyNbt.identity(fragment.parent(), fragment.id()))) {
            throw new IllegalArgumentException("Reward save differs from tagged payload");
        }
        root.removeTag(DELIVERY_TAG);
        if (root.isEmpty()) stack.setTagCompound(null);
        if (!key.equals(AEItemKey.of(stack)))
            throw new IllegalArgumentException("Reward business identity differs from payload");
    }

    @Nullable
    private WorldServer loaded(ItemDeliveryFragment.WorldDrop drop) {
        var world = server.getWorld(drop.dimensionId());
        return world != null && world.getChunkProvider().getLoadedChunk(drop.chunkX(), drop.chunkZ()) != null ? world : null;
    }

    private void requireOwner() {
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Rewards require the authoritative server thread");
        if (player.world.isRemote || player.getServerWorld().getMinecraftServer() != server || server.getWorld(0) != overworld
            || server.getWorld(player.dimension) != player.getServerWorld() || player.connection == null
            || server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) {
            throw new IllegalStateException("Reward adapter no longer owns its actual online player/server");
        }
    }

    /**
     * Absence is an external durable-storage responsibility, never inferred from finite scans.
     */
    @FunctionalInterface
    public interface AbsenceProof {
        boolean provenAbsent(EntityPlayerMP player, ItemDeliveryFragment fragment);
    }
}
