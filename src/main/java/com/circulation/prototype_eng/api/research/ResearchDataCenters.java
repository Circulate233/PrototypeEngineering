package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.Set;

/**
 * Owner-side access to the exact physical database selected by an existing network publication.
 */
public final class ResearchDataCenters {
    private final MachineIdentityRegistry identities;
    private final Runnable ownerCheck;

    public ResearchDataCenters(MachineIdentityRegistry identities, Runnable ownerCheck) {
        this.identities = identities;
        this.ownerCheck = ownerCheck;
    }

    @Nullable
    private static Bindings bindings(MachineInstance machine) {
        ComponentType<MachineInstance, ?> roleType = null;
        ComponentType<MachineInstance, ?> storageType = null;
        MachineNetworkDevice role = null;
        DataCenterStorage storage = null;
        var elements3 = machine.components().assemblyBindings();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var binding = elements3.get(index3);
            if (binding.component() instanceof MachineNetworkDevice candidate) {
                if (role != null)
                    throw new IllegalStateException("Data-center machine has multiple network component bindings");
                role = candidate;
                roleType = binding.type();
            }
            if (binding.component() instanceof DataCenterStorage candidate) {
                if (storage != null)
                    throw new IllegalStateException("Data-center machine has multiple storage component bindings");
                storage = candidate;
                storageType = binding.type();
            }
        }
        return role == null || storage == null ? null
            : new Bindings(roleType, role, storageType, storage);
    }

    @Nullable
    private static StorageBinding storageBinding(MachineInstance machine) {
        StorageBinding result = null;
        var elements1 = machine.components().assemblyBindings();
        for (int index1 = 0; index1 < elements1.size(); index1++) {
            var binding = elements1.get(index1);
            if (binding.component() instanceof DataCenterStorage candidate) {
                if (result != null)
                    throw new IllegalStateException("Data-center machine has multiple storage component bindings");
                result = new StorageBinding(binding.type(), candidate);
            }
        }
        return result;
    }

    /**
     * A stale/foreign target grants no storage access; ambiguous component declarations fail explicitly.
     */
    @Nullable
    public Capture capture(String teamId, UUID expectedDataCenterId) {
        ownerCheck.run();
        if (teamId.isBlank() || !teamId.equals(teamId.trim())) {
            throw new IllegalArgumentException("Data-center capture requires a canonical team ID");
        }
        long revision = identities.revision();

        var elements2 = identities.uniqueRegistrations();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var identity = elements2.get(index2);
            if (!identity.identity().machineId().equals(expectedDataCenterId)
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var bindings = bindings(machine);
            if (bindings == null || bindings.role().state() != NetworkWorkState.ACTIVE_DATA_CENTER) return null;
            var permit = bindings.role().workPermit();
            if (permit == null || !permit.current() || !permit.teamId().equals(teamId)) return null;
            var backend = bindings.storage().capture();
            if (!backend.snapshot().equals(permit.dataCenterResearch())) return null;
            var result = new Capture(this, revision, identity, machine, bindings, permit, backend);
            return result.current() ? result : null;
        }

        return null;
    }

    /**
     * Recover only the original physical storage. No current team, selected database, network role,
     * or old content authorization is borrowed here. New imports must use capture(team, id).
     */
    @Nullable
    public PhysicalCapture captureOriginal(Target target) {
        ownerCheck.run();
        long revision = identities.revision();

        var elements = identities.uniqueRegistrations();
        for (int index = 0; index < elements.size(); index++) {
            var identity = elements.get(index);
            if (!identity.identity().machineId().equals(target.dataCenterId())
                || !identity.identity().ownerId().equals(target.ownerId()) || !identity.location().equals(target.location())
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var binding = storageBinding(machine);
            if (binding == null) return null;
            var result = new PhysicalCapture(this, revision, identity, machine, binding);
            return result.current() ? result : null;
        }

        return null;
    }

    /**
     * Captures an exact member-owned database even when another database currently serves the team.
     */
    public @Nullable PhysicalCapture captureMemberOwned(Set<UUID> members, UUID expectedDataCenterId) {
        ownerCheck.run();
        long revision = identities.revision();
        var registrations = identities.uniqueRegistrations();
        for (int index = 0; index < registrations.size(); index++) {
            var identity = registrations.get(index);
            if (!identity.identity().machineId().equals(expectedDataCenterId)
                || !members.contains(identity.identity().ownerId())
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var binding = storageBinding(machine);
            if (binding == null) return null;
            var result = new PhysicalCapture(this, revision, identity, machine, binding);
            return result.current() ? result : null;
        }
        return null;
    }

    /**
     * Saved physical target. It identifies a recovery obligation and never grants team permission.
     */
    public record Target(UUID dataCenterId, UUID ownerId, MachineIdentity location) {
    }

    private record StorageBinding(ComponentType<MachineInstance, ?> type, DataCenterStorage storage) {
    }

    /**
     * Independent original binding proof that survives an import's own content publication.
     */
    public static final class PhysicalCapture {
        private final ResearchDataCenters selector;
        private final long revision;
        private final MachineIdentityRegistry.Registration identity;
        private final MachineInstance machine;
        private final StorageBinding binding;

        private PhysicalCapture(ResearchDataCenters selector, long revision, MachineIdentityRegistry.Registration identity,
                                MachineInstance machine, StorageBinding binding) {
            this.selector = selector;
            this.revision = revision;
            this.identity = identity;
            this.machine = machine;
            this.binding = binding;
        }

        public Target target() {
            return new Target(identity.identity().machineId(), identity.identity().ownerId(), identity.location());
        }

        public DataCenterStorage storage() {
            return binding.storage();
        }

        public DataCenterResearchSnapshot snapshot() {
            selector.ownerCheck.run();
            return binding.storage().snapshot();
        }

        public boolean current() {
            return currentBinding() && binding.storage().snapshot().dataCenterId().equals(identity.identity().machineId());
        }

        private boolean currentBinding() {
            selector.ownerCheck.run();
            var lifecycle = machine.lifecycle();
            if (selector.identities.revision() != revision || !identity.unique() || identity.runtimeIdentity() != machine
                || lifecycle == null || lifecycle.state() != MachineLifecycle.State.LOADED || !lifecycle.schedulingAllowed()
                || lifecycle.host().worldIdentity() != identity.worldIdentity() || !lifecycle.host().identity().equals(identity.location())) {
                return false;
            }
            var current = storageBinding(machine);
            return current != null && current.type() == binding.type() && current.storage() == binding.storage();
        }
    }

    private record Bindings(ComponentType<MachineInstance, ?> roleType, MachineNetworkDevice role,
                            ComponentType<MachineInstance, ?> storageType, DataCenterStorage storage) {
    }

    /**
     * The original storage instance and contents remain usable only while this exact authority is current.
     */
    public static final class Capture {
        private final ComponentType<MachineInstance, ?> roleType;
        private final MachineNetworkDevice role;
        private final NetworkRuntime.WorkPermit permit;
        private final DataCenterStorage.Capture backend;
        private final PhysicalCapture physical;

        private Capture(ResearchDataCenters selector, long revision, MachineIdentityRegistry.Registration identity,
                        MachineInstance machine, Bindings bindings, NetworkRuntime.WorkPermit permit, DataCenterStorage.Capture backend) {
            roleType = bindings.roleType();
            role = bindings.role();
            this.permit = permit;
            this.backend = backend;
            physical = new PhysicalCapture(selector, revision, identity, machine, new StorageBinding(bindings.storageType(), bindings.storage()));
        }

        public DataCenterResearchSnapshot snapshot() {
            return backend.snapshot();
        }

        public DataCenterStorage storage() {
            return physical.storage();
        }

        public Target target() {
            return physical.target();
        }

        /**
         * Keep this proof for persistence after the import has invalidated its old contents.
         */
        public boolean physicalCurrent() {
            return physical.current();
        }

        public UUID ownerId() {
            return physical.identity.identity().ownerId();
        }

        /**
         * Revalidate before publishing a write; a successful content update invalidates this capture.
         */
        public boolean current() {
            if (!physicallyCurrent() || !contentsCurrent() || !permit.authorized()) return false;
            return physicallyCurrent() && contentsCurrent() && physicallyCurrent();
        }

        private boolean contentsCurrent() {
            return backend.current().getAsBoolean()
                && physical.storage().current(permit.dataCenterResearch());
        }

        @SuppressWarnings("DataFlowIssue")
        private boolean physicallyCurrent() {
            return physical.currentBinding() && physical.machine.lifecycle().formed() && permit.current()
                && physical.machine.components().get(roleType) == role && role.state() == NetworkWorkState.ACTIVE_DATA_CENTER;
        }
    }
}
