package com.circulation.prototype_eng.api.machine;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * A closed cross-recipe cohort, with an exact weighted duration and deterministic integer IO shares.
 */
public final class CrossRecipeBatch {
    private CrossRecipeBatch() {
    }

    public static boolean running(MachineInstance owner) {
        var elements14 = owner.recipeHosts();
        for (int index14 = 0; index14 < elements14.size(); index14++) {
            var host = elements14.get(index14);
            var elements2 = host.slots();
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var slot = elements2.get(index2);
                var process = slot.process();
                if (process != null && process.crossRecipeTiming() != null) return true;
            }
        }
        return false;
    }

    public static boolean accepts(RecipeProcess candidate) {
        if (!(candidate.owner() instanceof CrossRecipeController)) return true;
        if (running(candidate.owner())) return false;
        var signature = signature(candidate);
        var elements17 = candidate.owner().recipeHosts();
        for (int index17 = 0; index17 < elements17.size(); index17++) {
            var host = elements17.get(index17);
            var elements5 = host.slots();
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var slot = elements5.get(index5);
                var process = slot.process();
                if (process != null && process != candidate) {
                    if (!signature.equals(signature(process))) return false;
                    if (signature.requiresOriginalDuration()
                        && candidate.recipe().durationTicks() != process.recipe().durationTicks()) return false;
                }
            }
        }
        return true;
    }

    /**
     * Called at the first real work capture, before any host or process has acquired a tick lease.
     */
    @SuppressWarnings("DataFlowIssue")
    public static void prepare(MachineInstance owner) {
        if (!(owner instanceof CrossRecipeController)) return;
        if (running(owner)) {
            Timing expected = null;
            var elements19 = owner.recipeHosts();
            for (int index19 = 0; index19 < elements19.size(); index19++) {
                var host = elements19.get(index19);
                var elements8 = host.slots();
                for (int index8 = 0; index8 < elements8.size(); index8++) {
                    var slot = elements8.get(index8);
                    var process = slot.process();
                    if (process == null) continue;
                    var timing = process.crossRecipeTiming();
                    if (expected != null && (!timing.batch.equals(expected.batch) || timing.duration != expected.duration))
                        throw new IllegalStateException("Inconsistent restored cross-recipe cohort");
                    expected = timing;
                }
            }
            return;
        }
        var members = new ObjectArrayList<RecipeProcess>();
        BigInteger work = BigInteger.ZERO;
        long parallels = 0;
        Signature expected = null;
        var elements21 = owner.recipeHosts();
        for (int index21 = 0; index21 < elements21.size(); index21++) {
            var host = elements21.get(index21);
            var elements9 = host.slots();
            for (int index9 = 0; index9 < elements9.size(); index9++) {
                var slot = elements9.get(index9);
                var process = slot.process();
                if (process == null) continue;
                process.requirePersistenceBoundary();
                var signature = signature(process);
                if (expected != null && !expected.equals(signature))
                    throw new IllegalStateException("Incompatible cross-recipe tick IO");
                expected = signature;
                int count = RecipeParallelism.effective(process);
                parallels = Math.addExact(parallels, count);
                work = work.add(BigInteger.valueOf(process.recipe().durationTicks()).multiply(BigInteger.valueOf(count)));
                members.add(process);
            }
        }
        if (members.isEmpty()) return;
        assign(members, work, parallels, UUID.randomUUID(), false);
    }

    private static void assign(List<RecipeProcess> members, BigInteger work, long parallels, UUID batch, boolean refresh) {
        var divisor = BigInteger.valueOf(parallels);
        int duration = work.add(divisor).subtract(BigInteger.ONE).divide(divisor).intValueExact();
        int materialOffset = 0;
        int inputOffset = 0;
        int outputOffset = 0;
        var timings = new ObjectArrayList<Timing>(members.size());
        for (int index20 = 0; index20 < members.size(); index20++) {
            var process = members.get(index20);
            int count = RecipeParallelism.effective(process);
            var offsets = new Object2ObjectLinkedOpenHashMap<RequirementPath, EnergyOffsets>(6);
            int[] energyOffsets = {inputOffset, outputOffset};
            var elements12 = process.orderedRequirements();
            for (int index12 = 0; index12 < elements12.size(); index12++) {
                var requirement = elements12.get(index12);
                energyOffsets(requirement, null, count, process.recipe().durationTicks(), duration, offsets, energyOffsets);
            }
            timings.add(new Timing(batch, duration, count, materialOffset, offsets));
            materialOffset = offset(materialOffset, BigInteger.valueOf(count).multiply(BigInteger.valueOf(process.recipe().durationTicks())), duration);
            inputOffset = energyOffsets[0];
            outputOffset = energyOffsets[1];
        }
        for (int index = 0; index < members.size(); index++) {
            if (refresh) members.get(index).refreshCrossRecipeTiming(timings.get(index));
            else members.get(index).crossRecipeTiming(timings.get(index));
        }
    }

    /**
     * START effects participate in the frozen quantity weights; a cohort then shares one work clock.
     */
    @SuppressWarnings("DataFlowIssue")
    public static void prepareRound(MachineInstance owner) {
        if (!(owner instanceof CrossRecipeController cross)) return;
        boolean allStarted = true;
        boolean noWork = true;
        boolean changed = false;
        long parallels = 0;
        double speed = 0;
        var elements22 = owner.recipeHosts();
        for (int index22 = 0; index22 < elements22.size(); index22++) {
            var host = elements22.get(index22);
            var elements10 = host.slots();
            for (int index10 = 0; index10 < elements10.size(); index10++) {
                var slot = elements10.get(index10);
                var process = slot.process();
                if (process == null || process.state().lifecycle().isTerminal()) continue;
                var timing = process.crossRecipeTiming();
                int count = RecipeParallelism.effective(process);
                changed |= count != timing.parallelism;
                allStarted &= process.state().started();
                noWork &= process.state().successfulTicks() == 0 && process.state().progress() == 0;
                long next = Math.addExact(parallels, count);
                double value = Math.max(0, process.attributes().value(StandardMachineAttributes.PROCESS_SPEED, 1));
                speed += (value - speed) * ((double) count / next);
                parallels = next;
            }
        }
        if (parallels == 0) return;
        if (allStarted && noWork && changed) {
            var members = new ObjectArrayList<RecipeProcess>();
            BigInteger weighted = BigInteger.ZERO;
            var elements15 = owner.recipeHosts();
            for (int index15 = 0; index15 < elements15.size(); index15++) {
                var host = elements15.get(index15);
                var elements4 = host.slots();
                for (int index4 = 0; index4 < elements4.size(); index4++) {
                    var slot = elements4.get(index4);
                    var process = slot.process();
                    if (process == null || process.state().lifecycle().isTerminal()) continue;
                    members.add(process);
                    weighted = weighted.add(BigInteger.valueOf(process.recipe().durationTicks()).multiply(BigInteger.valueOf(RecipeParallelism.effective(process))));
                }
            }
            assign(members, weighted, parallels, members.getFirst().crossRecipeTiming().batch, true);
        }
        cross.batchSpeed(speed);
    }

    private static <C> void energyOffsets(RecipeRequirement<C> requirement, @Nullable RequirementPath parent, int count,
                                          int originalDuration, int duration, Object2ObjectMap<RequirementPath, EnergyOffsets> offsets, int[] cursor) {
        var path = requirement.path(parent);
        if (requirement.payload() instanceof EnergyRequirement energy) {
            offsets.put(path, new EnergyOffsets(cursor[0], cursor[1]));
            cursor[0] = offset(cursor[0], energyTotal(energy.input(), energy.inputPerTick(), originalDuration, count), duration);
            cursor[1] = offset(cursor[1], energyTotal(energy.output(), energy.outputPerTick(), originalDuration, count), duration);
        }

        var elements = requirement.type().system().children(requirement.payload());
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var child = elements.get(index1);
            energyOffsets(child, path, count, originalDuration, duration, offsets, cursor);
        }

    }

    private static int offset(int previous, BigInteger total, int duration) {
        return duration == 0 ? 0 : total.add(BigInteger.valueOf(previous)).mod(BigInteger.valueOf(duration)).intValueExact();
    }

    public static BigInteger energyTotal(long once, long perTick, int originalDuration, int parallelism) {
        return BigInteger.valueOf(perTick).multiply(BigInteger.valueOf(originalDuration)).add(BigInteger.valueOf(once))
                         .multiply(BigInteger.valueOf(parallelism));
    }

    /**
     * END is admitted only after every remaining member has completed its shared clock.
     */
    public static boolean readyToEnd(RecipeProcess process) {
        var timing = process.crossRecipeTiming();
        if (timing == null) return true;
        var elements18 = process.owner().recipeHosts();
        for (int index18 = 0; index18 < elements18.size(); index18++) {
            var host = elements18.get(index18);
            var elements7 = host.slots();
            for (int index7 = 0; index7 < elements7.size(); index7++) {
                var slot = elements7.get(index7);
                var member = slot.process();
                if (member == null || member.state().lifecycle().isTerminal()) continue;
                var other = member.crossRecipeTiming();
                if (other == null || !other.batch().equals(timing.batch()))
                    throw new IllegalStateException("Cross-recipe cohort changed");
                if (!member.state().started() || member.state().progress() < other.duration()) return false;
            }
        }
        return true;
    }

    private static Signature signature(RecipeProcess process) {
        var inputs = new AEKey2LongMap.OpenHashMap(6);
        var outputs = new AEKey2LongMap.OpenHashMap(6);
        var policies = new ObjectOpenHashSet<TickPolicy>();
        var opaque = new Object2IntOpenHashMap<OpaqueTick>();
        var elements11 = process.orderedRequirements();
        for (int index11 = 0; index11 < elements11.size(); index11++) {
            var requirement = elements11.get(index11);
            signature(process, requirement, null, null, inputs, outputs, policies, opaque);
        }
        return new Signature(inputs, outputs, policies, opaque);
    }

    private static <C> void signature(RecipeProcess process, RecipeRequirement<C> requirement, @Nullable RequirementPath parent,
                                      @Nullable RecipeRequirement<?> wrapper,
                                      AEKey2LongMap inputs, AEKey2LongMap outputs,
                                      ObjectOpenHashSet<TickPolicy> policies, Object2IntOpenHashMap<OpaqueTick> opaque) {
        var path = requirement.path(parent);
        var overlays = process.components().get(RecipeOverlays.TYPE);
        if (overlays != null) requirement = overlays.resolve(requirement, path);
        if (requirement.payload() instanceof ResourceRequirement resources
            && (!resources.inputsPerTick().isEmpty() || !resources.outputsPerTick().isEmpty())) {
            add(inputs, resources.inputsPerTick());
            add(outputs, resources.outputsPerTick());
            double chance = resources.chance().effective(process);
            policies.add(new TickPolicy(new ResourceChance(chance, null), resources.inputAllocation(), resources.voidExcessOutputs(), wrapper));
            if (chance != 100) opaque.addTo(new OpaqueTick(requirement.type(),
                new ProbabilisticTick(resources.inputsPerTick(), resources.outputsPerTick(), chance), false), 1);
        }
        if (wrapper != null && requirement.payload() instanceof EnergyRequirement)
            policies.add(new TickPolicy(ResourceChance.ALWAYS, InputAllocation.SPLITTABLE, false, wrapper));
        if (requirement.type() != RecipeRequirements.RESOURCES && requirement.type() != RecipeRequirements.ENERGY
            && requirement.type() != RecipeRequirements.ATTRIBUTES) {
            var key = requirement.type().system().crossRecipeTickIo(requirement.payload());
            if (key != null) opaque.addTo(new OpaqueTick(requirement.type(), key,
                requirement.type().system().crossRecipeRetimesTickIo(requirement.payload())), 1);
        }

        var elements3 = requirement.type().system().children(requirement.payload());
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var child = elements3.get(index3);
            signature(process, child, path, wrapper == null ? requirement.definition() : wrapper, inputs, outputs, policies, opaque);
        }

    }

    /**
     * Recheck effective overlays before creating any new transaction for the cohort.
     */
    public static void validateTickIo(MachineInstance owner) {
        if (!(owner instanceof CrossRecipeController)) return;
        Signature expected = null;
        var elements16 = owner.recipeHosts();
        for (int index16 = 0; index16 < elements16.size(); index16++) {
            var host = elements16.get(index16);
            var elements6 = host.slots();
            for (int index6 = 0; index6 < elements6.size(); index6++) {
                var slot = elements6.get(index6);
                var process = slot.process();
                if (process == null || process.state().lifecycle().isTerminal()) continue;
                var actual = signature(process);
                if (expected != null && !expected.equals(actual))
                    throw new IllegalStateException("Cross-recipe tick IO changed after admission");
                expected = actual;
            }
        }
    }

    private static void add(AEKey2LongMap target, List<GenericStack> values) {
        for (int index13 = 0; index13 < values.size(); index13++) {
            var value = values.get(index13);
            if (target.getLong(value.what()) > Long.MAX_VALUE - value.amount())
                throw new ArithmeticException("Cross-recipe tick amount overflow");
            target.addTo(value.what(), value.amount());
        }
    }

    private record Signature(AEKey2LongMap inputs, AEKey2LongMap outputs, ObjectOpenHashSet<TickPolicy> policies,
                             Object2IntOpenHashMap<OpaqueTick> opaque) {
        private boolean requiresOriginalDuration() {
            for (var key : opaque.keySet()) if (!key.retimed) return true;
            for (var policy : policies)
                if (policy.wrapper != null || !policy.chance.equals(ResourceChance.ALWAYS)) return true;
            return false;
        }
    }

    private record OpaqueTick(RecipeRequirementType<?> type, Object key, boolean retimed) {
    }

    private record ProbabilisticTick(List<GenericStack> inputs, List<GenericStack> outputs, double chance) {
    }

    private record TickPolicy(ResourceChance chance, InputAllocation allocation, boolean voidOutputs,
                              @Nullable RecipeRequirement<?> wrapper) {
    }

    public record EnergyOffsets(int input, int output) {
    }

    public record Timing(UUID batch, int duration, int parallelism, int materialOffset,
                         Map<RequirementPath, EnergyOffsets> energyOffsets) {
        public Timing {
            if (duration < 0 || parallelism < 1 || materialOffset < 0 || materialOffset >= Math.max(1, duration))
                throw new IllegalArgumentException("Invalid cross-recipe timing");
            var owned = new Object2ObjectLinkedOpenHashMap<>(energyOffsets);
            for (var entry : owned.object2ObjectEntrySet()) {
                var value = entry.getValue();
                if (value.input < 0 || value.output < 0 || value.input >= Math.max(1, duration) || value.output >= Math.max(1, duration))
                    throw new IllegalArgumentException("Invalid energy rounding offset");
            }
            // JDK's pure-object view also prevents entry.setValue; a set-only wrapper does not.
            energyOffsets = owned;
        }

        /**
         * Nonnegative shares sum exactly to total, including a fractional speed and the final tick.
         */
        public long portion(BigInteger total, int offset, RecipeProcess.Tick tick) {
            if (duration == 0) return 0;
            long before = (long) Math.min(duration, tick.progressBefore());
            long after = (long) Math.min(duration, tick.progressAfter());
            var quotient = total.divideAndRemainder(BigInteger.valueOf(duration));
            long remainder = quotient[1].longValueExact();
            long extras = extras(after, offset, remainder) - extras(before, offset, remainder);
            return quotient[0].multiply(BigInteger.valueOf(after - before)).add(BigInteger.valueOf(extras)).longValueExact();
        }

        private long extras(long ticks, int offset, long remainder) {
            long end = offset + remainder;
            return end <= duration ? Math.max(0, Math.min(ticks, end) - offset)
                : Math.min(ticks, end - duration) + Math.max(0, ticks - offset);
        }

        public long materialTimes(RecipeProcess process) {
            return portion(BigInteger.valueOf(process.recipe().durationTicks()).multiply(BigInteger.valueOf(parallelism)),
                materialOffset, process.tickContext());
        }
    }
}
