package com.circulation.prototype_eng.api.research.delivery;

import org.jetbrains.annotations.Nullable;

import ae2.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.nbt.NBTTagCompound;

import java.util.UUID;

/**
 * Authoritative owner-thread item effects. Plan returns every immutable tagged leaf before any
 * insertion, using inventory first and world drops for the remainder. Each delivery is all-or-zero.
 * Scan must reject duplicate IDs, conflicting payloads and malformed tags. Only authoritative exact
 * targets can prove absence; a loaded-range observation cannot. Readiness and delivery share an owner.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public interface ItemDeliveryAdapter {
    ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, AEKey resource, long amount);

    /**
     * Plans from the full native stack, retaining ForgeCaps and all business NBT in every leaf.
     */
    ObjectList<ItemDeliveryFragment> plan(ItemDeliveryKey parent, NBTTagCompound nativeTemplate, long amount);

    Scan scan(ObjectList<ItemDeliveryFragment> delivering);

    boolean canDeliver(ItemDeliveryFragment fragment);

    /**
     * Durably prepares this exact PLANNED or RETRY_PLANNED leaf after its complete business plan
     * is saved, before the delivery owner saves DELIVERING. This hook must never issue items. Repeated
     * calls retain the same plan; only RETRY_PLANNED carries a durable proof of a prior zero effect.
     * Failures propagate unchanged and prevent the corresponding delivery from starting.
     */
    default void prepare(ItemDeliveryFragment fragment) {
        var status = fragment.status();
        if (status != ItemDeliveryFragment.Status.PLANNED && status != ItemDeliveryFragment.Status.RETRY_PLANNED) {
            throw new IllegalArgumentException("Only planned item delivery fragments may prepare their delivery evidence");
        }
    }

    /**
     * Zero accepted items and no entity is an authoritative statement that this invocation caused
     * no delivery effect. The delivery owner durably records it before retrying the same immutable plan.
     * Never return zero to hide an exception, partial effect or an unknown spawn outcome.
     */
    Delivery deliver(ItemDeliveryFragment fragment);

    /**
     * Synchronously save and flush the exact successful side effect before fragment confirmation.
     */
    void commit(ItemDeliveryFragment fragment, Delivery delivery);

    /**
     * Releases delivery-only tags/protection after this exact CONFIRMED fragment is durably saved.
     * Idempotent cleanup only: a missing leaf never permits issuing a replacement. Recovery calls
     * this again for confirmed leaves, including already claimed rewards.
     */
    default void release(ItemDeliveryFragment fragment) {
        if (fragment.status() != ItemDeliveryFragment.Status.CONFIRMED) {
            throw new IllegalArgumentException("Only confirmed item delivery fragments may release their delivery evidence");
        }
    }

    record Delivery(int acceptedCount, @Nullable UUID entityUuid) {
        public Delivery {
            if (acceptedCount < 0) throw new IllegalArgumentException("Negative acceptance");
        }
    }

    /**
     * Completed scan collections are borrowed read-only until delivery and its checkpoint finish.
     */
    record Scan(ObjectSet<UUID> observed, Object2ObjectMap<UUID, UUID> worldEntities, ObjectSet<UUID> provenAbsent) {

    }
}
