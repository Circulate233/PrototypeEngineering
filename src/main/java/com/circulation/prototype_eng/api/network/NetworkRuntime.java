package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.ControllerEnergy;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Owner-thread network publication for one identity registry. Previous results are revoked before
 * any capture callback. Registration handles and the identity revision prevent replaced or conflicting
 * runtimes from regaining old permission. This supplies only the network factor of recipe readiness.
 */
public final class NetworkRuntime implements AutoCloseable {
    private final MachineIdentityRegistry identities;
    private final NetworkResourceRegistry resources;
    private final OwnerTeamResolver teams;
    private final Runnable ownerCheck;
    private final NetworkParticipants participants;
    private final Reference2ObjectLinkedOpenHashMap<Object, Registration> registrations = new Reference2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectOpenHashMap<String, NetworkTeamBudget> budgets = new Object2ObjectOpenHashMap<>();
    @Nullable
    private volatile Publication published;
    private long nextEpoch;
    private long tick = -1;
    private boolean ticking;
    private boolean preparing;
    private boolean captureRevoked;
    private boolean closed;
    private boolean closing;

    public NetworkRuntime(MachineIdentityRegistry identities, NetworkResourceRegistry resources, OwnerTeamResolver teams, Runnable ownerCheck) {
        this.identities = Objects.requireNonNull(identities);
        this.resources = Objects.requireNonNull(resources);
        if (!resources.isFrozen()) {
            throw new IllegalStateException("Network runtime requires frozen computing resources");
        }
        this.teams = Objects.requireNonNull(teams);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
        participants = new NetworkParticipants(this, resources, teams, ownerCheck);
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
        revoke();
        try {
            participants.endTick();
        } finally {
            for (var budget : budgets.values()) budget.close();
            budgets.clear();
            ticking = false;
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

    /**
     * Captures and atomically publishes one complete batch at an exclusive scheduler boundary.
     * False means a captured proof became obsolete; no previous batch remains authorized.
     * Exceptions propagate with the publication already revoked. Participant sources must supply only
     * current ACTIVE snapshots and validate their version until this owner boundary returns.
     */
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
            NetworkPlan plan;
            while (true) {
                var inputs = NetworkPlanner.capture(resources, epoch, devices, contributions, teams);
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
                var absent = captures.remove(unavailable);
                devices.remove(absent.snapshot());
            }
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
                    additions.put(entry.getKey(), new NetworkTeamBudget(tick, resources, entry.getValue().resourcesPerTick(), ownerCheck));
                }
            }
            var states = new Reference2ObjectOpenHashMap<Registration, NetworkWorkState>(captures.size());
            for (var registration : captures.keySet()) {
                states.put(registration, Objects.requireNonNull(plan.workStates().get(registration.identity.identity().machineId())));
            }
            if (captureRevoked || identities.revision() != revision) {
                return false;
            }
            var publication = new Publication(revision, plan, states, captures, external);
            if (upkeep.isEmpty()) {
                budgets.putAll(additions);
                published = publication;
                powerSettled = true;
                return true;
            }
            var tokens = new ObjectArrayList<ResourceReservation>();
            for (var payment : upkeep) tokens.addAll(payment.reservations());
            powerSettled = true; // ResourceTransactions now owns all completion/compensation paths.
            return ResourceTransactions.commit(tokens, () -> {
                for (var payment : upkeep) payment.commit();
                // Existing teams retain their first accepted capacity and all consumption for this tick.
                budgets.putAll(additions);
                published = publication;
            }, () -> upkeep.forEach(ControllerEnergy.Payment::complete), () -> {
                published = null;
                for (int i = upkeep.size() - 1; i >= 0; i--) upkeep.get(i).rollback();
            });
        } catch (RuntimeException | Error cause) {
            failure = cause;
            throw cause;
        } finally {
            try {
                if (!powerSettled) rollbackPower(upkeep);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) throw cleanup;
                if (cleanup != failure) failure.addSuppressed(cleanup);
            } finally {
                preparing = false;
            }
        }
    }

    private static void rollbackPower(List<ControllerEnergy.Payment> payments) {
        if (payments.isEmpty()) return;
        var tokens = new ObjectArrayList<ResourceReservation>();
        for (var payment : payments) tokens.addAll(payment.reservations());
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
                          @Nullable LongFunction<ControllerEnergy.Payment> centerPower) {
        public Capture(NetworkDeviceSnapshot snapshot, BooleanSupplier current) {
            this(snapshot, current, null);
        }

        public Capture {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(current);
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
         * Immutable research supply; it is independent of ordinary work reservations, as in PN.
         */
        public Object2LongMap<NetworkResourceType> numericResources() {
            return budget.numericResources();
        }

        /**
         * Diagnostic snapshot of this issuing tick, including after its permit expires.
         */
        public Object2LongMap<NetworkResourceType> availableResources() {
            return budget.availableResources();
        }

        /**
         * Side-effect-free candidate admission; real acquisition must reserve again.
         */
        public boolean canReserve(Object2LongMap<NetworkResourceType> required) {
            if (!authorized()) {
                return false;
            }
            boolean center = publication.states.get(registration) == NetworkWorkState.ACTIVE_CENTER;
            return (center || budget.canRequestLease(registration.identity.identity().machineId())) && budget.canReserve(required);
        }

        /**
         * Allocates a fresh owner sequence for one real requirement attempt, including multiple work slots.
         */
        @Nullable
        public NetworkTeamBudget.Reservation reserve(ResourceLocation sourceId, Object2LongMap<NetworkResourceType> required) {
            registration.runtime.ownerCheck.run();
            long sequence = registration.nextWorkSequence;
            registration.nextWorkSequence = Math.incrementExact(sequence);
            return reserve(sourceId, sequence, required);
        }

        public NetworkTeamBudget.LeaseStatus requestLease() {
            return requestLease(NetworkResources.THREAD);
        }

        /**
         * Centers are exempt only from the standard machine thread lease.
         */
        public NetworkTeamBudget.LeaseStatus requestLease(NetworkResourceType type) {
            if (!authorized()) {
                return NetworkTeamBudget.LeaseStatus.EXPIRED;
            }
            if (type.equals(NetworkResources.THREAD) && publication.states.get(registration) == NetworkWorkState.ACTIVE_CENTER) {
                return NetworkTeamBudget.LeaseStatus.NOT_REQUIRED;
            }
            return budget.requestLease(registration.identity.identity().machineId(), type);
        }

        /**
         * Apply PN's machine lease before an ordinary numeric reservation. Null means expired authority,
         * insufficient logical capacity or an insufficient numeric vector. Each new work attempt needs
         * a stable source id and a new sequence; capture epoch is not a work sequence.
         */
        @Nullable
        public NetworkTeamBudget.Reservation reserve(ResourceLocation sourceId, long sequence,
                                                     Object2LongMap<NetworkResourceType> required) {
            var operation = new NetworkTeamBudget.Operation(registration.identity.identity().machineId(), sourceId, sequence);
            var lease = requestLease();
            if (lease == NetworkTeamBudget.LeaseStatus.EXPIRED || lease == NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY) {
                return null;
            }
            return budget.reserve(operation, required, this::authorized);
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
}
