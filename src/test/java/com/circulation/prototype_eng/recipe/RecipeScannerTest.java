package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismConstraint;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.machine.RecipeScanningSystem;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScannerTest {
    private static final KeyType TYPE = new KeyType("scanner");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key ALTERNATE = new Key(TYPE, "alternate");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceLocation GROUP = id("group");
    private static final ScanLimits ONE_PROBE = new ScanLimits(1, 1);
    private static final Runnable NO_OP = () -> {
    };

    private static RecipeProcess admitWithin(RecipeScanner scanner, int calls) {
        for (int call = 0; call < calls; call++) {
            var result = scanner.scan(ONE_PROBE);
            assertTrue(result.probes() <= 1);
            if (result.status() == RecipeScanner.Status.ADMITTED) {
                return Objects.requireNonNull(result.process());
            }
            assertEquals(RecipeScanner.Status.YIELDED, result.status());
            assertNull(result.process());
        }
        throw new AssertionError("Search did not admit within its bounded calls");
    }

    private static void assertProbeReleased(RecipeProcess process, Environment env) {
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertNull(process.host());
        assertTrue(ResourceInputLocks.getOrCreate(process).snapshot().isEmpty());
        assertEquals(env.input.amount(MATERIAL), env.input.availableAmount(MATERIAL));
        assertEquals(env.input.amount(ALTERNATE), env.input.availableAmount(ALTERNATE));
        assertEquals(0, env.output.amount(PRODUCT));
        process.requirePersistenceBoundary();
    }

    private static RecipeRequirement<Boolean> guard(String name, Function<RecipeProcess, ProcessResult> check) {
        return new RecipeRequirementType<Boolean>(id(name + "_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return new Outcome(check.apply(process));
            }
        }).requirement(id(name), true);
    }

    private static RecipeRequirement<ResourceRequirement> io(long start, long tick, long end) {
        return RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(stacks(MATERIAL, start),
            stacks(MATERIAL, tick), List.of(), stacks(PRODUCT, end), false));
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return amount == 0 ? List.of() : List.of(new GenericStack(key, amount));
    }

    private static MachineRecipe recipe(String name, int duration, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id(name), duration, List.of(requirements), Set.of(GROUP));
    }

    private static RecipeCatalog catalog(MachineRecipe... recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "scanner.pool", List.of(recipes))));
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(3, capacities, NO_OP);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("scanner_test", name);
    }

    @Test
    void implicitEmptyGroupsScanTheGlobalCatalogAcrossCandidateBudgetYields() {
        var machine = new MachineInstance();
        var host = new MachineWorkSlots(machine, List.of());
        var blocked = new MachineRecipe(id("global_blocked"), 2,
            List.of(guard("global_guard", _ -> new ProcessResult.Blocked("unavailable"))), Set.of(id("other_group")));
        var ungrouped = new MachineRecipe(id("global_ungrouped"), 2, List.of());
        RecipeCandidateIndex index = (_, groups, scope) -> {
            assertTrue(groups.isEmpty());
            assertEquals(List.of(blocked, ungrouped), scope);
            return null;
        };
        var scanner = new RecipeScanner(host, catalog(blocked, ungrouped), RecipeProcess::new,
            List.of(), List.of(index), _ -> false, () -> 17);
        var yielded = scanner.scan(ONE_PROBE);
        assertEquals(RecipeScanner.Status.YIELDED, yielded.status());
        assertEquals(1, yielded.candidates());
        assertEquals(1, yielded.probes());
        assertTrue(host.activeProcesses().isEmpty());
        var admitted = scanner.scan(ONE_PROBE);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        assertEquals(1, admitted.candidates());
        assertEquals(1, admitted.probes());
        assertSame(ungrouped, Objects.requireNonNull(admitted.process()).recipe());
        assertSame(admitted.process(), host.requireSlot("main").process());
    }

    @Test
    void wildcardSlotExpandsScanScopeWhileExplicitSlotsStillFilterAdmissionAndRestore() {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        var explicit = new WorkSlotDefinition("explicit", Set.of(GROUP));
        var wildcard = new WorkSlotDefinition("wildcard");
        var host = new MachineWorkSlots(machine, List.of(explicit, wildcard), Set.of(id("ignored_machine_group")), RecipeProcess::tick);
        var ungrouped = new MachineRecipe(id("mixed_ungrouped"), 2, List.of());
        var other = new MachineRecipe(id("mixed_other"), 2, List.of(), Set.of(id("other_group")));
        var matching = recipe("mixed_matching", 2);
        assertFalse(host.startIn("explicit", new RecipeProcess(machine, ungrouped, 17)));
        assertFalse(host.startIn("explicit", new RecipeProcess(machine, other, 17)));
        RecipeCandidateIndex index = (_, groups, scope) -> {
            assertTrue(groups.isEmpty());
            assertEquals(List.of(ungrouped, other, matching), scope);
            return scope;
        };
        var scanner = new RecipeScanner(host, catalog(ungrouped, other, matching), RecipeProcess::new,
            List.of(), List.of(index), _ -> false, () -> 17);
        var first = scanner.scan(new ScanLimits(3, 3));
        assertEquals(RecipeScanner.Status.ADMITTED, first.status());
        var firstProcess = Objects.requireNonNull(first.process());
        assertSame(firstProcess, host.requireSlot("wildcard").process());
        assertSame(ungrouped, firstProcess.recipe());
        var second = scanner.scan(new ScanLimits(3, 3));
        assertEquals(RecipeScanner.Status.ADMITTED, second.status());
        var secondProcess = Objects.requireNonNull(second.process());
        assertSame(matching, secondProcess.recipe());
        assertSame(secondProcess, host.requireSlot("explicit").process());
        assertEquals(3, second.candidates());
        assertEquals(1, second.probes());
        var previous = host.snapshot();
        var restoredUngrouped = new RecipeProcess(machine, ungrouped, 17);
        var restoredMatching = new RecipeProcess(machine, matching, 17);
        host.restore(List.of(new MachineWorkSlots.SlotSnapshot(explicit, restoredMatching, ProcessResult.SUCCESS),
            new MachineWorkSlots.SlotSnapshot(wildcard, restoredUngrouped, ProcessResult.SUCCESS)));
        assertSame(host, restoredUngrouped.host());
        assertSame(host, restoredMatching.host());
        assertNull(firstProcess.host());
        assertNull(secondProcess.host());
        assertThrows(IllegalArgumentException.class, () -> host.restore(List.of(
            new MachineWorkSlots.SlotSnapshot(explicit, new RecipeProcess(machine, other, 17), ProcessResult.SUCCESS), previous.getLast())));
        assertSame(restoredMatching, host.requireSlot("explicit").process());
        assertSame(restoredUngrouped, host.requireSlot("wildcard").process());
    }

    @Test
    void scheduleReadinessAndOrderingAdmitAtMostOneUniqueRecipeAndTickEachProcessOnce() {
        var env = new Environment(3, 1);
        env.input.insert(MATERIAL, 30, Actionable.MODULATE);
        var first = recipe("first", 6, io(2, 1, 0));
        var second = recipe("second", 6, io(2, 1, 0));
        var outside = new MachineRecipe(id("outside"), 6, List.of(), Set.of(id("other_group")));
        boolean[] ready = {false};
        int[] lookups = {0};
        RecipeCandidateIndex index = (_, _, scope) -> {
            lookups[0]++;
            return scope;
        };
        var scanner = new RecipeScanner(env.host, catalog(outside, first, second), RecipeProcess::new,
            List.of(), List.of(index), _ -> false, () -> 17);
        var system = new RecipeScanningSystem(new ScanLimits(10, 10), _ -> ready[0]);
        var scannerType = new ComponentType<>(id("scanner_component"), _ -> scanner, List.of(system));
        // The host was installed first, so the same-tick START below depends on runBefore ordering.
        env.machine.components().create(scannerType, env.machine);
        env.machine.onSchedule();
        assertTrue(env.host.activeProcesses().isEmpty());
        assertTrue(env.executions.isEmpty());
        assertEquals(0, lookups[0]);
        assertEquals(30, env.input.amount(MATERIAL));
        ready[0] = true;
        env.machine.onSchedule();
        assertEquals(1, env.host.activeProcesses().size());
        var firstProcess = env.host.activeProcesses().getFirst();
        assertSame(first, firstProcess.recipe());
        assertEquals(1, firstProcess.state().successfulTicks());
        assertEquals(27, env.input.amount(MATERIAL));
        env.machine.onSchedule();
        assertEquals(2, env.host.activeProcesses().size());
        var secondProcess = env.host.activeProcesses().getLast();
        assertSame(second, secondProcess.recipe());
        assertEquals(List.of(firstProcess, firstProcess, secondProcess), env.executions);
        assertEquals(2, firstProcess.state().successfulTicks());
        assertEquals(1, secondProcess.state().successfulTicks());
        assertEquals(23, env.input.amount(MATERIAL));
        env.machine.onSchedule();
        assertEquals(List.of(firstProcess, secondProcess), env.host.activeProcesses());
        assertNull(env.host.requireSlot("slot_2").process());
        assertEquals(List.of(firstProcess, firstProcess, secondProcess, firstProcess, secondProcess), env.executions);
        assertEquals(3, firstProcess.state().successfulTicks());
        assertEquals(2, secondProcess.state().successfulTicks());
        assertEquals(21, env.input.amount(MATERIAL));
        assertThrows(IllegalArgumentException.class, () -> system.onTick(new MachineInstance(), scanner));
        assertEquals(21, env.input.amount(MATERIAL));
    }

    @Test
    void descendingSearchFindsNonmonotonicFuzzySuccessAcrossYieldsWithoutKeepingLocksOrResources() {
        var env = new Environment(1, 4);
        env.input.insert(MATERIAL, 5, Actionable.MODULATE);
        env.input.insert(ALTERNATE, 8, Actionable.MODULATE);
        var resources = RecipeRequirements.RESOURCES.requirement(id("fuzzy_io"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
            List.of(new FuzzyInputGroup(List.of(MATERIAL, ALTERNATE), 4), new FuzzyInputGroup(List.of(MATERIAL), 2)), List.of()));
        var recipe = recipe("fuzzy", 3, resources);
        var one = new RecipeProcess(env.machine, recipe, 17);
        one.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.probeStart(one));
        assertProbeReleased(one, env);
        var attempted = new ObjectArrayList<RecipeProcess>();
        RecipeProcessFactory factory = (machine, definition, seed, tuning) -> {
            var process = new RecipeProcess(machine, definition, seed, tuning);
            attempted.add(process);
            return process;
        };
        var scanner = new RecipeScanner(env.host, catalog(recipe), factory, List.of(), List.of(), _ -> false, () -> 17);
        for (int amount = 4; amount >= 3; amount--) {
            var result = scanner.scan(ONE_PROBE);
            assertEquals(RecipeScanner.Status.YIELDED, result.status());
            assertEquals(1, result.probes());
            assertEquals(amount, RecipeParallelism.effective(attempted.getLast()));
            assertProbeReleased(attempted.getLast(), env);
            assertTrue(env.host.activeProcesses().isEmpty());
        }
        var result = scanner.scan(ONE_PROBE);
        assertEquals(RecipeScanner.Status.ADMITTED, result.status());
        var accepted = Objects.requireNonNull(result.process());
        assertSame(attempted.getLast(), accepted);
        assertEquals(2, RecipeParallelism.effective(accepted));
        assertEquals(RecipeProcess.Lifecycle.NEW, accepted.state().lifecycle());
        assertTrue(ResourceInputLocks.getOrCreate(accepted).snapshot().isEmpty());
        assertEquals(5, env.input.availableAmount(MATERIAL));
        assertEquals(8, env.input.availableAmount(ALTERNATE));
        env.machine.onSchedule();
        assertEquals(1, accepted.state().successfulTicks());
        assertEquals(1, env.input.amount(MATERIAL));
        assertEquals(0, env.input.amount(ALTERNATE));
        assertEquals(List.of(ALTERNATE, MATERIAL), new ObjectArrayList<>(ResourceInputLocks.getOrCreate(accepted).snapshot().values()));
        assertEquals(List.of(accepted), env.executions);
    }

    @Test
    void explicitlyMonotonicSearchHandlesIntegerBoundaryAndRevalidatesBestAfterBudgetYield() {
        var env = new Environment(1, Integer.MAX_VALUE);
        int maximum = Integer.MAX_VALUE - 3;
        var observed = new IntArrayList();
        var requirement = guard("long_boundary", process -> {
            int amount = RecipeParallelism.effective(process);
            observed.add(amount);
            return amount <= maximum ? ProcessResult.SUCCESS : new ProcessResult.Blocked("too_many");
        });
        var recipe = recipe("large", 2, requirement);
        var scanner = new RecipeScanner(env.host, catalog(recipe), RecipeProcess::new,
            List.of(), List.of(), _ -> true, () -> 17);
        var accepted = admitWithin(scanner, 40);
        assertEquals(maximum, RecipeParallelism.effective(accepted));
        assertTrue(observed.size() > 20);
        assertEquals(maximum, observed.getInt(observed.size() - 1));
        assertTrue(observed.subList(0, observed.size() - 1).contains(maximum));
        for (var iterator = observed.iterator(); iterator.hasNext(); ) {
            assertTrue(iterator.nextInt() > 0);
        }
        assertEquals(RecipeProcess.Lifecycle.NEW, accepted.state().lifecycle());
        env.machine.onSchedule();
        assertEquals(1, accepted.state().successfulTicks());
        assertEquals(maximum, observed.getInt(observed.size() - 1));

        var changing = new Environment(1, 1);
        boolean[] available = {true};
        int[] checks = {0};
        var changingRecipe = recipe("changing", 2, guard("live_gate", _ -> {
            checks[0]++;
            return available[0] ? ProcessResult.SUCCESS : new ProcessResult.Blocked("changed");
        }));
        var changingScanner = new RecipeScanner(changing.host, catalog(changingRecipe), RecipeProcess::new,
            List.of(), List.of(), _ -> true, () -> 17);
        assertEquals(RecipeScanner.Status.YIELDED, changingScanner.scan(ONE_PROBE).status());
        available[0] = false;
        assertEquals(RecipeScanner.Status.YIELDED, changingScanner.scan(ONE_PROBE).status());
        assertTrue(changing.host.activeProcesses().isEmpty());
        assertEquals(2, checks[0]);
        available[0] = true;
        var fresh = admitWithin(changingScanner, 3);
        assertSame(fresh, changing.host.requireSlot("slot_0").process());
        assertEquals(4, checks[0]);
    }

    @Test
    void pureConstraintsComposeBoundsAndChecksButStillRequireStartAndBadBoundsFaultTheScanner() {
        var env = new Environment(1, 10);
        env.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var boundInputs = new IntArrayList();
        var firstChecks = new IntArrayList();
        var secondChecks = new IntArrayList();
        RecipeParallelismConstraint first = new RecipeParallelismConstraint() {
            @Override
            public int upperBound(MachineInstance machine, MachineRecipe recipe, int currentLimit) {
                boundInputs.add(currentLimit);
                return 7;
            }

            @Override
            public ProcessResult test(RecipeProcess process) {
                int amount = RecipeParallelism.effective(process);
                firstChecks.add(amount);
                return amount <= 4 ? ProcessResult.SUCCESS : new ProcessResult.Blocked("first_limit");
            }
        };
        RecipeParallelismConstraint second = new RecipeParallelismConstraint() {
            @Override
            public int upperBound(MachineInstance machine, MachineRecipe recipe, int currentLimit) {
                boundInputs.add(currentLimit);
                return 5;
            }

            @Override
            public ProcessResult test(RecipeProcess process) {
                int amount = RecipeParallelism.effective(process);
                secondChecks.add(amount);
                return amount <= 3 ? ProcessResult.SUCCESS : new ProcessResult.Blocked("second_limit");
            }
        };
        var recipe = recipe("constrained", 2, io(1, 0, 0));
        var scanner = new RecipeScanner(env.host, catalog(recipe), RecipeProcess::new,
            List.of(first, second), List.of(), _ -> false, () -> 17);
        var budget = new ScanLimits(1, 2);
        assertEquals(RecipeScanner.Status.YIELDED, scanner.scan(budget).status());
        assertEquals(2, env.input.availableAmount(MATERIAL));
        var admitted = scanner.scan(budget);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        assertEquals(List.of(10, 7), boundInputs);
        assertEquals(List.of(5, 4, 3, 2), firstChecks);
        assertEquals(List.of(4, 3, 2), secondChecks);
        var process = Objects.requireNonNull(admitted.process());
        assertEquals(2, RecipeParallelism.effective(process));
        assertEquals(2, env.input.amount(MATERIAL));
        env.machine.onSchedule();
        assertEquals(0, env.input.amount(MATERIAL));
        assertEquals(1, process.state().successfulTicks());
        for (int invalid : new int[]{-1, 11}) {
            var broken = new Environment(1, 10);
            int[] bounds = {0};
            int[] factories = {0};
            var constraint = new RecipeParallelismConstraint() {
                @Override
                public int upperBound(MachineInstance machine, MachineRecipe definition, int currentLimit) {
                    bounds[0]++;
                    return invalid;
                }
            };
            var faulty = new RecipeScanner(broken.host, catalog(recipe), (machine, definition, seed, tuning) -> {
                factories[0]++;
                return new RecipeProcess(machine, definition, seed, tuning);
            }, List.of(constraint), List.of(), _ -> false, () -> 17);
            assertThrows(IllegalArgumentException.class, () -> faulty.scan(ONE_PROBE));
            assertThrows(IllegalStateException.class, () -> faulty.scan(ONE_PROBE));
            assertEquals(1, bounds[0]);
            assertEquals(0, factories[0]);
            assertTrue(broken.host.activeProcesses().isEmpty());
        }
    }

    @Test
    void candidateSeedAndProbabilityPolicyStayStableAcrossYieldsAndTheActualStart() {
        var originalTuning = ProbabilityTuning.defaultTuning();
        var captured = new ProbabilityTuning(true, 7);
        var changed = new ProbabilityTuning(false, 99);
        ProbabilityTuning.setDefault(captured);
        try {
            var env = new Environment(1, 4);
            env.input.insert(MATERIAL, 10, Actionable.MODULATE);
            var draws = new LongArrayList();
            var policies = new ObjectArrayList<ProbabilityTuning>();
            var calls = new IntArrayList();
            var recipe = recipe("stable_policy", 3, io(1, 0, 0), guard("policy", process -> {
                long draw = process.random("scanner_policy").nextLong();
                draws.add(draw);
                policies.add(process.probabilityTuning());
                int amount = RecipeParallelism.effective(process);
                calls.add(amount);
                return process.probabilityTuning().equals(captured) && draw == draws.getFirst() && amount <= 2
                    ? ProcessResult.SUCCESS : new ProcessResult.Blocked("policy_or_amount");
            }));
            long[] seedCalls = {0};
            var scanner = new RecipeScanner(env.host, catalog(recipe), () -> 900 + seedCalls[0]++);
            assertEquals(RecipeScanner.Status.YIELDED, scanner.scan(ONE_PROBE).status());
            assertEquals(10, env.input.availableAmount(MATERIAL));
            ProbabilityTuning.setDefault(changed);
            assertEquals(RecipeScanner.Status.YIELDED, scanner.scan(ONE_PROBE).status());
            var result = scanner.scan(ONE_PROBE);
            assertEquals(RecipeScanner.Status.ADMITTED, result.status());
            var process = Objects.requireNonNull(result.process());
            assertEquals(1, seedCalls[0]);
            assertEquals(List.of(4, 3, 2), calls);
            assertEquals(10, env.input.availableAmount(MATERIAL));
            env.machine.onSchedule();
            assertEquals(8, env.input.amount(MATERIAL));
            assertEquals(1, process.state().successfulTicks());
            assertEquals(List.of(4, 3, 2, 2), calls);
            assertTrue(draws.longStream().allMatch(draw -> draw == draws.getLong(0)));
            assertEquals(List.of(captured, captured, captured, captured), policies);
        } finally {
            ProbabilityTuning.setDefault(originalTuning);
        }
    }

    @Test
    void resolvedMachineParallelismAndLocalModifiersSurviveNewAndRunningHostPersistenceWithoutDoubleScaling() {
        var env = new Environment(1, 3);
        env.machine.attributes().addModifier(StandardMachineAttributes.PROCESS_PARALLELISM,
            new NumericAttributeModifier("parent", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        env.input.insert(MATERIAL, 6, Actionable.MODULATE);
        var recipe = recipe("resolved", 3, io(1, 1, 1));
        var candidates = new ObjectArrayList<RecipeProcess>();
        RecipeProcessFactory factory = (machine, definition, seed, tuning) -> {
            var process = new RecipeProcess(machine, definition, seed, tuning);
            process.attributes().addModifier(StandardMachineAttributes.PROCESS_PARALLELISM,
                new NumericAttributeModifier("local", 1, AttributeModifier.Operation.ADDITION));
            candidates.add(process);
            return process;
        };
        var scanner = new RecipeScanner(env.host, catalog(recipe), factory, List.of(), List.of(), _ -> false, () -> 17);
        assertEquals(RecipeScanner.Status.YIELDED, scanner.scan(ONE_PROBE).status());
        assertEquals(7, RecipeParallelism.effective(candidates.getFirst()));
        assertProbeReleased(candidates.getFirst(), env);
        var result = scanner.scan(ONE_PROBE);
        assertEquals(RecipeScanner.Status.ADMITTED, result.status());
        var accepted = Objects.requireNonNull(result.process());
        assertEquals(6, RecipeParallelism.effective(accepted));
        assertEquals(6, env.input.amount(MATERIAL));
        var keys = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
        var codec = new MachineWorkSlotsNbt("scanner_layout", List.of(RecipeProcessNbt.builder(recipe, "recipe_v1").resources(keys).build()));
        codec.restore(env.host, codec.encode(env.host));
        var fresh = Objects.requireNonNull(env.host.requireSlot("slot_0").process());
        assertNotSame(accepted, fresh);
        assertNull(accepted.host());
        assertEquals(RecipeProcess.Lifecycle.NEW, fresh.state().lifecycle());
        env.input.insert(MATERIAL, 24, Actionable.MODULATE);
        env.machine.onSchedule();
        assertEquals(18, env.input.amount(MATERIAL));
        assertEquals(1, fresh.state().successfulTicks());
        assertEquals(0, env.output.amount(PRODUCT));
        codec.restore(env.host, codec.encode(env.host));
        var restored = Objects.requireNonNull(env.host.requireSlot("slot_0").process());
        assertNotSame(fresh, restored);
        assertNull(fresh.host());
        env.machine.onSchedule();
        assertEquals(12, env.input.amount(MATERIAL));
        assertEquals(2, restored.state().successfulTicks());
        env.machine.onSchedule();
        assertEquals(6, env.input.amount(MATERIAL));
        assertEquals(6, env.output.amount(PRODUCT));
        assertEquals(3, restored.state().successfulTicks());
        assertTrue(env.host.activeProcesses().isEmpty());
        assertNull(restored.host());
        assertEquals(2, candidates.size());
    }

    @Test
    void invalidFactoriesAndExceptionalProbesFailClosedWithoutAdmittingOrKeepingReservations() {
        for (var defect : FactoryDefect.values()) {
            var env = new Environment(1, 1);
            int[] starts = {0};
            int[] factories = {0};
            var recipe = recipe("factory", 3, guard("factory_guard", _ -> {
                starts[0]++;
                return ProcessResult.SUCCESS;
            }));
            var scanner = new RecipeScanner(env.host, catalog(recipe), (machine, definition, seed, tuning) -> {
                factories[0]++;
                return switch (defect) {
                    case OWNER -> new RecipeProcess(new MachineInstance(), definition, seed, tuning);
                    case RECIPE ->
                        new RecipeProcess(machine, new MachineRecipe(definition.id(), 3, List.of(), Set.of(GROUP)), seed, tuning);
                    case SEED -> new RecipeProcess(machine, definition, seed + 1, tuning);
                    case TUNING -> new RecipeProcess(machine, definition, seed,
                        new ProbabilityTuning(!tuning.exactSmallBinomial(), tuning.exactSmallBinomialThreshold()));
                    case HOST -> {
                        var process = new RecipeProcess(machine, definition, seed, tuning);
                        var other = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("other", Set.of(GROUP))));
                        assertTrue(other.start(process));
                        yield process;
                    }
                };
            }, List.of(), List.of(), _ -> false, () -> 17);
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(ONE_PROBE));
            assertThrows(IllegalStateException.class, () -> scanner.scan(ONE_PROBE));
            assertEquals(1, factories[0]);
            assertEquals(0, starts[0]);
            assertTrue(env.host.activeProcesses().isEmpty());
        }
        var env = new Environment(1, 1);
        env.input.insert(MATERIAL, 10, Actionable.MODULATE);
        var failure = new IllegalStateException("external START failed");
        int[] starts = {0};
        var recipe = recipe("throwing", 3, io(2, 0, 0), guard("throwing_guard", _ -> {
            starts[0]++;
            throw failure;
        }));
        var candidates = new ObjectArrayList<RecipeProcess>();
        var scanner = new RecipeScanner(env.host, catalog(recipe), (machine, definition, seed, tuning) -> {
            var process = new RecipeProcess(machine, definition, seed, tuning);
            candidates.add(process);
            return process;
        }, List.of(), List.of(), _ -> false, () -> 17);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> scanner.scan(ONE_PROBE)));
        assertEquals(10, env.input.amount(MATERIAL));
        assertEquals(10, env.input.availableAmount(MATERIAL));
        assertTrue(env.host.activeProcesses().isEmpty());
        assertNull(candidates.getFirst().host());
        assertThrows(IllegalStateException.class, candidates.getFirst()::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, () -> scanner.scan(ONE_PROBE));
        assertEquals(1, starts[0]);
        assertEquals(1, candidates.size());
    }

    private enum FactoryDefect {OWNER, RECIPE, SEED, TUNING, HOST}

    private record Outcome(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private record Ports(@NotNull ResourceStorage input, @NotNull ResourceStorage output) implements ResourceHatch {
        private Ports {
            Objects.requireNonNull(input);
            Objects.requireNonNull(output);
        }

        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }

    private static final class Environment {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final List<RecipeProcess> executions = new ObjectArrayList<>();
        private final MachineWorkSlots host;

        private Environment(int slots, int parallelism) {
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, slots);
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(), List.of()));
            var layout = new ObjectArrayList<WorkSlotDefinition>();
            for (int slot = 0; slot < slots; slot++) {
                layout.add(new WorkSlotDefinition("slot_" + slot, Set.of(GROUP)));
            }
            host = new MachineWorkSlots(machine, layout, Set.of(), process -> {
                executions.add(process);
                process.tick();
            });
            machine.components().put(MachineWorkSlots.TYPE, host);
        }
    }
}
