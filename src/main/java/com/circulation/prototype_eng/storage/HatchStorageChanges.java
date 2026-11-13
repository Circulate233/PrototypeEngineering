package com.circulation.prototype_eng.storage;

import java.util.Objects;

/**
 * Candidate construction cannot dirty a real host before both sides have been installed.
 */
public final class HatchStorageChanges implements Runnable {
    private final Runnable changed;
    private volatile boolean enabled;

    public HatchStorageChanges(Runnable changed) {
        this.changed = Objects.requireNonNull(changed);
    }

    public void enable() {
        enabled = true;
    }

    @Override
    public void run() {
        if (enabled) changed.run();
    }
}
