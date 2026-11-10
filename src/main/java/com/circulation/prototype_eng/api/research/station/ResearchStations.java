package com.circulation.prototype_eng.api.research.station;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleContext;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Owner-side station selection. One dimension and world session share one material pool per segment.
 */
public final class ResearchStations {
    private final MachineIdentityRegistry identities;
    private final Runnable ownerCheck;
    private final Predicate<Site> absenceProof;
    private final Object2IntOpenHashMap<String> lastDimensions = new Object2IntOpenHashMap<>();

    public ResearchStations(MachineIdentityRegistry identities, Runnable ownerCheck) {
        this(identities, ownerCheck, _ -> false);
    }

    /**
     * Optional physical absence proof for cold recovery. Missing registry entries, unloaded chunks
     * and hosts whose saved identity cannot be read must return false. Never force-load a chunk.
     */
    public ResearchStations(MachineIdentityRegistry identities, Runnable ownerCheck, Predicate<Site> absenceProof) {
        this.identities = Objects.requireNonNull(identities);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        this.absenceProof = Objects.requireNonNull(absenceProof);
    }

    static List<Site> validatedSites(List<Site> sites) {
        var saved = List.copyOf(sites);
        if (saved.isEmpty()) {
            throw new IllegalArgumentException("A material segment requires physical stations");
        }
        int dimension = saved.getFirst().location().dimension();
        var ids = new ObjectLinkedOpenHashSet<UUID>();
        var locations = new ObjectLinkedOpenHashSet<MachineIdentity>();
        for (var site : saved) {
            if (!ids.add(site.identity().machineId()) || !locations.add(site.location()) || site.location().dimension() != dimension) {
                throw new IllegalArgumentException("Saved stations must be distinct and in one dimension");
            }
        }
        return saved;
    }

    private static MachineHatches pool(List<MachineHatches> sources) {
        var resources = new ObjectArrayList<ResourceHatch>();
        var inputs = new ObjectArrayList<EnergyInput>();
        var outputs = new ObjectArrayList<EnergyOutput>();
        for (var hatches : sources) {
            resources.addAll(hatches.resources());
            inputs.addAll(hatches.energyInputs());
            outputs.addAll(hatches.energyOutputs());
        }
        return new MachineHatches(resources, inputs, outputs);
    }

    @Nullable
    private static Station candidate(MachineIdentityRegistry.Registration identity, MachineInstance machine, String teamId, int tier) {
        var binding = attachment(machine);
        if (binding == null) {
            return null;
        }
        var permit = binding.role.workPermit();
        if (!eligible(permit, identity, teamId, tier)) {
            return null;
        }
        var result = new Station(identity, machine, binding.type, binding.role, MachineHatches.get(machine), Objects.requireNonNull(permit));
        return result.physicallyCurrent() ? result : null;
    }

    @Nullable
    private static RoleBinding attachment(MachineInstance machine) {
        ComponentType<MachineInstance, ?> roleType = null;
        MachineNetworkDevice role = null;
        for (var binding : machine.components().assemblyBindings()) {
            if (binding.component() instanceof MachineNetworkDevice device) {
                if (role != null) {
                    throw new IllegalStateException("Machine has multiple network attachments");
                }
                role = device;
                roleType = binding.type();
            }
        }
        return role == null ? null : new RoleBinding(Objects.requireNonNull(roleType), role);
    }

