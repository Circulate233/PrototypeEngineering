package com.circulation.prototype_eng.api.research.delivery;

import ae2.api.stacks.AEKey;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable all-or-zero leaf. Full tagged stack and exact recovery target are fixed before delivery.
 */
public record ItemDeliveryFragment(UUID id, ItemDeliveryKey parent, AEKey resource, int count,
                                   NBTTagCompound taggedStackNbt, Target target, Optional<UUID> entityUuid,
                                   Status status) {
    public ItemDeliveryFragment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(resource, "resource");
        taggedStackNbt = Objects.requireNonNull(taggedStackNbt, "taggedStackNbt").copy();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(status, "status");
        if (count < 1 || taggedStackNbt.isEmpty())
            throw new IllegalArgumentException("Invalid item delivery fragment payload");
        if ((target instanceof Inventory || status == Status.PLANNED || status == Status.RETRY_PLANNED) && entityUuid.isPresent()) {
            throw new IllegalArgumentException("Unexpected delivery entity identity");
        }
        if (target instanceof WorldDrop && status == Status.CONFIRMED && entityUuid.isEmpty()) {
            throw new IllegalArgumentException("Confirmed world delivery requires entity identity");
        }
    }

    @Override
    public NBTTagCompound taggedStackNbt() {
        return taggedStackNbt.copy();
    }

    /**
     * Business checkpoints may advance status, but cannot change a frozen plan or terminal result.
     */
    public void requireTransitionTo(ItemDeliveryFragment next) {
        Objects.requireNonNull(next);
        if (!id.equals(next.id) || !parent.equals(next.parent) || !resource.equals(next.resource)
            || count != next.count || !taggedStackNbt.equals(next.taggedStackNbt) || !target.equals(next.target)
            || entityUuid.isPresent() && !entityUuid.equals(next.entityUuid)
            || ((status == Status.PLANNED || status == Status.RETRY_PLANNED) && next.status != status
            && next.status != Status.DELIVERING && next.status != Status.ERROR)
            || (status == Status.DELIVERING && next.status != Status.DELIVERING && next.status != Status.CONFIRMED
            && !(next.status == Status.RETRY_PLANNED && entityUuid.isEmpty()))
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
        if (status != Status.DELIVERING || entityUuid.isPresent()) {
            throw new IllegalStateException("Only a started fragment without an entity can record no effect");
        }
        return new ItemDeliveryFragment(id, parent, resource, count, taggedStackNbt, target, Optional.empty(), Status.RETRY_PLANNED);
    }

    public ItemDeliveryFragment confirmed(@Nullable UUID entityId) {
        var entity = Optional.ofNullable(entityId);
        if (status != Status.DELIVERING) throw new IllegalStateException("Only started fragments can confirm");
        if (entityUuid.isPresent() && !entityUuid.equals(entity)) throw new IllegalStateException("Entity changed");
        return new ItemDeliveryFragment(id, parent, resource, count, taggedStackNbt, target, entity, Status.CONFIRMED);
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
