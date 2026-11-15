package com.circulation.prototype_eng.api.research.delivery;

import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Strict tagged identities. Existing reward tags and deterministic fragment IDs remain readable.
 */
public final class ItemDeliveryKeyNbt {
    private ItemDeliveryKeyNbt() {
    }

    public static NBTTagCompound identity(ItemDeliveryKey key, UUID fragmentId) {
        var tag = new NBTTagCompound();
        tag.setString("Player", key.playerUuid().toString());
        tag.setString("Fragment", fragmentId.toString());
        switch (key) {
            case RewardEventKey reward -> {
                tag.setString("Research", reward.researchId().toString());
                tag.setInteger("Level", reward.level());
                tag.setString("Group", reward.groupId().toString());
                tag.setString("Child", reward.childId().toString());
            }
            case DownloadOutputKey download -> {
                tag.setString("Kind", "DOWNLOAD");
                tag.setString("Operation", download.operationId().toString());
            }
            default -> throw new IllegalArgumentException("Unsupported item delivery identity");
        }
        return tag;
    }

    public static ItemDeliveryKey readIdentity(NBTTagCompound tag) {
        if (tag.hasKey("Kind")) {
            if (!tag.getString("Kind").equals("DOWNLOAD"))
                throw new IllegalArgumentException("Unknown item delivery kind");
            fragmentId(tag);
            return new DownloadOutputKey(uuid(tag, "Player"), uuid(tag, "Operation"));
        }
        fragmentId(tag);
        return new RewardEventKey(uuid(tag, "Player"), new ResourceLocation(tag.getString("Research")), tag.getInteger("Level"),
            new ResourceLocation(tag.getString("Group")), new ResourceLocation(tag.getString("Child")));
    }

    public static UUID fragmentId(NBTTagCompound tag) {
        return uuid(tag, "Fragment");
    }

    public static UUID fragmentId(ItemDeliveryKey key, int index) {
        if (index < 0) throw new IllegalArgumentException("Negative item delivery fragment index");
        var encoded = switch (key) {
            case RewardEventKey reward -> frame(reward.playerUuid().toString()) + frame(reward.researchId().toString())
                + frame(Integer.toString(reward.level())) + frame(reward.groupId().toString())
                + frame(reward.childId().toString()) + frame(Integer.toString(index));
            case DownloadOutputKey download -> frame("DOWNLOAD") + frame(download.playerUuid().toString())
                + frame(download.operationId().toString()) + frame(Integer.toString(index));
            default -> throw new IllegalArgumentException("Unsupported item delivery identity");
        };
        return UUID.nameUUIDFromBytes(encoded.getBytes(StandardCharsets.UTF_8));
    }

    private static String frame(String text) {
        return text.length() + ":" + text;
    }

    private static UUID uuid(NBTTagCompound tag, String field) {
        var raw = tag.getString(field);
        return UUID.fromString(raw);
    }
}
