package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
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

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Verifies vanilla's actual files after its writer settles; never reads a pending chunk cache.
 */
final class RewardDeliveryPersistence {
    private RewardDeliveryPersistence() {
    }

    static void verifyPlayer(Path file, UUID player, NBTTagCompound expected) throws IOException {
        requirePlayer(PlayerDataPersistence.readPlayer(file, player, false), player, expected);
    }

    static void verifyHost(Path file, UUID player, NBTTagCompound expected) throws IOException {
        requirePlayer(readHostForVerification(file, player), player, expected);
    }

    static RewardDeliveryJournal readPlayerJournal(Path file, UUID player) throws IOException {
        return journal(PlayerDataPersistence.readPlayer(file, player, false), player);
    }

    static RewardDeliveryJournal readHostJournal(Path file, UUID player) throws IOException {
        var saved = readCompressed(file);
        var data = saved.getCompoundTag("Data");
        // A newly created singleplayer world can predate its first host-player save.
        return data.hasKey("Player") ? journal(data.getCompoundTag("Player"), player)
            : RewardDeliveryJournal.read(new NBTTagCompound(), player);
    }

    /**
     * No saves or fallbacks: an ISSUING or missing receipt in any authoritative source stays unknown.
     */
    static ObjectSet<UUID> preparedFragments(Path playerFile, @Nullable Path hostFile, UUID player,
                                             RewardDeliveryJournal live, ObjectList<ItemDeliveryFragment> fragments) throws IOException {
        var candidates = new ObjectArrayList<ItemDeliveryFragment>();
        for (int index2 = 0; index2 < fragments.size(); index2++) {
            var fragment = fragments.get(index2);
            if (fragment.status() != ItemDeliveryFragment.Status.DELIVERING)
                throw new IllegalArgumentException("Only delivering fragments need absence evidence");
            if (live.contains(fragment, RewardDeliveryJournal.State.PREPARED)) candidates.add(fragment);
        }
        var result = new ObjectOpenHashSet<UUID>();
        if (candidates.isEmpty()) return result;
        var saved = readPlayerJournal(playerFile, player);
        var host = hostFile == null ? null : readHostJournal(hostFile, player);
        for (int index1 = 0; index1 < candidates.size(); index1++) {
            var fragment = candidates.get(index1);
            if (saved.contains(fragment, RewardDeliveryJournal.State.PREPARED)
                && (host == null || host.contains(fragment, RewardDeliveryJournal.State.PREPARED)))
                result.add(fragment.id());
        }
        return result;
    }

    static void verifyPlayerState(Path file, UUID player, NBTTagCompound expectedJournal,
                                  @Nullable NBTTagCompound leaf) throws IOException {
        verifySavedPlayerState(PlayerDataPersistence.readPlayer(file, player, false), player, expectedJournal, leaf);
    }

    static void verifyHostState(Path file, UUID player, NBTTagCompound expectedJournal,
                                @Nullable NBTTagCompound leaf) throws IOException {
        verifySavedPlayerState(readHostForVerification(file, player), player, expectedJournal, leaf);
    }

    private static NBTTagCompound readHostForVerification(Path file, UUID player) throws IOException {
        try {
            return PlayerDataPersistence.readPlayer(file, player, true);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Saved reward host player envelope is malformed", malformed);
        }
    }

    private static RewardDeliveryJournal journal(NBTTagCompound saved, UUID player) throws IOException {
        requirePlayerIdentity(saved, player);
        var entityData = saved.hasKey("ForgeData") ? saved.getCompoundTag("ForgeData") : new NBTTagCompound();
        return RewardDeliveryJournal.read(entityData, player);
    }

    static void verifySavedPlayerState(NBTTagCompound saved, UUID player, NBTTagCompound expected,
                                       @Nullable NBTTagCompound leaf) throws IOException {
        if (!journal(saved, player).snapshot().equals(expected))
            throw new IOException("Saved reward delivery journal differs");
        if (leaf != null) requireUniqueLeaf(saved, leaf);
    }

