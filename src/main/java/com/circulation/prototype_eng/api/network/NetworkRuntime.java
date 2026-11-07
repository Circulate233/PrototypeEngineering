package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
    private final Reference2ObjectLinkedOpenHashMap<Object, Registration> registrations = new Reference2ObjectLinkedOpenHashMap<>();
    @Nullable private volatile Publication published;
    private long nextEpoch;
    private boolean preparing;
    private boolean captureRevoked;
    private boolean closed;

    public NetworkRuntime(MachineIdentityRegistry identities, NetworkResourceRegistry resources, OwnerTeamResolver teams, Runnable ownerCheck) {
        this.identities = Objects.requireNonNull(identities);
        this.resources = Objects.requireNonNull(resources);
        if (!resources.isFrozen()) { throw new IllegalStateException("Network runtime requires frozen computing resources"); }
        this.teams = Objects.requireNonNull(teams);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
    }

    /** The source and validation proof run only on the owner thread, never on a recipe worker. */
    public Registration register(MachineIdentityRegistry.Registration identity, Supplier<@Nullable Capture> source) {
        requireMutation();
        Objects.requireNonNull(identity);
        Objects.requireNonNull(source);
        if (!identity.current() || !identities.owns(identity)) { throw new IllegalArgumentException("Network device needs this registry's current identity"); }
        if (registrations.containsKey(identity.runtimeIdentity())) { throw new IllegalArgumentException("Runtime already has a network role"); }
        revoke();
        var result = new Registration(this, identity, source);
        registrations.put(identity.runtimeIdentity(), result);
        return result;
    }

    public boolean refresh() { return refresh(_ -> List.of()); }

    /**
     * Captures and atomically publishes one complete batch at an exclusive scheduler boundary.
     * False means a captured proof became obsolete; no previous batch remains authorized.
     * Exceptions propagate with the publication already revoked. Participant sources must supply only
     * current ACTIVE snapshots and validate their version until this owner boundary returns.
     */
    public boolean refresh(LongFunction<? extends List<NetworkContribution>> participants) {
        requireMutation();
        Objects.requireNonNull(participants);
        revoke();
        long epoch = nextEpoch;
        nextEpoch = Math.addExact(nextEpoch, 1);
        long revision = identities.revision();
        captureRevoked = false;
        preparing = true;
        try {
            var captures = new Reference2ObjectOpenHashMap<Registration, Capture>();
            var devices = new ObjectArrayList<NetworkDeviceSnapshot>();
            for (var registration : registrations.values()) {
                if (!registration.identity.unique()) { continue; }
                var capture = registration.source.get();
                if (capture == null) { continue; }
                var snapshot = capture.snapshot();
                if (!snapshot.identity().equals(registration.identity.identity()) || snapshot.loadOrder() != registration.identity.loadOrder()) {
                    throw new IllegalArgumentException("Network source changed its registered identity or load order");
                }
                captures.put(registration, capture);
                devices.add(snapshot);
            }
            var inputs = NetworkPlanner.capture(resources, epoch, devices, participants.apply(epoch), teams);
            var plan = NetworkPlanner.plan(inputs);
            for (var entry : captures.reference2ObjectEntrySet()) {
                if (!entry.getKey().identity.unique() || !entry.getValue().current().getAsBoolean()) { return false; }
            }
            if (captureRevoked || identities.revision() != revision) { return false; }
            var states = new Reference2ObjectOpenHashMap<Registration, NetworkWorkState>();
            for (var registration : captures.keySet()) {
                states.put(registration, Objects.requireNonNull(plan.workStates().get(registration.identity.identity().machineId())));
            }
            published = new Publication(revision, plan, states);
            return true;
        } finally { preparing = false; }
    }

    /** Revoke before ending a tick or before changing captured network state at an owner boundary. */
    public void revoke() {
        ownerCheck.run();
        published = null;
        if (preparing) { captureRevoked = true; }
    }

    /** Exposes only a currently published network plan, never an older fallback. */
    @Nullable public NetworkPlan plan() {
        var current = published;
        return current != null && current.revision == identities.revision() ? current.plan : null;
    }

    @Override public void close() {
        ownerCheck.run();
        if (preparing) { throw new IllegalStateException("Cannot close while capturing a network batch"); }
        if (closed) { return; }
        revoke();
        closed = true;
        for (var registration : registrations.values()) { registration.active = false; }
        registrations.clear();
    }

    private void requireMutation() {
        ownerCheck.run();
        if (closed || preparing) { throw new IllegalStateException("Network runtime is closed or capturing"); }
    }

    /** A detached snapshot plus an owner-only exact-state proof, retained only until publication. */
    public record Capture(NetworkDeviceSnapshot snapshot, BooleanSupplier current) {
        public Capture { Objects.requireNonNull(snapshot); Objects.requireNonNull(current); }
    }

    /** One exact device attachment. Unloading an old handle never removes a replacement. */
    public static final class Registration implements AutoCloseable {
        private final NetworkRuntime runtime;
        private final MachineIdentityRegistry.Registration identity;
        private final Supplier<@Nullable Capture> source;
        private volatile boolean active = true;

        private Registration(NetworkRuntime runtime, MachineIdentityRegistry.Registration identity, Supplier<@Nullable Capture> source) {
            this.runtime = runtime;
            this.identity = identity;
            this.source = source;
        }

        /** Null means pending, stale, conflicted or unloaded; ordinary components are not affected. */
        @Nullable public NetworkWorkState state() {
            var current = runtime.published;
            return active && identity.unique() && current != null && current.revision == runtime.identities.revision()
                ? current.states.get(this) : null;
        }

        public boolean allowsNetworkWork() {
            var state = state();
            return state == NetworkWorkState.ACTIVE_CENTER || state == NetworkWorkState.ACTIVE_DATA_CENTER || state == NetworkWorkState.ACTIVE_DEVICE;
        }

        @Override public void close() {
            runtime.ownerCheck.run();
            if (!active) { return; }
            runtime.requireMutation();
            var current = runtime.published;
            if (current != null && current.states.containsKey(this)) { runtime.revoke(); }
            active = false;
            runtime.registrations.remove(identity.runtimeIdentity(), this);
        }
    }

    private record Publication(long revision, NetworkPlan plan, Reference2ObjectOpenHashMap<Registration, NetworkWorkState> states) { }
}
