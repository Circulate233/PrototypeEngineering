package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** PN selection and contribution rules, with owner capture separated from detached planning. */
public final class NetworkPlanner {
    private NetworkPlanner() { }

    /**
     * Invoke on the server owner thread after capturing current unique runtimes and active participants.
     * Each owner is resolved once. A provider failure aborts the whole batch; never reuse an older permit.
     */
    public static Inputs capture(NetworkResourceRegistry resources, long epoch, List<NetworkDeviceSnapshot> devices,
            List<NetworkContribution> contributions, OwnerTeamResolver resolver) {
        if (epoch < 0) { throw new IllegalArgumentException("Negative discovery epoch"); }
        if (!resources.isFrozen()) { throw new IllegalStateException("Network capture requires frozen computing resources"); }
        Objects.requireNonNull(resolver);
        var deviceCopy = new ObjectArrayList<>(devices);
        var contributionCopy = new ObjectArrayList<>(contributions);
        var owners = new ObjectLinkedOpenHashSet<UUID>();
        var deviceIds = new ObjectOpenHashSet<UUID>();
        var participantIds = new ObjectOpenHashSet<UUID>();
        for (var device : deviceCopy) {
            Objects.requireNonNull(device);
            if (!deviceIds.add(device.identity().machineId())) { throw new IllegalArgumentException("Duplicate device UUID in discovery"); }
            owners.add(device.identity().ownerId());
            checkResources(resources, device.resourcesPerTick());
            checkResources(resources, device.resourceModifier().flatAdjustments());
            checkResources(resources, device.resourceModifier().multiplierPpmAdjustments());
        }
        for (var contribution : contributionCopy) {
            Objects.requireNonNull(contribution);
            if (contribution.epoch() != epoch || !participantIds.add(contribution.participantId())) {
                throw new IllegalArgumentException("Stale or duplicate participant contribution");
            }
            owners.add(contribution.ownerId());
            checkResources(resources, contribution.resourcesPerTick());
        }
        var resolved = new Object2ObjectOpenHashMap<UUID, OwnerTeamResolution>();
        var teams = new Object2ObjectOpenHashMap<String, OwnerTeamResolution>();
        for (var owner : owners) {
            var result = Objects.requireNonNull(resolver.resolve(owner), "Owner resolver result");
            if (!owner.equals(result.ownerId())) { throw new IllegalArgumentException("Owner resolver returned another owner's result"); }
            if (result.accepted()) {
                var previous = teams.putIfAbsent(result.teamId(), result);
                if (previous != null && (previous.teamType() != result.teamType() || !previous.members().equals(result.members())
                        || !previous.teamOwnerId().equals(result.teamOwnerId()))) {
                    throw new IllegalArgumentException("Owner resolver returned inconsistent team membership in one batch");
                }
            }
            resolved.put(owner, result);
        }
        for (var team : teams.values()) {
            for (var member : team.members()) {
                var queriedMember = resolved.get(member);
                if (queriedMember != null && (!queriedMember.accepted() || !queriedMember.teamId().equals(team.teamId()))) {
                    throw new IllegalArgumentException("Queried owner disagrees with captured team membership");
                }
            }
        }
        return new Inputs(epoch, deviceCopy, contributionCopy, resolved);
    }

