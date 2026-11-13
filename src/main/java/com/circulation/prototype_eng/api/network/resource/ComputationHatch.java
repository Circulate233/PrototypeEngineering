package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;

/**
 * One physical inventory for installed computing items and arbitrary non-item resources.
 * Installed item changes notify subscribers after storage locks are released, possibly on a worker thread.
 * Listeners must only signal pending work; subscribe/unsubscribe at the owning machine boundary.
 */
public interface ComputationHatch extends SlottedResourceHatch {
    SlottedResourceStorage inventory();

    void addListener(Runnable listener);

    void removeListener(Runnable listener);

    @Override
    default SlottedResourceStorage input() {
        return inventory();
    }

    @Override
    default SlottedResourceStorage output() {
        return inventory();
    }
}
