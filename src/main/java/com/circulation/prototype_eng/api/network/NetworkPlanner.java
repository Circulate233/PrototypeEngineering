package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * PN selection and contribution rules, with owner capture separated from detached planning.
 */
public final class NetworkPlanner {
    private NetworkPlanner() {
    }

    /**
     * Invoke on the server owner thread after capturing current unique runtimes and active participants.
     * Each owner is resolved once. A provider failure aborts the whole batch; never reuse an older permit.
     * Both lists are borrowed immutable inputs: keep their contents unchanged while the returned Inputs
     * is retained or used by a planner, including across worker execution.
     * Device and participant IDs must be unique; contributions must belong to this epoch.
     * The resolver must return consistent owner/team membership for the batch.
     */
    public static Inputs capture(long epoch, List<NetworkDeviceSnapshot> devices,
                                 List<NetworkContribution> contributions, OwnerTeamResolver resolver) {
        var resolved = new Object2ObjectOpenHashMap<UUID, OwnerTeamResolution>();
        Function<UUID, OwnerTeamResolution> resolve = resolver::resolve;
        for (int index1 = 0; index1 < devices.size(); index1++) {
            var device = devices.get(index1);
            resolved.computeIfAbsent(device.identity().ownerId(), resolve);
        }
        for (int index6 = 0; index6 < contributions.size(); index6++) {
            var contribution = contributions.get(index6);
            resolved.computeIfAbsent(contribution.ownerId(), resolve);
        }
        return new Inputs(epoch, devices, contributions, resolved);
    }