    private static boolean eligible(@Nullable NetworkRuntime.WorkPermit permit, MachineIdentityRegistry.Registration identity, String teamId, int tier) {
        if (permit == null || !permit.current() || !permit.teamId().equals(teamId)) {
            return false;
        }
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

    private static boolean physicalCurrent(MachineIdentityRegistry.Registration identity, MachineInstance machine, MachineHatches hatches) {
        var lifecycle = machine.lifecycle();
        return identity.unique() && identity.runtimeIdentity() == machine && lifecycle != null && lifecycle.state() == MachineLifecycle.State.LOADED
            && lifecycle.host().worldIdentity() == identity.worldIdentity() && lifecycle.host().identity().equals(identity.location())
            && MachineHatches.get(machine) == hatches && hatches.resources().stream().allMatch(ResourceHatch::isValid)
            && hatches.energyInputs().stream().allMatch(EnergyInput::isValid) && hatches.energyOutputs().stream().allMatch(EnergyOutput::isValid);
    }

    public boolean absent(Site site) {
        ownerCheck.run();
        return absenceProof.test(Objects.requireNonNull(site));
    }

    /**
     * Capture admission facts without selecting a material pool, taking a lease or consuming work.
     */
    public Admission admission(String teamId) {
        ownerCheck.run();
        if (Objects.requireNonNull(teamId).isBlank()) {
            throw new IllegalArgumentException("Research admission requires a team");
        }
        long revision = identities.revision();
        AdmissionDevice center = null;
        var stations = new ObjectArrayList<AdmissionDevice>();
        for (var identity : identities.uniqueRegistrations()) {
            if (!(identity.runtimeIdentity() instanceof MachineInstance machine)) {
                continue;
            }
            var binding = attachment(machine);
            if (binding == null) {
                continue;
            }
            var permit = binding.role.workPermit();
            if (permit == null || !permit.current() || !permit.teamId().equals(teamId)) {
                continue;
            }
            var snapshot = permit.deviceSnapshot();
            var state = binding.role.state();
            if (!snapshot.identity().equals(identity.identity()) || snapshot.loadOrder() != identity.loadOrder() || !snapshot.formed()) {
                continue;
            }
            if (state == NetworkWorkState.ACTIVE_CENTER && snapshot.role() == NetworkDeviceRole.NETWORK_CENTER) {
                if (center != null) {
                    throw new IllegalStateException("Team has multiple active physical network centers");
                }
                center = new AdmissionDevice(identity, machine, binding, permit, NetworkWorkState.ACTIVE_CENTER);
            } else if (state == NetworkWorkState.ACTIVE_DEVICE && snapshot.role() == NetworkDeviceRole.RESEARCH_STATION) {
                stations.add(new AdmissionDevice(identity, machine, binding, permit, NetworkWorkState.ACTIVE_DEVICE));
            }
        }
        if (center == null) {
            return new Admission(this, revision, new TeamResearchStart.Environment(0, 0, 0, Object2LongMaps.emptyMap()), List.of());
        }
        int level = center.permit.deviceSnapshot().level();
        var dataCenter = center.permit.dataCenterResearch();
        int highestStation = 0;
        var sources = new ObjectArrayList<AdmissionDevice>();
        sources.add(center);
        for (var station : stations) {
            if (station.permit.epoch() != center.permit.epoch() || station.permit.tick() != center.permit.tick()) {
                continue;
            }
            highestStation = Math.max(highestStation, station.permit.deviceSnapshot().level());
            sources.add(station);
        }
        return new Admission(this, revision, new TeamResearchStart.Environment(level, dataCenter == null ? 0 : dataCenter.level(),
            highestStation, center.permit.availableResources()), sources);
    }

    /**
     * Lease acquisition precedes queue.planSegment; station count scales numeric grant limits, not material copies.
     */
    @Nullable
    public Capture capture(String teamId, int requiredTier) {
        ownerCheck.run();
        if (Objects.requireNonNull(teamId).isBlank() || requiredTier < 1) {
            throw new IllegalArgumentException("Station selection requires a team and positive tier");
        }
        var byDimension = new Int2ObjectOpenHashMap<ObjectArrayList<Station>>();
        for (var identity : identities.uniqueRegistrations()) {
            if (!(identity.runtimeIdentity() instanceof MachineInstance machine)) {
                continue;
            }
            var station = candidate(identity, machine, teamId, requiredTier);
            if (station != null) {
                byDimension.computeIfAbsent(identity.location().dimension(), _ -> new ObjectArrayList<>()).add(station);
            }
        }
        if (byDimension.isEmpty()) {
            return null;
        }
        var dimensions = new IntArrayList(byDimension.keySet());
        dimensions.sort(Integer::compare);
        int dimension = dimensions.getInt(0);
        if (lastDimensions.containsKey(teamId)) {
            int previous = lastDimensions.getInt(teamId);
            for (int next : dimensions) {
                if (next > previous) {
                    dimension = next;
                    break;
                }
            }
        }
        lastDimensions.put(teamId, dimension);
        var candidates = byDimension.get(dimension);
        candidates.sort(Comparator.comparing(station -> station.identity.identity().machineId()));
        Object worldIdentity = candidates.getFirst().identity.worldIdentity();
        var leased = new ObjectArrayList<Station>();
        for (var station : candidates) {
            if (station.identity.worldIdentity() == worldIdentity && station.physicallyCurrent() && lease(station.permit)) {
                leased.add(station);
            }
        }
        if (leased.isEmpty()) {
            return null;
        }
        var result = new Capture(this, teamId, requiredTier, dimension, leased, pool(leased.stream().map(Station::hatches).toList()));
        return result.current() ? result : null;
    }

    /**
     * Reload only the saved physical identities and locations; a new runtime must obtain fresh authority.
     */
    @Nullable
    public Capture restore(String teamId, int requiredTier, List<Site> sites) {
        ownerCheck.run();
        if (Objects.requireNonNull(teamId).isBlank() || requiredTier < 1) {
            throw new IllegalArgumentException("Station restoration requires a team and positive tier");
        }
        var saved = validatedSites(sites);
        var registered = uniqueById();
        var restored = new ObjectArrayList<Station>(saved.size());
        Object worldIdentity = null;
        for (var site : saved) {
            var identity = registered.get(site.identity().machineId());
            if (identity == null || !identity.identity().equals(site.identity()) || !identity.location().equals(site.location())
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) {
                return null;
            }
            if (worldIdentity == null) {
                worldIdentity = identity.worldIdentity();
            } else if (identity.worldIdentity() != worldIdentity) {
                return null;
            }
            var station = candidate(identity, machine, teamId, requiredTier);
            if (station == null) {
                return null;
            }
            restored.add(station);
        }
        for (var station : restored) {
            if (!station.physicallyCurrent() || !lease(station.permit)) {
                return null;
            }
        }
        var result = new Capture(this, teamId, requiredTier, saved.getFirst().location().dimension(), restored,
            pool(restored.stream().map(Station::hatches).toList()), saved);
        return result.current() ? result : null;
    }

    /**
     * Reacquire only real physical owners for terminal cleanup, even after their network team changes.
     */
    @Nullable
    public CleanupSources restoreForCleanup(List<Site> sites) {
        ownerCheck.run();
        var saved = validatedSites(sites);
        var registered = uniqueById();
        var restored = new ObjectArrayList<PhysicalSite>(saved.size());
        Object worldIdentity = null;
        for (var site : saved) {
            var identity = registered.get(site.identity().machineId());
            if (identity == null || !identity.identity().equals(site.identity()) || !identity.location().equals(site.location())
                || !(identity.runtimeIdentity() instanceof MachineInstance machine)) {
                return null;
            }
            if (worldIdentity == null) {
                worldIdentity = identity.worldIdentity();
            } else if (identity.worldIdentity() != worldIdentity) {
                return null;
            }
            var restoredSite = new PhysicalSite(identity, machine, MachineHatches.get(machine));
            if (!restoredSite.current()) {
                return null;
            }
            restored.add(restoredSite);
        }
        var result = new CleanupSources(this, saved, restored, pool(restored.stream().map(PhysicalSite::hatches).toList()));
        return result.current() ? result : null;
    }

    private Object2ObjectOpenHashMap<UUID, MachineIdentityRegistry.Registration> uniqueById() {
        var registered = new Object2ObjectOpenHashMap<UUID, MachineIdentityRegistry.Registration>();
        for (var identity : identities.uniqueRegistrations()) {
            registered.put(identity.identity().machineId(), identity);
        }
        return registered;
    }

    /**
     * Observe the original registration during ControllerBroken, before Unloaded closes it.
     */
    @Nullable
    public Site destroyedSite(MachineLifecycleContext context) {
        ownerCheck.run();
        Objects.requireNonNull(context).host().requireServerThread();
        for (var binding : context.machine().components().assemblyBindings()) {
            if (binding.component() instanceof MachineOwnership ownership) {
                var identity = ownership.registration();
                if (identity != null && identities.owns(identity) && identity.current() && identity.runtimeIdentity() == context.machine()
                    && identity.worldIdentity() == context.host().worldIdentity() && identity.location().equals(context.identity())) {
                    return new Site(identity.identity(), identity.location(), context.host().controllerBlockId());
                }
            }
        }
        return null;
    }

    /**
     * Continue exactly the original station instances and source pool with this tick's new permits and leases.
     */
    @Nullable
    public Capture renew(Capture previous) {
        ownerCheck.run();
        if (Objects.requireNonNull(previous).selector != this) {
            throw new IllegalArgumentException("Station capture belongs to another selector");
        }
        var renewed = new ObjectArrayList<Station>(previous.stations.size());
        for (var old : previous.stations) {
            if (!old.physicallyCurrent()) {
                return null;
            }
            var permit = old.role.workPermit();
            if (!eligible(permit, old.identity, previous.teamId, previous.requiredTier) || !lease(Objects.requireNonNull(permit))) {
                return null;
            }
            renewed.add(new Station(old.identity, old.machine, old.roleType, old.role, old.hatches, permit));
        }
        var result = new Capture(this, previous.teamId, previous.requiredTier, previous.dimension, renewed, previous.hatches, previous.sites);
        return result.current() ? result : null;
    }

    private record RoleBinding(ComponentType<MachineInstance, ?> type, MachineNetworkDevice role) {
    }

    private record AdmissionDevice(MachineIdentityRegistry.Registration identity, MachineInstance machine,
                                   RoleBinding binding,
                                   NetworkRuntime.WorkPermit permit, NetworkWorkState state) {
        private boolean current() {
            var lifecycle = machine.lifecycle();
            return identity.unique() && identity.runtimeIdentity() == machine && lifecycle != null && lifecycle.schedulingAllowed() && lifecycle.formed()
                && lifecycle.host().worldIdentity() == identity.worldIdentity() && lifecycle.host().identity().equals(identity.location())
                && machine.components().get(binding.type) == binding.role && binding.role.state() == state && permit.authorized();
        }
    }

    private record Station(MachineIdentityRegistry.Registration identity, MachineInstance machine,
                           ComponentType<MachineInstance, ?> roleType, MachineNetworkDevice role,
                           MachineHatches hatches, NetworkRuntime.WorkPermit permit) {
        private boolean physicallyCurrent() {
            var lifecycle = machine.lifecycle();
            return physicalCurrent(identity, machine, hatches) && machine.components().get(roleType) == role
                && lifecycle != null && lifecycle.schedulingAllowed() && lifecycle.formed();
        }
    }

    private record PhysicalSite(MachineIdentityRegistry.Registration identity, MachineInstance machine,
                                MachineHatches hatches) {
        private boolean current() {
            return physicalCurrent(identity, machine, hatches);
        }
    }

    /**
     * Only physical identity and location are persistent; world sessions, runtimes and permits are not.
     */
    public record Site(PersistentMachineIdentity identity, MachineIdentity location,
                       @Nullable ResourceLocation controllerBlockId) {
        public Site(PersistentMachineIdentity identity, MachineIdentity location) {
            this(identity, location, null);
        }

        public Site {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(location);
        }

        /**
         * The block provenance is recovery evidence, not an additional runtime identity.
         */
        public boolean sameStation(Site other) {
            return identity.equals(other.identity) && location.equals(other.location);
        }
    }

    /**
     * Frozen original network facts; revalidation never obtains replacement permission after callbacks.
     */
    public static final class Admission {
        private final ResearchStations selector;
        private final long revision;
        private final TeamResearchStart.Environment environment;
        private final List<AdmissionDevice> sources;

        private Admission(ResearchStations selector, long revision, TeamResearchStart.Environment environment, List<AdmissionDevice> sources) {
            this.selector = selector;
            this.revision = revision;
            this.environment = environment;
            this.sources = List.copyOf(sources);
        }

        public TeamResearchStart.Environment environment() {
            return environment;
        }

        public boolean current() {
            selector.ownerCheck.run();
            if (selector.identities.revision() != revision || !sources.stream().allMatch(AdmissionDevice::current)) {
                return false;
            }
            return sources.isEmpty() || environment.availableResources().equals(sources.getFirst().permit.availableResources());
        }
    }

    /**
     * Physical-only recovery proof. This type grants no forward work, network lease or numeric supply.
     */
    public static final class CleanupSources {
        private final ResearchStations selector;
        private final List<Site> sites;
        private final List<PhysicalSite> physical;
        private final MachineHatches hatches;

        private CleanupSources(ResearchStations selector, List<Site> sites, List<PhysicalSite> physical, MachineHatches hatches) {
            this.selector = selector;
            this.sites = sites;
            this.physical = List.copyOf(physical);
            this.hatches = hatches;
        }

        public List<Site> sites() {
            return sites;
        }

        public MachineInstance primary() {
            return physical.getFirst().machine();
        }

        public MachineHatches hatches() {
            return hatches;
        }

        public boolean current() {
            selector.ownerCheck.run();
            return physical.stream().allMatch(PhysicalSite::current);
        }
    }

    /**
     * Immutable site/source identities with replaceable per-tick authority; no station registry or numeric reservation.
     */
    public static final class Capture {
        private final ResearchStations selector;
        private final String teamId;
        private final int requiredTier;
        private final int dimension;
        private final List<Station> stations;
        private final List<Site> sites;
        private final List<MachineInstance> machines;
        private final ObjectSet<UUID> stationIds;
        private final MachineHatches hatches;

        private Capture(ResearchStations selector, String teamId, int requiredTier, int dimension, List<Station> stations, MachineHatches hatches) {
            this(selector, teamId, requiredTier, dimension, stations, hatches, null);
        }

        private Capture(ResearchStations selector, String teamId, int requiredTier, int dimension, List<Station> stations,
                        MachineHatches hatches, @Nullable List<Site> originalSites) {
            this.selector = selector;
            this.teamId = teamId;
            this.requiredTier = requiredTier;
            this.dimension = dimension;
            this.stations = List.copyOf(stations);
            sites = originalSites == null ? this.stations.stream().map(station -> new Site(station.identity.identity(), station.identity.location(),
                Objects.requireNonNull(station.machine.lifecycle()).host().controllerBlockId())).toList() : originalSites;
            machines = this.stations.stream().map(Station::machine).toList();
            this.hatches = hatches;
            var ids = new ObjectLinkedOpenHashSet<UUID>();
            stations.forEach(station -> ids.add(station.identity.identity().machineId()));
            stationIds = ObjectSets.unmodifiable(ids);
        }

        public String teamId() {
            return teamId;
        }

        @SuppressWarnings("unused") // Consumers retain the tier alongside the frozen station/source contract.
        public int requiredTier() {
            return requiredTier;
        }

        public int dimension() {
            return dimension;
        }

        public ObjectSet<UUID> stationIds() {
            return stationIds;
        }

        public List<Site> sites() {
            return sites;
        }

        public List<MachineInstance> machines() {
            return machines;
        }

        public MachineInstance primary() {
            return stations.getFirst().machine;
        }

        public MachineHatches hatches() {
            return hatches;
        }

        public Object2LongMap<NetworkResourceType> numericResources() {
            return stations.getFirst().permit.numericResources();
        }

        /**
         * Owner-only version/binding proofs remain valid inside source fences; no inventory is re-read or locked.
         */
        public boolean current() {
            selector.ownerCheck.run();
            long epoch = stations.getFirst().permit.epoch();
            long tick = stations.getFirst().permit.tick();
            for (var station : stations) {
                if (station.permit.epoch() != epoch || station.permit.tick() != tick || !station.physicallyCurrent() || !station.permit.authorized()) {
                    return false;
                }
            }
            return stations.stream().allMatch(Station::physicallyCurrent);
        }
    }
}
