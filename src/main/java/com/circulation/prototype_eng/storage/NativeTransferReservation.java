package com.circulation.prototype_eng.storage;

/**
 * One synchronous native side of an external transfer, used while its original context stays locked.
 * Export already deducted the offered amount and owns refund space; import only owns empty capacity.
 * Complete with the actual returned amount exactly once. Zero cancels an operation not executed.
 * An uncertain external effect must retain its obligation, never be guessed to have transferred zero.
 */
public interface NativeTransferReservation {
    long amount();

    void complete(long actual);
}
