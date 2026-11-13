package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.transaction.ReservedTransaction;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;

/**
 * Prepared supply and its actual costs; the network settles it only after device concurrency admission.
 */
public interface NetworkSupplyTransaction extends ReservedTransaction {
    Reference2DoubleMap<NetworkResourceType> resources();

    long concurrency();
}
