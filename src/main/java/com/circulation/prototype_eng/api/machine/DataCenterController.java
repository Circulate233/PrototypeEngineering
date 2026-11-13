package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.research.DataCenterStorage;

import java.util.Objects;

/**
 * Ready-to-use database runtime composed with an existing persistent storage component. Level,
 * capacity and contents belong to that component; downloads and synchronization use the shared
 * research service. This controller has no recipe work slots or independently scheduled business tick.
 */
public class DataCenterController extends MachineInstance implements DataCenter {
    private final ComponentType<MachineInstance, ? extends DataCenterStorage> storage;

    public DataCenterController(MachineType type, ComponentType<MachineInstance, ? extends DataCenterStorage> storage) {
        super(type);
        this.storage = storage;
    }

    @Override
    public DataCenterStorage dataCenterStorage() {
        return Objects.requireNonNull(components().get(storage));
    }

    @Override
    public boolean requiresTick() {
        return false;
    }

    @Override
    public boolean requiresNetwork() {
        return true;
    }
}
