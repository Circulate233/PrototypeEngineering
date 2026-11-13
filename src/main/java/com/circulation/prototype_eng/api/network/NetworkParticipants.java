package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.NetworkParticipant.Cancellation;
import com.circulation.prototype_eng.api.network.NetworkParticipant.Command;
import com.circulation.prototype_eng.api.network.NetworkParticipant.Snapshot;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolver;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * External participant attachments and original work receipts owned by one NetworkRuntime.
 * No independent scheduler, tick budget, team store or world persistence is created here.
 * All methods except immutable value access require the runtime owner thread.
 */
public final class NetworkParticipants {
    private static final ResourceLocation BUDGET_SOURCE = new ResourceLocation("prototype_eng", "external_participant");
    private final NetworkRuntime runtime;
    private final NetworkResourceRegistry resources;
    private final OwnerTeamResolver teams;
    private final Runnable ownerCheck;
    private final Object2ObjectLinkedOpenHashMap<UUID, Registration> registrations = new Object2ObjectLinkedOpenHashMap<>();
    private final ReferenceLinkedOpenHashSet<Work> pending = new ReferenceLinkedOpenHashSet<>();
    private long nextWorkSequence;
    private boolean operating;
    private boolean stopping;

    NetworkParticipants(NetworkRuntime runtime, NetworkResourceRegistry resources, OwnerTeamResolver teams, Runnable ownerCheck) {
        this.runtime = runtime;
        this.resources = resources;
        this.teams = teams;
        this.ownerCheck = ownerCheck;
    }

    /**
     * Public server entry for hosts that have no PE machine instance or physical structure.
     */
    @SuppressWarnings("unused") // External hosts use the active server's single runtime.
    public static NetworkParticipants forServer(MinecraftServer server) {
        return ServerNetworkRuntime.participants(server);
    }

    private static boolean sameOwner(OwnerTeamResolution expected, OwnerTeamResolution current) {
        return expected.ownerId().equals(current.ownerId()) && expected.status() == current.status()
            && (!expected.accepted() || expected.teamId().equals(current.teamId()) && expected.teamType() == current.teamType()
            && expected.members().equals(current.members()) && expected.teamOwnerId().equals(current.teamOwnerId()));
    }

    /**
     * The returned handle alone can detach this exact registration, including after replacement.
     */
    @SuppressWarnings("ReturnOfInnerClass")
    // Exact handles deliberately retain their owning session and cleanup authority.
    public Registration register(NetworkParticipant participant) {
        runtime.requireMutation();
        begin();
        try {
            if (stopping) throw new IllegalStateException("Network participants are stopping");
            Objects.requireNonNull(participant);
            var id = Objects.requireNonNull(participant.participantId());
            var owner = Objects.requireNonNull(participant.ownerId());
            var kind = Objects.requireNonNull(participant.kind());
            if (Objects.requireNonNull(participant.state()) == NetworkParticipant.State.UNREGISTERED) {
                throw new IllegalArgumentException("Cannot register an unregistered participant");
            }
            if (registrations.containsKey(id))
                throw new IllegalArgumentException("Duplicate participant identity: " + id);
            var registration = new Registration(participant, id, owner, kind);
            registration.requireIdentity();
            runtime.revoke();
            registrations.put(id, registration);
            return registration;
        } finally {
            operating = false;
        }
    }

    /**
     * Original receipts that still own cancellation or compensation, including detached hosts.
     */
    public List<Work> pendingWork() {
        ownerCheck.run();
        return List.copyOf(pending);
    }

    /**
     * Resolves original cleanup only; never captures another snapshot or replays a commit.
     */
    public boolean retryCleanup() {
        begin();
        try {
            return settlePending();
        } finally {
            operating = false;
        }
    }

    boolean busy() {
        return operating;
    }

    private void begin() {
        ownerCheck.run();
        if (operating || runtime.capturing())
            throw new IllegalStateException("Participant callback reentry is forbidden");
        operating = true;
    }

    /**
     * Every refresh retires prior-epoch uncommitted work before issuing another publication.
     */
    boolean beforeRefresh() {
        return retryCleanup();
    }

    void endTick() {
        try {
            retryCleanup();
        } finally {
            // Pending Work remains strongly held above. Terminal handles need not pin entire old batches.
            for (var registration : registrations.values()) registration.workById.clear();
        }
    }

    boolean stop() {
        begin();
        try {
            stopping = true;
            for (var registration : registrations.values()) {
                registration.active = false;
                registration.workById.clear();
            }
            registrations.clear();
            return settlePending();
        } finally {
            operating = false;
        }
    }

