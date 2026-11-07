package com.circulation.prototype_eng.research.reward;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardFragment;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

/** Verifies vanilla's actual files after its writer settles; never reads a pending chunk cache. */
final class RewardDeliveryPersistence {
    private RewardDeliveryPersistence() { }

    static void verifyPlayer(Path file, UUID player, NBTTagCompound expected) throws IOException {
        verifyPlayer(file, player, expected, new NBTTagCompound());
    }

    static void verifyPlayer(Path file, UUID player, NBTTagCompound expected, NBTTagCompound cursor) throws IOException {
        requirePlayer(readCompressed(file), player, expected, cursor);
    }

    static void verifyHost(Path file, UUID player, NBTTagCompound expected) throws IOException {
        verifyHost(file, player, expected, new NBTTagCompound());
    }

    static void verifyHost(Path file, UUID player, NBTTagCompound expected, NBTTagCompound cursor) throws IOException {
        requirePlayer(readCompressed(file).getCompoundTag("Data").getCompoundTag("Player"), player, expected, cursor);
    }

    static RewardDeliveryJournal readPlayerJournal(Path file, UUID player) throws IOException {
        return journal(readCompressed(file), player);
    }

    static RewardDeliveryJournal readHostJournal(Path file, UUID player) throws IOException {
        var saved = readCompressed(file);
        var data = StrictNbt.requireCompound(saved, "Data");
        // A newly created singleplayer world can predate its first host-player save.
        return data.hasKey("Player") ? journal(StrictNbt.requireCompound(data, "Player"), player)
            : RewardDeliveryJournal.read(new NBTTagCompound(), player);
    }

    /** No saves or fallbacks: an ISSUING or missing receipt in any authoritative source stays unknown. */
    static ObjectSet<UUID> preparedFragments(Path playerFile, @Nullable Path hostFile, UUID player,
            RewardDeliveryJournal live, ObjectList<RewardFragment> fragments) throws IOException {
        var candidates = new ObjectArrayList<RewardFragment>();
        for (var fragment : fragments) {
            if (fragment.status() != RewardFragment.Status.DELIVERING) throw new IllegalArgumentException("Only delivering fragments need absence evidence");
            if (live.contains(fragment, RewardDeliveryJournal.State.PREPARED)) candidates.add(fragment);
        }
        var result = new ObjectOpenHashSet<UUID>();
        if (candidates.isEmpty()) return result;
        var saved = readPlayerJournal(playerFile, player);
        var host = hostFile == null ? null : readHostJournal(hostFile, player);
        for (var fragment : candidates) {
            if (saved.contains(fragment, RewardDeliveryJournal.State.PREPARED)
                && (host == null || host.contains(fragment, RewardDeliveryJournal.State.PREPARED))) result.add(fragment.id());
        }
        return result;
    }

    static void verifyPlayerState(Path file, UUID player, NBTTagCompound expectedJournal, NBTTagCompound cursor,
            @Nullable NBTTagCompound leaf) throws IOException {
        requirePlayerState(readCompressed(file), player, expectedJournal, cursor, leaf);
    }

    static void verifyHostState(Path file, UUID player, NBTTagCompound expectedJournal, NBTTagCompound cursor,
            @Nullable NBTTagCompound leaf) throws IOException {
        var saved = readCompressed(file);
        requirePlayerState(StrictNbt.requireCompound(StrictNbt.requireCompound(saved, "Data"), "Player"), player, expectedJournal, cursor, leaf);
    }

    private static RewardDeliveryJournal journal(NBTTagCompound saved, UUID player) throws IOException {
        requirePlayerIdentity(saved, player);
        var entityData = saved.hasKey("ForgeData") ? StrictNbt.requireCompound(saved, "ForgeData") : new NBTTagCompound();
        return RewardDeliveryJournal.read(entityData, player);
    }

    private static void requirePlayerState(NBTTagCompound saved, UUID player, NBTTagCompound expected, NBTTagCompound cursor,
            @Nullable NBTTagCompound leaf) throws IOException {
        if (!journal(saved, player).snapshot().equals(expected)) throw new IOException("Saved reward delivery journal differs");
        if (leaf == null) requireCursor(saved, cursor);
        else requireUniqueLeaf(saved, leaf, cursor);
    }

