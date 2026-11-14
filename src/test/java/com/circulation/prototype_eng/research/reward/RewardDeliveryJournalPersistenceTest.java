package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the actual recovery decision against vanilla compressed player and host files.
 */
class RewardDeliveryJournalPersistenceTest {
    private static final UUID PLAYER = new UUID(0, 71);
    private static final TestResources.Key KEY = new TestResources.Key(new TestResources.KeyType("reward_journal"), "material");
    @TempDir
    Path directory;

    private static RewardDeliveryJournal empty() {
        return RewardDeliveryJournal.read(new NBTTagCompound(), PLAYER);
    }

    private static ItemDeliveryFragment fragment(int index, ItemDeliveryFragment.Target target) {
        var id = new UUID(0, index);
        var parent = new RewardEventKey(PLAYER, new ResourceLocation("test:research"), 1,
            new ResourceLocation("test:group"), new ResourceLocation("test:child"));
        var identity = new NBTTagCompound();
        identity.setString("Player", PLAYER.toString());
        identity.setString("Research", parent.researchId().toString());
        identity.setInteger("Level", parent.level());
        identity.setString("Group", parent.groupId().toString());
        identity.setString("Child", parent.childId().toString());
        identity.setString("Fragment", id.toString());
        var tag = new NBTTagCompound();
        tag.setTag(MinecraftItemDeliveryAdapter.DELIVERY_TAG, identity);
        tag.setString("business", "preserved");
        var stack = new NBTTagCompound();
        stack.setString("id", "test:reward");
        stack.setByte("Count", (byte) 2);
        stack.setShort("Damage", (short) 0);
        stack.setTag("tag", tag);
        return new ItemDeliveryFragment(id, parent, KEY, 2, stack, target, null, ItemDeliveryFragment.Status.PLANNED);
    }

    @Test
    void reloadProvesOnlyPreparedInventoryAndDropAttempts() throws IOException {
        var inventory = fragment(1, new ItemDeliveryFragment.Inventory(4));
        var drop = fragment(2, new ItemDeliveryFragment.WorldDrop(-1, -2, 3, -31, 64, 49));
        var journal = empty();
        journal.prepare(inventory);
        journal.prepare(drop);
        var file = save("player.dat", journal, false, null);
        var reloaded = RewardDeliveryPersistence.readPlayerJournal(file, PLAYER);
        var candidates = ObjectList.of(inventory.started(), drop.started());
        assertEquals(2, RewardDeliveryPersistence.preparedFragments(file, null, PLAYER, reloaded, candidates).size());

        journal.issue(inventory.started());
        save("player.dat", journal, false, null);
        var actual = RewardDeliveryPersistence.preparedFragments(file, null, PLAYER, reloaded, candidates);
        assertFalse(actual.contains(inventory.id()));
        assertTrue(actual.contains(drop.id()));
    }

    @Test
    void failedIssuingSaveAndMissingLegacyReceiptsCannotBecomeAbsenceProof() throws IOException {
        var fragment = fragment(1, new ItemDeliveryFragment.Inventory(4));
        var journal = empty();
        journal.prepare(fragment);
        var file = save("player.dat", journal, false, null);
        journal.issue(fragment.started());
        // The writer failed: only memory reached ISSUING. The old PREPARED file must not win.
        assertTrue(RewardDeliveryPersistence.preparedFragments(file, null, PLAYER, journal, ObjectList.of(fragment.started())).isEmpty());
        // Old versions had no journal. Lack of a file/receipt is not proof and creates nothing.
        var missing = directory.resolve("legacy.dat");
        assertTrue(RewardDeliveryPersistence.preparedFragments(missing, null, PLAYER, empty(), ObjectList.of(fragment.started())).isEmpty());
        assertFalse(Files.exists(missing));
        var prepared = RewardDeliveryPersistence.readPlayerJournal(file, PLAYER);
        save("player.dat", empty(), false, null);
        assertTrue(RewardDeliveryPersistence.preparedFragments(file, null, PLAYER, prepared, ObjectList.of(fragment.started())).isEmpty());
    }

