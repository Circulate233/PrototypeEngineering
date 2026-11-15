package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.FixedIoScanConstraint;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedIoScanConstraintTest {
    private static final KeyType FIRST_TYPE = new KeyType("fixed_io_first");
    private static final KeyType SECOND_TYPE = new KeyType("fixed_io_second");
    private static final Key MATERIAL = new Key(FIRST_TYPE, "material");
    private static final Key PRODUCT = new Key(FIRST_TYPE, "product");
    private static final Key OTHER_PRODUCT = new Key(SECOND_TYPE, "product");
    private static final ResourceLocation GROUP = id("group");
    private static final Runnable NO_OP = () -> {
    };
    private static final FixedIoScanConstraint CONSTRAINT = new FixedIoScanConstraint();

    private static RecipeRequirement<Boolean> contextRequirement(String name, Environment env, ProbeStats stats, long amount,
                                                                 ProcessResult result, RuntimeException acquisitionFailure,
                                                                 RuntimeException cleanupFailure) {
        return new RecipeRequirementType<Boolean>(new ResourceLocation("tick_context_test", name), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                stats.starts++;
                return RequirementTransaction.SUCCESS;
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                stats.events.add("tick:" + name);
                var tick = process.tickContext();
                assertEquals(new RecipeProcess.Tick(0, 3.5, 0, 1), tick);
                assertEquals(requirement.path(null), process.requirementPath());
                if (acquisitionFailure != null) {
                    throw acquisitionFailure;
                }
                var tokens = new ObjectArrayList<ResourceReservation>();
                if (amount != 0) {
                    var material = env.input.reserveExtract(env.input.snapshot(), MATERIAL, amount);
                    var energy = env.energy.reserveExtractEnergy(env.energy.energySnapshot(), amount);
                    assertEquals(ReservationResult.Status.ACCEPTED, material.status());
                    assertEquals(ReservationResult.Status.ACCEPTED, energy.status());
                    tokens.add(Objects.requireNonNull(material.reservation()));
                    tokens.add(Objects.requireNonNull(energy.reservation()));
                }
                stats.active++;
                return new ContextTransaction(process, process.requirementPath(), tick, tokens, result, stats, () -> {
                    stats.events.add("rollback:" + name);
                    stats.active--;
                    if (cleanupFailure != null) {
                        throw cleanupFailure;
                    }
                });
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                stats.ends++;
                return RequirementTransaction.SUCCESS;
            }
        }).requirement(id(name), true);
    }

    private static RecipeRequirement<ResourceRequirement> io(String name, long start, long tickInput, long tickOutput,
                                                             long end, boolean voidExcess) {
        return RecipeRequirements.RESOURCES.requirement(id(name), new ResourceRequirement(stacks(MATERIAL, start),
            stacks(MATERIAL, tickInput), stacks(PRODUCT, tickOutput), stacks(PRODUCT, end), voidExcess));
    }

    private static RecipeRequirement<EnergyRequirement> energy(long start, long tickInput, long tickOutput, long end, boolean voidExcess) {
        return RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(start, tickInput, tickOutput, end, voidExcess));
    }

    private static RecipeProcess process(Environment env, MachineRecipe recipe, int parallelism) {
        var process = new RecipeProcess(env.machine, recipe, 17);
        process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
        return process;
    }

    private static MachineRecipe recipe(String name, int duration, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id(name), duration, List.of(requirements), Set.of(GROUP));
    }

    private static SlottedResourceBuffer buffer(int slots, long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST_TYPE, capacity);
        capacities.put(SECOND_TYPE, capacity);
        return new SlottedResourceBuffer(slots, capacities, NO_OP);
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return amount == 0 ? List.of() : List.of(new GenericStack(key, amount));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("fixed_io_test", name);
    }

    @Test
    void futureStageProbesAggregateRequirementsAndAliasesAgainstBufferedEnergy() {
        var recipes = List.of(
            recipe("tick_capacity", 3, io("first", 0, 1, 2, 0, false), io("second", 0, 1, 2, 0, false)),
            recipe("end_capacity", 3, io("first", 0, 0, 0, 3, false), io("second", 0, 0, 0, 3, false)),
            recipe("energy_input", 3, energy(0, 30, 0, 0, false)),
            recipe("energy_output", 3, energy(0, 0, 0, 60, false)));
        for (var recipe : recipes) {
            var env = new Environment();
            env.output.insert(PRODUCT, 4, Actionable.MODULATE);
            var excessive = process(env, recipe, 2);
            var smaller = process(env, recipe, 1);
            assertInstanceOf(ProcessResult.Blocked.class, CONSTRAINT.test(excessive));
            assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(smaller));
            env.assertReleased();
            assertEquals(20, env.input.amount(MATERIAL));
            assertEquals(4, env.output.amount(PRODUCT));
            assertEquals(50, env.energy.storedEnergy());
            assertEquals(0, env.generated.storedEnergy());
            assertEquals(RecipeProcess.Lifecycle.NEW, excessive.state().lifecycle());
            assertEquals(0, smaller.state().successfulTicks());
            excessive.requirePersistenceBoundary();
            smaller.requirePersistenceBoundary();
        }
    }

    @Test
    void defaultScannerChoosesSmallerParallelismAndCommitsStartAndEndOnlyDuringActualTicks() {
        var env = new Environment();
        env.machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 4);
        env.output.insert(PRODUCT, 4, Actionable.MODULATE);
        var recipe = recipe("scan", 2, io("io", 2, 1, 0, 3, false), energy(3, 1, 0, 2, false));
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "fixed.io.pool", List.of(recipe))));
        var scanner = new RecipeScanner(env.host, catalog, () -> 17);
        var result = scanner.scan(new ScanLimits(1, 4));
        assertEquals(RecipeScanner.Status.ADMITTED, result.status());
        var accepted = Objects.requireNonNull(result.process());
        assertEquals(2, RecipeParallelism.effective(accepted));
        assertEquals(RecipeProcess.Lifecycle.NEW, accepted.state().lifecycle());
        env.assertReleased();
        assertEquals(20, env.input.amount(MATERIAL));
        assertEquals(4, env.output.amount(PRODUCT));
        assertEquals(50, env.energy.storedEnergy());
        env.machine.onSchedule();
        assertEquals(1, accepted.state().successfulTicks());
        assertEquals(14, env.input.amount(MATERIAL));
        assertEquals(4, env.output.amount(PRODUCT));
        assertEquals(42, env.energy.storedEnergy());
        env.machine.onSchedule();
        assertEquals(2, accepted.state().successfulTicks());
        assertEquals(12, env.input.amount(MATERIAL));
        assertEquals(10, env.output.amount(PRODUCT));
        assertEquals(40, env.energy.storedEnergy());
        assertEquals(4, env.generated.storedEnergy());
        assertNull(accepted.host());
        assertTrue(env.host.activeProcesses().isEmpty());
        env.machine.onSchedule();
        assertEquals(12, env.input.amount(MATERIAL));
        assertEquals(10, env.output.amount(PRODUCT));
        assertEquals(4, env.generated.storedEnergy());
    }

    @Test
    void zeroDurationSkipsImpossibleTickIoAndVoidOutputsRetainTheirRealEndBehavior() {
        var env = new Environment();
        env.output.insert(PRODUCT, 10, Actionable.MODULATE);
        env.generated.restore(100);
        var recipe = recipe("instant", 0, io("io", 1, 101, 100, 100, true), energy(1, 101, 100, 100, true));
        var process = process(env, recipe, 1);
        assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(process));
        env.assertReleased();
        assertEquals(20, env.input.amount(MATERIAL));
        assertTrue(env.host.start(process));
        env.machine.onSchedule();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(19, env.input.amount(MATERIAL));
        assertEquals(49, env.energy.storedEnergy());
        assertEquals(10, env.output.amount(PRODUCT));
        assertEquals(100, env.generated.storedEnergy());
        assertNull(process.host());
    }

    @Test
    void priorStageConsumptionCanFreeSharedResourceSlotsAcrossKeyTypesAndSharedEnergyCapacity() {
        for (var stages : List.of(new Stages(RecipeStage.START, RecipeStage.TICK),
            new Stages(RecipeStage.START, RecipeStage.END), new Stages(RecipeStage.TICK, RecipeStage.END))) {
            var machine = new MachineInstance();
            var shared = buffer(1, 10);
            var sharedEnergy = new EnergyBuffer(100, NO_OP);
            shared.insert(MATERIAL, 10, Actionable.MODULATE);
            sharedEnergy.receiveEnergy(100, Actionable.MODULATE);
            var ports = new Ports(shared, shared);
            machine.components().put(MachineHatches.TYPE,
                new MachineHatches(List.of(ports, ports), List.of(sharedEnergy, sharedEnergy), List.of(sharedEnergy, sharedEnergy)));
            var resource = RecipeRequirements.RESOURCES.requirement(id("shared_io"), new ResourceRequirement(
                stages.input == RecipeStage.START ? stacks(MATERIAL, 10) : List.of(),
                stages.input == RecipeStage.TICK ? stacks(MATERIAL, 10) : List.of(),
                stages.output == RecipeStage.TICK ? stacks(OTHER_PRODUCT, 8) : List.of(),
                stages.output == RecipeStage.END ? stacks(OTHER_PRODUCT, 8) : List.of(), false));
            var energy = RecipeRequirements.ENERGY.requirement(id("shared_energy"), new EnergyRequirement(
                stages.input == RecipeStage.START ? 20 : 0, stages.input == RecipeStage.TICK ? 20 : 0,
                stages.output == RecipeStage.TICK ? 10 : 0, stages.output == RecipeStage.END ? 10 : 0, false));
            var process = new RecipeProcess(machine, recipe("shared", 1, resource, energy), 17);
            assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(process));
            assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
            assertEquals(10, shared.amount(MATERIAL));
            assertEquals(10, shared.availableAmount(MATERIAL));
            assertEquals(0, shared.amount(OTHER_PRODUCT));
            assertEquals(100, sharedEnergy.storedEnergy());
            assertEquals(100, sharedEnergy.energySnapshot().availableEnergy());
            var host = new MachineWorkSlots(machine, List.of());
            machine.components().put(MachineWorkSlots.TYPE, host);
            assertTrue(host.start(process));
            machine.onSchedule();
            assertEquals(0, shared.amount(MATERIAL));
            assertEquals(8, shared.amount(OTHER_PRODUCT));
            assertEquals(90, sharedEnergy.storedEnergy());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertNull(process.host());
        }
    }

    @Test
    void dynamicWrappersExternalStagesAndComponentCallbacksAreSkippedWhilePureOverlaysAreObserved() {
        var env = new Environment();
        env.output.insert(PRODUCT, 10, Actionable.MODULATE);
        int[] callbacks = {0};
        var external = new RecipeRequirementType<Boolean>(id("external_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return unexpected();
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return unexpected();
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return unexpected();
            }

            private RequirementTransaction unexpected() {
                callbacks[0]++;
                throw new AssertionError("Unsupported stage executed");
            }
        }).requirement(id("external"), true);
        var matcher = new NamedResourceMatcher(id("matcher"), (_, _) -> {
            callbacks[0]++;
            throw new AssertionError("Dynamic matcher executed");
        });
        var unsupported = new ObjectArrayList<RecipeRequirement<?>>();
        unsupported.add(RecipeRequirements.RESOURCES.requirement(id("chance"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, new ResourceChance(50, null), null)));
        unsupported.add(RecipeRequirements.RESOURCES.requirement(id("attribute_chance"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, new ResourceChance(100, id("chance_multiplier")), null)));
        unsupported.add(RecipeRequirements.RESOURCES.requirement(id("random"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS,
            new RandomOutputPool(List.of(new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 1), 1)), 1))));
        unsupported.add(RecipeRequirements.RESOURCES.requirement(id("fuzzy"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
            List.of(new FuzzyInputGroup(List.of(MATERIAL), 1)), List.of())));
        unsupported.add(RecipeRequirements.RESOURCES.requirement(id("dynamic"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
            List.of(), List.of(new DynamicInputGroup(matcher, MATERIAL, 1)))));
        unsupported.add(RecipeRequirements.ATTRIBUTES.requirement(id("attribute"), new AttributeRequirement(id("attribute"),
            new NumericAttributeModifier("attribute", 1, AttributeModifier.Operation.ADDITION))));
        unsupported.add(RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(external), List.of())));
        unsupported.add(RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(1, external)));
        unsupported.add(external);
        var fixed = io("fixed", 0, 0, 0, 1, false);
        for (var requirement : unsupported) {
            var candidate = process(env, recipe("unsupported", 3, fixed, requirement), 1);
            assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(candidate));
            assertEquals(RecipeProcess.Lifecycle.NEW, candidate.state().lifecycle());
            assertEquals(0, candidate.state().successfulTicks());
            candidate.requirePersistenceBoundary();
        }
        var callbackCandidate = process(env, recipe("callbacks", 3, fixed), 1);
        assertInstanceOf(ProcessResult.Blocked.class, CONSTRAINT.test(callbackCandidate));
        var type = new ComponentType<RecipeProcess, Boolean>(id("component"), _ -> true, List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return FixedIoScanConstraintTest.id("component_system");
            }

            @Override
            public void onPreTick(RecipeProcess process, Boolean component) {
                callbacks[0]++;
            }
        }));
        callbackCandidate.components().create(type, callbackCandidate);
        assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(callbackCandidate));
        assertEquals(0, callbacks[0]);
        env.assertReleased();
        var overlayEnv = new Environment();
        overlayEnv.output.insert(PRODUCT, 4, Actionable.MODULATE);
        var target = io("overlay_target", 0, 0, 0, 3, false);
        var projected = process(overlayEnv, recipe("overlay", 3, target), 2);
        assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(projected));
        var overlays = RecipeOverlays.getOrCreate(projected);
        overlays.put(target, "fixed_patch", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 4), -1)).complete();
        var saved = overlays.snapshot();
        assertInstanceOf(ProcessResult.Blocked.class, CONSTRAINT.test(projected));
        assertEquals(saved, overlays.snapshot());
        overlays.put(target, "fixed_patch", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 2), -1)).complete();
        assertSame(ProcessResult.SUCCESS, CONSTRAINT.test(projected));
        overlayEnv.assertReleased();
    }

    @Test
    void isolatedTickProbeKeepsOneFrozenContextThroughoutAcquisitionAndReverseCleanupForEveryResult() {
        for (var result : List.of(ProcessResult.SUCCESS, new ProcessResult.Blocked("waiting"), new ProcessResult.Failure("invalid"))) {
            var env = new Environment();
            var stats = new ProbeStats();
            var process = process(env, recipe("context", 3,
                contextRequirement("first", env, stats, 2, ProcessResult.SUCCESS, null, null),
                contextRequirement("second", env, stats, 1, result, null, null),
                contextRequirement("third", env, stats, 0, ProcessResult.SUCCESS, null, null)), 1);
            process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 3.5);
            var original = process.state();
            assertSame(result, RecipeStageExecutor.probeStage(process, RecipeStage.TICK));
            assertEquals(result == ProcessResult.SUCCESS
                ? List.of("tick:first", "tick:second", "tick:third", "rollback:third", "rollback:second", "rollback:first")
                : List.of("tick:first", "tick:second", "rollback:second", "rollback:first"), stats.events);
            assertEquals(original, process.state());
            assertEquals(0, stats.active);
            assertEquals(0, stats.commits);
            assertEquals(0, stats.completes);
            assertEquals(0, stats.starts);
            assertEquals(0, stats.ends);
            assertNull(process.tickContext());
            assertNull(process.requirementPath());
            process.requirePersistenceBoundary();
            env.assertReleased();
        }
    }

    @Test
    void isolatedTickProbeAggregatesAcquisitionAndRollbackFaultsWhileReleasingAllRealTokens() {
        var env = new Environment();
        var stats = new ProbeStats();
        var acquisition = new IllegalStateException("last tick acquisition failed");
        var cleanup = new IllegalStateException("second tick cleanup failed");
        var process = process(env, recipe("fault", 3,
            contextRequirement("first", env, stats, 2, ProcessResult.SUCCESS, null, null),
            contextRequirement("second", env, stats, 1, ProcessResult.SUCCESS, null, cleanup),
            contextRequirement("third", env, stats, 0, ProcessResult.SUCCESS, acquisition, null)), 1);
        process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 3.5);
        var original = process.state();
        assertSame(acquisition, assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.probeStage(process, RecipeStage.TICK)));
        assertEquals(List.of(cleanup), List.of(acquisition.getSuppressed()));
        assertEquals(List.of("tick:first", "tick:second", "tick:third", "rollback:second", "rollback:first"), stats.events);
        assertEquals(original, process.state());
        assertEquals(0, stats.active);
        assertEquals(0, stats.commits);
        assertEquals(0, stats.completes);
        env.assertReleased();
        assertEquals(20, env.input.amount(MATERIAL));
        assertEquals(50, env.energy.storedEnergy());
        assertNull(process.tickContext());
        assertNull(process.requirementPath());
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    private record ContextTransaction(RecipeProcess process, RequirementPath path, RecipeProcess.Tick tick,
                                      List<ResourceReservation> tokens, ProcessResult outcome, ProbeStats stats,
                                      Runnable cleanup) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            checkScope();
            return outcome;
        }

        @Override
        public List<ResourceReservation> reservations() {
            checkScope();
            return tokens;
        }

        @Override
        public void commit() {
            checkScope();
            stats.commits++;
        }

        @Override
        public void complete() {
            checkScope();
            stats.completes++;
        }

        @Override
        public void rollback() {
            checkScope();
            cleanup.run();
        }

        private void checkScope() {
            assertSame(tick, process.tickContext());
            assertEquals(path, process.requirementPath());
        }
    }

    private static final class ProbeStats {
        private final List<String> events = new ObjectArrayList<>();
        private int active;
        private int commits;
        private int completes;
        private int starts;
        private int ends;
    }

    private static final class Environment {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer(4, 100);
        private final SlottedResourceBuffer output = buffer(1, 10);
        private final EnergyBuffer energy = new EnergyBuffer(100, 100, 6, NO_OP);
        private final EnergyBuffer generated = new EnergyBuffer(100, 6, 100, NO_OP);
        private final MachineWorkSlots host;

        private Environment() {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
            energy.restore(50);
            var ports = new Ports(input, output);
            machine.components().put(MachineHatches.TYPE,
                new MachineHatches(List.of(ports, ports), List.of(energy, energy), List.of(generated, generated)));
            host = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("main", Set.of(GROUP))));
            machine.components().put(MachineWorkSlots.TYPE, host);
        }

        private void assertReleased() {
            assertEquals(input.amount(MATERIAL), input.availableAmount(MATERIAL));
            assertEquals(10 - output.amount(PRODUCT), output.insert(PRODUCT, 100, Actionable.SIMULATE));
            assertEquals(energy.storedEnergy(), energy.energySnapshot().availableEnergy());
            assertEquals(100 - generated.storedEnergy(), generated.energySnapshot().availableCapacity());
        }
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }

    private record Stages(RecipeStage input, RecipeStage output) {
    }
}
