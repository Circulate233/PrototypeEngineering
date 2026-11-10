package com.circulation.prototype_eng.api.storage;

import org.jetbrains.annotations.Nullable;

/**
 * Machine-side resource access. Input and output have independent contents.
 * Storage is independently synchronized; host discovery still belongs to the server thread.
 * Implementations share the physical balance's StorageContext across all adapter aliases.
 */
public interface ResourceHatch {
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
