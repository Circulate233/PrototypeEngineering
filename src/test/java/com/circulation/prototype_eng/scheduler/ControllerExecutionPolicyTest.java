package com.circulation.prototype_eng.scheduler;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerExecutionPolicyTest {
    private static final KeyType KEYS = new KeyType("controller_policy");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("controller_policy_test", path);
    }

    @Test
    void allFourPoliciesKeepDiscoveryAdmissionWorkAndSnapshotOnTheirSelectedThreads() {
        Thread owner = Thread.currentThread();
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            for (var policy : ControllerExecutionPolicy.values()) {
                for (boolean cross : new boolean[]{false, true}) {
                    var fixture = new Fixture(scheduler, policy, cross);
                    try {
                        scheduler.tick();
                        fixture.assertCompleted();
                    } finally {
                        fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                    }
                }
            }
        }
    }

    @Test
    void crossRecipeCohortSpansActualHostComponentsAndTicksEachHostOnlyOnce() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            var fixture = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, true);
            try {
                Objects.requireNonNull(fixture.machine).attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
                var slots = new ComponentType<>(id("second_batch_host"),
                    owner -> new MachineWorkSlots(owner, List.of(new WorkSlotDefinition("second"))), MachineWorkSlots.TYPE.systems());
                var second = fixture.machine.components().create(slots, fixture.machine);
                var firstRecipe = fixture.definition;
                var secondRecipe = new MachineRecipe(id("second_batch_recipe"), 2, firstRecipe.orderedRequirements(), Set.of(), 1, RecipeParallelismSelection.fixed(1));
                var secondProcess = new RecipeProcess(fixture.machine, secondRecipe, 9);
                secondProcess.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
                assertTrue(second.start(secondProcess));
                assertEquals(20, Objects.requireNonNull(fixture.resources.input()).insert(fixture.raw, 20, Actionable.MODULATE));
                assertEquals(10, fixture.energy.receiveEnergy(10, Actionable.MODULATE));
                scheduler.tick();
                var firstProcess = fixture.created.element();
                assertEquals(2, firstProcess.durationTicks());
                assertEquals(0, firstProcess.state().progress(), "The full FE output must block the whole TICK round");
                assertEquals(0, secondProcess.state().progress());
                assertEquals(20, fixture.energy.savedEnergy(), "A rejected peer round must roll back every FE share");
                assertEquals(1, fixture.energy.extractEnergy(1, Actionable.MODULATE));
                scheduler.tick();
                assertEquals(1, firstProcess.state().progress());
                assertEquals(1, secondProcess.state().progress(), "The later component cannot execute the already-driven host again");
                assertEquals(0, Objects.requireNonNull(fixture.resources.output()).amount(fixture.product));
                scheduler.tick();
                assertEquals(RecipeProcess.Lifecycle.COMPLETED, firstProcess.state().lifecycle());
                assertEquals(RecipeProcess.Lifecycle.COMPLETED, secondProcess.state().lifecycle());
                assertEquals(6, fixture.resources.output().amount(fixture.product));
                assertEquals(8, fixture.energy.savedEnergy());
                assertEquals(3, fixture.observations.stream().filter(value -> value.operation.equals("post")).count(),
                    "Skipping an already-driven later host must still resume normal/post callbacks");
            } finally {
                fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void idleMachinesDiscoverConcurrentlyBeforeEitherOwnerAdmissionRuns() {
        var entered = new CountDownLatch(2);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            var first = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, false);
            var second = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, true);
            Runnable awaitBoth = () -> {
                entered.countDown();
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "Idle discovery must be dispatched before waiting for another machine");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertTrue(first.created.isEmpty());
                assertTrue(second.created.isEmpty());
            };
            first.onDiscovery = awaitBoth;
            second.onDiscovery = awaitBoth;
            try {
                scheduler.tick();
                first.assertCompleted();
                second.assertCompleted();
                assertEquals(0, entered.getCount());
            } finally {
                second.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                first.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void oneDiscoveryFailureConsumesOnlyItsOwnReceiptAndReleasesEveryMachine() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            var first = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, false);
            var second = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, true);
            var failure = new IllegalStateException("one discovery failed");
            var completed = new CountDownLatch(1);
            first.onDiscovery = () -> { throw failure; };
            second.onDiscovery = completed::countDown;
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
                assertEquals(0, completed.getCount(), "The other accepted discovery must actually exit before cleanup");
                assertTrue(first.created.isEmpty());
                assertTrue(second.created.isEmpty());
                Objects.requireNonNull(first.machine).requirePersistenceBoundary();
                Objects.requireNonNull(second.machine).requirePersistenceBoundary();
            } finally {
                second.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                first.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void nestedOwnerRequirementKeepsRecipeSettlementOnOwnerWithoutChangingAsyncMachineCallbacks() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            for (boolean cross : new boolean[]{false, true}) {
                var fixture = new Fixture(scheduler, ControllerExecutionPolicy.FULL_ASYNC, cross, true);
                try {
                    scheduler.tick();
                    fixture.assertCompleted();
                    int reads = fixture.ownerCapabilityReads.get();
                    assertTrue(reads > 0);
                    assertTrue(fixture.created.element().requiresOwnerThread());
                    assertTrue(fixture.created.element().requiresOwnerThread());
                    assertEquals(reads, fixture.ownerCapabilityReads.get(), "The immutable nested capability must be cached per process");
                } finally {
                    fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                }
            }
        }
    }

    private record Observation(String operation, Thread thread) { }

    private static final class Fixture {
        private final Thread owner = Thread.currentThread();
        private final ControllerExecutionPolicy policy;
        private final boolean ownerRequired;
        private final TaskScheduler scheduler;
        private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<MachineGuiSnapshot> snapshots = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<RecipeProcess> created = new ConcurrentLinkedQueue<>();
        private final AtomicInteger callbacks = new AtomicInteger();
        private final AtomicInteger ownerCapabilityReads = new AtomicInteger();
        private Runnable onDiscovery = () -> { };
        private final Key raw = new Key(KEYS, "raw");
        private final Key product = new Key(KEYS, "product");
        private final BufferedResourceHatch resources = new BufferedResourceHatch(HatchMode.MIXED, 2,
            Reference2LongMaps.singleton(KEYS, 100L), () -> { });
        private final EnergyBuffer energy = new EnergyBuffer(20, () -> { });
        private final MachineController controller;
        private final MachineInstance machine;
        private final MachineRecipe definition;

        private Fixture(TaskScheduler scheduler, ControllerExecutionPolicy policy, boolean cross) {
            this(scheduler, policy, cross, false);
        }

        private Fixture(TaskScheduler scheduler, ControllerExecutionPolicy policy, boolean cross, boolean ownerRequired) {
            this.scheduler = scheduler;
            this.policy = policy;
            this.ownerRequired = ownerRequired;
            var observer = new RecipeRequirementType<>(id("observe"), new RecipeRequirementSystem<String>() {
                @Override public Object crossRecipeTickIo(String payload) { return null; }
                @Override
                public boolean requiresOwnerThread() {
                    ownerCapabilityReads.incrementAndGet();
                    return ownerRequired;
                }

                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                    record(process.host() == null ? "probe" : RecipeStage.START.name());
                    return RequirementTransaction.SUCCESS;
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                    record(RecipeStage.TICK.name());
                    return RequirementTransaction.SUCCESS;
                }

                @Override
                public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                    record(RecipeStage.END.name());
                    return RequirementTransaction.SUCCESS;
                }
            });
            RecipeParallelismSelection selection = process -> {
                record("select");
                process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            };
            RecipeRequirement<?> observed = observer.requirement(id("observed"), "state");
            if (ownerRequired) observed = RecipeRequirements.SELECTIVE.requirement(id("nested"), new SelectiveRequirement(List.of(observed)));
            var recipe = new MachineRecipe(id("recipe"), 1, List.of(observed,
                RecipeRequirements.RESOURCES.requirement(id("resource"), new ResourceRequirement(
                    List.of(new GenericStack(raw, 2)), List.of(new GenericStack(raw, 1)), List.of(), List.of(new GenericStack(product, 3)), false)),
                RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false))), Set.of(), 1, selection);
            definition = recipe;
            var pool = new ObservedPool(id("pool"), "test.pool", List.of(recipe), List.of(), this);
            var catalog = new RecipeCatalog(List.of(pool));
            RecipeProcessFactory factory = (instance, recipeDefinition, seed, tuning) -> {
                record("factory");
                var process = new RecipeProcess(instance, recipeDefinition, seed, tuning);
                created.add(process);
                return process;
            };
            var scanning = RecipeScanning.component(id("scanner"), host -> cross
                ? RecipeScanner.crossRecipe(host, catalog, factory, List.of(), List.of(), _ -> false, () -> 11, List.of())
                : RecipeScanner.multithreaded(host, catalog, factory, List.of(), List.of(), _ -> false, () -> 11, List.of()),
                new ScanLimits(8, 8), _ -> true);
            var phases = new ComponentType<>(id("callbacks"), _ -> "state", List.of(new MachineSystem<>() {
                @Override
                public ResourceLocation id() {
                    return ControllerExecutionPolicyTest.id("phases");
                }

                @Override
                public void onPreTick(MachineInstance instance, String state) {
                    record("pre");
                    Fixture.this.scheduler.runAtBoundary(() -> {
                        assertSame(owner, Thread.currentThread());
                        assertEquals(0, callbacks.get(), "A snapshot cannot overlap any machine callback");
                        snapshots.add(MachineGuiSnapshot.capture(instance, 0, MachineGuiSnapshot.MAX_ROWS));
                    });
                }

                @Override
                public void onTick(MachineInstance instance, String state) {
                    record("normal");
                }

                @Override
                public void onPostTick(MachineInstance instance, String state) {
                    record("post");
                }
            }));
            var type = MachineType.builder(id(policy.name().toLowerCase(Locale.ROOT) + (cross ? "_cross" : "_thread")), RecipeText.Cancelled, id("shape"))
                .executionPolicy(policy).recipePool(pool.id()).component(phases).component(MachineWorkSlots.TYPE).component(scanning)
                .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 1).attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1).build();
            var assembly = MachineAssembly.builder(type, "policy-v1").recreated(phases, scanning)
                .workSlots(new MachineWorkSlotsNbt("slots-v1", List.of()))
                .instanceFactory(cross ? CrossRecipeController::new : MultithreadController::new).build();
            controller = new MachineController(assembly, new Host(owner, type.id()), scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            machine = Objects.requireNonNull(controller.machine());
            assertEquals(6, Objects.requireNonNull(resources.input()).insert(raw, 6, Actionable.MODULATE));
            assertEquals(10, energy.receiveEnergy(10, Actionable.MODULATE));
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(resources), List.of(energy), List.of(energy)));
        }

        private void record(String operation) {
            assertEquals(1, callbacks.incrementAndGet(), "One machine's callbacks must never overlap");
            try {
                observations.add(new Observation(operation, Thread.currentThread()));
            } finally {
                assertEquals(0, callbacks.decrementAndGet());
            }
        }

        private void assertCompleted() {
            assertFalse(created.isEmpty(), "The real scanner must construct a candidate");
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, created.element().state().lifecycle());
            assertEquals(3, Objects.requireNonNull(resources.input()).amount(raw));
            assertEquals(3, Objects.requireNonNull(resources.output()).amount(product));
            assertEquals(6, energy.savedEnergy());
            for (var operation : List.of("scan-begin", "scan-lookup", "factory", "select", "probe", "pre", "normal", "post",
                RecipeStage.START.name(), RecipeStage.TICK.name(), RecipeStage.END.name())) {
                var matches = observations.stream().filter(value -> value.operation.equals(operation)).toList();
                assertFalse(matches.isEmpty(), "Missing actual callback " + operation + " under " + policy);
                boolean asynchronous = switch (operation) {
                    case "scan-begin", "scan-lookup" -> policy.scanAsync();
                    case "factory", "select", "probe" -> false;
                    case "START", "TICK", "END" -> policy.workAsync() && !ownerRequired;
                    default -> policy.workAsync();
                };
                for (var observation : matches) {
                    if (asynchronous) assertNotSame(owner, observation.thread, operation + " under " + policy);
                    else assertSame(owner, observation.thread, operation + " under " + policy);
                }
            }
            assertEquals(List.of("pre", "normal", "post"), observations.stream().map(Observation::operation)
                .filter(operation -> Set.of("pre", "normal", "post").contains(operation)).toList());
            assertEquals(1, snapshots.size());
            assertEquals(policy, snapshots.element().details().policy());
            assertEquals(0, snapshots.element().details().running());
            machine.requirePersistenceBoundary();
        }
    }

    private record ObservedPool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes,
                                List<MachineType> machines, Fixture fixture) implements RecipePool {
        @Override
        public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            return new ObservedPool(id, translationKey, List.copyOf(recipes), List.copyOf(machines), fixture);
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // This fixture retains the actual incremental pool callback boundary.
        public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
            fixture.record("scan-begin");
            fixture.onDiscovery.run();
            return new RecipeCandidateIndex.Cursor() {
                private boolean done;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (done) return true;
                    if (!budget.take()) return false;
                    fixture.record("scan-lookup");
                    done = true;
                    return true;
                }

                @Override
                public List<MachineRecipe> result() {
                    assertTrue(done);
                    return recipes;
                }
            };
        }
    }

    private record Host(Thread owner, ResourceLocation type) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No game World in the real scheduler fixture");
        }

        @Override
        public void requireServerThread() {
            assertSame(owner, Thread.currentThread());
        }
    }
}
