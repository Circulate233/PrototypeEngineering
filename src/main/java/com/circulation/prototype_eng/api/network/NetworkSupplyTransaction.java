package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.transaction.ReservedTransaction;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

/**
 * Prepared supply and its actual costs; the network settles it only after device concurrency admission.
 */
public interface NetworkSupplyTransaction extends ReservedTransaction {
    Object2LongMap<NetworkResourceType> resources();
}
