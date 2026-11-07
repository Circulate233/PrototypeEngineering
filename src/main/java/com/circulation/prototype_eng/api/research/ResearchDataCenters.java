package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Owner-side access to the exact physical database selected by an existing network publication. */
public final class ResearchDataCenters {
    private final MachineIdentityRegistry identities;
    private final Runnable ownerCheck;

    public ResearchDataCenters(MachineIdentityRegistry identities, Runnable ownerCheck) {
        this.identities = Objects.requireNonNull(identities);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
    }

    /** A stale/foreign target grants no storage access; ambiguous component declarations fail explicitly. */
    @Nullable public Capture capture(String teamId, UUID expectedDataCenterId) {
        ownerCheck.run();
        if (Objects.requireNonNull(teamId).isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Data-center capture requires a canonical team ID");
        }
        Objects.requireNonNull(expectedDataCenterId);
        long revision = identities.revision();
        for (var identity : identities.uniqueRegistrations()) {
            if (!identity.identity().machineId().equals(expectedDataCenterId)
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var bindings = bindings(machine);
            if (bindings == null || bindings.role().state() != NetworkWorkState.ACTIVE_DATA_CENTER) return null;
            var permit = bindings.role().workPermit();
            if (permit == null || !permit.current() || !permit.teamId().equals(teamId)) return null;
            var device = permit.deviceSnapshot();
            if (!device.identity().equals(identity.identity()) || device.loadOrder() != identity.loadOrder()
                || device.role() != NetworkDeviceRole.DATA_CENTER || !device.formed()) return null;
            var backend = Objects.requireNonNull(bindings.storage().capture(), "Data-center storage capture");
            if (!backend.snapshot().equals(device.dataCenterResearch())
                || !backend.snapshot().equals(permit.dataCenterResearch())) return null;
            var result = new Capture(this, revision, identity, machine, bindings, permit, backend);
            return result.current() ? result : null;
        }
        return null;
    }

    @Nullable private static Bindings bindings(MachineInstance machine) {
        ComponentType<MachineInstance, ?> roleType = null;
        ComponentType<MachineInstance, ?> storageType = null;
        MachineNetworkDevice role = null;
        DataCenterStorage storage = null;
        for (var binding : machine.components().assemblyBindings()) {
            if (binding.component() instanceof MachineNetworkDevice candidate) {
                if (role != null) throw new IllegalStateException("Data-center machine has multiple network component bindings");
                role = candidate;
                roleType = binding.type();
            }
            if (binding.component() instanceof DataCenterStorage candidate) {
                if (storage != null) throw new IllegalStateException("Data-center machine has multiple storage component bindings");
                storage = candidate;
                storageType = binding.type();
            }
        }
        return role == null || storage == null ? null
            : new Bindings(Objects.requireNonNull(roleType), role, Objects.requireNonNull(storageType), storage);
    }

    private record Bindings(ComponentType<MachineInstance, ?> roleType, MachineNetworkDevice role,
                            ComponentType<MachineInstance, ?> storageType, DataCenterStorage storage) { }

    /** The original storage instance and contents remain usable only while this exact authority is current. */
    public static final class Capture {
        private final ResearchDataCenters selector;
        private final long revision;
        private final MachineIdentityRegistry.Registration identity;
        private final MachineInstance machine;
        private final Bindings bindings;
        private final NetworkRuntime.WorkPermit permit;
        private final DataCenterStorage.Capture backend;

        private Capture(ResearchDataCenters selector, long revision, MachineIdentityRegistry.Registration identity,
                         MachineInstance machine, Bindings bindings, NetworkRuntime.WorkPermit permit, DataCenterStorage.Capture backend) {
            this.selector = selector;
            this.revision = revision;
            this.identity = identity;
            this.machine = machine;
            this.bindings = bindings;
            this.permit = permit;
            this.backend = backend;
        }

        public DataCenterResearchSnapshot snapshot() { return backend.snapshot(); }
        public DataCenterStorage storage() { return bindings.storage(); }
        public UUID ownerId() { return identity.identity().ownerId(); }

        /** Revalidate before publishing a write; a successful content update invalidates this capture. */
        public boolean current() {
            selector.ownerCheck.run();
            if (!physicallyCurrent() || !contentsCurrent() || !permit.authorized()) return false;
            return physicallyCurrent() && contentsCurrent() && physicallyCurrent() && permit.current();
        }

        private boolean contentsCurrent() {
            return backend.current().getAsBoolean()
                && bindings.storage().current(Objects.requireNonNull(permit.dataCenterResearch()));
        }

        private boolean physicallyCurrent() {
            var lifecycle = machine.lifecycle();
            if (selector.identities.revision() != revision || !identity.unique() || identity.runtimeIdentity() != machine
                || lifecycle == null || lifecycle.state() != MachineLifecycle.State.LOADED
                || !lifecycle.schedulingAllowed() || !lifecycle.formed()
                || lifecycle.host().worldIdentity() != identity.worldIdentity()
                || !lifecycle.host().identity().equals(identity.location()) || !permit.current()
                || bindings.role().state() != NetworkWorkState.ACTIVE_DATA_CENTER) return false;
            var now = bindings(machine);
            return now != null && now.roleType() == bindings.roleType() && now.role() == bindings.role()
                && now.storageType() == bindings.storageType() && now.storage() == bindings.storage();
        }
    }
}
