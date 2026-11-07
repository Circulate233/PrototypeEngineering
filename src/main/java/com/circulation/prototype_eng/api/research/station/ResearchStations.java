package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner-side station selection. One dimension and world session share one material pool per segment. */
public final class ResearchStations {
    private final MachineIdentityRegistry identities;
    private final Runnable ownerCheck;
    private final Object2IntOpenHashMap<String> lastDimensions = new Object2IntOpenHashMap<>();

    public ResearchStations(MachineIdentityRegistry identities, Runnable ownerCheck) {
        this.identities = Objects.requireNonNull(identities);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
    }

    /** Lease acquisition precedes queue.planSegment; station count scales numeric grant limits, not material copies. */
    @Nullable public Capture capture(String teamId, int requiredTier) {
        ownerCheck.run();
        if (Objects.requireNonNull(teamId).isBlank() || requiredTier < 1) { throw new IllegalArgumentException("Station selection requires a team and positive tier"); }
        var byDimension = new Int2ObjectOpenHashMap<ObjectArrayList<Station>>();
        for (var identity : identities.uniqueRegistrations()) {
            if (!(identity.runtimeIdentity() instanceof MachineInstance machine)) { continue; }
            var station = candidate(identity, machine, teamId, requiredTier);
            if (station != null) { byDimension.computeIfAbsent(identity.location().dimension(), _ -> new ObjectArrayList<>()).add(station); }
        }
        if (byDimension.isEmpty()) { return null; }
        var dimensions = new IntArrayList(byDimension.keySet());
        dimensions.sort(Integer::compare);
        int dimension = dimensions.getInt(0);
        if (lastDimensions.containsKey(teamId)) {
            int previous = lastDimensions.getInt(teamId);
            for (int next : dimensions) { if (next > previous) { dimension = next; break; } }
        }
        lastDimensions.put(teamId, dimension);
        var candidates = byDimension.get(dimension);
        candidates.sort(Comparator.comparing(station -> station.identity.identity().machineId()));
        Object worldIdentity = candidates.getFirst().identity.worldIdentity();
        var leased = new ObjectArrayList<Station>();
        for (var station : candidates) {
            if (station.identity.worldIdentity() == worldIdentity && station.physicallyCurrent() && lease(station.permit)) { leased.add(station); }
        }
        if (leased.isEmpty()) { return null; }
        var resources = new ObjectArrayList<ResourceHatch>();
        var inputs = new ObjectArrayList<EnergyInput>();
        var outputs = new ObjectArrayList<EnergyOutput>();
        for (var station : leased) {
            resources.addAll(station.hatches.resources());
            inputs.addAll(station.hatches.energyInputs());
            outputs.addAll(station.hatches.energyOutputs());
        }
        var result = new Capture(this, teamId, requiredTier, dimension, leased, new MachineHatches(resources, inputs, outputs));
        return result.current() ? result : null;
    }

    /** Continue exactly the original station instances and source pool with this tick's new permits and leases. */
    @Nullable public Capture renew(Capture previous) {
        ownerCheck.run();
        if (Objects.requireNonNull(previous).selector != this) { throw new IllegalArgumentException("Station capture belongs to another selector"); }
        var renewed = new ObjectArrayList<Station>(previous.stations.size());
        for (var old : previous.stations) {
            if (!old.physicallyCurrent()) { return null; }
            var permit = old.role.workPermit();
            if (!eligible(permit, old.identity, previous.teamId, previous.requiredTier) || !lease(Objects.requireNonNull(permit))) { return null; }
            renewed.add(new Station(old.identity, old.machine, old.roleType, old.role, old.hatches, permit));
        }
        var result = new Capture(this, previous.teamId, previous.requiredTier, previous.dimension, renewed, previous.hatches);
        return result.current() ? result : null;
    }