    private boolean settlePending() {
        Throwable failure = null;
        for (var work : new ObjectArrayList<>(pending)) {
            try {
                work.cancelOriginal();
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        return pending.isEmpty();
    }

    CaptureSet capture(long tick, long epoch) {
        boolean previous = operating;
        operating = true;
        try {
            var result = new CaptureSet();
            for (var registration : registrations.values()) {
                registration.requireIdentity();
                var state = Objects.requireNonNull(registration.participant.state());
                if (state == NetworkParticipant.State.UNREGISTERED) {
                    throw new IllegalStateException("Registered participant reported UNREGISTERED");
                }
                NetworkParticipant.Capture capture = null;
                if (state == NetworkParticipant.State.ACTIVE) {
                    capture = Objects.requireNonNull(registration.participant.capture(tick, epoch));
                    var snapshot = capture.snapshot();
                    if (!snapshot.participantId().equals(registration.id) || !snapshot.ownerId().equals(registration.owner)
                        || snapshot.kind() != registration.kind || snapshot.tick() != tick || snapshot.epoch() != epoch) {
                        throw new IllegalArgumentException("Participant capture changed its identity or requested generation");
                    }
                    var last = registration.lastSnapshot;
                    if (last != null && (snapshot.version() < last.version()
                        || snapshot.version() == last.version() && (snapshot.tick() != last.tick() || !snapshot.sameCapabilities(last)))) {
                        throw new IllegalArgumentException("Participant snapshot version did not advance with its tick or capabilities");
                    }
                    for (var type : snapshot.resources().keySet()) resources.canonical(type);
                    for (var work : snapshot.workTypes()) {
                        for (var type : work.resources().keySet()) resources.canonical(type);
                    }
                    registration.lastSnapshot = snapshot;
                    result.contributions.add(new NetworkContribution(registration.id, registration.owner, epoch,
                        snapshot.version(), snapshot.resources()));
                }
                result.observed.put(registration, new Observed(state, capture));
            }
            return result;
        } finally {
            operating = previous;
        }
    }

    boolean current(CaptureSet captures) {
        boolean previous = operating;
        operating = true;
        try {
            for (var entry : captures.observed.reference2ObjectEntrySet()) {
                var registration = entry.getKey();
                var observed = entry.getValue();
                if (!registration.active || registrations.get(registration.id) != registration) return false;
                registration.requireIdentity();
                if (registration.participant.state() != observed.state) return false;
                var capture = observed.capture;
                if (capture != null) {
                    if (!capture.current().getAsBoolean()) return false;
                    var expected = captures.owners.get(registration.owner);
                    if (expected != null && !sameOwner(expected, Objects.requireNonNull(teams.resolve(registration.owner))))
                        return false;
                    // A proof/resolver may discover an invalidation; sample identity and state once more.
                    registration.requireIdentity();
                    if (registration.participant.state() != observed.state) return false;
                }
            }
            return true;
        } finally {
            operating = previous;
        }
    }

    public enum PlanStatus {
        ACCEPTED, INACTIVE, DUPLICATE_PLAN, NO_SNAPSHOT, STALE_SNAPSHOT, OWNER_TEAM_REJECTED,
        WORK_UNSUPPORTED, SOURCE_UNSUPPORTED, LEASE_DENIED, RESOURCE_DENIED
    }

    public enum WorkState {PLANNED, COMMITTED, CANCELLING, CANCELLED}

    public enum WorkResult {COMMITTED, ALREADY_COMMITTED, CANCELLED, ALREADY_CANCELLED, CLEANUP_PENDING}

    static final class CaptureSet {
        final ObjectArrayList<NetworkContribution> contributions = new ObjectArrayList<>();
        private final Reference2ObjectLinkedOpenHashMap<Registration, Observed> observed = new Reference2ObjectLinkedOpenHashMap<>();
        private final Object2ObjectOpenHashMap<UUID, OwnerTeamResolution> owners = new Object2ObjectOpenHashMap<>();

        void bindOwners(NetworkPlanner.Inputs inputs) {
            for (var registration : observed.keySet()) {
                if (observed.get(registration).capture != null)
                    owners.put(registration.owner, inputs.owner(registration.owner));
            }
        }
    }

    private record Observed(NetworkParticipant.State state, @Nullable NetworkParticipant.Capture capture) {
    }

    public record PlanResult(PlanStatus status, @Nullable Work work) {
        public PlanResult {
            Objects.requireNonNull(status);
            if ((status == PlanStatus.ACCEPTED) == (work == null))
                throw new IllegalArgumentException("Inconsistent participant plan result");
        }

        private static PlanResult denied(PlanStatus status) {
            return new PlanResult(status, null);
        }
    }

    public final class Registration implements AutoCloseable {
        private final NetworkParticipant participant;
        private final UUID id;
        private final UUID owner;
        private final NetworkParticipant.Kind kind;
        private final Object2ObjectOpenHashMap<UUID, Work> workById = new Object2ObjectOpenHashMap<>();
        private @Nullable Snapshot lastSnapshot;
        private volatile boolean active = true;

        private Registration(NetworkParticipant participant, UUID id, UUID owner, NetworkParticipant.Kind kind) {
            this.participant = participant;
            this.id = id;
            this.owner = owner;
            this.kind = kind;
        }

        private void requireIdentity() {
            if (!id.equals(participant.participantId()) || !owner.equals(participant.ownerId()) || kind != participant.kind()) {
                throw new IllegalStateException("Participant identity changed after registration");
            }
        }

        /**
         * Returns this publication's worker-safe value only while its entire supply is authorized.
         */
        public @Nullable Snapshot snapshot() {
            begin();
            try {
                var publication = runtime.publication();
                if (publication == null || !active || !runtime.authorized(publication)) return null;
                var observed = publication.participants().observed.get(this);
                return observed == null || observed.capture == null ? null : observed.capture.snapshot();
            } finally {
                operating = false;
            }
        }

        /**
         * One work unit consumes its frozen complete numeric vector and one shared machine lease.
         */
        public PlanResult plan(UUID planId, Snapshot snapshot, ResourceLocation workType, @Nullable ResourceLocation materialSource) {
            begin();
            try {
                Objects.requireNonNull(planId);
                Objects.requireNonNull(snapshot);
                Objects.requireNonNull(workType);
                if (stopping || !active) return PlanResult.denied(PlanStatus.INACTIVE);
                if (workById.containsKey(planId)) return PlanResult.denied(PlanStatus.DUPLICATE_PLAN);
                for (var work : pending) {
                    if (work.registration == this && work.command.planId().equals(planId))
                        return PlanResult.denied(PlanStatus.DUPLICATE_PLAN);
                }
                var publication = runtime.publication();
                if (publication == null) return PlanResult.denied(PlanStatus.NO_SNAPSHOT);
                var observed = publication.participants().observed.get(this);
                if (observed == null || observed.capture == null || observed.capture.snapshot() != snapshot) {
                    return PlanResult.denied(PlanStatus.STALE_SNAPSHOT);
                }
                if (!runtime.authorized(publication)) return PlanResult.denied(PlanStatus.STALE_SNAPSHOT);
                var resolved = publication.participants().owners.get(owner);
                if (resolved == null || !resolved.accepted() || !publication.plan().teams().containsKey(resolved.teamId())) {
                    return PlanResult.denied(PlanStatus.OWNER_TEAM_REJECTED);
                }
                NetworkParticipant.WorkType selectedWork = null;
                for (var candidate : snapshot.workTypes())
                    if (candidate.id().equals(workType)) selectedWork = candidate;
                if (selectedWork == null) return PlanResult.denied(PlanStatus.WORK_UNSUPPORTED);
                NetworkParticipant.MaterialSource selectedSource = null;
                for (var candidate : snapshot.materialSources())
                    if (candidate.id().equals(materialSource)) selectedSource = candidate;
                if (materialSource != null && selectedSource == null)
                    return PlanResult.denied(PlanStatus.SOURCE_UNSUPPORTED);
                var budget = runtime.participantBudget(resolved.teamId());
                var lease = budget.requestLease(id);
                if (lease == NetworkTeamBudget.LeaseStatus.EXPIRED || lease == NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY) {
                    return PlanResult.denied(PlanStatus.LEASE_DENIED);
                }
                long sequence = nextWorkSequence;
                nextWorkSequence = Math.incrementExact(sequence);
                var reservation = budget.reserve(new NetworkTeamBudget.Operation(id, BUDGET_SOURCE, sequence), selectedWork.resources(),
                    () -> active && runtime.authorized(publication));
                if (reservation == null) return PlanResult.denied(PlanStatus.RESOURCE_DENIED);
                var command = new Command(id, owner, snapshot.tick(), snapshot.epoch(), snapshot.version(), planId, selectedWork, selectedSource);
                var work = new Work(this, publication, command, reservation);
                pending.add(work);
                workById.put(planId, work);
                return new PlanResult(PlanStatus.ACCEPTED, work);
            } finally {
                operating = false;
            }
        }

        @Override
        public void close() {
            begin();
            try {
                if (active) {
                    active = false;
                    registrations.remove(id, this);
                    var publication = runtime.publication();
                    if (publication != null && publication.participants().observed.containsKey(this)) runtime.revoke();
                }
                Throwable failure = null;
                for (var work : new ObjectArrayList<>(pending)) {
                    if (work.registration != this) continue;
                    try {
                        work.cancelOriginal();
                    } catch (RuntimeException | Error problem) {
                        if (failure == null) failure = problem;
                        else if (failure != problem) failure.addSuppressed(problem);
                    }
                }
                if (failure instanceof RuntimeException exception) throw exception;
                if (failure instanceof Error error) throw error;
                workById.clear();
            } finally {
                operating = false;
            }
        }
    }

    /**
     * The exact original callback, publication and issuing budget. Callers can retain this handle after
     * a tick, detach or session shutdown for idempotent results and explicit original-work compensation.
     */
    public final class Work {
        private final Registration registration;
        private final NetworkRuntime.Publication publication;
        private final Command command;
        private final NetworkTeamBudget.Reservation reservation;
        private WorkState state = WorkState.PLANNED;
        private Cancellation cancellation = Cancellation.RETRYABLE;
        private @Nullable Throwable failure;

        private Work(Registration registration, NetworkRuntime.Publication publication, Command command,
                     NetworkTeamBudget.Reservation reservation) {
            this.registration = registration;
            this.publication = publication;
            this.command = command;
            this.reservation = reservation;
        }

        public Command command() {
            return command;
        }

        public WorkState state() {
            ownerCheck.run();
            return state;
        }

        public @Nullable Throwable failure() {
            ownerCheck.run();
            return failure;
        }

        public WorkResult commit() {
            begin();
            try {
                if (state == WorkState.COMMITTED) return WorkResult.ALREADY_COMMITTED;
                if (state == WorkState.CANCELLED) return WorkResult.ALREADY_CANCELLED;
                if (state == WorkState.CANCELLING) return cancelOriginal();
                // Authorization itself invokes host proofs and owner resolution. Its failures own the
                // same original cleanup as the later commit callback, and must never leave PLANNED.
                state = WorkState.CANCELLING;
                try {
                    if (!registration.active || !runtime.authorized(publication)) return cancelOriginal();
                    var result = Objects.requireNonNull(registration.participant.commit(command));
                    if (result == NetworkParticipant.Commit.REJECTED) {
                        cancellation = Cancellation.CANCELLED;
                        return cancelOriginal();
                    }
                    if (!reservation.commit()) return cancelOriginal();
                    state = WorkState.COMMITTED;
                    pending.remove(this);
                    return WorkResult.COMMITTED;
                } catch (RuntimeException | Error problem) {
                    recordFailure(problem);
                    runtime.revoke();
                    try {
                        cancelOriginal();
                    } catch (RuntimeException | Error _) {
                        // cancelOriginal already retained the exact cleanup failure on this work.
                    }
                    throw problem;
                }
            } finally {
                operating = false;
            }
        }

        /**
         * Finished work is not implicitly undone by cancellation, refresh, unload or server stop.
         */
        public WorkResult cancel() {
            begin();
            try {
                if (state == WorkState.COMMITTED) return WorkResult.ALREADY_COMMITTED;
                return cancelOriginal();
            } finally {
                operating = false;
            }
        }

        /**
         * Explicit compensation may undo finished work, but always refunds its original tick book.
         */
        public WorkResult compensate() {
            begin();
            try {
                if (state == WorkState.CANCELLED) return WorkResult.ALREADY_CANCELLED;
                if (state == WorkState.COMMITTED) {
                    state = WorkState.CANCELLING;
                    pending.add(this);
                }
                return cancelOriginal();
            } finally {
                operating = false;
            }
        }

        private WorkResult cancelOriginal() {
            if (state == WorkState.CANCELLED) return WorkResult.ALREADY_CANCELLED;
            state = WorkState.CANCELLING;
            try {
                if (cancellation == Cancellation.UNKNOWN) {
                    cancellation = Objects.requireNonNull(registration.participant.cancellationState(command));
                }
                if (cancellation == Cancellation.RETRYABLE) {
                    cancellation = Cancellation.UNKNOWN;
                    cancellation = Objects.requireNonNull(registration.participant.cancel(command));
                }
                if (cancellation != Cancellation.CANCELLED) {
                    runtime.revoke();
                    return WorkResult.CLEANUP_PENDING;
                }
                reservation.rollback();
                state = WorkState.CANCELLED;
                pending.remove(this);
                return WorkResult.CANCELLED;
            } catch (RuntimeException | Error problem) {
                recordFailure(problem);
                runtime.revoke();
                throw problem;
            }
        }

        private void recordFailure(Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
    }
}
