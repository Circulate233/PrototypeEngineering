package com.circulation.prototype_eng.api.research.delivery;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent delivery effects and native templates, without game bootstrap or reward eligibility.
 */
class ItemDeliveryTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final DownloadOutputKey KEY = new DownloadOutputKey(PLAYER, new UUID(0, 2));
    private static final AEKey ITEM = new TestResources.Key(new TestResources.KeyType("item_delivery"), "item");

    private static ItemDelivery.Status advance(Owner owner, Adapter adapter) {
        return ItemDelivery.advance(() -> ObjectList.of(owner.current), owner::checkpoint, adapter, () -> true);
    }

    private static ItemDeliveryFragment fragment(NBTTagCompound template) {
        var id = ItemDeliveryKeyNbt.fragmentId(KEY, 0);
        return new ItemDeliveryFragment(id, KEY, ITEM, 1, ItemDeliveryTemplates.taggedLeaf(template, 1, KEY, id),
            new ItemDeliveryFragment.Inventory(2), Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
    }

    private static NBTTagCompound template() {
        var result = new NBTTagCompound();
        result.setString("id", "delivery:output");
        result.setByte("Count", (byte) 1);
        result.setShort("Damage", (short) 7);
        var business = new NBTTagCompound();
        business.setString("business", "original");
        result.setTag("tag", business);
        var caps = new NBTTagCompound();
        caps.setInteger("energy", 123);
        result.setTag("ForgeCaps", caps);
        return result;
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }

    @Test
    void nativeLeafRetainsCompleteCapabilitiesAndIsIndependentOfTemplateMutation() {
        var template = template();
        var original = template.copy();
        var fragment = fragment(template);
        assertEquals(original, template);
        assertEquals(original.getCompoundTag("ForgeCaps"), fragment.taggedStackNbt().getCompoundTag("ForgeCaps"));
        assertEquals("original", fragment.taggedStackNbt().getCompoundTag("tag").getString("business"));
        var identity = fragment.taggedStackNbt().getCompoundTag("tag").getCompoundTag(ItemDeliveryTemplates.DELIVERY_TAG);
        assertEquals(KEY, ItemDeliveryKeyNbt.readIdentity(identity));
        assertFalse(identity.hasKey("Research"));
        assertFalse(identity.hasKey("Group"));
        template.getCompoundTag("ForgeCaps").setInteger("energy", 0);
        fragment.taggedStackNbt().getCompoundTag("ForgeCaps").setInteger("energy", 1);
        assertEquals(123, fragment.taggedStackNbt().getCompoundTag("ForgeCaps").getInteger("energy"));
    }

    @Test
    void nativeFileRoundTripKeepsAnObservedFrozenOutputWithNestedEmptyListsRecoverable(@TempDir Path directory) throws IOException {
        var candidate = template();
        var cleared = new NBTTagList();
        cleared.appendTag(new NBTTagCompound());
        cleared.removeTag(0);
        candidate.getCompoundTag("tag").setTag("cleared", cleared);
        var bucket = new NBTTagCompound();
        bucket.setTag("cleared", cleared.copy());
        var buckets = new NBTTagList();
        buckets.appendTag(bucket);
        var nestedLists = new NBTTagList();
        nestedLists.appendTag(cleared.copy());
        candidate.getCompoundTag("ForgeCaps").setTag("buckets", buckets);
        candidate.getCompoundTag("ForgeCaps").setTag("nestedLists", nestedLists);
        var original = candidate.copy();
        var nativeFile = directory.resolve("native-output.dat");
        var nativeImage = candidate.copy();
        try (var output = new DataOutputStream(Files.newOutputStream(nativeFile))) {
            CompressedStreamTools.write(nativeImage, output);
        }
        try (var input = new DataInputStream(Files.newInputStream(nativeFile))) {
            assertNotEquals(original, CompressedStreamTools.read(input), "Native storage changes the in-memory type of cleared lists");
        }
        var frozen = fragment(candidate).started();
        var codec = new ItemDeliveryFragmentNbt(new ResourceKeyNbt(id -> ITEM.getType().getId().equals(id) ? ITEM.getType() : null));
        var pendingFile = directory.resolve("pending-output.dat");
        try (var output = new DataOutputStream(Files.newOutputStream(pendingFile))) {
            CompressedStreamTools.write(codec.encode(frozen), output);
        }
        ItemDeliveryFragment restored;
        try (var input = new DataInputStream(Files.newInputStream(pendingFile))) {
            restored = codec.decode(CompressedStreamTools.read(input));
        }
        frozen.requireTransitionTo(restored);
        assertEquals(original, candidate, "Freezing must leave the caller's native capability tree unchanged");
        var owner = new Owner(restored);
        var adapter = new Adapter(owner);
        adapter.physical = true;
        assertEquals(ItemDelivery.Status.COMPLETED, advance(owner, adapter));
        assertEquals(0, adapter.deliveries);
        assertEquals(1, adapter.commits);
        assertEquals(ItemDeliveryFragment.Status.CONFIRMED, owner.durable.status());
    }

    @Test
    void rewardIdentityAndOldDeterministicLeafIdsRemainCompatible() {
        var reward = new RewardEventKey(PLAYER, new ResourceLocation("delivery", "research"), 2,
            new ResourceLocation("delivery", "group"), new ResourceLocation("delivery", "child"));
        var encoded = frame(PLAYER.toString()) + frame(reward.researchId().toString()) + frame("2")
            + frame(reward.groupId().toString()) + frame(reward.childId().toString()) + frame("0");
        var id = UUID.nameUUIDFromBytes(encoded.getBytes(StandardCharsets.UTF_8));
        assertEquals(id, ItemDeliveryKeyNbt.fragmentId(reward, 0));
        var tag = ItemDeliveryKeyNbt.identity(reward, id);
        assertFalse(tag.hasKey("Kind"));
        assertEquals(reward, ItemDeliveryKeyNbt.readIdentity(tag));
        assertEquals(id, ItemDeliveryKeyNbt.fragmentId(tag));
        assertNotEquals(ItemDeliveryKeyNbt.fragmentId(KEY, 0), ItemDeliveryKeyNbt.fragmentId(new DownloadOutputKey(PLAYER, new UUID(0, 3)), 0));
    }

    @Test
    void mixedOrMalformedIdentitiesAndReservedTemplatesAreRejected() {
        var identity = ItemDeliveryKeyNbt.identity(KEY, new UUID(0, 4));
        identity.setString("Group", "delivery:fake_reward");
        assertThrows(IllegalArgumentException.class, () -> ItemDeliveryKeyNbt.readIdentity(identity));
        var reserved = template();
        reserved.getCompoundTag("tag").setTag(ItemDeliveryTemplates.DELIVERY_TAG, new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> ItemDeliveryTemplates.copy(reserved));
        var malformed = template();
        malformed.setString("ForgeCaps", "lost");
        assertThrows(IllegalArgumentException.class, () -> ItemDeliveryTemplates.copy(malformed));
    }

    @Test
    void observedDownloadEffectIsCommittedAndConfirmedWithoutReissuing() {
        var owner = new Owner(fragment(template()).started());
        var adapter = new Adapter(owner);
        adapter.physical = true;
        assertEquals(ItemDelivery.Status.COMPLETED, advance(owner, adapter));
        assertEquals(0, adapter.deliveries);
        assertEquals(1, adapter.commits);
        assertEquals(1, adapter.releases);
        assertEquals(ItemDeliveryFragment.Status.CONFIRMED, owner.durable.status());
    }

    @Test
    void unknownDownloadEffectCannotBeRetriedFromLoadedAbsence() {
        var owner = new Owner(fragment(template()).started());
        var adapter = new Adapter(owner);
        assertEquals(ItemDelivery.Status.RETRY_LATER, advance(owner, adapter));
        assertEquals(0, adapter.deliveries);
        assertEquals(ItemDeliveryFragment.Status.DELIVERING, owner.durable.status());
    }

    @Test
    void explicitZeroEffectPersistsRetryBeforeTheSameNativePlanCanRunAgain() {
        var owner = new Owner(fragment(template()));
        var adapter = new Adapter(owner);
        adapter.zero = true;
        var payload = owner.current.taggedStackNbt();
        assertEquals(ItemDelivery.Status.RETRY_LATER, advance(owner, adapter));
        assertEquals(ItemDeliveryFragment.Status.RETRY_PLANNED, owner.durable.status());
        assertEquals(payload, owner.durable.taggedStackNbt());
        adapter.zero = false;
        assertEquals(ItemDelivery.Status.COMPLETED, advance(owner, adapter));
        assertEquals(2, adapter.deliveries);
        assertEquals(payload, owner.durable.taggedStackNbt());
    }

    @Test
    void failedConfirmationCheckpointRetainsObservedEffectForRestartRecovery() {
        var owner = new Owner(fragment(template()));
        owner.failConfirmation = true;
        var adapter = new Adapter(owner);
        assertThrows(IllegalStateException.class, () -> advance(owner, adapter));
        assertTrue(adapter.physical);
        assertEquals(ItemDeliveryFragment.Status.DELIVERING, owner.durable.status());
        assertEquals(0, adapter.releases);
        owner.current = owner.durable;
        owner.failConfirmation = false;
        assertEquals(ItemDelivery.Status.COMPLETED, advance(owner, adapter));
        assertEquals(1, adapter.deliveries);
        assertEquals(2, adapter.commits);
        assertEquals(1, adapter.releases);
    }

    @Test
    void businessCheckpointCannotChangeCapabilitiesOrDownloadOwnership() {
        var original = fragment(template());
        var changed = original.taggedStackNbt();
        changed.getCompoundTag("ForgeCaps").setInteger("energy", 0);
        var changedPayload = new ItemDeliveryFragment(original.id(), KEY, ITEM, original.count(), changed, original.target(), Optional.empty(), original.status());
        assertThrows(IllegalStateException.class, () -> original.requireTransitionTo(changedPayload));
        var changedOwner = new ItemDeliveryFragment(original.id(), new DownloadOutputKey(PLAYER, new UUID(0, 8)), ITEM,
            original.count(), original.taggedStackNbt(), original.target(), Optional.empty(), original.status());
        assertThrows(IllegalStateException.class, () -> original.requireTransitionTo(changedOwner));
    }

    @Test
    void sharedFragmentCodecRetainsDownloadIdentityCapabilitiesAndExactDropTarget() {
        var codec = new ItemDeliveryFragmentNbt(new ResourceKeyNbt(id -> ITEM.getType().getId().equals(id) ? ITEM.getType() : null));
        var inventory = fragment(template());
        var drop = new ItemDeliveryFragment(inventory.id(), KEY, ITEM, 1, inventory.taggedStackNbt(),
            new ItemDeliveryFragment.WorldDrop(-1, -1, 2, -1.25, 80.5, 33.5), Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
        assertEquals(drop, codec.decode(codec.encode(drop)));
        var invalidParent = codec.encode(drop);
        invalidParent.getCompoundTag("Parent").setString("Fragment", new UUID(0, 9).toString());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidParent));
        var invalidTarget = codec.encode(drop);
        invalidTarget.getCompoundTag("Target").setInteger("ChunkX", 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidTarget));
    }

    private static final class Owner {
        private ItemDeliveryFragment current;
        private ItemDeliveryFragment durable;
        private boolean failConfirmation;

        private Owner(ItemDeliveryFragment fragment) {
            current = fragment;
            durable = fragment;
        }

        private void checkpoint(ItemDeliveryFragment next) {
            current.requireTransitionTo(next);
            current = next;
            if (failConfirmation && next.status() == ItemDeliveryFragment.Status.CONFIRMED)
                throw new IllegalStateException("checkpoint unavailable");
            durable = next;
        }
    }

    private static final class Adapter implements ItemDeliveryAdapter {
        private final Owner owner;
        private boolean physical;
        private boolean zero;
        private int deliveries;
        private int commits;
        private int releases;

        private Adapter(Owner owner) {
            this.owner = owner;
        }

        @Override
        public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, AEKey resource, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, NBTTagCompound template, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Scan scan(ObjectList<ItemDeliveryFragment> delivering) {
            var observed = new ObjectOpenHashSet<UUID>();
            if (physical) observed.add(delivering.getFirst().id());
            return new Scan(observed, new Object2ObjectOpenHashMap<>(), new ObjectOpenHashSet<>());
        }

        @Override
        public boolean canDeliver(ItemDeliveryFragment fragment) {
            return true;
        }

        @Override
        public Delivery deliver(ItemDeliveryFragment fragment) {
            assertEquals(fragment, owner.durable);
            deliveries++;
            if (zero) return new Delivery(0, Optional.empty());
            physical = true;
            return new Delivery(fragment.count(), Optional.empty());
        }

        @Override
        public void commit(ItemDeliveryFragment fragment, Delivery delivery) {
            assertTrue(physical);
            commits++;
        }

        @Override
        public void release(ItemDeliveryFragment fragment) {
            assertEquals(fragment, owner.durable);
            releases++;
        }
    }
}
