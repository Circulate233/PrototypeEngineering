package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.ControllerEnergy;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.api.transaction.ReservedTransaction;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Owner-thread network publication for one identity registry. Previous results are revoked before
 * any capture callback. Registration handles and the identity revision prevent replaced or conflicting
 * runtimes from regaining old permission. This supplies only the network factor of recipe readiness.
 */
public final class NetworkRuntime implements AutoCloseable {
    private final MachineIdentityRegistry identities;
    private final OwnerTeamResolver teams;
    private final Runnable ownerCheck;
    private final NetworkParticipants participants;
    private final Reference2ObjectLinkedOpenHashMap<Object, Registration> registrations = new Reference2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectOpenHashMap<String, NetworkTeamBudget> budgets = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<Runnable> displayReads = new ObjectArrayList<>();
    @Nullable
    private volatile Publication published;
    private long nextEpoch;
    private long tick = -1;
    private boolean ticking;
    private boolean preparing;
    private boolean captureRevoked;
    private boolean closed;
    private boolean closing;

    public NetworkRuntime(MachineIdentityRegistry identities, OwnerTeamResolver teams, Runnable ownerCheck) {
        this.identities = Objects.requireNonNull(identities);
        this.teams = Objects.requireNonNull(teams);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        participants = new NetworkParticipants(this, teams, ownerCheck);
    }

    /**
     * External devices attach to this same runtime and share its actual tick budgets.
     */
    public NetworkParticipants participants() {
        return participants;
    }

    /**
     * Begin once per actual server tick, independently of how often its network is captured.
     */
    public void beginTick(long tick) {
        requireMutation();
        if (ticking || tick < 0 || tick <= this.tick) {
            throw new IllegalStateException("Network tick must advance after the previous tick ends");
        }
        this.tick = tick;
        ticking = true;
    }

    /**
     * End after all accepted workers have joined; old receipts retain only issuing-tick compensation.
     */
    public void endTick() {
        requireMutation();
        try {
            int count = displayReads.size();
            for (int index = 0; index < count; index++) displayReads.get(index).run();
        } finally {
            displayReads.clear();
            revoke();
            try {
                participants.endTick();
            } finally {
                for (var budget : budgets.values()) budget.close();
                budgets.clear();
                ticking = false;
            }
        }
    }

    /**
     * The source and validation proof run only on the owner thread, never on a recipe worker.
     */
    public Registration register(MachineIdentityRegistry.Registration identity, Supplier<@Nullable Capture> source) {
        Objects.requireNonNull(source);
        return register(identity, _ -> source.get());
    }

    /**
     * Supplies the identity already owned by this registration to the capture callback.
     */
    public Registration register(MachineIdentityRegistry.Registration identity,
                                 Function<MachineIdentityRegistry.Registration, @Nullable Capture> source) {
        requireMutation();
        Objects.requireNonNull(identity);
        Objects.requireNonNull(source);
        if (!identity.current() || !identities.owns(identity)) {
            throw new IllegalArgumentException("Network device needs this registry's current identity");
        }
        if (registrations.containsKey(identity.runtimeIdentity())) {
            throw new IllegalArgumentException("Runtime already has a network role");
        }
        revoke();
        var result = new Registration(this, identity, source);
        registrations.put(identity.runtimeIdentity(), result);
        return result;
    }

    public boolean refresh() {
        return refresh(_ -> List.of());
    }

    private static NetworkDeviceSnapshot withResources(NetworkDeviceSnapshot source, long concurrency, Reference2DoubleMap<NetworkResourceType> resources) {
        return new NetworkDeviceSnapshot(source.identity(), source.role(), source.level(), source.formed(), source.loadOrder(), concurrency,
            resources, source.resourceModifier(), source.dataCenterCapacity(), source.storedResearchCount(), source.dataCenterResearch());
    }

    private static void rollbackSupply(List<? extends ReservedTransaction> payments) {
        var tokens = new ObjectArrayList<ResourceReservation>();
        for (int index9 = 0; index9 < payments.size(); index9++) {
            var payment = payments.get(index9);
            tokens.addAll(payment.reservations());
        }
        ResourceTransactions.rollback(tokens, () -> {
            for (int i = payments.size() - 1; i >= 0; i--) payments.get(i).rollback();
        });
    }

