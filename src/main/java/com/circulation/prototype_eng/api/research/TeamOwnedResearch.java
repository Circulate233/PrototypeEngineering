package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Legacy direct ITEM_ONLY source retained to read old worlds and settle frozen downloads.
 */
public final class TeamOwnedResearch {
    public static final NbtCodec<Snapshot> CODEC = new SnapshotCodec();
    private final ResearchRegistry registry;
    private final Object2ObjectOpenHashMap<ResourceLocation, UUID> sourceReservations = new Object2ObjectOpenHashMap<>();
    private Snapshot state;
    @Nullable
    private Completion writer;

    public TeamOwnedResearch(String teamId, ResearchRegistry registry) {
        this.registry = registry;
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research registry is not ready");
        }
        state = new Snapshot(teamId, Object2IntMaps.emptyMap());
    }

    public synchronized String teamId() {
        return state.teamId();
    }

    public synchronized int completedLevel(ResourceLocation id) {
        return state.levels().getInt(id);
    }

    public synchronized Object2IntMap<ResourceLocation> completedLevelsSnapshot() {
        return state.levels();
    }

    public synchronized Snapshot snapshot() {
        return state;
    }

    public synchronized boolean busy() {
        return writer != null;
    }

    public synchronized boolean canWriteResearch(ResourceLocation id) {
        return writer == null && !sourceReservations.containsKey(id);
    }

    public synchronized boolean sourceReserved(ResourceLocation id) {
        return sourceReservations.containsKey(id);
    }

    public synchronized boolean sourceReservedBy(ResourceLocation id, UUID operationId) {
        return operationId.equals(sourceReservations.get(id));
    }

    /**
     * Runtime-only reservation; direct team completions for other research remain available.
     */
    public synchronized boolean tryReserveSource(ResourceLocation id, UUID operationId) {
        var existing = sourceReservations.get(id);
        if (existing != null) return existing.equals(operationId);
        if (writer != null) return false;
        registry.require(id);
        sourceReservations.put(id, operationId);
        return true;
    }

    public synchronized boolean canReleaseSource(ResourceLocation id, UUID operationId) {
        return writer == null || !id.equals(writer.researchId) || !operationId.equals(writer.operationId);
    }

    public synchronized boolean releaseSource(ResourceLocation id, UUID operationId) {
        if (!canReleaseSource(id, operationId)) return false;
        sourceReservations.remove(id, operationId);
        return true;
    }

    public ResearchRegistry registry() {
        return registry;
    }

    /**
     * Restore data only; a pending completion or download owns no serializable permission.
     */
    public synchronized void restore(Snapshot saved) {
        requireAvailable();
        if (!sourceReservations.isEmpty())
            throw new IllegalStateException("Cannot replace reserved team research sources");
        if (!state.teamId().equals(saved.teamId())) {
            throw new IllegalArgumentException("Team snapshot identity changed");
        }
        for (var entry : saved.levels().object2IntEntrySet()) {
            requireLevel(entry.getKey(), entry.getIntValue());
        }
        state = saved;
    }

    @SuppressWarnings("ReturnOfInnerClass") // The receipt intentionally retains its exact source until settlement.
    public synchronized Completion prepareCompletion(ResourceLocation id, int level) {
        requireAvailable();
        requireUnreserved(id);
        requireLevel(id, level);
        int before = state.levels().getInt(id);
        return acquire(id, Math.max(before, level), null);
    }

    @SuppressWarnings("ReturnOfInnerClass") // The receipt intentionally retains its exact source until settlement.
    @Nullable
    public synchronized Completion tryPrepareCompletion(ResourceLocation id, int level) {
        if (!canWriteResearch(id)) return null;
        return prepareCompletion(id, level);
    }

    /**
     * Download source consumption shares the completion writer; rollback retains its exact baseline.
     */
    @SuppressWarnings("ReturnOfInnerClass") // A download must compensate the same source that it reserved.
    public synchronized Completion prepareSourceMutation(ResourceLocation id, int expectedBeforeLevel, int afterLevel) {
        requireAvailable();
        requireUnreserved(id);
        return prepareSourceMutation(id, expectedBeforeLevel, afterLevel, null);
    }

    @SuppressWarnings("ReturnOfInnerClass") // A download must compensate the same source that it reserved.
    @Nullable
    public synchronized Completion tryPrepareSourceMutation(ResourceLocation id, int expectedBeforeLevel,
                                                            int afterLevel, @Nullable UUID operationId) {
        if (writer != null || (operationId == null ? sourceReserved(id) : !sourceReservedBy(id, operationId)))
            return null;
        return prepareSourceMutation(id, expectedBeforeLevel, afterLevel, operationId);
    }

    private Completion prepareSourceMutation(ResourceLocation id, int expectedBeforeLevel, int afterLevel, @Nullable UUID operationId) {
        requireLevel(id, expectedBeforeLevel);
        if (afterLevel < 0 || afterLevel > expectedBeforeLevel || state.levels().getInt(id) != expectedBeforeLevel) {
            throw new IllegalArgumentException("Invalid team research source transition");
        }
        return acquire(id, afterLevel, operationId);
    }

    private Completion acquire(ResourceLocation id, int level, @Nullable UUID operationId) {
        var levels = new Object2IntOpenHashMap<>(state.levels());
        if (level == 0) {
            levels.removeInt(id);
        } else {
            levels.put(id, level);
        }
        writer = new Completion(id, operationId, state, new Snapshot(state.teamId(), levels), level > state.levels().getInt(id));
        return writer;
    }

    private void requireLevel(ResourceLocation id, int level) {
        var definition = registry.require(id);
        if (definition.specialOnly() || definition.deliveryPolicy() != ResearchDownloadDeliveryPolicy.ITEM_ONLY
            || level < 1 || level > definition.maxLevel()) {
            throw new IllegalArgumentException("Invalid direct team research level: " + id + " = " + level);
        }
    }

    private void requireAvailable() {
        if (writer != null) {
            throw new IllegalStateException("Team research source has an active writer");
        }
    }

    private void requireUnreserved(ResourceLocation id) {
        if (sourceReservations.containsKey(id))
            throw new IllegalStateException("Team research source is reserved by a download");
    }

    /**
     * Completed zero-default levels are borrowed read-only for this snapshot's lifetime.
     */
    public record Snapshot(String teamId, Object2IntMap<ResourceLocation> levels) {
    }

    private static final class SnapshotCodec implements NbtCodec<Snapshot> {
        @Override
        public NBTTagCompound encode(Snapshot value) {
            var tag = new NBTTagCompound();
            tag.setString("Team", value.teamId());
            var levels = new NBTTagList();
            for (var entry : value.levels().object2IntEntrySet()) {
                var item = new NBTTagCompound();
                item.setString("Id", entry.getKey().toString());
                item.setInteger("Level", entry.getIntValue());
                levels.appendTag(item);
            }
            tag.setTag("Levels", levels);
            return tag;
        }

        @Override
        public Snapshot decode(NBTTagCompound tag) {
            var list = tag.getTagList("Levels", Constants.NBT.TAG_COMPOUND);
            var levels = new Object2IntOpenHashMap<ResourceLocation>();
            for (int index = 0; index < list.tagCount(); index++) {
                var entry = list.getCompoundTagAt(index);
                var id = new ResourceLocation(entry.getString("Id"));
                int level = entry.getInteger("Level");
                if (level < 1) throw new IllegalArgumentException("Invalid team research level");
                levels.put(id, level);
            }
            var teamId = tag.getString("Team");
            if (teamId.isBlank() || !teamId.equals(teamId.trim()))
                throw new IllegalArgumentException("Invalid stable team id");
            return new Snapshot(teamId, levels);
        }
    }

    /**
     * Concrete source transaction; it never invokes addon callbacks.
     */
    public final class Completion implements ReversibleTransaction {
        private final ResourceLocation researchId;
        private final @Nullable UUID operationId;
        private final Snapshot before;
        private final Snapshot after;
        private final boolean levelRaised;
        private boolean committed;
        @Nullable
        private Resolution terminal;

        private Completion(ResourceLocation researchId, @Nullable UUID operationId, Snapshot before, Snapshot after, boolean levelRaised) {
            this.researchId = researchId;
            this.operationId = operationId;
            this.before = before;
            this.after = after;
            this.levelRaised = levelRaised;
        }

        @SuppressWarnings("unused") // Retained for callers of the legacy direct-source API.
        public boolean levelRaised() {
            return levelRaised;
        }

        /**
         * Exact live writer proof for an enclosing world's own held-source checkpoint.
         */
        public boolean owns(TeamOwnedResearch source) {
            synchronized (TeamOwnedResearch.this) {
                return source == TeamOwnedResearch.this && writer == this && terminal == null
                    && state == (committed ? after : before);
            }
        }

        /**
         * Borrowed immutable snapshot; another mutation never inherits this checkpoint right.
         */
        public Snapshot snapshot() {
            synchronized (TeamOwnedResearch.this) {
                if (!owns(TeamOwnedResearch.this))
                    throw new IllegalStateException("Team completion no longer owns its source snapshot");
                return state;
            }
        }

        @Override
        public void commit() {
            synchronized (TeamOwnedResearch.this) {
                if (writer != this || terminal != null || committed || state != before) {
                    throw new IllegalStateException("Team completion no longer owns its baseline");
                }
                state = after;
                committed = true;
            }
        }

        @Override
        public void complete() {
            settle(Resolution.COMPLETED);
        }

        @Override
        public void rollback() {
            settle(Resolution.ROLLED_BACK);
        }

        private void settle(Resolution expected) {
            synchronized (TeamOwnedResearch.this) {
                if (terminal == expected) {
                    return;
                }
                if (terminal != null || writer != this || state != (committed ? after : before)
                    || expected == Resolution.COMPLETED && !committed) {
                    throw new IllegalStateException("Team completion has incompatible state");
                }
                if (expected == Resolution.ROLLED_BACK) {
                    state = before;
                }
                terminal = expected;
                writer = null;
            }
        }

        @Override
        public Resolution resolution() {
            synchronized (TeamOwnedResearch.this) {
                return terminal == null ? Resolution.REVERSIBLE : terminal;
            }
        }
    }
}
