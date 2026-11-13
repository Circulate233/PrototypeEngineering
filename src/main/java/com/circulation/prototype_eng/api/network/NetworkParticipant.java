package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * External owner-bound machine, device or material provider. All callbacks run on the network owner
 * thread and must return before work proceeds. Only Snapshot and Command may cross worker boundaries.
 * Callbacks must not mutate network registrations, publish a batch or invoke another participant work
 * operation. A host may revoke the current network publication when its captured state changes.
 */
public interface NetworkParticipant {
    UUID participantId();

    UUID ownerId();

    Kind kind();

    State state();

    /**
     * Captures actual capabilities without changing host/world state. The proof is owner-only,
     * read-only and must detect every change affecting identity, resources, work or material sources.
     * Versions may repeat in one actual tick only for identical capabilities; later ticks advance them.
     */
    Capture capture(long tick, long epoch);

    /**
     * Applies exactly this authenticated work. REJECTED certifies no effects remain. A throwing
     * callback retains enough original-command evidence for cancellation; commit is never replayed.
     */
    Commit commit(Command command);

    /**
     * Undoes this original command, including a partially failed commit or a plan never committed.
     * The first call is made only after commit has actually returned or thrown. CANCELLED certifies
     * complete cleanup. UNKNOWN retains the original budget; RETRYABLE certifies safe later retry.
     */
    Cancellation cancel(Command command);

    /**
     * Read-only resolution after a cancellation returned UNKNOWN or threw. RETRYABLE authorizes only
     * another cancel of this command, never another commit. CANCELLED prevents duplicate cleanup.
     */
    default Cancellation cancellationState(Command command) {
        Objects.requireNonNull(command);
        return Cancellation.UNKNOWN;
    }

    enum Kind {EXTERNAL_MACHINE, EXTERNAL_DEVICE, MATERIAL_PROVIDER}

    enum State {REGISTERED, ACTIVE, INACTIVE, UNREGISTERED}

    enum Commit {COMMITTED, REJECTED}

    enum Cancellation {CANCELLED, RETRYABLE, UNKNOWN}

    record WorkType(ResourceLocation id, Object2LongMap<NetworkResourceType> resources) {
        public WorkType {
            Objects.requireNonNull(id);
            resources = NetworkResourceModifier.copyResources(resources);
            for (var entry : resources.object2LongEntrySet()) {
                if (entry.getKey().logicalConcurrency() || entry.getLongValue() <= 0) {
                    throw new IllegalArgumentException("Participant work needs positive numeric demands");
                }
            }
        }
    }

    /**
     * Stable source and affinity identities; no live inventory, world or transaction crosses a worker.
     */
    record MaterialSource(ResourceLocation id, String affinity) {
        public MaterialSource {
            Objects.requireNonNull(id);
            Objects.requireNonNull(affinity);
            if (affinity.isBlank() || !affinity.equals(affinity.trim())) {
                throw new IllegalArgumentException("Participant source affinity must be non-blank and trimmed");
            }
        }
    }

    /**
     * Work types and material sources are borrowed immutable lists. Providers and readers must not
     * change either list while this snapshot or work derived from it is retained.
     * Resource quantities follow the same rule and use a zero default return value.
     */
    record Snapshot(UUID participantId, UUID ownerId, Kind kind, long tick, long epoch, long version,
                    Object2LongMap<NetworkResourceType> resources,
                    List<WorkType> workTypes, List<MaterialSource> materialSources) {
        public Snapshot {
            Objects.requireNonNull(participantId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(kind);
            if (tick < 0 || epoch < 0 || version < 0) {
                throw new IllegalArgumentException("Negative participant snapshot generation");
            }
            NetworkResourceModifier.validateResources(resources);
            var ids = new ObjectOpenHashSet<ResourceLocation>();
            for (var work : workTypes) {
                if (!ids.add(work.id())) throw new IllegalArgumentException("Duplicate participant work type");
            }
            ids.clear();
            for (var source : materialSources) {
                if (!ids.add(source.id())) throw new IllegalArgumentException("Duplicate participant material source");
            }
        }

        boolean sameCapabilities(Snapshot other) {
            return resources.equals(other.resources) && workTypes.equals(other.workTypes)
                && materialSources.equals(other.materialSources);
        }
    }

    record Capture(Snapshot snapshot, BooleanSupplier current) {
        public Capture {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(current);
        }
    }

    /**
     * Runtime callbacks receive the accepted snapshot's capabilities, never a caller-supplied team id.
     */
    record Command(UUID participantId, UUID ownerId, long tick, long epoch, long version, UUID planId,
                   WorkType workType, @Nullable MaterialSource materialSource) {
        public Command {
            Objects.requireNonNull(participantId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(planId);
            Objects.requireNonNull(workType);
            if (tick < 0 || epoch < 0 || version < 0) {
                throw new IllegalArgumentException("Negative participant work generation");
            }
        }
    }
}
