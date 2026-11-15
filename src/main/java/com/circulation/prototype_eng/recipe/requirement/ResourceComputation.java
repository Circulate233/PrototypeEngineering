package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.BinomialSampler;
import com.circulation.prototype_eng.api.recipe.probability.ChanceMath;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Independent quantitative facts; no process, hatches, context, authorization or reservation.
 */
class ResourceComputation implements RecipePureComputation {
    private final ResourceRequirement payload;
    private final RecipeStage stage;
    private final long seed;
    private final String salt;
    private final int parallelism;
    private final double chance;
    private final ProbabilityTuning tuning;
    private final List<AEKey2LongMap> sourceAmounts;
    private final int hatchCount;
    private final RecipeProcess.Tick tick;
    private final AEKey2LongMap available = new AEKeyLinkedOpenHashMap(6);
    private final AEKey2LongMap consumed = new AEKeyLinkedOpenHashMap(6);
    private final AEKey2LongMap produced = new AEKeyLinkedOpenHashMap(6);
    private final AEKey2LongMap checkedInputs = new AEKeyLinkedOpenHashMap(6);
    private final AEKey2LongMap checkedOutputs = new AEKeyLinkedOpenHashMap(6);
    private final AEKey2LongMap randomOutputs = new AEKeyLinkedOpenHashMap(6);
    private final ObjectArrayList<List<AEKey>> dynamic = new ObjectArrayList<>();
    @Nullable
    private BinomialSampler.Cursor chanceDraw;
    @Nullable
    private WeightedSampling.Cursor<GenericStack> outputDraw;
    @Nullable
    private DynamicInputCandidates.Cursor candidates;
    @Nullable
    private Iterator<Object2LongMap.Entry<AEKey>> sourceEntries;
    private int source;
    private int stack;
    private int quantityPhase;
    private int group;
    private long execution;
    private int randomStack;
    private long times;
    private long guaranteed;
    private boolean chanceReady;
    private boolean randomReady;
    private boolean merged;
    private boolean complete;
    @Nullable
    private ResourceComputation cached;
    @Nullable
    private ResourceComputation reused;
    @Nullable
    private Iterator<Object2LongMap.Entry<AEKey>> proofEntries;
    private int proofSource;
    private boolean proofStarted;
    private int payloadPhase;
    private int payloadEntry;
    private int payloadCandidate;
    private boolean payloadGroupStarted;

    ResourceComputation(ResourceRequirement payload, RecipeStage stage, long seed, String salt,
                        int parallelism, double chance, ProbabilityTuning tuning, List<AEKey2LongMap> sourceAmounts, int hatchCount,
                        RecipeProcess.Tick tick, @Nullable ResourceComputation cached) {
        this.payload = payload;
        this.stage = stage;
        this.seed = seed;
        this.salt = salt;
        this.parallelism = parallelism;
        this.chance = chance;
        this.tuning = tuning;
        // This list is exclusively owned by a completed capture and is never mutated afterwards.
        this.sourceAmounts = sourceAmounts;
        this.cached = cached;
        this.hatchCount = hatchCount;
        this.tick = tick;
    }

    private static void addChecked(AEKey2LongMap amounts, AEKey key, long amount) {
        if (amount > Long.MAX_VALUE - amounts.getLong(key)) {
            throw new ArithmeticException("long overflow");
        }
        amounts.addTo(key, amount);
    }

    boolean matches(ResourceRequirement payload, RecipeStage stage, long seed, String salt, int parallelism,
                    double chance, ProbabilityTuning tuning, List<AEKey2LongMap> sources, int hatchCount,
                    RecipeProcess.Tick tick) {
        return compatiblePayload(payload) && this.stage == stage && this.seed == seed && this.salt.equals(salt)
            && this.parallelism == parallelism && Double.doubleToLongBits(this.chance) == Double.doubleToLongBits(chance)
            && this.tuning.equals(tuning) && sourceAmounts.size() == sources.size() && this.hatchCount == hatchCount
            && Objects.equals(this.tick, tick);
    }

