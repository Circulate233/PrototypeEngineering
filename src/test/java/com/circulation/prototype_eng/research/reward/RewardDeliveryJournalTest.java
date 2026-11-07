package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.delivery.DownloadOutputKey;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryTemplates;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import com.circulation.prototype_eng.testsupport.TestResources;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actual NBT state transitions without players, registries, a Minecraft server or bootstrap.
 */
class RewardDeliveryJournalTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER_PLAYER = new UUID(0, 2);
    private static final UUID LEAF = new UUID(0, 3);
    private static final TestResources.Key ITEM = new TestResources.Key(new TestResources.KeyType("delivery_journal"), "item");
    private static final RewardEventKey PARENT = new RewardEventKey(PLAYER, id("research"), 2, id("group"), id("child"));

    private static void rejectsMutation(NBTTagCompound original, Consumer<NBTTagCompound> mutation) {
        var changed = original.copy();
        mutation.accept(changed);
        // NBTTagDouble.equals uses primitive ==, so a NaN does not equal even its own deep copy.
        var before = assertDoesNotThrow(() -> nbtBytes(changed));
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.read(changed, PLAYER));
        assertArrayEquals(before, assertDoesNotThrow(() -> nbtBytes(changed)));
    }

    private static byte[] nbtBytes(NBTTagCompound tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
        }
        return bytes.toByteArray();
    }

    private static RewardDeliveryJournal prepared(ItemDeliveryFragment fragment) {
        var journal = RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
        journal.prepare(fragment);
        return journal;
    }

    private static NBTTagCompound saved(ItemDeliveryFragment fragment) {
        var data = new NBTTagCompound();
        prepared(fragment).writeTo(data);
        return data;
    }

    private static NBTTagCompound journalEmptySnapshot() {
        return RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER).snapshot();
    }

    private static NBTTagCompound store(NBTTagCompound data) {
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getCompoundTag("prototype_eng").getCompoundTag("reward_delivery_receipts");
    }

    private static NBTTagCompound firstReceipt(NBTTagCompound data) {
        return store(data).getTagList("receipts", 10).getCompoundTagAt(0);
    }

    private static ItemDeliveryFragment fragment(UUID id, RewardEventKey parent, int count, ItemDeliveryFragment.Target target) {
        var identity = new NBTTagCompound();
        identity.setString("Player", parent.playerUuid().toString());
        identity.setString("Research", parent.researchId().toString());
        identity.setInteger("Level", parent.level());
        identity.setString("Group", parent.groupId().toString());
        identity.setString("Child", parent.childId().toString());
        identity.setString("Fragment", id.toString());
        var tag = new NBTTagCompound();
        tag.setTag(RewardItemProtection.DELIVERY_TAG, identity);
        tag.setString("business", "retained");
        var stack = new NBTTagCompound();
        stack.setString("id", "minecraft:stone");
        stack.setByte("Count", (byte) count);
        stack.setShort("Damage", (short) 0);
        stack.setTag("tag", tag);
        return new ItemDeliveryFragment(id, parent, ITEM, count, stack, target, Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("prototype_eng", path);
    }

    @Test
    void legacyRewardJournalMigratesWithoutChangingItsFrozenEvidence() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var modern = saved(planned);
        var legacy = modern.copy();
        store(legacy).setInteger("version", 1);
        var recovered = RewardDeliveryJournal.read(legacy, PLAYER);
        assertTrue(recovered.contains(planned, RewardDeliveryJournal.State.PREPARED));
        assertEquals(RewardDeliveryJournal.read(modern, PLAYER).snapshot(), recovered.snapshot());
    }

    @Test
    void independentDownloadReceiptRetainsCapabilitiesAndRejectsOperationSubstitution() {
        var key = new DownloadOutputKey(PLAYER, new UUID(0, 10));
        var nativeStack = new NBTTagCompound();
        nativeStack.setString("id", "delivery_journal:output");
        nativeStack.setByte("Count", (byte) 1);
        nativeStack.setShort("Damage", (short) 0);
        var caps = new NBTTagCompound();
        caps.setInteger("energy", 123);
        nativeStack.setTag("ForgeCaps", caps);
        var leaf = ItemDeliveryKeyNbt.fragmentId(key, 0);
        var planned = new ItemDeliveryFragment(leaf, key, ITEM, 1, ItemDeliveryTemplates.taggedLeaf(nativeStack, 1, key, leaf),
            new ItemDeliveryFragment.Inventory(7), Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
        var data = saved(planned);
        var recovered = RewardDeliveryJournal.read(data, PLAYER);
        assertTrue(recovered.contains(planned, RewardDeliveryJournal.State.PREPARED));
        recovered.issue(planned.started());
        assertTrue(recovered.contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertEquals(caps, firstReceipt(data).getCompoundTag("stack").getCompoundTag("ForgeCaps"));
        rejectsMutation(data, changed -> firstReceipt(changed).getCompoundTag("delivery").setString("Operation", new UUID(0, 11).toString()));
        rejectsMutation(data, changed -> store(changed).setInteger("version", 1));
    }

    @Test
    void preparedIssueAndConfirmedRemovalSurviveActualNbtReloads() {
        var data = new NBTTagCompound();
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var journal = RewardDeliveryJournal.read(data, PLAYER);
        assertFalse(journal.contains(planned, RewardDeliveryJournal.State.PREPARED));
        assertTrue(journal.prepare(planned));
        assertFalse(journal.prepare(planned));
        journal.writeTo(data);
        var prepared = RewardDeliveryJournal.read(data.copy(), PLAYER);
        assertTrue(prepared.contains(planned, RewardDeliveryJournal.State.PREPARED));
        assertFalse(prepared.contains(planned, RewardDeliveryJournal.State.ISSUING));
        prepared.issue(planned.started());
        prepared.writeTo(data);
        var issuing = RewardDeliveryJournal.read(data.copy(), PLAYER);
        assertTrue(issuing.contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertFalse(issuing.contains(planned, RewardDeliveryJournal.State.PREPARED));
        assertTrue(issuing.remove(planned.started().confirmed(null)));
        assertFalse(issuing.remove(planned.started().confirmed(null)));
        issuing.writeTo(data);
        assertEquals(journalEmptySnapshot(), RewardDeliveryJournal.read(data, PLAYER).snapshot());
    }

    @Test
    void issuingCannotRestartWithoutAnExplicitNoEffectRetry() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var journal = prepared(planned);
        journal.issue(planned.started());
        var issued = journal.snapshot();
        assertThrows(IllegalStateException.class, () -> journal.issue(planned.started()));
        assertThrows(IllegalStateException.class, () -> journal.prepare(planned));
        assertThrows(IllegalStateException.class, () -> journal.prepare(planned.started()));
        assertEquals(issued, journal.snapshot());
        assertTrue(journal.prepare(planned.started().retryPlanned()));
        assertFalse(journal.prepare(planned.started().retryPlanned()));
        journal.issue(planned.started());
        assertEquals(issued, journal.snapshot());
    }

    @Test
    void missingAndUnstartedReceiptsCannotIssueAndUnconfirmedCannotRemove() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var journal = RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
        assertThrows(IllegalStateException.class, () -> journal.issue(planned.started()));
        assertThrows(IllegalStateException.class, () -> journal.issue(planned));
        assertThrows(IllegalStateException.class, () -> journal.remove(planned.started()));
        assertEquals(journalEmptySnapshot(), journal.snapshot());
    }

    @Test
    void matchingIdCannotChangeCountParentPayloadOrTarget() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var journal = prepared(planned);
        var original = journal.snapshot();
        var changedParent = new RewardEventKey(PLAYER, id("other_research"), 2, id("group"), id("child"));
        var changedStack = planned.taggedStackNbt();
        changedStack.getCompoundTag("tag").setString("business", "changed");
        var payload = new ItemDeliveryFragment(LEAF, PARENT, ITEM, 2, changedStack, planned.target(), Optional.empty(), ItemDeliveryFragment.Status.PLANNED);
        for (var conflict : new ItemDeliveryFragment[]{
            fragment(LEAF, PARENT, 3, planned.target()), fragment(LEAF, changedParent, 2, planned.target()), payload,
            fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(8))
        }) {
            assertThrows(IllegalStateException.class, () -> journal.prepare(conflict));
            assertThrows(IllegalStateException.class, () -> journal.issue(conflict.started()));
            assertThrows(IllegalStateException.class, () -> journal.contains(conflict, RewardDeliveryJournal.State.PREPARED));
            assertThrows(IllegalStateException.class, () -> journal.remove(conflict.started().confirmed(null)));
            assertEquals(original, journal.snapshot());
        }
    }

    @Test
    void dropReceiptsKeepTheirExactDimensionChunkAndPosition() {
        var target = new ItemDeliveryFragment.WorldDrop(-1, -1, 2, -1.25, 80.5, 33.5);
        var planned = fragment(LEAF, PARENT, 2, target);
        var data = new NBTTagCompound();
        var journal = prepared(planned);
        journal.issue(planned.started());
        journal.writeTo(data);
        var restored = RewardDeliveryJournal.read(data, PLAYER);
        assertTrue(restored.contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertThrows(IllegalStateException.class, () -> restored.contains(
            fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.WorldDrop(-1, -1, 2, -1.25, 81.5, 33.5)), RewardDeliveryJournal.State.ISSUING));
        assertTrue(restored.remove(planned.started().confirmed(LEAF)));
    }

    @Test
    void multipleReceiptsKeepTheirOrderAndRemovalDoesNotEraseAnother() {
        var first = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var second = fragment(new UUID(0, 4), PARENT, 1, new ItemDeliveryFragment.Inventory(8));
        var journal = prepared(first);
        journal.prepare(second);
        journal.issue(first.started());
        journal.prepare(second);
        var receipts = journal.snapshot().getTagList("receipts", 10);
        assertEquals(LEAF.toString(), receipts.getCompoundTagAt(0).getString("fragment"));
        assertEquals(second.id().toString(), receipts.getCompoundTagAt(1).getString("fragment"));
        journal.remove(first.started().confirmed(null));
        assertTrue(journal.contains(second, RewardDeliveryJournal.State.PREPARED));
        assertEquals(1, journal.snapshot().getTagList("receipts", 10).tagCount());
    }

    @Test
    void writesPreserveCurrentManualReceiptsAndOtherNamespaces() {
        var data = new NBTTagCompound();
        var persisted = new NBTTagCompound();
        var namespace = new NBTTagCompound();
        var manual = new NBTTagCompound();
        manual.setString("untouched", "manual receipt");
        namespace.setTag("manual_material_receipts", manual);
        persisted.setTag("prototype_eng", namespace);
        persisted.setString("other_mod", "retained");
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        data.setString("outside_persisted", "retained");
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var journal = RewardDeliveryJournal.read(data, PLAYER);
        journal.prepare(planned);
        // A different owner may update its own data after this journal snapshot was read.
        namespace.setString("new_manual_metadata", "latest");
        journal.writeTo(data);
        assertEquals(manual, data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getCompoundTag("prototype_eng").getCompoundTag("manual_material_receipts"));
        assertEquals("latest", data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getCompoundTag("prototype_eng").getString("new_manual_metadata"));
        journal.remove(planned.started().confirmed(null));
        journal.writeTo(data);
        var cleaned = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        assertEquals("retained", cleaned.getString("other_mod"));
        assertEquals("retained", data.getString("outside_persisted"));
        assertFalse(cleaned.getCompoundTag("prototype_eng").hasKey("reward_delivery_receipts"));
        assertEquals(manual, cleaned.getCompoundTag("prototype_eng").getCompoundTag("manual_material_receipts"));
    }

    @Test
    void decodingAndSnapshotsDoNotRetainBorrowedNbt() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var data = new NBTTagCompound();
        prepared(planned).writeTo(data);
        var restored = RewardDeliveryJournal.read(data, PLAYER);
        var expected = restored.snapshot();
        firstReceipt(data).getCompoundTag("stack").getCompoundTag("tag").setString("business", "borrowed mutation");
        var snapshot = restored.snapshot();
        snapshot.getTagList("receipts", 10).getCompoundTagAt(0).getCompoundTag("stack").setByte("Count", (byte) 99);
        assertEquals(expected, restored.snapshot());
        assertTrue(restored.contains(planned, RewardDeliveryJournal.State.PREPARED));
        restored.writeTo(data);
        assertEquals(expected, RewardDeliveryJournal.read(data, PLAYER).snapshot());
    }

    @Test
    void wrongPlayersAndDuplicateIdsFailDuringReload() {
        var data = saved(fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7)));
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.read(data, OTHER_PLAYER));
        var list = store(data).getTagList("receipts", 10);
        list.appendTag(list.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.read(data, PLAYER));
        var other = new RewardEventKey(OTHER_PLAYER, id("research"), 2, id("group"), id("child"));
        var empty = RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
        assertThrows(IllegalArgumentException.class, () -> empty.prepare(fragment(LEAF, other, 2, new ItemDeliveryFragment.Inventory(7))));
        assertEquals(journalEmptySnapshot(), empty.snapshot());
    }

    @Test
    void nestedWrongTypesVersionsAndUnknownFieldsFailBeforeAnyPublication() {
        var data = saved(fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7)));
        rejectsMutation(data, tag -> tag.setString(EntityPlayer.PERSISTED_NBT_TAG, "wrong type"));
        rejectsMutation(data, tag -> tag.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).setInteger("prototype_eng", 1));
        rejectsMutation(data, tag -> store(tag).setByte("version", (byte) 1));
        rejectsMutation(data, tag -> store(tag).setInteger("version", 3));
        rejectsMutation(data, tag -> store(tag).setBoolean("unknown", true));
        rejectsMutation(data, tag -> firstReceipt(tag).setLong("count", 2));
        rejectsMutation(data, tag -> firstReceipt(tag).setString("state", "CONFIRMED"));
        rejectsMutation(data, tag -> firstReceipt(tag).setBoolean("runtimePermission", true));
        rejectsMutation(data, tag -> firstReceipt(tag).setString("fragment", "0-0-0-0-3"));
        rejectsMutation(data, tag -> firstReceipt(tag).removeTag("child"));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setString("type", "unknown"));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setInteger("slot", 36));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setString("runtimeWorld", "ignored"));
    }

    @Test
    void inconsistentCountsAndEmbeddedIdentitiesCannotCreateAbsenceEvidence() {
        var data = saved(fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7)));
        rejectsMutation(data, tag -> firstReceipt(tag).setInteger("count", 0));
        rejectsMutation(data, tag -> firstReceipt(tag).setInteger("count", 128));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("stack").setByte("Count", (byte) 3));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("stack").getCompoundTag("tag").removeTag(RewardItemProtection.DELIVERY_TAG));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("stack").getCompoundTag("tag")
                                                      .getCompoundTag(RewardItemProtection.DELIVERY_TAG).setString("Player", OTHER_PLAYER.toString()));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("stack").setString("id", "stone"));
    }

    @Test
    void malformedDropCoordinatesAndListElementsCannotReload() {
        var data = saved(fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.WorldDrop(-1, -1, 2, -1.25, 80.5, 33.5)));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setDouble("x", Double.NaN));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setInteger("chunkX", 0));
        rejectsMutation(data, tag -> firstReceipt(tag).getCompoundTag("target").setFloat("y", 80.5f));
        var strings = new NBTTagList();
        strings.appendTag(new NBTTagString("not a receipt"));
        rejectsMutation(data, tag -> store(tag).setTag("receipts", strings));
    }

    @Test
    void receiptLimitAppliesToBothReloadAndNewPreparation() {
        var journal = RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
        for (int index = 0; index < RewardDeliveryJournal.MAX_RECEIPTS; index++) {
            journal.prepare(fragment(new UUID(1, index), PARENT, 1, new ItemDeliveryFragment.Inventory(0)));
        }
        assertThrows(IllegalStateException.class, () -> journal.prepare(fragment(LEAF, PARENT, 1, new ItemDeliveryFragment.Inventory(1))));
        var data = new NBTTagCompound();
        journal.writeTo(data);
        assertEquals(journal.snapshot(), RewardDeliveryJournal.read(data, PLAYER).snapshot());
        var another = prepared(fragment(LEAF, PARENT, 1, new ItemDeliveryFragment.Inventory(1)));
        assertThrows(IllegalStateException.class, () -> RewardDeliveryJournal.reconcile(journal, another, null));
        var list = store(data).getTagList("receipts", 10);
        list.appendTag(list.getCompoundTagAt(0).copy());
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.read(data, PLAYER));
    }

    @Test
    void reconcileRetainsPreparedOnlyWhenEveryRequiredSourceAgrees() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var loaded = prepared(planned);
        var saved = prepared(planned);
        var host = prepared(planned);
        assertEquals(loaded.snapshot(), RewardDeliveryJournal.reconcile(loaded, saved, host).snapshot());
        assertEquals(loaded.snapshot(), RewardDeliveryJournal.reconcile(loaded, saved, null).snapshot());
    }

    @Test
    void reconcileMakesIssuingWinRegardlessOfItsAuthoritativeSource() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var ready = prepared(planned);
        var issuing = prepared(planned);
        issuing.issue(planned.started());
        assertTrue(RewardDeliveryJournal.reconcile(issuing, ready, ready).contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertTrue(RewardDeliveryJournal.reconcile(ready, issuing, ready).contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertTrue(RewardDeliveryJournal.reconcile(ready, ready, issuing).contains(planned, RewardDeliveryJournal.State.ISSUING));
        assertTrue(RewardDeliveryJournal.reconcile(ready, issuing, null).contains(planned, RewardDeliveryJournal.State.ISSUING));
    }

    @Test
    void reconcilePromotesMissingSourcesAndDoesNotManufactureAbsenceAfterSaving() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var ready = prepared(planned);
        var empty = RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
        for (var recovered : new RewardDeliveryJournal[]{
            RewardDeliveryJournal.reconcile(empty, ready, ready), RewardDeliveryJournal.reconcile(ready, empty, ready),
            RewardDeliveryJournal.reconcile(ready, ready, empty), RewardDeliveryJournal.reconcile(empty, ready, null),
            RewardDeliveryJournal.reconcile(ready, empty, null), RewardDeliveryJournal.reconcile(empty, empty, ready)
        }) {
            assertFalse(recovered.contains(planned, RewardDeliveryJournal.State.PREPARED));
            assertTrue(recovered.contains(planned, RewardDeliveryJournal.State.ISSUING));
            var ordinarySave = new NBTTagCompound();
            recovered.writeTo(ordinarySave);
            var reloaded = RewardDeliveryJournal.read(ordinarySave, PLAYER);
            assertFalse(reloaded.contains(planned, RewardDeliveryJournal.State.PREPARED));
            assertThrows(IllegalStateException.class, () -> reloaded.prepare(planned));
        }
    }

    @Test
    void reconcileUnionsAllSourcesInOrderWithoutMutatingOrAliasingThem() {
        var first = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var second = fragment(new UUID(0, 4), PARENT, 1, new ItemDeliveryFragment.Inventory(8));
        var third = fragment(new UUID(0, 5), PARENT, 1, new ItemDeliveryFragment.WorldDrop(-1, -1, 2, -1.25, 80.5, 33.5));
        var loaded = prepared(first);
        var saved = prepared(second);
        var host = prepared(third);
        var loadedBefore = loaded.snapshot();
        var savedBefore = saved.snapshot();
        var hostBefore = host.snapshot();
        var merged = RewardDeliveryJournal.reconcile(loaded, saved, host);
        var list = merged.snapshot().getTagList("receipts", 10);
        assertEquals(first.id().toString(), list.getCompoundTagAt(0).getString("fragment"));
        assertEquals(second.id().toString(), list.getCompoundTagAt(1).getString("fragment"));
        assertEquals(third.id().toString(), list.getCompoundTagAt(2).getString("fragment"));
        assertTrue(merged.contains(first, RewardDeliveryJournal.State.ISSUING));
        assertTrue(merged.contains(second, RewardDeliveryJournal.State.ISSUING));
        assertTrue(merged.contains(third, RewardDeliveryJournal.State.ISSUING));
        assertTrue(merged.remove(first.started().confirmed(null)));
        loaded.issue(first.started());
        assertEquals(1, loaded.snapshot().getTagList("receipts", 10).tagCount());
        assertEquals(savedBefore, saved.snapshot());
        assertEquals(hostBefore, host.snapshot());
        assertEquals(2, merged.snapshot().getTagList("receipts", 10).tagCount());
        // The loaded source changed only in the explicit issue call, not during reconciliation.
        loadedBefore.getTagList("receipts", 10).getCompoundTagAt(0).setString("state", "ISSUING");
        assertEquals(loadedBefore, loaded.snapshot());
    }

    @Test
    void reconcileRejectsIdentityConflictsAndForeignEmptySourcesBeforeChangingAnything() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var loaded = prepared(planned);
        var saved = prepared(fragment(new UUID(0, 4), PARENT, 1, new ItemDeliveryFragment.Inventory(8)));
        saved.prepare(fragment(LEAF, PARENT, 3, planned.target()));
        var originalLoaded = loaded.snapshot();
        var originalSaved = saved.snapshot();
        assertThrows(IllegalStateException.class, () -> RewardDeliveryJournal.reconcile(loaded, saved, null));
        assertEquals(originalLoaded, loaded.snapshot());
        assertEquals(originalSaved, saved.snapshot());
        var otherPlayer = RewardDeliveryJournal.read(new NBTTagCompound(), OTHER_PLAYER);
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.reconcile(loaded, otherPlayer, null));
        assertThrows(IllegalArgumentException.class, () -> RewardDeliveryJournal.reconcile(loaded, loaded, otherPlayer));
        assertEquals(originalLoaded, loaded.snapshot());
    }

    @Test
    void reconciledUncertaintyCanRestartOnlyThroughLaterExplicitRetryEvidence() {
        var planned = fragment(LEAF, PARENT, 2, new ItemDeliveryFragment.Inventory(7));
        var staleHost = prepared(planned);
        var newerPlayerFile = prepared(planned);
        newerPlayerFile.issue(planned.started());
        var recovered = RewardDeliveryJournal.reconcile(staleHost, newerPlayerFile, staleHost);
        assertThrows(IllegalStateException.class, () -> recovered.prepare(planned));
        assertTrue(recovered.prepare(planned.started().retryPlanned()));
        assertTrue(recovered.contains(planned, RewardDeliveryJournal.State.PREPARED));
        var savedRetry = new NBTTagCompound();
        recovered.writeTo(savedRetry);
        var retry = RewardDeliveryJournal.read(savedRetry, PLAYER);
        assertTrue(retry.contains(planned, RewardDeliveryJournal.State.PREPARED));
        retry.issue(planned.started());
        assertTrue(retry.contains(planned, RewardDeliveryJournal.State.ISSUING));
    }
}
