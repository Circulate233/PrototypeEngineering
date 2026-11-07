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
    Delivery deliver(RewardFragment fragment);

    /** Synchronously save and flush the exact successful side effect before fragment confirmation. */
    void commit(RewardFragment fragment, Delivery delivery);

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
