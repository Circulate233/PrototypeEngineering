package com.circulation.prototype_eng.api.research.station;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.ControllerEnergy;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.queue.ResearchProgress;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pools materials from loaded team stations; only contributors acquire concurrency and pay FE.
 */
public final class ResearchStations {
    private final MachineIdentityRegistry identities;
    private final Runnable ownerCheck;

    public ResearchStations(MachineIdentityRegistry identities, Runnable ownerCheck) {
        this.identities = identities;
        this.ownerCheck = ownerCheck;
    }

    private ObjectArrayList<Station> find(String teamId, int tier) {
        ownerCheck.run();
        var result = new ObjectArrayList<Station>();

        var elements4 = identities.uniqueRegistrations();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var identity = elements4.get(index4);
            if (!(identity.runtimeIdentity() instanceof MachineInstance machine) || !(machine instanceof ResearchStation))
                continue;
            var role = machine.networkDevice();
            if (role == null) continue;
            var permit = role.workPermit();
            if (permit == null || !permit.teamId().equals(teamId)) continue;
            var snapshot = permit.deviceSnapshot();
            if (snapshot.level() < tier)
                continue;
            result.add(new Station(identity.identity().machineId(), machine, role, permit));
        }

        result.sort(Comparator.comparing(Station::id));
        return result;
    }

    private static ObjectArrayList<ResourceStorage> inputs(List<Station> stations) {
        var storages = new ObjectArrayList<ResourceStorage>();
        var identities = new ReferenceOpenHashSet<>();
        for (int index11 = 0; index11 < stations.size(); index11++) {
            var station = stations.get(index11);
            var elements10 = MachineHatches.get(station.machine()).resources();
            for (int index10 = 0; index10 < elements10.size(); index10++) {
                var hatch = elements10.get(index10);
                var elements = hatch.inputPartitions();
                for (int index1 = 0; index1 < elements.size(); index1++) {
                    var partition = elements.get(index1);
                    var input = partition.input();
                    if (input != null && partition.isValid() && identities.add(partition.inputStorageIdentity()))
                        storages.add(input);
                }
            }
        }
        return storages;
    }

    private static AEKey2LongMap available(List<ResourceStorage> sources, AEKey2LongMap remaining) {
        var result = new AEKey2LongMap.OpenHashMap();
        for (var entry : remaining.object2LongEntrySet()) {
            long missing = entry.getLongValue();
            for (int index8 = 0; index8 < sources.size(); index8++) {
                var source = sources.get(index8);
                if (missing == 0) break;
                long amount = Math.min(missing, source.availableAmount(entry.getKey()));
                result.addTo(entry.getKey(), amount);
                missing -= amount;
            }
        }
        return result;
    }

    public AEKey2LongMap availableMaterials(String teamId, int tier, AEKey2LongMap remaining) {
        return available(inputs(find(teamId, tier)), remaining);
    }

    public Admission admission(String teamId) {
        ownerCheck.run();
        NetworkRuntime.WorkPermit center = null;
        int highestStation = 0;

        var elements3 = identities.uniqueRegistrations();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var identity = elements3.get(index3);
            if (!(identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var device = machine.networkDevice();
            var permit = device == null ? null : device.workPermit();
            if (permit == null || !permit.teamId().equals(teamId)) continue;
            if (device.state() == NetworkWorkState.ACTIVE_CENTER) center = permit;
            else if (machine instanceof ResearchStation)
                highestStation = Math.max(highestStation, permit.deviceSnapshot().level());
        }

        var environment = new TeamResearchStart.Environment(center == null ? 0 : center.deviceSnapshot().level(),
            highestStation, center == null ? Reference2DoubleMaps.emptyMap() : center.availableResources());
        return new Admission(identities, identities.revision(), center, environment);
    }

    public @Nullable Capture capture(String teamId, int tier) {
        var stations = find(teamId, tier);
        var workers = new ObjectArrayList<Station>();
        for (int index2 = 0; index2 < stations.size(); index2++) {
            var station = stations.get(index2);
            if (!station.machine().canPayPower(station.permit().tick())) continue;
            var lease = station.permit().requestLease();
            if (lease == NetworkTeamBudget.LeaseStatus.GRANTED || lease == NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED)
                workers.add(station);
        }
        return workers.isEmpty() ? null : new Capture(teamId, workers, inputs(stations));
    }

    private record Station(UUID id, MachineInstance machine, MachineNetworkDevice device,
                           NetworkRuntime.WorkPermit permit) {
    }

    public record Admission(MachineIdentityRegistry identities, long revision,
                            @Nullable NetworkRuntime.WorkPermit center,
                            TeamResearchStart.Environment environment) {
        public boolean current() {
            return identities.revision() == revision && (center == null || center.authorized());
        }
    }

    public static final class Capture {
        private final String teamId;
        private final List<Station> workers;
        private final List<ResourceStorage> inputs;
        private final List<ResearchProgress.StationRate> rates;

        private Capture(String teamId, List<Station> workers, List<ResourceStorage> inputs) {
            this.teamId = teamId;
            this.workers = workers;
            this.inputs = inputs;
            var rates = new ObjectArrayList<ResearchProgress.StationRate>(workers.size());
            for (int index9 = 0; index9 < workers.size(); index9++) {
                var station = workers.get(index9);
                var values = station.device().networkValues();
                var provided = values == null ? ((ResearchStation) station.machine()).researchRates() : values.researchRates;
                rates.add(new ResearchProgress.StationRate(station.id(), provided));
            }
            this.rates = rates;
        }

        public String teamId() {
            return teamId;
        }

        public List<ResearchProgress.StationRate> rates() {
            return rates;
        }

        public Reference2DoubleMap<NetworkResourceType> availableResources() {
            return workers.getFirst().permit().availableResources();
        }

        public AEKey2LongMap availableMaterials(AEKey2LongMap remaining) {
            return available(inputs, remaining);
        }

        public boolean current() {
            if (!workers.getFirst().permit().authorized()) return false;
            for (int index6 = 0; index6 < workers.size(); index6++) {
                var worker = workers.get(index6);
                if (!worker.permit().current()) return false;
            }
            for (int index5 = 0; index5 < inputs.size(); index5++) {
                var input = inputs.get(index5);
                if (!input.isValid()) return false;
            }
            return true;
        }

        public void releaseUnused(List<UUID> used) {
            int usedIndex = 0;
            for (int index = 0; index < workers.size(); index++) {
                var worker = workers.get(index);
                if (usedIndex < used.size() && used.get(usedIndex).equals(worker.id())) usedIndex++;
                else worker.permit().releaseLease();
            }
        }

        public @Nullable NetworkTeamBudget.Reservation reserveNetwork(ResourceLocation research, Reference2DoubleMap<NetworkResourceType> grants, List<UUID> used) {
            for (int index7 = 0; index7 < workers.size(); index7++) {
                var worker = workers.get(index7);
                if (worker.id().equals(used.getFirst())) return worker.permit().reserve(research, grants);
            }
            throw new IllegalStateException("Research contributor is absent from its capture");
        }

        public boolean reserveMaterials(AEKey2LongMap amounts, List<ResourceReservation> tokens) {
            for (var entry : amounts.object2LongEntrySet()) {
                long remaining = entry.getLongValue();
                for (int index12 = 0; index12 < inputs.size(); index12++) {
                    var input = inputs.get(index12);
                    if (remaining == 0) break;
                    var context = input.context();
                    context.lock();
                    try {
                        var snapshot = input.snapshotFor(entry.getKey());
                        long amount = Math.min(remaining, snapshot.available().getLong(entry.getKey()));
                        if (amount == 0) continue;
                        var reserved = input.reserveExtract(snapshot, entry.getKey(), amount);
                        if (reserved.status() != ReservationResult.Status.ACCEPTED) return false;
                        tokens.add(reserved.reservation());
                        remaining -= amount;
                    } finally {
                        context.unlock();
                    }
                }
                if (remaining != 0) return false;
            }
            return true;
        }

        public boolean reservePower(List<UUID> used, List<ControllerEnergy.Payment> payments, List<ResourceReservation> tokens) {
            int usedIndex = 0;
            for (int index = 0; index < workers.size() && usedIndex < used.size(); index++) {
                var worker = workers.get(index);
                if (!used.get(usedIndex).equals(worker.id())) continue;
                usedIndex++;
                var payment = worker.machine().reservePower(worker.permit().tick());
                if (payment == null) return false;
                if (payment != ControllerEnergy.Payment.FREE) {
                    payments.add(payment);
                    tokens.addAll(payment.reservations());
                }
            }
            return true;
        }
    }
}
