package com.circulation.prototype_eng.api.research.queue;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Persistent progress for one team research target, retained after removal from the queue.
 * Access on the team owner thread. Returned maps are read-only and resource types must be shared singletons.
 */
public final class ResearchProgress {
    private final Target target;
    private final ResearchLevelStage stage;
    private final Reference2DoubleMap<NetworkResourceType> totals;
    private final Reference2DoubleMap<NetworkResourceType> limits;
    private Snapshot state;

    public ResearchProgress(ResearchRegistry registry, Target target) {
        this.target = target;
        stage = registry.require(target.researchId()).stageForLevel(target.level());
        totals = stage.resourceDemandForLevel(target.level());
        limits = stage.resourceLimitsForLevel(target.level());
        state = new Snapshot(target, 0, stage.materialTotals(), new Reference2DoubleOpenHashMap<>());
    }

    public Target target() {
        return target;
    }

    public ResearchLevelStage stage() {
        return stage;
    }

    public Reference2DoubleMap<NetworkResourceType> totals() {
        return totals;
    }

    public Reference2DoubleMap<NetworkResourceType> limits() {
        return limits;
    }

    public Snapshot snapshot() {
        return state;
    }

    public void restore(Snapshot saved) {
        if (!target.equals(saved.target()) || saved.elapsedTicks() < 0 || saved.elapsedTicks() > stage.durationTicks())
            throw new IllegalArgumentException("Invalid saved research target or elapsed time");
        if (saved.remainingMaterials().size() != stage.materialTotals().size())
            throw new IllegalArgumentException("Saved research materials differ from definition");
        for (var entry : stage.materialTotals().object2LongEntrySet()) {
            long remaining = saved.remainingMaterials().getLong(entry.getKey());
            if (!saved.remainingMaterials().containsKey(entry.getKey()) || remaining < 0 || remaining > entry.getLongValue())
                throw new IllegalArgumentException("Saved remaining material is outside its total");
        }
        for (var entry : saved.resources().reference2DoubleEntrySet()) {
            double value = entry.getDoubleValue();
            if (!totals.containsKey(entry.getKey()) || !Double.isFinite(value) || value < 0 || value > totals.getDouble(entry.getKey()))
                throw new IllegalArgumentException("Saved network research progress is outside its total");
        }
        state = saved;
    }

    void apply(Snapshot next) {
        state = next;
    }