    @Test
    void integratedRecoveryRequiresAgreementWithBothFilesBeforeAnyOverwrite() throws IOException {
        var fragment = fragment(1, new ItemDeliveryFragment.Inventory(4));
        var prepared = empty();
        prepared.prepare(fragment);
        var playerFile = save("player.dat", prepared, false, null);
        var hostFile = save("level.dat", prepared, true, null);
        var candidates = ObjectList.of(fragment.started());
        assertTrue(RewardDeliveryPersistence.preparedFragments(playerFile, hostFile, PLAYER, prepared, candidates).contains(fragment.id()));
        var issuing = RewardDeliveryPersistence.readPlayerJournal(playerFile, PLAYER);
        issuing.issue(fragment.started());
        save("player.dat", issuing, false, null);
        assertTrue(RewardDeliveryPersistence.preparedFragments(playerFile, hostFile, PLAYER, prepared, candidates).isEmpty());
        save("player.dat", prepared, false, null);
        save("level.dat", issuing, true, null);
        assertTrue(RewardDeliveryPersistence.preparedFragments(playerFile, hostFile, PLAYER, prepared, candidates).isEmpty());
        assertTrue(RewardDeliveryPersistence.readHostJournal(hostFile, PLAYER).contains(fragment, RewardDeliveryJournal.State.ISSUING));
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.preparedFragments(playerFile, directory.resolve("missing-level.dat"), PLAYER, prepared, candidates));
        var reconciled = RewardDeliveryJournal.reconcile(prepared,
            RewardDeliveryPersistence.readPlayerJournal(playerFile, PLAYER), RewardDeliveryPersistence.readHostJournal(hostFile, PLAYER));
        // A subsequent ordinary save must keep the stronger evidence from the other recovery file.
        save("player.dat", reconciled, false, null);
        save("level.dat", reconciled, true, null);
        assertTrue(RewardDeliveryPersistence.preparedFragments(playerFile, hostFile, PLAYER, reconciled, candidates).isEmpty());
    }

    @Test
    void persistenceBarrierRequiresFullJournalAlongsideTheDeliveredLeaf() throws IOException {
        var fragment = fragment(1, new ItemDeliveryFragment.Inventory(4));
        var prepared = empty();
        prepared.prepare(fragment);
        var file = save("player.dat", prepared, false, fragment.taggedStackNbt());
        var issuing = RewardDeliveryPersistence.readPlayerJournal(file, PLAYER);
        issuing.issue(fragment.started());
        // Correct physical item evidence cannot conceal a stale journal.
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayerState(file, PLAYER, issuing.snapshot(), fragment.taggedStackNbt()));
        save("player.dat", issuing, false, fragment.taggedStackNbt());
        RewardDeliveryPersistence.verifyPlayerState(file, PLAYER, issuing.snapshot(), fragment.taggedStackNbt());
        var host = save("level.dat", issuing, true, fragment.taggedStackNbt());
        RewardDeliveryPersistence.verifyHostState(host, PLAYER, issuing.snapshot(), fragment.taggedStackNbt());
        assertThrows(IOException.class, () -> RewardDeliveryPersistence.verifyPlayerState(file, new UUID(0, 72), issuing.snapshot(), null));
        save("player.dat", prepared, false, null);
        var moved = new ItemDeliveryFragment(fragment.id(), fragment.parent(), KEY, fragment.count(), fragment.taggedStackNbt(),
            new ItemDeliveryFragment.Inventory(6), null, ItemDeliveryFragment.Status.DELIVERING);
        var changedLive = empty();
        changedLive.prepare(new ItemDeliveryFragment(moved.id(), moved.parent(), KEY, moved.count(), moved.taggedStackNbt(), moved.target(),
            null, ItemDeliveryFragment.Status.PLANNED));
        assertThrows(IllegalStateException.class, () -> RewardDeliveryPersistence.preparedFragments(file, null, PLAYER, changedLive, ObjectList.of(moved)));
    }

    private Path save(String name, RewardDeliveryJournal journal, boolean host, NBTTagCompound leaf) throws IOException {
        var player = new NBTTagCompound();
        player.setUniqueId("UUID", PLAYER);
        var data = new NBTTagCompound();
        journal.writeTo(data);
        player.setTag("ForgeData", data);
        var inventory = new NBTTagList();
        if (leaf != null) {
            var stack = leaf.copy();
            stack.setByte("Slot", (byte) 4);
            inventory.appendTag(stack);
        }
        player.setTag("Inventory", inventory);
        var root = player;
        if (host) {
            var world = new NBTTagCompound();
            world.setTag("Player", player);
            root = new NBTTagCompound();
            root.setTag("Data", world);
        }
        var file = directory.resolve(name);
        try (var output = Files.newOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, output);
        }
        return file;
    }
}