    /** Only immutable captures are read here; this method may run on a worker. */
    public static NetworkPlan plan(Inputs inputs) {
        Objects.requireNonNull(inputs);
        var states = new Object2ObjectLinkedOpenHashMap<UUID, NetworkWorkState>();
        var teamIds = new Object2ObjectLinkedOpenHashMap<UUID, String>();
        var groups = new Object2ObjectLinkedOpenHashMap<String, ObjectArrayList<NetworkDeviceSnapshot>>();
        for (var device : inputs.devices) {
            var owner = inputs.owners.get(device.identity().ownerId());
            if (!owner.accepted()) {
                states.put(device.identity().machineId(), NetworkWorkState.OWNER_TEAM_REJECTED);
            } else {
                teamIds.put(device.identity().machineId(), owner.teamId());
                groups.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>()).add(device);
            }
        }
        var contributions = new Object2ObjectOpenHashMap<String, ObjectArrayList<NetworkContribution>>();
        for (var contribution : inputs.contributions) {
            var owner = inputs.owners.get(contribution.ownerId());
            if (owner.accepted()) { contributions.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>()).add(contribution); }
        }
        var selections = new Object2ObjectLinkedOpenHashMap<String, NetworkPlan.Team>();
        for (var entry : groups.object2ObjectEntrySet()) {
            var devices = entry.getValue();
            NetworkDeviceSnapshot center = null;
            for (var device : devices) {
                if (device.formed() && device.role() == NetworkDeviceRole.NETWORK_CENTER && (center == null
                        || device.level() > center.level() || device.level() == center.level() && device.loadOrder() < center.loadOrder())) {
                    center = device;
                }
            }
            if (center == null) {
                for (var device : devices) {
                    states.put(device.identity().machineId(), device.formed() ? NetworkWorkState.NO_ACTIVE_CENTER : NetworkWorkState.UNFORMED);
                }
                continue;
            }
            NetworkDeviceSnapshot dataCenter = null;
            for (var device : devices) {
                if (device.formed() && device.role() == NetworkDeviceRole.DATA_CENTER && device.level() <= center.level()
                        && (dataCenter == null || outranksDataCenter(device, dataCenter))) { dataCenter = device; }
            }
            var totals = new Object2LongOpenHashMap<NetworkResourceType>();
            accumulate(totals, center.resourcesPerTick());
            for (var device : devices) {
                NetworkWorkState state;
                if (!device.formed()) { state = NetworkWorkState.UNFORMED; }
                else if (device.level() > center.level() && device.role() != NetworkDeviceRole.NETWORK_CENTER) {
                    state = NetworkWorkState.CENTER_LEVEL_INSUFFICIENT;
                } else if (device.role() == NetworkDeviceRole.NETWORK_CENTER) {
                    state = device == center ? NetworkWorkState.ACTIVE_CENTER : NetworkWorkState.SAME_TYPE_CONFLICT;
                } else if (device.role() == NetworkDeviceRole.DATA_CENTER) {
                    state = device == dataCenter ? NetworkWorkState.ACTIVE_DATA_CENTER : NetworkWorkState.SAME_TYPE_CONFLICT;
                } else { state = NetworkWorkState.ACTIVE_DEVICE; }
                states.put(device.identity().machineId(), state);
                if (state == NetworkWorkState.ACTIVE_DEVICE && device.role() == NetworkDeviceRole.COMPUTER) {
                    accumulate(totals, device.resourcesPerTick());
                }
            }
            var participantResources = contributions.get(entry.getKey());
            if (participantResources != null) {
                for (var contribution : participantResources) { accumulate(totals, contribution.resourcesPerTick()); }
            }
            selections.put(entry.getKey(), new NetworkPlan.Team(center.identity().machineId(), center.level(),
                dataCenter == null ? null : dataCenter.identity().machineId(), center.resourceModifier().apply(totals)));
        }
        return new NetworkPlan(inputs.epoch, states, teamIds, selections);
    }

    private static boolean outranksDataCenter(NetworkDeviceSnapshot candidate, NetworkDeviceSnapshot retained) {
        if (candidate.level() != retained.level()) { return candidate.level() > retained.level(); }
        if (candidate.storedResearchCount() != retained.storedResearchCount()) { return candidate.storedResearchCount() > retained.storedResearchCount(); }
        return candidate.loadOrder() < retained.loadOrder();
    }

    private static void accumulate(Object2LongOpenHashMap<NetworkResourceType> totals, Object2LongMap<NetworkResourceType> contribution) {
        for (var entry : contribution.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount > Long.MAX_VALUE - totals.getLong(entry.getKey())) { throw new ArithmeticException("Network resource total overflow"); }
            totals.addTo(entry.getKey(), amount);
        }
    }

    private static void checkResources(NetworkResourceRegistry types, Object2LongMap<NetworkResourceType> resources) {
        for (var type : resources.keySet()) {
            types.canonical(type);
        }
    }

    /** Frozen batch, constructible only through validated owner-thread capture. */
    public static final class Inputs {
        private final long epoch;
        private final ObjectList<NetworkDeviceSnapshot> devices;
        private final ObjectList<NetworkContribution> contributions;
        private final Object2ObjectMap<UUID, OwnerTeamResolution> owners;

        private Inputs(long epoch, ObjectList<NetworkDeviceSnapshot> devices, ObjectList<NetworkContribution> contributions,
                Object2ObjectMap<UUID, OwnerTeamResolution> owners) {
            this.epoch = epoch;
            this.devices = ObjectLists.unmodifiable(devices);
            this.contributions = ObjectLists.unmodifiable(contributions);
            this.owners = Object2ObjectMaps.unmodifiable(owners);
        }
    }
}