    /**
     * Input maps and stations must remain unchanged during this call. Material payments unlock independently accumulated network progress.
     */
    public @Nullable Step plan(List<StationRate> stations, Reference2DoubleMap<NetworkResourceType> available,
                               AEKey2LongMap materials) {
        var grants = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        double fraction = (double) Math.min(stage.durationTicks() - state.elapsedTicks(), stations.size()) / stage.durationTicks()
            + (double) state.elapsedTicks() / stage.durationTicks();
        for (var entry : totals.reference2DoubleEntrySet()) {
            var type = entry.getKey();
            double total = entry.getDoubleValue();
            if (total == 0) continue;
            double remaining = total - state.resources().getDouble(type);
            double maximum = Math.clamp(available.getDouble(type), 0, remaining);
            double capacity = 0;
            for (var station : stations)
                capacity += Math.min(maximum - capacity, Math.min(limits.getDouble(type), station.rates().getDouble(type)));
            double granted = capacity;
            if (granted > 0) grants.put(type, granted);
            fraction = Math.max(fraction, (state.resources().getDouble(type) + granted) / total);
        }
        fraction = Math.min(1, fraction);
        AEKey2LongMap payments = ImmutableResourceAmounts.empty();
        AEKey2LongMap remainingMaterials = state.remainingMaterials();
        int materialKinds = stage.materialTotals().size();
        double materialFraction = materialKinds == 0 ? 1 : 0;
        for (var entry : stage.materialTotals().object2LongEntrySet())
            materialFraction += (double) (entry.getLongValue() - state.remainingMaterials().getLong(entry.getKey())) / entry.getLongValue() / materialKinds;
        boolean allPaid = true;
        for (var entry : stage.materialTotals().object2LongEntrySet()) {
            var key = entry.getKey();
            long total = entry.getLongValue();
            long remaining = state.remainingMaterials().getLong(key);
            double missingFraction = Math.max(0, fraction - materialFraction);
            long needed = fraction == 1 ? remaining : Math.min(remaining, (long) Math.ceil(missingFraction * materialKinds * total));
            long payment = Math.min(needed, materials.getLong(key));
            if (payment > 0) {
                if (payments.isEmpty()) {
                    payments = new AEKey2LongMap.OpenHashMap();
                    remainingMaterials = new AEKey2LongMap.OpenHashMap();
                    remainingMaterials.putAll(state.remainingMaterials());
                }
                payments.addTo(key, payment);
                remainingMaterials.addTo(key, -payment);
            }
            materialFraction += (double) payment / total / materialKinds;
            allPaid &= remaining == payment;
        }
        if (allPaid) materialFraction = 1;
        long allowedTime = materialFraction >= 1 ? stage.durationTicks() : (long) Math.floor(stage.durationTicks() * materialFraction);
        long timeGrant = Math.clamp(allowedTime - state.elapsedTicks(), 0, stations.size());
        for (var iterator = grants.reference2DoubleEntrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            double allowed = Math.max(0, totals.getDouble(entry.getKey()) * materialFraction - state.resources().getDouble(entry.getKey()));
            double amount = Math.min(entry.getDoubleValue(), allowed);
            if (amount == 0) iterator.remove();
            else entry.setValue(amount);
        }
        if (timeGrant == 0 && grants.isEmpty() && payments.isEmpty()) return null;
        var outstanding = new Reference2DoubleOpenHashMap<>(grants);
        var used = new ObjectArrayList<UUID>();
        var capacity = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        long timeLeft = timeGrant;
        for (var station : stations) {
            boolean worked = timeLeft > 0;
            if (worked) timeLeft--;
            for (var entry : outstanding.reference2DoubleEntrySet()) {
                double amount = Math.min(entry.getDoubleValue(), Math.min(limits.getDouble(entry.getKey()), station.rates().getDouble(entry.getKey())));
                if (amount > 0) {
                    entry.setValue(entry.getDoubleValue() - amount);
                    worked = true;
                }
            }
            if (!worked) continue;
            used.add(station.machineId());
            for (var entry : limits.reference2DoubleEntrySet())
                capacity.addTo(entry.getKey(), Math.min(Double.MAX_VALUE - capacity.getDouble(entry.getKey()),
                    Math.min(entry.getDoubleValue(), station.rates().getDouble(entry.getKey()))));
        }
        if (used.isEmpty() && !payments.isEmpty()) {
            var station = stations.getFirst();
            used.add(station.machineId());
            for (var entry : limits.reference2DoubleEntrySet())
                capacity.addTo(entry.getKey(), Math.min(entry.getDoubleValue(), station.rates().getDouble(entry.getKey())));
        }
        Reference2DoubleMap<NetworkResourceType> accumulated = state.resources();
        if (!grants.isEmpty()) {
            var changed = new Reference2DoubleOpenHashMap<>(state.resources());
            for (var entry : grants.reference2DoubleEntrySet()) changed.addTo(entry.getKey(), entry.getDoubleValue());
            accumulated = changed;
        }
        long elapsed = state.elapsedTicks() + timeGrant;
        boolean complete = elapsed == stage.durationTicks();
        for (var amounts = remainingMaterials.values().iterator(); amounts.hasNext(); )
            complete &= amounts.nextLong() == 0;
        for (var entry : totals.reference2DoubleEntrySet())
            complete &= accumulated.getDouble(entry.getKey()) >= entry.getDoubleValue();
        return new Step(state, new Snapshot(target, elapsed, remainingMaterials, accumulated), payments, grants, capacity, used, complete);
    }

    public record Target(ResourceLocation researchId, int level) {
        public Target {
            if (level < 1) throw new IllegalArgumentException("Research target level must be positive");
        }
    }

    /**
     * Completed maps are transferred read-only; consumers must not modify them.
     */
    public record Snapshot(Target target, long elapsedTicks, AEKey2LongMap remainingMaterials,
                           Reference2DoubleMap<NetworkResourceType> resources) {
    }

    public record StationRate(UUID machineId, Reference2DoubleMap<NetworkResourceType> rates) {
    }

    public record Step(Snapshot before, Snapshot after, AEKey2LongMap payments,
                       Reference2DoubleMap<NetworkResourceType> grants,
                       Reference2DoubleMap<NetworkResourceType> capacity, List<UUID> stations, boolean complete) {
    }
}
