package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

/**
 * Published external supply. Resources are a zero-default, read-only vector including THREAD.
 */
public interface NetworkComputer {
    long providedConcurrency();

    Object2LongMap<NetworkResourceType> providedResources();
}
