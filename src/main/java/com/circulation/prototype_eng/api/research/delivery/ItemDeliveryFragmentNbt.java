package com.circulation.prototype_eng.api.research.delivery;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * Shared full fragment codec; reward children may supply their already-persisted parent identity.
 */
public final class ItemDeliveryFragmentNbt implements NbtCodec<ItemDeliveryFragment> {
    private final ResourceKeyNbt resources;

    public ItemDeliveryFragmentNbt(ResourceKeyNbt resources) {
        this.resources = resources;
    }

    private static NBTTagCompound encodeTarget(ItemDeliveryFragment.Target destination) {
        var target = new NBTTagCompound();
        switch (destination) {
            case ItemDeliveryFragment.Inventory inventory -> {
                target.setString("Kind", "INVENTORY");
                target.setInteger("Slot", inventory.slot());
            }
            case ItemDeliveryFragment.WorldDrop drop -> {
                target.setString("Kind", "WORLD_DROP");
                target.setInteger("Dimension", drop.dimensionId());
                target.setInteger("ChunkX", drop.chunkX());
                target.setInteger("ChunkZ", drop.chunkZ());
                target.setDouble("X", drop.x());
                target.setDouble("Y", drop.y());
                target.setDouble("Z", drop.z());
            }
        }
        return target;
    }

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    @Override
    public NBTTagCompound encode(ItemDeliveryFragment value) {
        var tag = encodePayload(value);
        tag.setTag("Parent", ItemDeliveryKeyNbt.identity(value.parent(), value.id()));
        return tag;
    }

    @Override
    public ItemDeliveryFragment decode(NBTTagCompound tag) {
        var identity = tag.getCompoundTag("Parent");
        var parent = ItemDeliveryKeyNbt.readIdentity(identity);
        var result = decodePayload(tag, parent);
        if (!ItemDeliveryKeyNbt.fragmentId(identity).equals(result.id()))
            throw new IllegalArgumentException("Item delivery parent fragment ID differs");
        return result;
    }

    /**
     * Existing reward child layout retains its externally supplied parent and all native payload.
     */
    public NBTTagCompound encodePayload(ItemDeliveryFragment value) {
        var tag = new NBTTagCompound();
        tag.setString("Id", value.id().toString());
        tag.setTag("Resource", resources.encode(value.resource()));
        tag.setInteger("Count", value.count());
        tag.setTag("Stack", value.taggedStackNbt());
        tag.setString("Status", value.status().name());
        if ((value.entityUuid() != null)) tag.setString("Entity", value.entityUuid().toString());
        tag.setTag("Target", encodeTarget(value.target()));
        return tag;
    }

    public ItemDeliveryFragment decodePayload(NBTTagCompound tag, ItemDeliveryKey parent) {
        var targetTag = tag.getCompoundTag("Target");
        var target = switch (targetTag.getString("Kind")) {
            case "INVENTORY" -> new ItemDeliveryFragment.Inventory(targetTag.getInteger("Slot"));
            case "WORLD_DROP" ->
                new ItemDeliveryFragment.WorldDrop(targetTag.getInteger("Dimension"), targetTag.getInteger("ChunkX"),
                    targetTag.getInteger("ChunkZ"), targetTag.getDouble("X"), targetTag.getDouble("Y"), targetTag.getDouble("Z"));
            default -> throw new IllegalArgumentException("Unknown item delivery target");
        };
        return new ItemDeliveryFragment(uuid(tag.getString("Id")), parent, resources.decode(tag.getCompoundTag("Resource")),
            tag.getInteger("Count"), tag.getCompoundTag("Stack"), target,
            tag.hasKey("Entity") ? uuid(tag.getString("Entity")) : null,
            ItemDeliveryFragment.Status.valueOf(tag.getString("Status")));
    }
}