    private static NBTTagCompound readCompressed(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
            return CompressedStreamTools.readCompressed(Channels.newInputStream(channel));
        }
    }

    private static void requirePlayer(NBTTagCompound saved, UUID player, NBTTagCompound expected) throws IOException {
        requirePlayerIdentity(saved, player);
        requireUniqueLeaf(saved, expected);
    }

    private static void requirePlayerIdentity(NBTTagCompound saved, UUID player) throws IOException {
        if (!saved.hasUniqueId("UUID") || !player.equals(saved.getUniqueId("UUID")))
            throw new IOException("Saved reward player identity differs");
    }

    static void verifyDrop(Path directory, int x, int z, UUID entityId, NBTTagCompound expected) throws IOException {
        var file = directory.resolve("region").resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!Files.isRegularFile(file) || Files.size(file) < 8192 || Files.size(file) % 4096 != 0)
            throw new IOException("Saved reward region is missing or malformed: " + file);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        // A fresh RegionFile reads the on-disk header, unlike AnvilChunkLoader's pending snapshots.
        var region = new RegionFile(file.toFile());
        try {
            try (var input = region.getChunkDataInputStream(x & 31, z & 31)) {
                if (input == null) throw new IOException("Saved reward chunk is missing or unreadable: " + x + "," + z);
                var saved = CompressedStreamTools.read(input);
                var level = saved.getCompoundTag("Level");
                if (!level.hasKey("xPos", Constants.NBT.TAG_INT) || !level.hasKey("zPos", Constants.NBT.TAG_INT)
                    || level.getInteger("xPos") != x || level.getInteger("zPos") != z)
                    throw new IOException("Saved reward chunk coordinates differ");
                var entities = level.getTagList("Entities", Constants.NBT.TAG_COMPOUND);
                int matches = 0;
                for (int index = 0; index < entities.tagCount(); index++) {
                    var entity = entities.getCompoundTagAt(index);
                    var item = entity.getCompoundTag("Item");
                    boolean identity = entity.hasUniqueId("UUID") && entityId.equals(entity.getUniqueId("UUID"));
                    if (identity || sameFragment(item, expected)) {
                        if (!identity || !RewardEntities.ID.toString().equals(entity.getString("id")) || !item.equals(expected))
                            throw new IOException("Saved reward entity identity or payload differs");
                        if (!entity.getBoolean(RewardItemEntity.PENDING_TAG))
                            throw new IOException("Saved reward entity has already released its delivery");
                        matches++;
                    }
                }
                if (matches != 1)
                    throw new IOException("Saved reward chunk requires exactly one tagged leaf; found " + matches);
            }
        } finally {
            region.close();
        }
    }

    private static void requireUniqueLeaf(NBTTagCompound player, NBTTagCompound expected) throws IOException {
        NBTTagList entries = player.getTagList("Inventory", Constants.NBT.TAG_COMPOUND);
        int matches = 0;
        var slots = new IntOpenHashSet();
        for (int index = 0; index < entries.tagCount(); index++) {
            var actual = entries.getCompoundTagAt(index);
            int slot = actual.getByte("Slot") & 255;
            if (!actual.hasKey("Slot", Constants.NBT.TAG_BYTE) || !(slot < 36 || slot >= 100 && slot < 104 || slot == 150)
                || !slots.add(slot)) throw new IOException("Saved reward inventory has an invalid or duplicate slot");
            if (sameFragment(actual, expected)) {
                actual.removeTag("Slot");
                if (!actual.equals(expected)) throw new IOException("Saved reward inventory leaf payload differs");
                matches++;
            }
        }
        if (matches != 1)
            throw new IOException("Saved reward inventory requires exactly one tagged leaf; found " + matches);
    }

    private static boolean sameFragment(NBTTagCompound actual, NBTTagCompound expected) {
        var id = expected.getCompoundTag("tag").getCompoundTag(MinecraftItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment");
        if (id.isEmpty()) throw new IllegalArgumentException("Reward persistence requires a tagged fragment");
        return id.equals(actual.getCompoundTag("tag").getCompoundTag(MinecraftItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment"));
    }
}
