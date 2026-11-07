package com.circulation.prototype_eng.research.reward;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraftforge.common.util.Constants;

/** Verifies vanilla's actual files after its writer settles; never reads a pending chunk cache. */
final class RewardDeliveryPersistence {
    private RewardDeliveryPersistence() { }

    static void verifyPlayer(Path file, UUID player, NBTTagCompound expected) throws IOException {
        requirePlayer(readCompressed(file), player, expected);
    }

    static void verifyHost(Path file, UUID player, NBTTagCompound expected) throws IOException {
        requirePlayer(readCompressed(file).getCompoundTag("Data").getCompoundTag("Player"), player, expected);
    }

    private static NBTTagCompound readCompressed(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
            return CompressedStreamTools.readCompressed(Channels.newInputStream(channel));
        }
    }

    private static void requirePlayer(NBTTagCompound saved, UUID player, NBTTagCompound expected) throws IOException {
        if (!saved.hasUniqueId("UUID") || !player.equals(saved.getUniqueId("UUID"))) throw new IOException("Saved reward player identity differs");
        requireUniqueLeaf(saved.getTagList("Inventory", Constants.NBT.TAG_COMPOUND), expected);
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

    private static void requireUniqueLeaf(NBTTagList entries, NBTTagCompound expected) throws IOException {
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
        if (matches != 1) throw new IOException("Saved reward inventory requires exactly one tagged leaf; found " + matches);
    }

    private static boolean sameFragment(NBTTagCompound actual, NBTTagCompound expected) {
        var id = expected.getCompoundTag("tag").getCompoundTag(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment");
        if (id.isEmpty()) throw new IllegalArgumentException("Reward persistence requires a tagged fragment");
        return id.equals(actual.getCompoundTag("tag").getCompoundTag(MinecraftRewardItemDeliveryAdapter.DELIVERY_TAG).getString("Fragment"));
    }
}
