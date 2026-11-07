package com.circulation.prototype_eng.api.research.reward;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative owner-thread item effects. Plan returns every immutable tagged leaf before any
 * insertion, using inventory first and world drops for the remainder. Each delivery is all-or-zero.
 * Scan must reject duplicate IDs, conflicting payloads and malformed tags. Only authoritative exact
 * targets can prove absence; a loaded-range observation cannot. Readiness and delivery share an owner.
 */
public interface RewardItemDeliveryAdapter {
    ObjectList<RewardFragment> plan(RewardEventKey parent, RewardChild.Item reward);
    Scan scan(ObjectList<RewardFragment> delivering);
    boolean canDeliver(RewardFragment fragment);

    /**
     * Durably prepares this exact PLANNED or RETRY_PLANNED leaf after its complete selection plan
     * is saved, before the claimer saves DELIVERING. This hook must never issue items. Repeated
     * calls retain the same plan; only RETRY_PLANNED carries a durable proof of a prior zero effect.
     * Failures propagate unchanged and prevent the corresponding delivery from starting.
     */
    default void prepare(RewardFragment fragment) {
        var status = Objects.requireNonNull(fragment).status();
        if (status != RewardFragment.Status.PLANNED && status != RewardFragment.Status.RETRY_PLANNED) {
            throw new IllegalArgumentException("Only planned reward fragments may prepare their delivery evidence");
        }
    }

    /**
     * Zero accepted items and no entity is an authoritative statement that this invocation caused
     * no delivery effect. The claimer durably records it before retrying the same immutable plan.
     * Never return zero to hide an exception, partial effect or an unknown spawn outcome.
     */
    Delivery deliver(RewardFragment fragment);

    /** Synchronously save and flush the exact successful side effect before fragment confirmation. */
    void commit(RewardFragment fragment, Delivery delivery);

    /**
     * Releases delivery-only tags/protection after this exact CONFIRMED fragment is durably saved.
     * Idempotent cleanup only: a missing leaf never permits issuing a replacement. Recovery calls
     * this again for confirmed leaves, including already claimed rewards.
     */
    default void release(RewardFragment fragment) {
        if (Objects.requireNonNull(fragment).status() != RewardFragment.Status.CONFIRMED) {
            throw new IllegalArgumentException("Only confirmed reward fragments may release their delivery evidence");
        }
    }

    record Delivery(int acceptedCount, Optional<UUID> entityUuid) {
        public Delivery {
            Objects.requireNonNull(entityUuid, "entityUuid");
            if (acceptedCount < 0) throw new IllegalArgumentException("Negative acceptance");
        }
    }

    record Scan(ObjectSet<UUID> observed, Object2ObjectMap<UUID, UUID> worldEntities, ObjectSet<UUID> provenAbsent) {
        public Scan {
            observed = ObjectSets.unmodifiable(new ObjectOpenHashSet<>(Objects.requireNonNull(observed, "observed")));
            worldEntities = RewardSnapshots.map(Objects.requireNonNull(worldEntities, "worldEntities"));
            provenAbsent = ObjectSets.unmodifiable(new ObjectOpenHashSet<>(Objects.requireNonNull(provenAbsent, "provenAbsent")));
        }
    }
}
