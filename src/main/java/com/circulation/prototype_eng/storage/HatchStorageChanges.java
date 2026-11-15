package com.circulation.prototype_eng.storage;


/**
 * Candidate construction cannot dirty a real host before both sides have been installed.
 */
public final class HatchStorageChanges implements Runnable {
    private final Runnable changed;
    private volatile boolean enabled;

    public HatchStorageChanges(Runnable changed) {
        this.changed = changed;
    }

    public void enable() {
        enabled = true;
    }

    @Override
    public void run() {
        if (enabled) changed.run();
    }
}
