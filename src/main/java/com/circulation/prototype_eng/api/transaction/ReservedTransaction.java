package com.circulation.prototype_eng.api.transaction;

import com.circulation.prototype_eng.api.storage.ResourceReservation;

import java.util.List;

/**
 * An additional business participant carrying already acquired storage tokens. ResourceBatch owns
 * token application and settlement; the participant handles only its business state. Both lists
 * are borrowed unchanged until settlement, and these tokens must not also be passed separately.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public interface ReservedTransaction extends ReversibleTransaction {
    List<? extends ResourceReservation> reservations();
}
