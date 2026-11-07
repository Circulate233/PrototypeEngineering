package com.circulation.prototype_eng.api.research.delivery;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared full fragment codec; reward children may supply their already-persisted parent identity.
 */
public final class ItemDeliveryFragmentNbt implements NbtCodec<ItemDeliveryFragment> {
    private final ResourceKeyNbt resources;

    public ItemDeliveryFragmentNbt(ResourceKeyNbt resources) {
        this.resources = Objects.requireNonNull(resources);
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
        var parsed = UUID.fromString(text);
        if (!parsed.toString().equals(text))
            throw new IllegalArgumentException("Non-canonical item delivery fragment UUID");
        return parsed;
    }

    @Override
    public NBTTagCompound encode(ItemDeliveryFragment value) {
        var tag = encodePayload(value);
        tag.setTag("Parent", ItemDeliveryKeyNbt.identity(value.parent(), value.id()));
        return tag;
    }

    @Override
    public ItemDeliveryFragment decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Parent", "Id", "Resource", "Count", "Stack", "Status", "Entity", "Target");
        var identity = StrictNbt.requireCompound(tag, "Parent");
        var parent = ItemDeliveryKeyNbt.readIdentity(identity);
        var payload = tag.copy();
        payload.removeTag("Parent");
        var result = decodePayload(payload, parent);
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
        if (value.entityUuid().isPresent()) tag.setString("Entity", value.entityUuid().orElseThrow().toString());
        tag.setTag("Target", encodeTarget(value.target()));
        return tag;
    }

    public ItemDeliveryFragment decodePayload(NBTTagCompound tag, ItemDeliveryKey parent) {
        StrictNbt.requireKeys(tag, "Id", "Resource", "Count", "Stack", "Status", "Entity", "Target");
        var targetTag = StrictNbt.requireCompound(tag, "Target");
        var target = switch (StrictNbt.requireString(targetTag, "Kind")) {
            case "INVENTORY" -> {
                StrictNbt.requireKeys(targetTag, "Kind", "Slot");
                yield new ItemDeliveryFragment.Inventory(StrictNbt.requireInt(targetTag, "Slot"));
            }
            case "WORLD_DROP" -> {
                StrictNbt.requireKeys(targetTag, "Kind", "Dimension", "ChunkX", "ChunkZ", "X", "Y", "Z");
                yield new ItemDeliveryFragment.WorldDrop(StrictNbt.requireInt(targetTag, "Dimension"), StrictNbt.requireInt(targetTag, "ChunkX"),
                    StrictNbt.requireInt(targetTag, "ChunkZ"), StrictNbt.requireDouble(targetTag, "X"), StrictNbt.requireDouble(targetTag, "Y"), StrictNbt.requireDouble(targetTag, "Z"));
            }
            default -> throw new IllegalArgumentException("Unknown item delivery target");
        };
        return new ItemDeliveryFragment(uuid(StrictNbt.requireString(tag, "Id")), parent, resources.decode(StrictNbt.requireCompound(tag, "Resource")),
            StrictNbt.requireInt(tag, "Count"), StrictNbt.requireCompound(tag, "Stack"), target,
            tag.hasKey("Entity") ? Optional.of(uuid(StrictNbt.requireString(tag, "Entity"))) : Optional.empty(),
            ItemDeliveryFragment.Status.valueOf(StrictNbt.requireString(tag, "Status")));
    }
}
