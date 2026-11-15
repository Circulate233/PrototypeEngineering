package com.circulation.prototype_eng.api.research.delivery;

import ae2.api.stacks.AEKey;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable all-or-zero leaf. Full tagged stack and exact recovery target are fixed before delivery.
 * The completed NBT payload is borrowed read-only for this fragment and every retained checkpoint.
 * entityUuid is null for inventory delivery and before a world entity is created.
 */
public record ItemDeliveryFragment(UUID id, ItemDeliveryKey parent, AEKey resource, int count,
                                   NBTTagCompound taggedStackNbt, Target target, @Nullable UUID entityUuid,
                                   Status status) {
    public ItemDeliveryFragment {
        if (count < 1 || taggedStackNbt.isEmpty())
            throw new IllegalArgumentException("Invalid item delivery fragment payload");
        if ((target instanceof Inventory || status == Status.PLANNED || status == Status.RETRY_PLANNED) && (entityUuid != null)) {
            throw new IllegalArgumentException("Unexpected delivery entity identity");
        }
        if (target instanceof WorldDrop && status == Status.CONFIRMED && (entityUuid == null)) {
            throw new IllegalArgumentException("Confirmed world delivery requires entity identity");
        }
    }


    /**
     * Business checkpoints may advance status, but cannot change a frozen plan or terminal result.
     */
    public void requireTransitionTo(ItemDeliveryFragment next) {
        if (!id.equals(next.id) || !parent.equals(next.parent) || !resource.equals(next.resource)
            || count != next.count || !taggedStackNbt.equals(next.taggedStackNbt) || !target.equals(next.target)
            || (entityUuid != null) && !entityUuid.equals(next.entityUuid)
            || ((status == Status.PLANNED || status == Status.RETRY_PLANNED) && next.status != status
            && next.status != Status.DELIVERING && next.status != Status.ERROR)
            || (status == Status.DELIVERING && next.status != Status.DELIVERING && next.status != Status.CONFIRMED
            && !(next.status == Status.RETRY_PLANNED && (entityUuid == null)))
            || ((status == Status.CONFIRMED || status == Status.ERROR) && !equals(next))) {
            throw new IllegalStateException("Item delivery plan or terminal result changed");
        }
    }

    public ItemDeliveryFragment started() {
        if (status != Status.PLANNED && status != Status.RETRY_PLANNED)
            throw new IllegalStateException("Only planned fragments can start");
        return new ItemDeliveryFragment(id, parent, resource, count, taggedStackNbt, target, entityUuid, Status.DELIVERING);
    }

    /**
     * Caller has proved that this started attempt performed no effect; the exact plan remains fixed.
     */
    public ItemDeliveryFragment retryPlanned() {
        if (status != Status.DELIVERING || (entityUuid != null)) {
            throw new IllegalStateException("Only a started fragment without an entity can record no effect");
        }
        return new ItemDeliveryFragment(id, parent, resource, count, taggedStackNbt, target, null, Status.RETRY_PLANNED);
    }

    public ItemDeliveryFragment confirmed(@Nullable UUID entityId) {
        if (status != Status.DELIVERING) throw new IllegalStateException("Only started fragments can confirm");
        if ((entityUuid != null) && !entityUuid.equals(entityId)) throw new IllegalStateException("Entity changed");
        return new ItemDeliveryFragment(id, parent, resource, count, taggedStackNbt, target, entityId, Status.CONFIRMED);
    }

    public enum Status {PLANNED, RETRY_PLANNED, DELIVERING, CONFIRMED, ERROR}

    public sealed interface Target permits Inventory, WorldDrop {
    }

    public record Inventory(int slot) implements Target {
        public Inventory {
            if (slot < 0) throw new IllegalArgumentException("Invalid inventory slot");
        }
    }

    public record WorldDrop(int dimensionId, int chunkX, int chunkZ, double x, double y, double z) implements Target {
        public WorldDrop {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || ((int) Math.floor(x) >> 4) != chunkX || ((int) Math.floor(z) >> 4) != chunkZ) {
                throw new IllegalArgumentException("Invalid delivery drop location");
            }
        }
    }
}
