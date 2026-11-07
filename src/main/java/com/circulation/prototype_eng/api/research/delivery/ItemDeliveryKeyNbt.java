package com.circulation.prototype_eng.api.research.delivery;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import net.minecraft.nbt.NBTTagCompound;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Strict tagged identities. Existing reward tags and deterministic fragment IDs remain readable.
 */
public final class ItemDeliveryKeyNbt {
    private ItemDeliveryKeyNbt() {
    }

    public static NBTTagCompound identity(ItemDeliveryKey key, UUID fragmentId) {
        var tag = new NBTTagCompound();
        tag.setString("Player", Objects.requireNonNull(key).playerUuid().toString());
        tag.setString("Fragment", Objects.requireNonNull(fragmentId).toString());
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
            StrictNbt.requireKeys(tag, "Kind", "Player", "Operation", "Fragment");
            if (!StrictNbt.requireString(tag, "Kind").equals("DOWNLOAD"))
                throw new IllegalArgumentException("Unknown item delivery kind");
            fragmentId(tag);
            return new DownloadOutputKey(uuid(tag, "Player"), uuid(tag, "Operation"));
        }
        StrictNbt.requireKeys(tag, "Player", "Research", "Level", "Group", "Child", "Fragment");
        fragmentId(tag);
        return new RewardEventKey(uuid(tag, "Player"), StrictNbt.requireId(tag, "Research"), StrictNbt.requireInt(tag, "Level"),
            StrictNbt.requireId(tag, "Group"), StrictNbt.requireId(tag, "Child"));
    }

    public static UUID fragmentId(NBTTagCompound tag) {
        return uuid(tag, "Fragment");
    }

    public static UUID fragmentId(ItemDeliveryKey key, int index) {
        if (index < 0) throw new IllegalArgumentException("Negative item delivery fragment index");
        var encoded = switch (Objects.requireNonNull(key)) {
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
        var raw = StrictNbt.requireString(tag, field);
        var parsed = UUID.fromString(raw);
        if (!parsed.toString().equals(raw)) throw new IllegalArgumentException("Non-canonical item delivery UUID");
        return parsed;
    }
}
