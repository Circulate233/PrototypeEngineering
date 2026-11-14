package com.circulation.prototype_eng.api.research.delivery;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Advances immutable item effects under the business owner's exact durable fragment checkpoint.
 */
public final class ItemDelivery {
    private ItemDelivery() {
    }

    /**
     * The complete plan must already be durable. checkpoint replaces only the supplied fragment and
     * synchronously persists the owner's complete state; throwing retains that pending checkpoint.
     * After an effect, its commit and confirmation remain owed even if current becomes false.
     * Each fragments result is a borrowed immutable snapshot. Checkpoint callbacks must replace the
     * owner's collection rather than mutate a previously returned collection during iteration.
     */
    public static Status advance(Supplier<? extends Iterable<ItemDeliveryFragment>> fragments,
                                 Consumer<ItemDeliveryFragment> checkpoint,
                                 ItemDeliveryAdapter adapter, BooleanSupplier current) {
        Objects.requireNonNull(fragments);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(current);
        if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
        var delivering = new ObjectArrayList<ItemDeliveryFragment>();
        for (var fragment : fragments.get()) {
            if (fragment.status() == ItemDeliveryFragment.Status.ERROR) return Status.RECOVERY_ERROR;
            if (fragment.status() == ItemDeliveryFragment.Status.CONFIRMED) {
                if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
                adapter.release(fragment);
            }
            if (fragment.status() == ItemDeliveryFragment.Status.DELIVERING) delivering.add(fragment);
        }
        if (!delivering.isEmpty()) {
            var scan = adapter.scan(delivering);
            if (!validScan(scan, delivering)) return Status.RECOVERY_ERROR;
            for (int index2 = 0; index2 < delivering.size(); index2++) {
                var fragment = delivering.get(index2);
                if (!scan.observed().contains(fragment.id())) continue;
                if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
                var entity = scan.worldEntities().get(fragment.id());
                if (!validEntity(fragment, entity)) return Status.RECOVERY_ERROR;
                adapter.commit(fragment, new ItemDeliveryAdapter.Delivery(fragment.count(), entity));
                confirm(checkpoint, adapter, fragment, entity);
            }
            for (int index = 0; index < delivering.size(); index++) {
                var fragment = delivering.get(index);
                if (!scan.provenAbsent().contains(fragment.id())) continue;
                if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
                checkpoint.accept(fragment.retryPlanned());
            }
            for (var fragment : fragments.get()) {
                if (fragment.status() == ItemDeliveryFragment.Status.DELIVERING) return Status.RETRY_LATER;
            }
        }
        // The persisted list order preserves inventory-first planning, independent of UUID sorting.
        for (var fragment : fragments.get()) {
            if (fragment.status() != ItemDeliveryFragment.Status.PLANNED
                && fragment.status() != ItemDeliveryFragment.Status.RETRY_PLANNED) continue;
            if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
            if (!adapter.canDeliver(fragment)) return Status.RETRY_LATER;
            if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
            adapter.prepare(fragment);
            if (!current.getAsBoolean()) return Status.STALE_CONTEXT;
            var started = fragment.started();
            checkpoint.accept(started);
            if (!current.getAsBoolean()) {
                if ((started.entityUuid() == null)) checkpoint.accept(started.retryPlanned());
                return Status.STALE_CONTEXT;
            }
            var delivery = adapter.deliver(started);
            if (delivery != null && delivery.acceptedCount() == 0 && (delivery.entityUuid() == null) && (started.entityUuid() == null)) {
                checkpoint.accept(started.retryPlanned());
                return Status.RETRY_LATER;
            }
            if (delivery == null || delivery.acceptedCount() != started.count()
                || !validEntity(started, delivery.entityUuid())) return Status.RETRY_LATER;
            adapter.commit(started, delivery);
            confirm(checkpoint, adapter, started, delivery.entityUuid());
        }
        return Status.COMPLETED;
    }

    private static void confirm(Consumer<ItemDeliveryFragment> checkpoint, ItemDeliveryAdapter adapter,
                                ItemDeliveryFragment fragment, @Nullable UUID entity) {
        var confirmed = fragment.confirmed(entity);
        checkpoint.accept(confirmed);
        adapter.release(confirmed);
    }

    private static boolean validEntity(ItemDeliveryFragment fragment, @Nullable UUID entity) {
        return (fragment.target() instanceof ItemDeliveryFragment.WorldDrop) == (entity != null)
            && ((fragment.entityUuid() == null) || fragment.entityUuid().equals(entity));
    }

    private static boolean validScan(@Nullable ItemDeliveryAdapter.Scan scan, ObjectList<ItemDeliveryFragment> delivering) {
        if (scan == null) return false;
        var ids = new ObjectOpenHashSet<UUID>();
        for (int index1 = 0; index1 < delivering.size(); index1++) {
            var fragment = delivering.get(index1);
            ids.add(fragment.id());
        }
        if (!ids.containsAll(scan.observed()) || !ids.containsAll(scan.provenAbsent())
            || !scan.observed().containsAll(scan.worldEntities().keySet())) return false;
        for (var id : scan.observed()) if (scan.provenAbsent().contains(id)) return false;
        for (var entry : scan.worldEntities().object2ObjectEntrySet()) if (entry.getValue() == null) return false;
        return true;
    }

    public enum Status {COMPLETED, RETRY_LATER, RECOVERY_ERROR, STALE_CONTEXT}
}
