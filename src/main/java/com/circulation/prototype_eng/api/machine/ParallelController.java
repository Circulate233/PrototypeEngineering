package com.circulation.prototype_eng.api.machine;

/**
 * A structure contribution; snapshots are immutable and safe for recipe-discovery workers.
 */
public interface ParallelController {
    Snapshot snapshot();

    record Snapshot(int selected, int maximum, long revision, boolean valid) {
        public Snapshot {
            if (selected < 0 || selected > maximum || revision < 0)
                throw new IllegalArgumentException("Invalid parallel controller snapshot");
        }
    }
}
