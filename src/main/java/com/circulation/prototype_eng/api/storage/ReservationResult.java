package com.circulation.prototype_eng.api.storage;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Expected planning rejections are values; invalid requests and token misuse are programming errors.
 */
public record ReservationResult(Status status, @Nullable ResourceReservation reservation) {
    public ReservationResult {
        Objects.requireNonNull(status);
        if ((status == Status.ACCEPTED) == (reservation == null)) {
            throw new IllegalArgumentException("Reservation result and status disagree");
        }
    }

    public enum Status {ACCEPTED, INVALID_STORAGE, STALE_SNAPSHOT, INSUFFICIENT}
}