    private boolean compatiblePayload(ResourceRequirement current) {
        var originalPool = payload.randomOutputs();
        var currentPool = current.randomOutputs();
        return payload.voidExcessOutputs() == current.voidExcessOutputs() && payload.chance().equals(current.chance())
            && payload.inputAllocation() == current.inputAllocation()
            && payload.inputs().size() == current.inputs().size() && payload.inputsPerTick().size() == current.inputsPerTick().size()
            && payload.outputsPerTick().size() == current.outputsPerTick().size() && payload.outputs().size() == current.outputs().size()
            && payload.fuzzyInputs().size() == current.fuzzyInputs().size() && payload.dynamicInputs().size() == current.dynamicInputs().size()
            && (originalPool == currentPool || originalPool != null && currentPool != null
            && originalPool.pickCount() == currentPool.pickCount() && originalPool.candidates().size() == currentPool.candidates().size());
    }

    /**
     * Exact effective-payload proof runs in the pure worker, one entry or group header per unit.
     */
    private boolean provePayload(RecipeWorkBudget budget) {
        while (cached != null && payloadPhase < 7) {
            var original = cached.payload;
            if (payloadPhase < 4 || payloadPhase == 6) {
                List<?> before = switch (payloadPhase) {
                    case 0 -> original.inputs();
                    case 1 -> original.inputsPerTick();
                    case 2 -> original.outputsPerTick();
                    case 3 -> original.outputs();
                    default -> original.randomOutputs() == null ? List.of() : original.randomOutputs().candidates();
                };
                List<?> after = switch (payloadPhase) {
                    case 0 -> payload.inputs();
                    case 1 -> payload.inputsPerTick();
                    case 2 -> payload.outputsPerTick();
                    case 3 -> payload.outputs();
                    default -> payload.randomOutputs() == null ? List.of() : payload.randomOutputs().candidates();
                };
                if (before != after) {
                    while (payloadEntry < before.size()) {
                        if (!budget.take()) {
                            return false;
                        }
                        if (!before.get(payloadEntry).equals(after.get(payloadEntry++))) {
                            cached = null;
                            return true;
                        }
                    }
                }
            } else {
                boolean fuzzy = payloadPhase == 4;
                List<?> before = fuzzy ? original.fuzzyInputs() : original.dynamicInputs();
                List<?> after = fuzzy ? payload.fuzzyInputs() : payload.dynamicInputs();
                while (before != after && payloadEntry < before.size()) {
                    if (!payloadGroupStarted && !budget.take()) {
                        return false;
                    }
                    List<AEKey> beforeKeys;
                    List<AEKey> afterKeys;
                    boolean sameHeader;
                    if (fuzzy) {
                        var first = original.fuzzyInputs().get(payloadEntry);
                        var second = payload.fuzzyInputs().get(payloadEntry);
                        beforeKeys = first.candidates();
                        afterKeys = second.candidates();
                        sameHeader = first.amount() == second.amount();
                    } else {
                        var first = original.dynamicInputs().get(payloadEntry);
                        var second = payload.dynamicInputs().get(payloadEntry);
                        beforeKeys = first.displayedCandidates();
                        afterKeys = second.displayedCandidates();
                        sameHeader = first.matcher().equals(second.matcher()) && first.pattern().equals(second.pattern())
                            && first.amount() == second.amount() && first.maxCandidates() == second.maxCandidates()
                            && Objects.equals(first.candidateOrder(), second.candidateOrder());
                    }
                    if (!payloadGroupStarted) {
                        if (!sameHeader || beforeKeys.size() != afterKeys.size()) {
                            cached = null;
                            return true;
                        }
                        payloadGroupStarted = true;
                    }
                    if (beforeKeys != afterKeys) {
                        while (payloadCandidate < beforeKeys.size()) {
                            if (!budget.take()) {
                                return false;
                            }
                            if (!beforeKeys.get(payloadCandidate).equals(afterKeys.get(payloadCandidate++))) {
                                cached = null;
                                return true;
                            }
                        }
                    }
                    payloadEntry++;
                    payloadCandidate = 0;
                    payloadGroupStarted = false;
                }
            }
            payloadPhase++;
            payloadEntry = 0;
        }
        return true;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public boolean advance(RecipeWorkBudget budget) {
        if (complete) {
            return true;
        }
        if (cached != null) {
            if (!provePayload(budget)) {
                return false;
            }
            while (cached != null && proofSource < sourceAmounts.size()) {
                var original = cached.sourceAmounts.get(proofSource);
                var current = sourceAmounts.get(proofSource);
                if (!proofStarted) {
                    if (!budget.take()) {
                        return false;
                    }
                    if (original.size() != current.size()) {
                        cached = null;
                        break;
                    }
                    proofEntries = original.object2LongEntrySet().iterator();
                    proofStarted = true;
                }
                var entries = proofEntries;
                while (entries.hasNext()) {
                    if (!budget.take()) {
                        return false;
                    }
                    var entry = entries.next();
                    if (entry.getLongValue() != current.getLong(entry.getKey()) || !current.containsKey(entry.getKey())) {
                        cached = null;
                        break;
                    }
                }
                if (cached == null) {
                    break;
                }
                proofSource++;
                proofStarted = false;
                proofEntries = null;
            }
            if (cached != null) {
                reused = cached.reused == null ? cached : cached.reused;
                cached = null;
            }
        }
        if (reused != null) {
            complete = reused.advance(budget);
            return complete;
        }
        if (!chanceReady && !chance(budget)) {
            return false;
        }
        while (source < sourceAmounts.size()) {
            if (sourceEntries == null) {
                sourceEntries = sourceAmounts.get(source).object2LongEntrySet().iterator();
            }
            while (sourceEntries.hasNext()) {
                if (!budget.take()) {
                    return false;
                }
                var entry = sourceEntries.next();
                long amount = entry.getLongValue();
                if (amount < 0) {
                    throw new IllegalStateException("Negative available resource balance");
                }
                if (amount > 0) {
                    available.addTo(entry.getKey(), Math.min(amount, Long.MAX_VALUE - available.getLong(entry.getKey())));
                }
            }
            sourceEntries = null;
            source++;
        }
        while (quantityPhase < 4) {
            if (quantityPhase % 2 == 0 && payload.inputAllocation() != InputAllocation.SPLITTABLE
                && (stage != RecipeStage.START || payload.fuzzyInputs().isEmpty() && payload.dynamicInputs().isEmpty())) {
                if (!budget.take()) return false;
                // The owner projection cursor scales these separately. Their joint key total may
                // exceed long even though every independent demand and physical balance is valid.
                quantityPhase++;
                continue;
            }
            var inputs = inputs();
            var outputs = outputs();
            var stacks = quantityPhase % 2 == 0 ? inputs : outputs;
            var target = switch (quantityPhase) {
                case 0 -> consumed;
                case 1 -> produced;
                case 2 -> checkedInputs;
                default -> checkedOutputs;
            };
            long count = quantityPhase < 2 ? times : checkTimes();
            while (count > 0 && stack < stacks.size()) {
                if (!budget.take()) {
                    return false;
                }
                var value = stacks.get(stack++);
                // Default/group selection retains its existing aggregate quantity contract.
                addChecked(target, value.what(), value.amount());
            }
            if (sourceEntries == null) {
                sourceEntries = target.object2LongEntrySet().iterator();
            }
            while (sourceEntries.hasNext()) {
                if (!budget.take()) {
                    return false;
                }
                var entry = sourceEntries.next();
                entry.setValue(ChanceMath.scaleCount(entry.getLongValue(), count));
            }
            sourceEntries = null;
            stack = 0;
            quantityPhase++;
        }
        var pool = stage == RecipeStage.END ? payload.randomOutputs() : null;
        if (!randomReady && pool != null && pool.hasOutputs() && times > 0) {
            if (pool.pickCount() >= pool.candidates().size()) {
                while (randomStack < pool.candidates().size()) {
                    if (!budget.take()) {
                        return false;
                    }
                    var value = pool.candidates().get(randomStack++).value();
                    addChecked(randomOutputs, value.what(), value.amount());
                }
                if (sourceEntries == null) {
                    sourceEntries = randomOutputs.object2LongEntrySet().iterator();
                }
                while (sourceEntries.hasNext()) {
                    if (!budget.take()) {
                        return false;
                    }
                    var entry = sourceEntries.next();
                    entry.setValue(ChanceMath.scaleCount(entry.getLongValue(), times));
                }
                sourceEntries = null;
                execution = times;
            } else {
                while (execution < times) {
                    if (outputDraw == null) {
                        outputDraw = new WeightedSampling.Cursor<>(new Random(seed ^ (salt + ":random:" + execution).hashCode()),
                            pool.candidates(), pool.pickCount());
                    }
                    if (!outputDraw.advance(budget)) {
                        return false;
                    }
                    var selected = outputDraw.result();
                    while (randomStack < selected.size()) {
                        if (!budget.take()) {
                            return false;
                        }
                        var value = selected.get(randomStack++);
                        addChecked(randomOutputs, value.what(), value.amount());
                    }
                    randomStack = 0;
                    outputDraw = null;
                    execution++;
                }
            }
        }
        randomReady = true;
        while (group < (stage == RecipeStage.START ? payload.dynamicInputs().size() : 0)) {
            if (candidates == null) {
                candidates = new DynamicInputCandidates.Cursor(available.keySet(), payload.dynamicInputs().get(group), true);
            }
            if (!candidates.advance(budget)) {
                return false;
            }
            dynamic.add(candidates.result());
            candidates = null;
            group++;
        }
        // Merge only once after every draw; incomplete results stay private throughout all slices.
        if (!merged) {
            if (sourceEntries == null) {
                sourceEntries = randomOutputs.object2LongEntrySet().iterator();
            }
            while (sourceEntries.hasNext()) {
                if (!budget.take()) {
                    return false;
                }
                var entry = sourceEntries.next();
                addChecked(produced, entry.getKey(), entry.getLongValue());
                addChecked(checkedOutputs, entry.getKey(), entry.getLongValue());
            }
            sourceEntries = null;
            merged = true;
        }
        complete = true;
        return true;
    }

    private boolean chance(RecipeWorkBudget budget) {
        if (chance == 100) {
            times = parallelism;
        } else if (chance <= 0 || parallelism <= 0) {
            times = 0;
        } else {
            if (chanceDraw == null) {
                double ratio = chance / 100;
                double whole = Math.floor(ratio);
                guaranteed = whole >= 0x1.0p63 ? Long.MAX_VALUE : ChanceMath.scaleCount((long) whole, parallelism);
                chanceDraw = new BinomialSampler.Cursor(new Random(seed ^ salt.hashCode()), parallelism,
                    guaranteed == Long.MAX_VALUE ? 0 : ratio - whole, tuning);
            }
            if (!chanceDraw.advance(budget)) {
                return false;
            }
            int extra = chanceDraw.result();
            times = guaranteed > Long.MAX_VALUE - extra ? Long.MAX_VALUE : guaranteed + extra;
        }
        chanceReady = true;
        return true;
    }

    private List<GenericStack> inputs() {
        return switch (stage) {
            case START -> payload.inputs();
            case TICK -> payload.inputsPerTick();
            case END -> List.of();
        };
    }

    private List<GenericStack> outputs() {
        return switch (stage) {
            case START -> List.of();
            case TICK -> payload.outputsPerTick();
            case END -> payload.outputs();
        };
    }

    long checkTimes() {
        return Math.max(parallelism, ChanceMath.maxTimes(parallelism, chance));
    }

    long times() {
        if (reused != null) {
            return reused.times();
        }
        requireComplete();
        return times;
    }

    AEKey2LongMap consumed() {
        if (reused != null) {
            return reused.consumed();
        }
        requireComplete();
        return consumed;
    }

    AEKey2LongMap produced() {
        if (reused != null) {
            return reused.produced();
        }
        requireComplete();
        return produced;
    }

    AEKey2LongMap checkedInputs() {
        if (reused != null) {
            return reused.checkedInputs();
        }
        requireComplete();
        return checkedInputs;
    }

    AEKey2LongMap checkedOutputs() {
        if (reused != null) {
            return reused.checkedOutputs();
        }
        requireComplete();
        return checkedOutputs;
    }

    List<List<AEKey>> dynamic() {
        if (reused != null) {
            return reused.dynamic();
        }
        requireComplete();
        return dynamic;
    }

    boolean knows(AEKey key) {
        if (reused != null) {
            return reused.knows(key);
        }
        requireComplete();
        return available.containsKey(key);
    }

    private void requireComplete() {
        if (!complete) {
            throw new IllegalStateException("Resource preparation is incomplete");
        }
    }
}
