package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;

/**
 * Published external supply. Double resources are a zero-default, read-only vector;
 * machine concurrency is provided separately as an integer capacity.
 */
public interface NetworkComputer {
    long providedConcurrency();

    Reference2DoubleMap<NetworkResourceType> providedResources();
}