    /**
     * Only immutable captures are read here; this method may run on a worker.
     */
    public static NetworkPlan plan(Inputs inputs) {
        Objects.requireNonNull(inputs);
        var states = new Object2ObjectLinkedOpenHashMap<UUID, NetworkWorkState>();
        var teamIds = new Object2ObjectLinkedOpenHashMap<UUID, String>();
        var groups = new Object2ObjectLinkedOpenHashMap<String, ObjectArrayList<NetworkDeviceSnapshot>>();
        for (int index8 = 0; index8 < inputs.devices.size(); index8++) {
            var device = inputs.devices.get(index8);
            var owner = inputs.owners.get(device.identity().ownerId());
            if (!owner.accepted()) {
                states.put(device.identity().machineId(), NetworkWorkState.OWNER_TEAM_REJECTED);
            } else {
                teamIds.put(device.identity().machineId(), owner.teamId());
                groups.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>()).add(device);
            }
        }
        var contributions = new Object2ObjectOpenHashMap<String, ObjectArrayList<NetworkContribution>>();
        for (int index7 = 0; index7 < inputs.contributions.size(); index7++) {
            var contribution = inputs.contributions.get(index7);
            var owner = inputs.owners.get(contribution.ownerId());
            if (owner.accepted()) {
                contributions.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>()).add(contribution);
            }
        }
        var selections = new Object2ObjectLinkedOpenHashMap<String, NetworkPlan.Team>();
        for (var entry : groups.object2ObjectEntrySet()) {
            var devices = entry.getValue();
            NetworkDeviceSnapshot center = null;
            for (int index4 = 0; index4 < devices.size(); index4++) {
                var device = devices.get(index4);
                if (device.formed() && device.role() == NetworkDeviceRole.NETWORK_CENTER && (center == null
                    || device.level() > center.level() || device.level() == center.level()
                    && device.identity().machineId().compareTo(center.identity().machineId()) < 0)) {
                    center = device;
                }
            }
            if (center == null) {
                for (int index = 0; index < devices.size(); index++) {
                    var device = devices.get(index);
                    states.put(device.identity().machineId(), device.formed() ? NetworkWorkState.NO_ACTIVE_CENTER : NetworkWorkState.UNFORMED);
                }
                continue;
            }
            NetworkDeviceSnapshot dataCenter = null;
            for (int index3 = 0; index3 < devices.size(); index3++) {
                var device = devices.get(index3);
                if (device.formed() && device.role() == NetworkDeviceRole.DATA_CENTER && device.level() <= center.level()
                    && (dataCenter == null || outranksDataCenter(device, dataCenter))) {
                    dataCenter = device;
                }
            }
            var totals = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            accumulate(totals, center.resourcesPerTick());
            long concurrency = center.concurrencyPerTick();
            for (int index5 = 0; index5 < devices.size(); index5++) {
                var device = devices.get(index5);
                NetworkWorkState state;
                if (!device.formed()) {
                    state = NetworkWorkState.UNFORMED;
                } else if (device.level() > center.level() && device.role() != NetworkDeviceRole.NETWORK_CENTER) {
                    state = NetworkWorkState.CENTER_LEVEL_INSUFFICIENT;
                } else if (device.role() == NetworkDeviceRole.NETWORK_CENTER) {
                    state = device == center ? NetworkWorkState.ACTIVE_CENTER : NetworkWorkState.SAME_TYPE_CONFLICT;
                } else if (device.role() == NetworkDeviceRole.DATA_CENTER) {
                    state = device == dataCenter ? NetworkWorkState.ACTIVE_DATA_CENTER : NetworkWorkState.SAME_TYPE_CONFLICT;
                } else {
                    state = NetworkWorkState.ACTIVE_DEVICE;
                }
                states.put(device.identity().machineId(), state);
                if (state == NetworkWorkState.ACTIVE_DEVICE || state == NetworkWorkState.ACTIVE_DATA_CENTER) {
                    accumulate(totals, device.resourcesPerTick());
                    concurrency = Math.addExact(concurrency, device.concurrencyPerTick());
                }
            }
            var participantResources = contributions.get(entry.getKey());
            if (participantResources != null) {
                for (int index2 = 0; index2 < participantResources.size(); index2++) {
                    var contribution = participantResources.get(index2);
                    accumulate(totals, contribution.resourcesPerTick());
                    concurrency = Math.addExact(concurrency, contribution.concurrencyPerTick());
                }
            }
            selections.put(entry.getKey(), new NetworkPlan.Team(center.identity().machineId(), center.level(),
                dataCenter == null ? null : dataCenter.identity().machineId(), concurrency, external(center.resourceModifier().apply(totals)),
                dataCenter == null ? null : dataCenter.dataCenterResearch()));
        }
        return new NetworkPlan(inputs.epoch, states, teamIds, selections);
    }

    private static boolean outranksDataCenter(NetworkDeviceSnapshot candidate, NetworkDeviceSnapshot retained) {
        if (candidate.level() != retained.level()) {
            return candidate.level() > retained.level();
        }
        if (candidate.storedResearchCount() != retained.storedResearchCount()) {
            return candidate.storedResearchCount() > retained.storedResearchCount();
        }
        return candidate.identity().machineId().compareTo(retained.identity().machineId()) < 0;
    }

    private static Reference2DoubleMap<NetworkResourceType> external(Reference2DoubleMap<NetworkResourceType> produced) {
        produced.reference2DoubleEntrySet().removeIf(entry -> entry.getKey().internal());
        return produced;
    }

    private static void accumulate(Reference2DoubleOpenHashMap<NetworkResourceType> totals, Reference2DoubleMap<NetworkResourceType> contribution) {
        for (var entry : contribution.reference2DoubleEntrySet()) {
            if (entry.getKey().internal()) continue;
            double amount = entry.getDoubleValue();
            NetworkResourceModifier.sum(amount, totals.getDouble(entry.getKey()));
            totals.addTo(entry.getKey(), amount);
        }
    }

    /**
     * Frozen batch, constructible only through validated owner-thread capture.
     */
    public static final class Inputs {
        private final long epoch;
        private final List<NetworkDeviceSnapshot> devices;
        private final List<NetworkContribution> contributions;
        private final Object2ObjectMap<UUID, OwnerTeamResolution> owners;

        private Inputs(long epoch, List<NetworkDeviceSnapshot> devices, List<NetworkContribution> contributions,
                       Object2ObjectMap<UUID, OwnerTeamResolution> owners) {
            this.epoch = epoch;
            this.devices = devices;
            this.contributions = contributions;
            this.owners = owners;
        }

        OwnerTeamResolution owner(UUID ownerId) {
            return Objects.requireNonNull(owners.get(ownerId));
        }

        Inputs withDevices(List<NetworkDeviceSnapshot> devices) {
            return new Inputs(epoch, devices, contributions, owners);
        }
    }
}
