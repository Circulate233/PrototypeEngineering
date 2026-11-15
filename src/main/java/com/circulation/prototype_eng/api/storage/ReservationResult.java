package com.circulation.prototype_eng.api.storage;

import org.jetbrains.annotations.Nullable;

/**
 * ACCEPTED requires a non-null reservation; rejection statuses require null.
 */
public record ReservationResult(Status status, @Nullable ResourceReservation reservation) {
    public static final ReservationResult INVALID_STORAGE = new ReservationResult(Status.INVALID_STORAGE, null);
    public static final ReservationResult STALE_SNAPSHOT = new ReservationResult(Status.STALE_SNAPSHOT, null);
    public static final ReservationResult INSUFFICIENT = new ReservationResult(Status.INSUFFICIENT, null);

    /**
     * Returns the shared result for a rejection status. The caller must not pass ACCEPTED.
     */
    public static ReservationResult rejected(Status status) {
        return switch (status) {
            case INVALID_STORAGE -> INVALID_STORAGE;
            case STALE_SNAPSHOT -> STALE_SNAPSHOT;
            case INSUFFICIENT -> INSUFFICIENT;
            case ACCEPTED -> throw new IllegalArgumentException("ACCEPTED requires a reservation");
        };
    }

    public enum Status {ACCEPTED, INVALID_STORAGE, STALE_SNAPSHOT, INSUFFICIENT}
}
