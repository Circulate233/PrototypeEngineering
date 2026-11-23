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

import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * Input maps and stations must remain unchanged during this call.
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
            for (int index = 0; index < stations.size(); index++) {
                var station = stations.get(index);
                capacity += Math.min(maximum - capacity, Math.min(limits.getDouble(type), station.rates().getDouble(type)));
            }
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
        long allowedTime = allPaid ? stage.durationTicks() : allowedTime(remainingMaterials, materialFraction);
        for (var iterator = grants.reference2DoubleEntrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            double allowed = Math.max(0, totals.getDouble(entry.getKey()) * materialFraction - state.resources().getDouble(entry.getKey()));
            double amount = Math.min(entry.getDoubleValue(), allowed);
            if (amount == 0) iterator.remove();
            else entry.setValue(amount);
        }
        Reference2DoubleMap<NetworkResourceType> accumulated = state.resources();
        if (!grants.isEmpty()) {
            var changed = new Reference2DoubleOpenHashMap<>(state.resources());
            for (var entry : grants.reference2DoubleEntrySet()) changed.addTo(entry.getKey(), entry.getDoubleValue());
            accumulated = changed;
        }
        allowedTime = supportedTicks(allowedTime, stage.durationTicks(), totals, accumulated);
        long timeGrant = Math.clamp(allowedTime - state.elapsedTicks(), 0, stations.size());
        if (timeGrant == 0 && grants.isEmpty() && payments.isEmpty()) return null;
        var outstanding = new Reference2DoubleOpenHashMap<>(grants);
        var used = new ObjectArrayList<UUID>();
        var capacity = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        long timeLeft = timeGrant;
        for (int index2 = 0; index2 < stations.size(); index2++) {
            var station = stations.get(index2);
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
        long elapsed = state.elapsedTicks() + timeGrant;
        boolean complete = elapsed == stage.durationTicks();
        for (var amounts = remainingMaterials.values().iterator(); amounts.hasNext(); )
            complete &= amounts.nextLong() == 0;
        for (var entry : totals.reference2DoubleEntrySet())
            complete &= accumulated.getDouble(entry.getKey()) >= entry.getDoubleValue();
        return new Step(state, new Snapshot(target, elapsed, remainingMaterials, accumulated), payments, grants, capacity, used, complete);
    }

    public static long supportedTicks(long elapsedTicks, long durationTicks, Reference2DoubleMap<NetworkResourceType> totals,
                                      Reference2DoubleMap<NetworkResourceType> accumulated) {
        long supported = elapsedTicks;
        for (var entry : totals.reference2DoubleEntrySet()) {
            double total = entry.getDoubleValue();
            if (total == 0) continue;
            double amount = accumulated.getDouble(entry.getKey());
            if (amount >= total) continue;
            if (amount == 0) return 0;
            double scaled = durationTicks * (amount / total);
            long limit;
            if (Math.abs(scaled - Math.rint(scaled)) > Math.ulp(scaled) * 4) limit = (long) Math.floor(scaled);
            else limit = new BigDecimal(amount).multiply(BigDecimal.valueOf(durationTicks))
                .divide(new BigDecimal(total), 0, RoundingMode.FLOOR).longValueExact();
            supported = Math.min(supported, limit);
        }
        return supported;
    }

    private long allowedTime(AEKey2LongMap remainingMaterials, double materialFraction) {
        if (materialFraction == 0) return 0;
        double scaled = stage.durationTicks() * materialFraction;
        // Only integer boundaries need exact arithmetic; rounding must neither stall paid work nor unlock unpaid time.
        double uncertainty = Math.ulp(scaled) * (2D * stage.materialTotals().size() + 4);
        if (Math.abs(scaled - Math.rint(scaled)) > uncertainty) return (long) Math.floor(scaled);
        var numerator = BigInteger.ZERO;
        var denominator = BigInteger.ONE;
        for (var entry : stage.materialTotals().object2LongEntrySet()) {
            long paid = entry.getLongValue() - remainingMaterials.getLong(entry.getKey());
            if (paid == 0) continue;
            var total = BigInteger.valueOf(entry.getLongValue());
            var common = denominator.gcd(total);
            var multiplier = total.divide(common);
            numerator = numerator.multiply(multiplier).add(BigInteger.valueOf(paid).multiply(denominator.divide(common)));
            denominator = denominator.multiply(multiplier);
        }
        return numerator.multiply(BigInteger.valueOf(stage.durationTicks()))
                        .divide(denominator.multiply(BigInteger.valueOf(stage.materialTotals().size()))).longValueExact();
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