    private static NBTTagCompound readCompressed(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
            return CompressedStreamTools.readCompressed(Channels.newInputStream(channel));
        }
    }

    private static void requirePlayer(NBTTagCompound saved, UUID player, NBTTagCompound expected, NBTTagCompound cursor) throws IOException {
        requirePlayerIdentity(saved, player);
        requireUniqueLeaf(saved, expected, cursor);
    }

    private static void requirePlayerIdentity(NBTTagCompound saved, UUID player) throws IOException {
        if (!saved.hasUniqueId("UUID") || !player.equals(saved.getUniqueId("UUID"))) throw new IOException("Saved reward player identity differs");
    }

    static void verifyDrop(Path directory, int x, int z, UUID entityId, NBTTagCompound expected, NBTTagCompound protection) throws IOException {
        var file = directory.resolve("region").resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!Files.isRegularFile(file) || Files.size(file) < 8192 || Files.size(file) % 4096 != 0) throw new IOException("Saved reward region is missing or malformed: " + file);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
        // A fresh RegionFile reads the on-disk header, unlike AnvilChunkLoader's pending snapshots.
        var region = new RegionFile(file.toFile());
        try {
            try (var input = region.getChunkDataInputStream(x & 31, z & 31)) {
                if (input == null) throw new IOException("Saved reward chunk is missing or unreadable: " + x + "," + z);
                var saved = CompressedStreamTools.read(input);
                var level = saved.getCompoundTag("Level");
                if (!level.hasKey("xPos", Constants.NBT.TAG_INT) || !level.hasKey("zPos", Constants.NBT.TAG_INT)
                    || level.getInteger("xPos") != x || level.getInteger("zPos") != z) throw new IOException("Saved reward chunk coordinates differ");
                var entities = level.getTagList("Entities", Constants.NBT.TAG_COMPOUND);
                int matches = 0;
                for (int index = 0; index < entities.tagCount(); index++) {
                    var entity = entities.getCompoundTagAt(index);
                    var item = entity.getCompoundTag("Item");
                    boolean identity = entity.hasUniqueId("UUID") && entityId.equals(entity.getUniqueId("UUID"));
                    if (identity || sameFragment(item, expected)) {
                        if (!identity || !"minecraft:item".equals(entity.getString("id")) || !item.equals(expected)) throw new IOException("Saved reward entity identity or payload differs");
                        if (!entity.hasKey("Age", Constants.NBT.TAG_SHORT) || entity.getShort("Age") != Short.MIN_VALUE
                            || !entity.hasKey("PickupDelay", Constants.NBT.TAG_SHORT) || entity.getShort("PickupDelay") != Short.MAX_VALUE
                            || !entity.hasKey("Invulnerable", Constants.NBT.TAG_BYTE) || !entity.getBoolean("Invulnerable")
                            || !entity.getCompoundTag("ForgeData").getCompoundTag("PrototypeEngineeringRewardProtection").equals(protection)) {
                            throw new IOException("Saved reward drop lost its unconfirmed protection");
                        }
                        matches++;
                    }
                }
                if (matches != 1) throw new IOException("Saved reward chunk requires exactly one tagged leaf; found " + matches);
            }
        } finally { region.close(); }
    }

    private static void requireUniqueLeaf(NBTTagCompound player, NBTTagCompound expected, NBTTagCompound expectedCursor) throws IOException {
        NBTTagList entries = player.getTagList("Inventory", Constants.NBT.TAG_COMPOUND);
        int matches = 0;
        var slots = new IntOpenHashSet();
        for (int index = 0; index < entries.tagCount(); index++) {
            var actual = entries.getCompoundTagAt(index).copy();
            int slot = actual.getByte("Slot") & 255;
            if (!actual.hasKey("Slot", Constants.NBT.TAG_BYTE) || !(slot < 36 || slot >= 100 && slot < 104 || slot == 150)
                || !slots.add(slot)) throw new IOException("Saved reward inventory has an invalid or duplicate slot");
            if (sameFragment(actual, expected)) {
                actual.removeTag("Slot");
                if (!actual.equals(expected)) throw new IOException("Saved reward inventory leaf payload differs");
                matches++;
            }
        }
        requireCursor(player, expectedCursor);
        if (!expectedCursor.isEmpty() && sameFragment(expectedCursor, expected)) {
            if (!expectedCursor.equals(expected)) throw new IOException("Saved reward cursor payload differs");
            matches++;
        }
        if (matches != 1) throw new IOException("Saved reward inventory requires exactly one tagged leaf; found " + matches);
    }

    private static void requireCursor(NBTTagCompound player, NBTTagCompound expectedCursor) throws IOException {
        if (player.hasKey(RewardPlayerInventory.PROTECTED_CURSOR_TAG)) {
            if (!player.hasKey(RewardPlayerInventory.PROTECTED_CURSOR_TAG, Constants.NBT.TAG_COMPOUND)) {
                throw new IOException("Saved reward cursor evidence is malformed");
            }
            var cursor = player.getCompoundTag(RewardPlayerInventory.PROTECTED_CURSOR_TAG);
            // Match the complete live cursor even when it belongs to another fragment. An unrelated
            // corrupt cursor would make readProtectedCursor reject this entire saved player.
            if (expectedCursor.isEmpty() || !cursor.equals(expectedCursor)) throw new IOException("Saved protected cursor differs from its actual owner");
        } else if (!expectedCursor.isEmpty()) {
            throw new IOException("Saved protected cursor is missing");
        }
    }

    private static boolean sameFragment(NBTTagCompound actual, NBTTagCompound expected) {
        var id = expected.getCompoundTag("tag").getCompoundTag(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment");
        if (id.isEmpty()) throw new IllegalArgumentException("Reward persistence requires a tagged fragment");
        return id.equals(actual.getCompoundTag("tag").getCompoundTag(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment"));
    }
}
