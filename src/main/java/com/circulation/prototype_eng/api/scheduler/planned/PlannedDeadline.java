package com.circulation.prototype_eng.api.scheduler.planned;

import java.time.Duration;
import java.util.Objects;

/**
 * One monotonic time budget shared across execution phases and joins.
 */
public record PlannedDeadline(long startedAtNanos, long timeoutNanos) {
    public PlannedDeadline {
        if (timeoutNanos <= 0L) {
            throw new IllegalArgumentException("Planned execution timeout must be positive");
        }
    }

    public static PlannedDeadline after(Duration duration, long currentNanos) {
        Objects.requireNonNull(duration, "Planned execution duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Planned execution duration must be positive");
        }
        return new PlannedDeadline(currentNanos, duration.toNanos());
    }

    public static PlannedDeadline after(Duration duration) {
        return after(duration, System.nanoTime());
    }

    /**
     * Signed elapsed differences permit nanoTime's negative values and wraparound.
     */
    public long remainingNanos(long now) {
        long elapsed = now - startedAtNanos;
        if (elapsed < 0L || elapsed >= timeoutNanos) {
            return 0L;
        }
        return timeoutNanos - elapsed;
    }

    public boolean expired(long now) {
        return remainingNanos(now) == 0L;
    }
}
