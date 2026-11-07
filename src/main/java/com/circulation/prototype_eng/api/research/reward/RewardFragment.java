package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.AEKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

/** Immutable all-or-zero leaf. Full tagged stack and exact recovery target are fixed before delivery. */
public record RewardFragment(UUID id, RewardEventKey parent, AEKey resource, int count,
        NBTTagCompound taggedStackNbt, Target target, Optional<UUID> entityUuid, Status status) {
    public RewardFragment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(resource, "resource");
        taggedStackNbt = Objects.requireNonNull(taggedStackNbt, "taggedStackNbt").copy();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(status, "status");
        if (count < 1 || taggedStackNbt.isEmpty()) throw new IllegalArgumentException("Invalid reward fragment payload");
        if ((target instanceof Inventory || status == Status.PLANNED || status == Status.RETRY_PLANNED) && entityUuid.isPresent()) {
            throw new IllegalArgumentException("Unexpected reward entity identity");
        }
        if (target instanceof WorldDrop && status == Status.CONFIRMED && entityUuid.isEmpty()) {
            throw new IllegalArgumentException("Confirmed world reward requires entity identity");
        }
    }

    @Override public NBTTagCompound taggedStackNbt() { return taggedStackNbt.copy(); }

    public RewardFragment started() {
        if (status != Status.PLANNED && status != Status.RETRY_PLANNED) throw new IllegalStateException("Only planned fragments can start");
        return new RewardFragment(id, parent, resource, count, taggedStackNbt, target, entityUuid, Status.DELIVERING);
    }

    /** Caller has proved that this started attempt performed no effect; the exact plan remains fixed. */
    public RewardFragment retryPlanned() {
        if (status != Status.DELIVERING || entityUuid.isPresent()) {
            throw new IllegalStateException("Only a started fragment without an entity can record no effect");
        }
        return new RewardFragment(id, parent, resource, count, taggedStackNbt, target, Optional.empty(), Status.RETRY_PLANNED);
    }

    public RewardFragment confirmed(@Nullable UUID entityId) {
        var entity = Optional.ofNullable(entityId);
        if (status != Status.DELIVERING) throw new IllegalStateException("Only started fragments can confirm");
        if (entityUuid.isPresent() && !entityUuid.equals(entity)) throw new IllegalStateException("Entity changed");
        return new RewardFragment(id, parent, resource, count, taggedStackNbt, target, entity, Status.CONFIRMED);
    }

    public enum Status { PLANNED, RETRY_PLANNED, DELIVERING, CONFIRMED, ERROR }
    public sealed interface Target permits Inventory, WorldDrop { }
    public record Inventory(int slot) implements Target {
        public Inventory { if (slot < 0) throw new IllegalArgumentException("Invalid inventory slot"); }
    }
    public record WorldDrop(int dimensionId, int chunkX, int chunkZ, double x, double y, double z) implements Target {
        public WorldDrop {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || ((int) Math.floor(x) >> 4) != chunkX || ((int) Math.floor(z) >> 4) != chunkZ) {
                throw new IllegalArgumentException("Invalid reward drop location");
            }
        }
    }
}
