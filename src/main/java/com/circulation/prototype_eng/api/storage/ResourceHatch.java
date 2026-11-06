package com.circulation.prototype_eng.api.storage;

import ae2.api.storage.MEStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Machine-side resource access. Input and output have independent contents.
 * Call only on the owning server thread and reacquire after the host unloads.
 * Implementations may use any backend that honours the MEStorage contract.
 */
public interface ResourceHatch {
    /** Materials the machine can consume, or null if this hatch has no input. */
    @Nullable
    MEStorage input();

    /** Storage for machine products, or null if this hatch has no output. */
    @Nullable
    MEStorage output();

    /** Stable identity of the underlying input balance. Override when returning temporary adapter views. */
    @Nullable
    default Object inputStorageIdentity() { return input(); }

    /** Stable identity of the output balance, distinct from input for a mixed hatch. */
    @Nullable
    default Object outputStorageIdentity() { return output(); }

    /** Whether this particular host instance can still participate in work. */
    boolean isValid();
}