    /**
     * Captures and atomically publishes one complete batch at an exclusive scheduler boundary.
     * False means a captured proof became obsolete; no previous batch remains authorized.
     * Exceptions propagate with the publication already revoked. Participant sources must supply only
     * current ACTIVE snapshots and validate their version until this owner boundary returns.
     */
    @SuppressWarnings("DataFlowIssue")
    public boolean refresh(LongFunction<? extends List<NetworkContribution>> participants) {
        requireMutation();
        if (!ticking) {
            throw new IllegalStateException("Network capture requires an active server tick");
        }
        Objects.requireNonNull(participants);
        revoke();
        if (!this.participants.beforeRefresh()) return false;
        long epoch = nextEpoch;
        nextEpoch = Math.addExact(nextEpoch, 1);
        long revision = identities.revision();
        captureRevoked = false;
        preparing = true;
        var upkeep = new ObjectArrayList<ControllerEnergy.Payment>();
        var computerWork = new Reference2ObjectOpenHashMap<Registration, NetworkSupplyTransaction>();
        boolean powerSettled = false;
        Throwable failure = null;
        try {
            var captures = new Reference2ObjectOpenHashMap<Registration, Capture>(registrations.size());
            var devices = new ObjectArrayList<NetworkDeviceSnapshot>(registrations.size());
            for (var registration : registrations.values()) {
                if (!registration.identity.unique()) {
                    continue;
                }
                var capture = registration.source.apply(registration.identity);
                if (capture == null) {
                    continue;
                }
                var snapshot = capture.snapshot();
                if (!snapshot.identity().equals(registration.identity.identity()) || snapshot.loadOrder() != registration.identity.loadOrder()) {
                    throw new IllegalArgumentException("Network source changed its registered identity or load order");
                }
                if (snapshot.role() == NetworkDeviceRole.DATA_CENTER && snapshot.dataCenterResearch() == null) {
                    throw new IllegalArgumentException("Runtime data centers require captured research contents, not only a count");
                }
                captures.put(registration, capture);
                devices.add(snapshot);
            }
            var external = this.participants.capture(tick, epoch);
            var contributions = new ObjectArrayList<>(external.contributions);
            contributions.addAll(Objects.requireNonNull(participants.apply(epoch)));
            prepareDeviceEvents(epoch, captures, devices, contributions);
            var states = new Reference2ObjectOpenHashMap<Registration, NetworkWorkState>(captures.size());
            NetworkPlan plan;
            while (true) {
                var inputs = NetworkPlanner.capture(epoch, devices, contributions, teams);
                external.bindOwners(inputs);
                plan = NetworkPlanner.plan(inputs);
                Registration unavailable = null;
                for (var entry : captures.reference2ObjectEntrySet()) {
                    var capture = entry.getValue();
                    if (capture.centerPower() == null || plan.workStates().get(capture.snapshot().identity().machineId()) != NetworkWorkState.ACTIVE_CENTER)
                        continue;
                    var payment = capture.centerPower().apply(tick);
                    if (payment == null) {
                        unavailable = entry.getKey();
                        break;
                    }
                    if (payment != ControllerEnergy.Payment.FREE) upkeep.add(payment);
                }
                if (unavailable == null) break;
                rollbackPower(upkeep);
                states.put(unavailable, NetworkWorkState.INSUFFICIENT_POWER);
                var absent = captures.remove(unavailable);
                devices.remove(absent.snapshot());
            }
            var supplyOrder = new ObjectArrayList<Registration>();
            for (var entry : captures.reference2ObjectEntrySet())
                if (entry.getValue().computerWork() != null
                    && plan.workStates().get(entry.getValue().snapshot().identity().machineId()) == NetworkWorkState.ACTIVE_DEVICE)
                    supplyOrder.add(entry.getKey());
            supplyOrder.sort(Comparator.comparing(registration -> registration.identity.identity().machineId()));
            for (int index6 = 0; index6 < supplyOrder.size(); index6++) {
                var registration = supplyOrder.get(index6);
                var capture = captures.get(registration);
                var work = capture.computerWork().apply(tick);
                computerWork.put(registration, work);
                var snapshot = withResources(capture.snapshot(), work.concurrency(), work.resources());
                captures.put(registration, new Capture(snapshot, capture.current(), capture.centerPower(), capture.computerWork()));
            }
            updateDevices(devices, captures);
            var admission = admitServices(epoch, devices, captures, contributions, computerWork);
            plan = admission.plan();
            for (var entry : captures.reference2ObjectEntrySet()) {
                if (!entry.getKey().identity.unique() || !entry.getValue().current().getAsBoolean()) {
                    return false;
                }
            }
            if (!this.participants.current(external)) return false;
            if (captureRevoked || identities.revision() != revision) {
                return false;
            }
            var additions = new Object2ObjectOpenHashMap<String, NetworkTeamBudget>();
            for (var entry : plan.teams().entrySet()) {
                if (!budgets.containsKey(entry.getKey())) {
                    additions.put(entry.getKey(), new NetworkTeamBudget(tick, entry.getValue().concurrencyPerTick(), entry.getValue().resourcesPerTick(), ownerCheck));
                }
            }
            for (var registration : captures.keySet()) {
                states.put(registration, Objects.requireNonNull(plan.workStates().get(registration.identity.identity().machineId())));
            }
            if (captureRevoked || identities.revision() != revision) {
                return false;
            }
            var payments = new ObjectArrayList<ReservedTransaction>(upkeep.size() + computerWork.size());
            payments.addAll(upkeep);
            payments.addAll(computerWork.values());
            var tokens = new ObjectArrayList<ResourceReservation>();
            for (int index1 = 0; index1 < payments.size(); index1++) {
                var payment = payments.get(index1);
                tokens.addAll(payment.reservations());
            }
            powerSettled = true; // ResourceTransactions now owns all completion/compensation paths.
            if (!ResourceTransactions.commit(tokens,
                () -> {
                    for (int index2 = 0; index2 < payments.size(); index2++) {
                        var payment = payments.get(index2);
                        if (!payment.prepareCommit()) return false;
                    }
                    return true;
                },
                () -> {
                    for (int index3 = 0; index3 < payments.size(); index3++) {
                        var payment = payments.get(index3);
                        if (!payment.validateCommit()) return false;
                    }
                    return true;
                },
                () -> payments.forEach(ReversibleTransaction::commit),
                () -> payments.forEach(ReversibleTransaction::complete),
                () -> {
                    for (int i = payments.size() - 1; i >= 0; i--) payments.get(i).rollback();
                })) return false;
            // Cost application changes the supplying hatch versions. Capture their post-payment proofs
            // after every domain lock and notification has finished, before exposing any new permits.
            for (var registration : computerWork.keySet()) {
                var captured = registration.source.apply(registration.identity);
                if (captured == null || !captured.current().getAsBoolean()
                    || !captured.snapshot().resourcesPerTick().equals(computerWork.get(registration).resources()))
                    return false;
                captures.put(registration, captured);
            }
            if (captureRevoked || identities.revision() != revision || !this.participants.current(external))
                return false;
            budgets.putAll(additions);
            for (var entry : plan.teams().entrySet())
                budgets.get(entry.getKey()).refreshCapacity(entry.getValue().concurrencyPerTick(), entry.getValue().resourcesPerTick());
            var elements = admission.services();
            for (int index11 = 0; index11 < elements.size(); index11++) {
                var registration = elements.get(index11);
                var id = registration.identity.identity().machineId();
                budgets.get(plan.teamIds().get(id)).requestNetworkLease(id);
            }
            published = new Publication(revision, plan, states, captures, external);
            return true;
        } catch (RuntimeException | Error cause) {
            failure = cause;
            throw cause;
        } finally {
            try {
                if (!powerSettled) {
                    var payments = new ObjectArrayList<ReservedTransaction>(upkeep);
                    payments.addAll(computerWork.values());
                    rollbackSupply(payments);
                }
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) throw cleanup;
                if (cleanup != failure) failure.addSuppressed(cleanup);
            } finally {
                preparing = false;
            }
        }
    }

    private void prepareDeviceEvents(long epoch, Reference2ObjectOpenHashMap<Registration, Capture> captures,
                                     ObjectArrayList<NetworkDeviceSnapshot> devices, List<NetworkContribution> contributions) {
        var inputs = NetworkPlanner.capture(epoch, devices, contributions, teams);
        var grouped = new Object2ObjectOpenHashMap<String, ObjectArrayList<NetworkData.Device>>();
        var external = new Object2ObjectOpenHashMap<String, List<NetworkContribution>>();
        for (var entry : captures.reference2ObjectEntrySet()) {
            var registration = entry.getKey();
            var owner = inputs.owner(registration.identity.identity().ownerId());
            if (owner.accepted()) grouped.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>())
                                         .add(new NetworkData.Device(registration.identity.location(), entry.getValue().snapshot()));
        }
        for (int index12 = 0; index12 < contributions.size(); index12++) {
            var contribution = contributions.get(index12);
            var owner = inputs.owner(contribution.ownerId());
            if (owner.accepted())
                external.computeIfAbsent(owner.teamId(), _ -> new ObjectArrayList<>()).add(contribution);
        }
        var networks = new Object2ObjectOpenHashMap<String, NetworkData>();
        for (var entry : grouped.object2ObjectEntrySet())
            networks.put(entry.getKey(), new NetworkData(entry.getKey(), tick, entry.getValue(), external.getOrDefault(entry.getKey(), List.of())));
        for (var entry : captures.reference2ObjectEntrySet()) {
            if (!(entry.getKey().identity.runtimeIdentity() instanceof MachineInstance machine)) continue;
            var device = machine.networkDevice();
            if (device == null) continue;
            var owner = inputs.owner(entry.getKey().identity.identity().ownerId());
            if (!owner.accepted()) continue;
            var data = networks.get(owner.teamId());
            device.networkData(data);
            if (machine instanceof ComputerController) continue;
            var capture = entry.getValue();
            var raw = capture.snapshot();
            var values = new NetworkResourceState(raw.resourcesPerTick(), Reference2DoubleMaps.emptyMap(), raw.concurrencyPerTick());
            if (machine instanceof ResearchStation station) values.researchRates.putAll(station.researchRates());
            NetworkDeviceEvents.fire(new NetworkDeviceEvent(machine, data, values));
            device.networkValues(values);
            var modified = withResources(raw, values.concurrency, values.resources);
            entry.setValue(new Capture(modified, capture.current(), capture.centerPower(), capture.computerWork()));
        }
        updateDevices(devices, captures);
    }

    private static void updateDevices(ObjectArrayList<NetworkDeviceSnapshot> devices, Reference2ObjectOpenHashMap<Registration, Capture> captures) {
        devices.clear();
        for (var capture : captures.values()) devices.add(capture.snapshot());
    }

    private Admission admitServices(long epoch, ObjectArrayList<NetworkDeviceSnapshot> devices,
                                    Reference2ObjectOpenHashMap<Registration, Capture> captures,
                                    List<NetworkContribution> contributions,
                                    Reference2ObjectOpenHashMap<Registration, NetworkSupplyTransaction> work) {
        var inputs = NetworkPlanner.capture(epoch, devices, contributions, teams);
        var initial = NetworkPlanner.plan(inputs);
        for (var entry : budgets.entrySet()) {
            entry.getValue().retainNetworkLeases(id -> entry.getKey().equals(initial.teamIds().get(id))
                && (initial.workStates().get(id) == NetworkWorkState.ACTIVE_DEVICE
                || initial.workStates().get(id) == NetworkWorkState.ACTIVE_DATA_CENTER));
        }
        var capacities = new Object2ObjectOpenHashMap<String, ServiceCapacity>();
        var computers = new ObjectArrayList<Registration>();
        var databases = new ObjectArrayList<Registration>();
        var services = new ObjectArrayList<Registration>();
        var rejected = new Object2ObjectOpenHashMap<UUID, String>();
        var rejectedDatabases = new ObjectOpenHashSet<String>();
        for (var registration : captures.keySet()) {
            var snapshot = captures.get(registration).snapshot();
            var id = snapshot.identity().machineId();
            var state = initial.workStates().get(id);
            var team = initial.teamIds().get(id);
            if (state == NetworkWorkState.ACTIVE_CENTER)
                capacities.put(team, new ServiceCapacity(snapshot.concurrencyPerTick(), budgets.get(team)));
            else if (snapshot.role() == NetworkDeviceRole.COMPUTER && state == NetworkWorkState.ACTIVE_DEVICE)
                computers.add(registration);
            else if (state == NetworkWorkState.ACTIVE_DATA_CENTER) databases.add(registration);
        }
        for (int index13 = 0; index13 < contributions.size(); index13++) {
            var contribution = contributions.get(index13);
            var owner = inputs.owner(contribution.ownerId());
            var capacity = owner.accepted() ? capacities.get(owner.teamId()) : null;
            if (capacity != null) capacity.add(contribution.concurrencyPerTick());
        }
        for (int index5 = 0; index5 < devices.size(); index5++) {
            var snapshot = devices.get(index5);
            if (snapshot.role() != NetworkDeviceRole.COMPUTER
                && initial.workStates().get(snapshot.identity().machineId()) == NetworkWorkState.ACTIVE_DEVICE)
                capacities.get(initial.teamIds().get(snapshot.identity().machineId())).add(snapshot.concurrencyPerTick());
        }
        // Already-issued leases remain valid for this tick. A fresh computer must obtain a thread
        // from the center, external sources or an earlier admitted computer before adding its own supply.
        computers.removeIf(registration -> {
            var id = registration.identity.identity().machineId();
            var capacity = capacities.get(initial.teamIds().get(id));
            if (capacity.budget == null || !capacity.budget.hasLease(id)) return false;
            capacity.add(captures.get(registration).snapshot().concurrencyPerTick());
            services.add(registration);
            return true;
        });
        computers.sort(Comparator.<Registration>comparingLong(registration ->
                                     captures.get(registration).snapshot().concurrencyPerTick()).reversed()
                                 .thenComparing(registration -> registration.identity.identity().machineId()));
        for (int index7 = 0; index7 < computers.size(); index7++) {
            var registration = computers.get(index7);
            var snapshot = captures.get(registration).snapshot();
            var team = initial.teamIds().get(snapshot.identity().machineId());
            var capacity = capacities.get(team);
            if (capacity.available() > 0) {
                capacity.claimed++;
                capacity.add(snapshot.concurrencyPerTick());
                services.add(registration);
            } else rejected.put(snapshot.identity().machineId(), team);
        }
        for (int index8 = 0; index8 < databases.size(); index8++) {
            var registration = databases.get(index8);
            var id = registration.identity.identity().machineId();
            var team = initial.teamIds().get(id);
            var capacity = capacities.get(team);
            if (capacity.budget != null && capacity.budget.hasLease(id)) {
                capacity.add(captures.get(registration).snapshot().concurrencyPerTick());
                services.add(registration);
            } else if (capacity.available() > 0) {
                capacity.claimed++;
                capacity.add(captures.get(registration).snapshot().concurrencyPerTick());
                services.add(registration);
            } else rejectedDatabases.add(team);
        }
        if (!rejectedDatabases.isEmpty()) {
            for (int index = 0; index < devices.size(); index++) {
                var snapshot = devices.get(index);
                var team = initial.teamIds().get(snapshot.identity().machineId());
                if (snapshot.role() == NetworkDeviceRole.DATA_CENTER && rejectedDatabases.contains(team))
                    rejected.put(snapshot.identity().machineId(), team);
            }
        }
        devices.removeIf(snapshot -> rejected.containsKey(snapshot.identity().machineId()));
        for (var registration : captures.keySet()) {
            if (!rejected.containsKey(registration.identity.identity().machineId())) continue;
            var preparation = work.remove(registration);
            if (preparation != null) rollbackSupply(List.of(preparation));
        }
        var plan = NetworkPlanner.plan(inputs.withDevices(devices));
        if (!rejected.isEmpty()) {
            var states = plan.workStates();
            var owners = plan.teamIds();
            for (var entry : rejected.entrySet()) {
                states.put(entry.getKey(), NetworkWorkState.INSUFFICIENT_THREADS);
                owners.put(entry.getKey(), entry.getValue());
            }
        }
        return new Admission(plan, services);
    }

    private static final class ServiceCapacity {
        private long raw;
        private long claimed;
        private final @Nullable NetworkTeamBudget budget;

        private ServiceCapacity(long raw, @Nullable NetworkTeamBudget budget) {
            this.raw = raw;
            this.budget = budget;
        }

        private void add(long amount) {
            raw = Math.addExact(raw, amount);
        }

        private long available() {
            return (budget == null ? raw : budget.availableConcurrency(raw)) - claimed;
        }
    }

    private record Admission(NetworkPlan plan, List<Registration> services) {
    }

    private static void rollbackPower(List<ControllerEnergy.Payment> payments) {
        if (payments.isEmpty()) return;
        var tokens = new ObjectArrayList<ResourceReservation>();
        for (int index10 = 0; index10 < payments.size(); index10++) {
            var payment = payments.get(index10);
            tokens.addAll(payment.reservations());
        }
        payments.clear();
        ResourceTransactions.rollback(tokens);
    }

    /**
     * Revoke before ending a tick or before changing captured network state at an owner boundary.
     */
    public void revoke() {
        ownerCheck.run();
        published = null;
        if (preparing) {
            captureRevoked = true;
        }
    }

    /**
     * Exposes only a currently published network plan, never an older fallback.
     */
    @Nullable
    public NetworkPlan plan() {
        var current = published;
        return current != null && current.revision == identities.revision() ? current.plan : null;
    }

    @Override
    public void close() {
        ownerCheck.run();
        if (preparing || participants.busy()) {
            throw new IllegalStateException("Cannot close while capturing a network batch");
        }
        // Finished receipts can acquire explicit compensation duties after an earlier successful close.
        // New work remains prohibited, but every close must revisit the retained original cleanup.
        closing = true;
        displayReads.clear();
        revoke();
        boolean settled;
        try {
            settled = participants.stop();
        } finally {
            for (var budget : budgets.values()) budget.close();
            budgets.clear();
            ticking = false;
        }
        if (!settled)
            throw new IllegalStateException("Network participants retain unresolved cleanup; retry close after resolution");
        closed = true;
        for (var registration : registrations.values()) {
            registration.active = false;
        }
        registrations.clear();
    }

    void requireMutation() {
        ownerCheck.run();
        if (closed || closing || preparing || participants.busy()) {
            throw new IllegalStateException("Network runtime is closed or capturing");
        }
    }

    boolean capturing() {
        return preparing;
    }

    @Nullable Publication publication() {
        return published;
    }

    NetworkTeamBudget participantBudget(String teamId) {
        return Objects.requireNonNull(budgets.get(teamId));
    }

    /**
     * Includes the external suppliers on which ordinary machine budgets also depend.
     */
    boolean authorized(Publication publication) {
        ownerCheck.run();
        if (published != publication || publication.revision != identities.revision()) return false;
        boolean valid;
        try {
            valid = true;
            for (var capture : publication.captures.values()) {
                if (!capture.current().getAsBoolean() || published != publication) {
                    valid = false;
                    break;
                }
            }
            if (valid) valid = participants.current(publication.participants);
        } catch (RuntimeException | Error failure) {
            if (published == publication) revoke();
            throw failure;
        }
        valid &= published == publication && publication.revision == identities.revision();
        if (!valid && published == publication) revoke();
        return valid;
    }

    /**
     * A detached snapshot plus an owner-only exact-state proof, also checked before budget work.
     */
    public record Capture(NetworkDeviceSnapshot snapshot, BooleanSupplier current,
                          @Nullable LongFunction<ControllerEnergy.Payment> centerPower,
                          @Nullable LongFunction<NetworkSupplyTransaction> computerWork) {
        public Capture(NetworkDeviceSnapshot snapshot, BooleanSupplier current) {
            this(snapshot, current, null, null);
        }

        public Capture(NetworkDeviceSnapshot snapshot, BooleanSupplier current, @Nullable LongFunction<ControllerEnergy.Payment> centerPower) {
            this(snapshot, current, centerPower, null);
        }

    }

    /**
     * One exact device attachment. Unloading an old handle never removes a replacement.
     */
    public static final class Registration implements AutoCloseable {
        private final NetworkRuntime runtime;
        private final MachineIdentityRegistry.Registration identity;
        private final Function<MachineIdentityRegistry.Registration, @Nullable Capture> source;
        private long nextWorkSequence;
        private volatile boolean active = true;

        private Registration(NetworkRuntime runtime, MachineIdentityRegistry.Registration identity,
                             Function<MachineIdentityRegistry.Registration, @Nullable Capture> source) {
            this.runtime = runtime;
            this.identity = identity;
            this.source = source;
        }

        /**
         * Null means pending, stale, conflicted or unloaded; ordinary components are not affected.
         */
        @Nullable
        public NetworkWorkState state() {
            var current = runtime.published;
            return active && identity.unique() && current != null && current.revision == runtime.identities.revision()
                ? current.states.get(this) : null;
        }

        public boolean allowsNetworkWork() {
            var state = state();
            return state == NetworkWorkState.ACTIVE_CENTER || state == NetworkWorkState.ACTIVE_DATA_CENTER || state == NetworkWorkState.ACTIVE_DEVICE;
        }

        /**
         * Borrowed read-only display access at the runtime owner boundary; it grants no work.
         */
        public @Nullable TeamView view() {
            runtime.ownerCheck.run();
            var current = runtime.published;
            if (!active || current == null || !current.states.containsKey(this)) return null;
            var teamId = current.plan.teamIds().get(identity.identity().machineId());
            var team = current.plan.teams().get(teamId);
            var budget = runtime.budgets.get(teamId);
            return team == null ? new TeamView(current.states.get(this), null, 0, _ -> 0)
                : new TeamView(current.states.get(this), team, budget.usedConcurrency(), budget::occupiedResource);
        }

        /**
         * Queue a read-only GUI capture after this tick's workers join and before its budgets close.
         */
        public void readDisplayAtEndTick(Runnable read) {
            runtime.ownerCheck.run();
            runtime.displayReads.add(read);
        }

        void revoke() {
            runtime.revoke();
        }

        /**
         * Capture an owner-side work capability for this exact publication, or null while denied.
         */
        @Nullable
        public WorkPermit workPermit() {
            runtime.ownerCheck.run();
            var current = runtime.published;
            if (current == null || !allowsNetworkWork()) {
                return null;
            }
            var teamId = Objects.requireNonNull(current.plan.teamIds().get(identity.identity().machineId()));
            var permit = new WorkPermit(this, current, teamId, Objects.requireNonNull(runtime.budgets.get(teamId)));
            return permit.authorized() ? permit : null;
        }

        @Override
        public void close() {
            runtime.ownerCheck.run();
            if (!active) {
                return;
            }
            runtime.requireMutation();
            var current = runtime.published;
            if (current != null && current.states.containsKey(this)) {
                runtime.revoke();
            }
            active = false;
            runtime.registrations.remove(identity.runtimeIdentity(), this);
        }
    }

    /**
     * Current publication authority, separate from the tick's persistent balances. All budget operations
     * are owner-only. Workers may inspect current() but cannot spend or commit through this capability.
     */
    public static final class WorkPermit {
        private final Registration registration;
        private final Publication publication;
        private final String teamId;
        private final NetworkTeamBudget budget;

        private WorkPermit(Registration registration, Publication publication, String teamId, NetworkTeamBudget budget) {
            this.registration = registration;
            this.publication = publication;
            this.teamId = teamId;
            this.budget = budget;
        }

        public boolean current() {
            var runtime = registration.runtime;
            return runtime.published == publication && registration.active && registration.identity.unique()
                && publication.revision == runtime.identities.revision();
        }

        public long tick() {
            return budget.tick();
        }

        public long epoch() {
            return publication.plan.epoch();
        }

        /**
         * This exact attachment's frozen device in the issuing publication; never re-samples a source.
         */
        public NetworkDeviceSnapshot deviceSnapshot() {
            return Objects.requireNonNull(publication.captures.get(registration)).snapshot();
        }

        @SuppressWarnings("unused")
        // External research/participant hosts retain the captured team alongside work identity.
        public String teamId() {
            return teamId;
        }

        /**
         * Exact contents of the selected data center in this publication, never a team/cloud substitute.
         */
        @Nullable
        public DataCenterResearchSnapshot dataCenterResearch() {
            return Objects.requireNonNull(publication.plan.teams().get(teamId)).dataCenterResearch();
        }

        /**
         * Current total supply for display. Research must reserve the remaining availableResources budget.
         */
        public Reference2DoubleMap<NetworkResourceType> numericResources() {
            return budget.numericResources();
        }

        /**
         * Diagnostic snapshot of this issuing tick, including after its permit expires.
         */
        public Reference2DoubleMap<NetworkResourceType> availableResources() {
            return budget.availableResources();
        }

        /**
         * Side-effect-free candidate admission; real acquisition must reserve again.
         */
        public boolean canReserve(Reference2DoubleMap<NetworkResourceType> required) {
            if (!authorized()) {
                return false;
            }
            boolean center = publication.states.get(registration) == NetworkWorkState.ACTIVE_CENTER;
            var id = registration.identity.identity().machineId();
            boolean leased = center || (priorityDevice() ? budget.canRequestNetworkLease(id) : budget.canRequestLease(id));
            return leased && budget.canReserve(required);
        }

        private boolean priorityDevice() {
            var role = deviceSnapshot().role();
            return role == NetworkDeviceRole.COMPUTER || role == NetworkDeviceRole.DATA_CENTER;
        }

        /**
         * Allocates a fresh owner sequence for one real requirement attempt, including multiple work slots.
         */
        @Nullable
        public NetworkTeamBudget.Reservation reserve(ResourceLocation sourceId, Reference2DoubleMap<NetworkResourceType> required) {
            registration.runtime.ownerCheck.run();
            long sequence = registration.nextWorkSequence;
            registration.nextWorkSequence = Math.incrementExact(sequence);
            return reserve(sourceId, sequence, required);
        }

        /**
         * The fixed integer machine lease; active centers are exempt.
         */
        public NetworkTeamBudget.LeaseStatus requestLease() {
            if (!authorized()) return NetworkTeamBudget.LeaseStatus.EXPIRED;
            if (publication.states.get(registration) == NetworkWorkState.ACTIVE_CENTER)
                return NetworkTeamBudget.LeaseStatus.NOT_REQUIRED;
            var id = registration.identity.identity().machineId();
            return priorityDevice() ? budget.requestNetworkLease(id) : budget.requestLease(id);
        }

        /**
         * Releases the ordinary concurrency lease of a station that did not work this tick.
         */
        public void releaseLease() {
            budget.releaseLease(registration.identity.identity().machineId());
        }

        /**
         * Apply PN's machine lease before an ordinary numeric reservation. Null means expired authority,
         * insufficient logical capacity or an insufficient numeric vector. Each new work attempt needs
         * a stable source id and a new sequence; capture epoch is not a work sequence.
         */
        @Nullable
        public NetworkTeamBudget.Reservation reserve(ResourceLocation sourceId, long sequence,
                                                     Reference2DoubleMap<NetworkResourceType> required) {
            var operation = new NetworkTeamBudget.Operation(registration.identity.identity().machineId(), sourceId, sequence);
            var lease = requestLease();
            if (lease == NetworkTeamBudget.LeaseStatus.EXPIRED || lease == NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY) {
                return null;
            }
            return budget.reserve(operation, required, () -> authorized()
                && (publication.states.get(registration) == NetworkWorkState.ACTIVE_CENTER || budget.hasLease(operation.machineId())));
        }

        /**
         * Owner-side revalidation of every captured supplier and its contents; workers use current().
         */
        public boolean authorized() {
            var runtime = registration.runtime;
            runtime.ownerCheck.run();
            return current() && runtime.authorized(publication) && current();
        }
    }

    record Publication(long revision, NetworkPlan plan,
                       Reference2ObjectOpenHashMap<Registration, NetworkWorkState> states,
                       Reference2ObjectOpenHashMap<Registration, Capture> captures,
                       NetworkParticipants.CaptureSet participants) {
    }

    public record TeamView(NetworkWorkState state, @Nullable NetworkPlan.Team team, int usedConcurrency,
                           ToDoubleFunction<NetworkResourceType> occupiedResource) {
    }
}
