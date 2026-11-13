package com.circulation.prototype_eng.api.storage;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Machine-side resource access. Sides may own independent contents or share one bidirectional balance.
 * Storage is independently synchronized; host discovery still belongs to the server thread.
 * Implementations share the physical balance's StorageContext across all adapter aliases.
 */
public interface ResourceHatch {
    /**
     * Uncolored input is its own group and is also visible to every colored input group.
     */
    default ResourceInputGroup inputGroup() {
        return ResourceInputGroup.TRANSPARENT;
    }

    /**
     * A pattern provider exposes independently isolated views without aggregating their balances.
     */
    default List<? extends ResourceHatch> inputPartitions() {
        return List.of(this);
    }

    /**
     * Shared catalyst views join every isolated input partition of the same color.
     */
    default boolean sharedInput() {
        return false;
    }

    /**
     * Materials the machine can consume, or null if this hatch has no input.
     */
    @Nullable
    ResourceStorage input();

    /**
     * Storage for machine products, or null if this hatch has no output.
     */
    @Nullable
    ResourceStorage output();

    /**
     * Stable identity of the underlying input balance. Temporary views must share its context.
     */
    @Nullable
    default Object inputStorageIdentity() {
        var storage = input();
        return storage == null ? null : storage.context();
    }

    /**
     * Stable identity of the output balance, distinct from input for a mixed hatch.
     */
    @Nullable
    default Object outputStorageIdentity() {
        var storage = output();
        return storage == null ? null : storage.context();
    }

    /**
     * Whether this particular host instance can still participate in work.
     */
    boolean isValid();
}