    @Nullable private static Station candidate(MachineIdentityRegistry.Registration identity, MachineInstance machine, String teamId, int tier) {
        ComponentType<MachineInstance, ?> roleType = null;
        MachineNetworkDevice role = null;
        for (var binding : machine.components().assemblyBindings()) {
            if (binding.component() instanceof MachineNetworkDevice device) {
                if (role != null) { throw new IllegalStateException("Station machine has multiple network attachments"); }
                role = device;
                roleType = binding.type();
            }
        }
        if (role == null) { return null; }
        var permit = role.workPermit();
        if (!eligible(permit, identity, teamId, tier)) { return null; }
        var result = new Station(identity, machine, Objects.requireNonNull(roleType), role, MachineHatches.get(machine), Objects.requireNonNull(permit));
        return result.physicallyCurrent() ? result : null;
    }

    private static boolean eligible(@Nullable NetworkRuntime.WorkPermit permit, MachineIdentityRegistry.Registration identity, String teamId, int tier) {
        if (permit == null || !permit.current() || !permit.teamId().equals(teamId)) { return false; }
        var snapshot = permit.deviceSnapshot();
        var dataCenter = permit.dataCenterResearch();
        return snapshot.identity().equals(identity.identity()) && snapshot.loadOrder() == identity.loadOrder()
            && snapshot.role() == NetworkDeviceRole.RESEARCH_STATION && snapshot.formed() && snapshot.level() >= tier
            && dataCenter != null && dataCenter.level() >= tier;
    }

    private static boolean lease(NetworkRuntime.WorkPermit permit) {
        var status = permit.requestLease();
        return status == NetworkTeamBudget.LeaseStatus.GRANTED || status == NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED;
    }

    private record Station(MachineIdentityRegistry.Registration identity, MachineInstance machine,
                           ComponentType<MachineInstance, ?> roleType, MachineNetworkDevice role,
                           MachineHatches hatches, NetworkRuntime.WorkPermit permit) {
        private boolean physicallyCurrent() {
            var lifecycle = machine.lifecycle();
            return identity.unique() && identity.runtimeIdentity() == machine && machine.components().get(roleType) == role
                && lifecycle != null && lifecycle.schedulingAllowed() && lifecycle.formed()
                && lifecycle.host().worldIdentity() == identity.worldIdentity() && lifecycle.host().identity().equals(identity.location())
                && MachineHatches.get(machine) == hatches && hatches.resources().stream().allMatch(ResourceHatch::isValid)
                && hatches.energyInputs().stream().allMatch(EnergyInput::isValid) && hatches.energyOutputs().stream().allMatch(EnergyOutput::isValid);
        }
    }

    /** Immutable site/source identities with replaceable per-tick authority; no station registry or numeric reservation. */
    public static final class Capture {
        private final ResearchStations selector;
        private final String teamId;
        private final int requiredTier;
        private final int dimension;
        private final List<Station> stations;
        private final ObjectSet<UUID> stationIds;
        private final MachineHatches hatches;

        private Capture(ResearchStations selector, String teamId, int requiredTier, int dimension, List<Station> stations, MachineHatches hatches) {
            this.selector = selector;
            this.teamId = teamId;
            this.requiredTier = requiredTier;
            this.dimension = dimension;
            this.stations = List.copyOf(stations);
            this.hatches = hatches;
            var ids = new ObjectLinkedOpenHashSet<UUID>();
            stations.forEach(station -> ids.add(station.identity.identity().machineId()));
            stationIds = ObjectSets.unmodifiable(ids);
        }

        public String teamId() { return teamId; }
        @SuppressWarnings("unused") // Consumers retain the tier alongside the frozen station/source contract.
        public int requiredTier() { return requiredTier; }
        public int dimension() { return dimension; }
        public ObjectSet<UUID> stationIds() { return stationIds; }
        public MachineInstance primary() { return stations.getFirst().machine; }
        public MachineHatches hatches() { return hatches; }
        public Object2LongMap<NetworkResourceType> numericResources() { return stations.getFirst().permit.numericResources(); }

        /** Owner-only version/binding proofs remain valid inside source fences; no inventory is re-read or locked. */
        public boolean current() {
            selector.ownerCheck.run();
            long epoch = stations.getFirst().permit.epoch();
            long tick = stations.getFirst().permit.tick();
            for (var station : stations) {
                if (station.permit.epoch() != epoch || station.permit.tick() != tick || !station.physicallyCurrent() || !station.permit.authorized()) { return false; }
            }
            return stations.stream().allMatch(Station::physicallyCurrent);
        }
    }
}
