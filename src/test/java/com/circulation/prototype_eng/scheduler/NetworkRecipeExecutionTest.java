package com.circulation.prototype_eng.scheduler;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.ComputerSupplyFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.machine.RecipeScanningSystem;
import com.circulation.prototype_eng.machine.WorkSlotProcessingSystem;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRecipeExecutionTest {
    private static final ResourceLocation SCANNING_ID = new RecipeScanningSystem(new ScanLimits(1, 1), _ -> true).id();

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static int sourceLane(EnergyBuffer energy) {
        var affinity = "storage:" + energy.context().order();
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < affinity.length(); index++) {
            hash ^= affinity.charAt(index);
            hash *= 0x100000001b3L;
        }
        return Math.floorMod(hash, 2);
    }

    private static MachineType.Builder type(String name) {
        return MachineType.builder(id(name), GuiText.ResourceStorage, id("structure"));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("network_execution_test", name);
    }

    @Test
    void fullAsyncRecipeControllersActuallyDispatchBothAdmittedProcessesToOriginalParallelWorkers() {
        for (var factory : List.<Function<MachineType, MachineInstance>>of(MultithreadController::new, CrossRecipeController::new)) {
            try (var fixture = new Fixture(1, 20, 1, true, original -> new MachineRecipe(original.id(), original.durationTicks(),
                original.orderedRequirements(), original.recipeGroups(), 2, RecipeParallelismSelection.fixed(1)))) {
                var worker = fixture.worker(true, 2, null, Layout.ORDINARY, factory, true, ControllerExecutionPolicy.FULL_ASYNC);
                worker.machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
                assertEquals(ExecutionMode.PLANNED_PARALLEL, worker.machine.executionMode());
                assertEquals(ControllerExecutionPolicy.FULL_ASYNC, worker.machine.executionPolicy());
                assertEquals(ExecutionMode.PLANNED_PARALLEL, Objects.requireNonNull(worker.machine.components().get(MachineScheduling.TYPE)).mode());
                fixture.tick();
                assertEquals(0, fixture.pureEntered.getCount(), "Both processes on this actual assembled controller must overlap pure preparation");
                assertEquals(2, fixture.pureThreads.size());
                assertNotSame(fixture.owner, fixture.pureThreads.getFirst());
                assertNotSame(fixture.owner, fixture.pureThreads.getLast());
                assertNotSame(fixture.pureThreads.getFirst(), fixture.pureThreads.getLast());
                assertEquals(80, worker.energy.savedEnergy());
                assertEquals(2, worker.sourceApplies.get());
                assertNotSame(fixture.owner, worker.sourceThread.get());
                assertEquals(14, fixture.availableCompute(worker), "Both final-one processes share one machine lease and settle COMPUTE 3 each");
                assertTrue(worker.slots.activeProcesses().isEmpty());
            }
        }
    }

    @Test
    void realConcurrentControllersOverlapPurePreparationAndKeepOriginalOwnerAndSourceCallbacks() {
        try (var fixture = new Fixture(2, 20, 1, true)) {
            var first = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            var second = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            var one = fixture.process(first, first.slots, 1);
            var two = fixture.process(second, second.slots, 1);
            fixture.tick();
            assertEquals(0, fixture.pureEntered.getCount(), "Both independent kernels must enter before either continues");
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, one.state().lifecycle());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, two.state().lifecycle());
            assertEquals(90, first.energy.savedEnergy());
            assertEquals(90, second.energy.savedEnergy());
            assertEquals(1, first.processPost.get());
            assertEquals(1, second.processPost.get());
            assertNotSame(fixture.owner, fixture.pureThreads.getFirst());
            assertNotSame(fixture.pureThreads.getFirst(), fixture.pureThreads.getLast());
            assertEquals(List.of("pre", "before", "after", "post"), first.events);
            assertEquals(List.of("pre", "before", "after", "post"), second.events);
        }
    }

    @Test
    void realControllerContinuesMoreThanOneBudgetOfEmptyRootsInsideTheOriginalDeadline() {
        try (var fixture = new Fixture(1, 1000, 0, false, original -> {
            var roots = new ObjectArrayList<>(original.orderedRequirements());
            for (int index = 0; index < 16_500; index++) {
                roots.add(RecipeRequirements.RESOURCES.requirement(id("empty_" + index), new ResourceRequirement(List.of(), List.of())));
            }
            return new MachineRecipe(original.id(), original.durationTicks(), roots);
        })) {
            var worker = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            var process = fixture.process(worker, worker.slots, 1);
            fixture.tick();
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(90, worker.energy.savedEnergy());
            assertEquals(1, worker.processPre.get());
            assertEquals(1, worker.processPost.get());
            assertEquals(1, worker.sourceApplies.get());
        }
    }

    @Test
    void realConcurrentControllerResumesOnlyPureFactsAcrossNewAuthorizedTicksAndRejectsChangedQuantities() {
        var type = new KeyType("cross_tick_random");
        var keys = new ObjectArrayList<Key>();
        var pool = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>();
        for (int index = 0; index < 1000; index++) {
            var key = new Key(type, "key_" + index);
            keys.add(key);
            pool.add(new WeightedSampling.Weighted<>(new GenericStack(key, 1), index + 1));
        }
        var output = new BufferedResourceHatch(HatchMode.OUTPUT, 1000, Reference2LongMaps.singleton(type, 10_000L), () -> {
        });
        var firstSlice = new AtomicBoolean(true);
        var tickGate = new AtomicBoolean(true);
        var builtin = RecipeRequirements.RESOURCES.system();
        var controlled = new RecipeRequirementType<>(id("controlled_random"), new RecipeRequirementSystem<ResourceRequirement>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass")
            // Deliberately revoke the first actual capture after one accepted pure slice.
            public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement, RecipeStage stage) {
                var binding = builtin.prepare(process, requirement, stage);
                if (binding == null || !firstSlice.get()) {
                    return binding;
                }
                return new RequirementPreparation() {
                    private RecipePureComputation original;
                    private RecipePureComputation wrapped;

                    @Override
                    public RecipePureComputation computation() {
                        var current = binding.computation();
                        if (current == null) return null;
                        if (current != original) {
                            original = current;
                            wrapped = budget -> {
                                boolean first = firstSlice.get();
                                var slice = first ? budget.allocate(1) : budget;
                                boolean ready;
                                try {
                                    ready = current.advance(slice);
                                } finally {
                                    if (first) budget.reclaim(slice);
                                }
                                if (!ready && firstSlice.compareAndSet(true, false)) tickGate.set(false);
                                return ready;
                            };
                        }
                        return wrapped;
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        var transaction = binding.acquire();
                        if (transaction instanceof PreparedRequirement) {
                            // Keep this test's pure worker gate attached while the native owner
                            // capture publishes later preparation pauses and their exact tokens.
                            return new PreparedRequirement(this) {
                                @Override
                                public List<? extends ResourceReservation> reservations() {
                                    return transaction.reservations();
                                }

                                @Override
                                public void complete() {
                                    super.complete();
                                    transaction.complete();
                                }

                                @Override
                                public void rollback() {
                                    super.rollback();
                                    transaction.rollback();
                                }
                            };
                        }
                        return transaction;
                    }

                    @Override
                    public void retainPureFacts() {
                        binding.retainPureFacts();
                    }
                };
            }
        });
        var random = controlled.requirement(id("random"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, new RandomOutputPool(pool, 3)));
        try (var fixture = new Fixture(1, 1000, 0, false, original -> {
            var requirements = new ObjectArrayList<>(original.orderedRequirements());
            requirements.add(random);
            return new MachineRecipe(original.id(), original.durationTicks(), requirements);
        }, tickGate)) {
            var worker = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            Objects.requireNonNull(worker.machine.lifecycle()).form(new MachineHatches(List.of(output), List.of(worker.energy), List.of()));
            var process = fixture.process(worker, worker.slots, 128);
            fixture.tick();
            assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
            assertEquals(90, worker.energy.savedEnergy(), "The confirmed START must not run again after END pure work yields");
            for (var key : keys) {
                assertEquals(0, Objects.requireNonNull(output.output()).amount(key));
            }
            // The next capture must discard the old 128-execution cursor instead of continuing its RNG.
            process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 64);
            int ticks = 1;
            while (process.state().lifecycle() != RecipeProcess.Lifecycle.COMPLETED) {
                assertTrue(++ticks < 100, "Real scheduler capture must accumulate pure slices across ticks");
                fixture.tick();
            }
            assertEquals(2, ticks);
            long produced = 0;
            for (var key : keys) {
                produced += Objects.requireNonNull(output.output()).amount(key);
            }
            assertEquals(64 * 3, produced);
            assertEquals(90, worker.energy.savedEnergy());
            assertEquals(1, worker.sourceApplies.get());
            assertEquals(0, process.state().successfulTicks());
            assertEquals(ticks, worker.processPre.get());
            assertEquals(ticks, worker.processPost.get());
        }
    }

    @Test
    void realConcurrentControllerScansThenTicksOnOwnerAndResumesNormalCallbacksAfterSourceSettlement() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(true, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            fixture.tick();
            assertEquals(ExecutionMode.CONCURRENT, worker.machine.executionMode());
            assertEquals(List.of("pre", "before", "after", "post"), worker.events);
            assertEquals(1, worker.processes.size(), "The real scanner must run at its original normal cursor position");
            var process = worker.processes.getFirst();
            assertEquals(1, process.state().successfulTicks());
            assertEquals(1, worker.processPre.get());
            assertEquals(1, worker.processPost.get());
            assertEquals(90, worker.energy.savedEnergy());
            assertNotSame(fixture.owner, worker.sourceThread.get(), "Physical FE apply stays on the existing source lane");
            assertEquals(17, fixture.availableCompute(worker));
            assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, fixture.permit(worker).requestLease());
            fixture.tick();
            assertEquals(2, process.state().successfulTicks());
            assertEquals(2, worker.processPost.get());
            assertEquals(90, worker.energy.savedEnergy());
        }
    }

    @Test
    void realControllersScanMoreThanOneWorkSliceAndUndoProbeSourcesOnTheirOriginalLanesBeforeAdmission() {
        for (var mode : List.of(ExecutionMode.CONCURRENT, ExecutionMode.PLANNED_PARALLEL)) {
            try (var fixture = new Fixture(1, 1000, 3, false, original -> {
                var roots = new ObjectArrayList<>(original.orderedRequirements());
                for (int index = 0; index < 2500; index++)
                    roots.add(RecipeRequirements.RESOURCES.requirement(
                        id("scan_empty_" + index), new ResourceRequirement(List.of(), List.of())));
                return new MachineRecipe(original.id(), original.durationTicks(), roots);
            })) {
                var worker = fixture.worker(true, 2, mode, Layout.ORDINARY);
                fixture.tick();
                assertEquals(1, worker.processes.size());
                assertEquals(1, worker.processes.getFirst().state().successfulTicks());
                assertEquals(1, worker.probeRollbacks.get());
                assertNotSame(fixture.owner, worker.probeRollbackThread.get());
                assertEquals(1, worker.slots.activeProcesses().size(), "One machine tick admits at most one process");
                assertEquals(90, worker.energy.savedEnergy());
                assertEquals(List.of("pre", "before", "after", "post"), worker.events);
                assertEquals(1, worker.processPre.get());
                assertEquals(1, worker.processPost.get());
            }
        }
    }

    @Test
    void anOriginalScanLosingItsTickDuringSourceUndoCannotAdmitOrRunSuccessorCallbacks() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(true, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            worker.onProbeRollback = () -> fixture.tickGate.set(false);
            fixture.tick();
            assertEquals(1, worker.probeRollbacks.get());
            assertNotSame(fixture.owner, worker.probeRollbackThread.get());
            assertTrue(worker.slots.activeProcesses().isEmpty());
            assertEquals(100, worker.energy.savedEnergy());
            assertEquals(List.of("pre", "before"), worker.events);
            assertEquals(0, worker.processPre.get());
            assertEquals(0, worker.processPost.get());
            assertTrue(worker.processes.getFirst().persistenceReady());
            worker.slots.requireQuiescent();
        }
    }

    @Test
    void aRealMachineScanConsumesAFatalWorkerReceiptBeforeItsNextOriginalSourceCleanup() {
        var fatal = new OutOfMemoryError("scan source rollback fatal");
        var callbackExited = new AtomicBoolean();
        try (var fixture = new Fixture(1, 20, 3);
             var workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
             var lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var worker = fixture.worker(true, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            worker.onProbeRollback = () -> {
                try {
                    throw fatal;
                } finally {
                    callbackExited.set(true);
                }
            };
            var tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
            fixture.tickOpen = true;
            var lifecycle = Objects.requireNonNull(worker.machine.lifecycle());
            var originalDeadline = PlannedDeadline.after(Duration.ofSeconds(5));
            var execution = new MachineRecipeExecution(tasks,
                List.of(new MachineRecipeExecution.Candidate(worker.machine, lifecycle, () -> true)),
                originalDeadline, fixture::refresh, () -> fixture.tickOpen);
            boolean delivered = false;
            for (int step = 0; step < 10000 && !delivered && !execution.finished(); step++) {
                try {
                    execution.advance(originalDeadline);
                } catch (OutOfMemoryError reported) {
                    assertSame(fatal, reported);
                    assertTrue(callbackExited.get(), "The fatal receipt belongs to the actual exited source callback");
                    assertEquals(1, worker.probeRollbacks.get());
                    delivered = true;
                }
                execution.awaitExit();
            }
            assertTrue(delivered, "The original attempt must deliver its actual fatal source receipt");
            execution.awaitExit();
            assertTrue(callbackExited.get());
            assertEquals(1, worker.probeRollbacks.get());
            assertNotSame(fixture.owner, worker.probeRollbackThread.get());
            for (int step = 0; step < 4 && !execution.finished(); step++) {
                try {
                    execution.advance(originalDeadline);
                } catch (OutOfMemoryError reported) {
                    assertSame(fatal, reported);
                    assertTrue(execution.finished(), "An already delivered fatal may remain in history, but its worker receipt cannot be consumed again");
                }
                execution.awaitExit();
            }
            assertTrue(execution.finished(), "The original Source terminal proof and machine lifecycle can finish on cleanup");
            assertEquals(1, worker.probeRollbacks.get(), "Cleanup queries the exact terminal token instead of replaying its throwing rollback");
            assertEquals(100, worker.energy.savedEnergy());
            assertTrue(worker.slots.activeProcesses().isEmpty());
            assertEquals(List.of("pre", "before"), worker.events);
            assertEquals(0, worker.processPre.get());
            assertEquals(0, worker.processPost.get());
            worker.slots.requireQuiescent();
            fixture.tickOpen = false;
            worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertEquals(MachineLifecycle.State.UNLOADED, lifecycle.state());
        }
    }

    @Test
    void sameMachineSlotsScaleNumericResourcesAndShareOneThreadWhileAnotherMachineIsDenied() {
        for (var mode : List.of(ExecutionMode.CONCURRENT, ExecutionMode.PLANNED_PARALLEL)) {
            try (var fixture = new Fixture(1, 20, 3)) {
                var first = fixture.worker(false, 2, mode, Layout.ALIAS);
                var second = fixture.worker(false, 1, mode, Layout.ORDINARY);
                var one = fixture.process(first, first.slots, 2);
                var two = fixture.process(first, first.slots, 2);
                var other = fixture.process(second, second.slots, 1);
                fixture.tick();
                assertEquals(1, one.state().successfulTicks());
                assertEquals(1, two.state().successfulTicks());
                assertEquals(0, other.state().successfulTicks());
                assertEquals(2, first.processPost.get(), "An alias work-slot binding must not execute the same slots twice");
                assertEquals(1, second.processPost.get());
                assertEquals(8, fixture.availableCompute(first));
                assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, fixture.permit(first).requestLease());
                assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, fixture.permit(second).requestLease());
                assertEquals(80, first.energy.savedEnergy());
            }

        }
    }

    @Test
    void distinctWorkSlotBindingsResumeInOrderWithoutReplayingEarlierWork() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(false, 2, ExecutionMode.CONCURRENT, Layout.SECONDARY);
            var one = fixture.process(worker, worker.slots, 1);
            var two = fixture.process(worker, Objects.requireNonNull(worker.secondary), 1);
            fixture.tick();
            assertEquals(1, one.state().successfulTicks());
            assertEquals(1, two.state().successfulTicks());
            assertEquals(2, worker.processPost.get());
            assertEquals(14, fixture.availableCompute(worker));
            assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, fixture.permit(worker).requestLease());
            assertEquals(80, worker.energy.savedEnergy());
        }
    }

    @Test
    void explicitMainControllerRetainsOrdinaryOwnerDispatch() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(true, 1, ExecutionMode.MAIN_THREAD, Layout.ORDINARY);
            fixture.tick();
            assertSame(fixture.owner, worker.sourceThread.get());
            assertEquals(1, worker.processPost.get());
            assertEquals(List.of("pre", "before", "after", "post"), worker.events);
            assertEquals(1, worker.processes.getFirst().state().successfulTicks());
        }
    }

    @Test
    void retirementFromPreCancelsUnstartedNormalCallbacksAndReleasesLifecycleForUnload() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(true, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            worker.onPre = () -> worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            fixture.tick();
            assertEquals(List.of("pre"), worker.events);
            assertTrue(worker.processes.isEmpty());
            assertEquals(0, worker.processPost.get());
            assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
            assertEquals(0, fixture.scheduler.registeredCount());
        }
    }

    @Test
    void consumedDeferralThenCallbackFailureDoesNotTickOrLeakTheLifecycle() {
        try (var fixture = new Fixture(1, 20, 3)) {
            var worker = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.FAIL_AFTER_DEFER);
            var process = fixture.process(worker, worker.slots, 1);
            var failure = assertThrows(IllegalStateException.class, fixture::tick);
            assertEquals("failure after exact deferral", failure.getMessage());
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(0, worker.processPre.get());
            assertEquals(0, worker.processPost.get());
            assertEquals(0, worker.sourceApplies.get());
            worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
            assertTrue(fixture.scheduler.tryClose());
        }
    }

    @Test
    void successorCallbackFailurePreservesExactlyOneRecipePostAndPlainFailFastMachinePost() {
        try (var fixture = new Fixture(1, 20, 0)) {
            var worker = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            var process = fixture.process(worker, worker.slots, 1);
            worker.onAfter = () -> {
                throw new IllegalStateException("successor failed");
            };
            assertEquals("successor failed", assertThrows(IllegalStateException.class, fixture::tick).getMessage());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(1, worker.processPost.get());
            assertEquals(1, worker.sourceApplies.get());
            assertEquals(List.of("pre", "before", "after"), worker.events);
            worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
        }
    }

    @Test
    void unloadDuringPhysicalApplyWaitsForActualSourceExitAndCompensatesBeforeBoundary() throws Exception {
        for (var mode : List.of(ExecutionMode.CONCURRENT, ExecutionMode.PLANNED_PARALLEL)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var sourceExited = new AtomicBoolean();
            try (var fixture = new Fixture(1, 20, 3)) {
                var worker = fixture.worker(false, 1, mode, Layout.ORDINARY);
                worker.adapter = actual -> new ReservationAdapter(actual) {
                    @Override
                    public void apply() {
                        super.apply();
                        entered.countDown();
                        await(release);
                        sourceExited.set(true);
                    }
                };
                var process = fixture.process(worker, worker.slots, 1);
                var retire = new FutureTask<Void>(() -> {
                    await(entered);
                    worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                    assertEquals(MachineLifecycle.State.LOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
                    assertFalse(sourceExited.get());
                    assertThrows(IllegalStateException.class, () -> Objects.requireNonNull(worker.machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                    release.countDown();
                    return null;
                });
                Thread.ofPlatform().daemon().start(retire);
                try {
                    fixture.tick();
                    retire.get(5, TimeUnit.SECONDS);
                    assertTrue(sourceExited.get());
                    assertEquals(100, worker.energy.savedEnergy());
                    assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
                    assertEquals(1, worker.processPost.get());
                    assertEquals(List.of("pre", "before"), worker.events);
                    assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
                } finally {
                    release.countDown();
                    retire.get(5, TimeUnit.SECONDS);
                }
            } finally {
                release.countDown();
            }

        }
    }

    @Test
    void unknownCompletionRetainsOriginalCycleAndOldTickOnlyConfirmsWithoutSuccessorReplay() {
        for (var mode : List.of(ExecutionMode.CONCURRENT, ExecutionMode.PLANNED_PARALLEL)) {
            var proof = new AtomicBoolean();
            var completions = new AtomicInteger();
            try (var fixture = new Fixture(1, 20, 3)) {
                var worker = fixture.worker(false, 1, mode, Layout.ORDINARY);
                worker.adapter = actual -> new ReservationAdapter(actual) {
                    @Override
                    public void complete() {
                        completions.incrementAndGet();
                        super.complete();
                        throw new IllegalStateException("completion proof withheld");
                    }

                    @Override
                    public Resolution resolution() {
                        return proof.get() ? super.resolution() : Resolution.UNKNOWN;
                    }
                };
                fixture.process(worker, worker.slots, 1);
                assertEquals("completion proof withheld", assertThrows(IllegalStateException.class, fixture::tick).getMessage());
                assertFalse(fixture.scheduler.tryClose());
                assertEquals(0, worker.processPost.get());
                assertEquals(1, completions.get());
                assertThrows(IllegalStateException.class, worker.slots::snapshot);
                fixture.tick();
                assertEquals(1, completions.get(), "A newer server tick only queries the original uncertain source");
                assertEquals(1, worker.sourceApplies.get());
                assertEquals(0, worker.processPost.get());
                worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                assertEquals(MachineLifecycle.State.LOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
                proof.set(true);
                assertTrue(fixture.scheduler.tryClose());
                assertEquals(1, completions.get());
                assertEquals(1, worker.sourceApplies.get());
                assertEquals(1, worker.processPost.get());
                assertEquals(List.of("pre", "before"), worker.events);
                assertEquals(90, worker.energy.savedEnergy());
                assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(worker.machine.lifecycle()).state());
            } finally {
                proof.set(true);
            }

        }
    }

    @Test
    void independentMachinesKeepParallelSourceLanesAndRefreshOnlyAfterTheirActualExit() {
        var bothSources = new CountDownLatch(2);
        var activeSources = new AtomicInteger();
        try (var fixture = new Fixture(2, 20, 3)) {
            var first = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            var second = fixture.worker(false, 1, ExecutionMode.CONCURRENT, Layout.ORDINARY);
            while (sourceLane(first.energy) == sourceLane(second.energy)) {
                second.energy = new EnergyBuffer(100, () -> {
                });
                second.energy.receiveEnergy(100, Actionable.MODULATE);
                Objects.requireNonNull(second.machine.lifecycle()).form(new MachineHatches(List.of(), List.of(second.energy), List.of()));
            }
            fixture.onRefresh = () -> assertEquals(0, activeSources.get(), "Permission publication cannot refresh inside an active source scope");
            UnaryOperator<ResourceReservation> adapter = actual -> new ReservationAdapter(actual) {
                @Override
                public void apply() {
                    activeSources.incrementAndGet();
                    try {
                        bothSources.countDown();
                        await(bothSources);
                        super.apply();
                    } finally {
                        activeSources.decrementAndGet();
                    }
                }
            };
            first.adapter = adapter;
            second.adapter = adapter;
            var one = fixture.process(first, first.slots, 1);
            var two = fixture.process(second, second.slots, 1);
            fixture.tick();
            assertNotSame(first.sourceThread.get(), second.sourceThread.get());
            assertEquals(1, one.state().successfulTicks());
            assertEquals(1, two.state().successfulTicks());
            assertEquals(1, first.processPost.get());
            assertEquals(1, second.processPost.get());
            assertEquals(14, fixture.availableCompute(first));
        }
    }

    private enum Layout {ORDINARY, ALIAS, SECONDARY, FAIL_AFTER_DEFER}

    private static class OverlapComputation implements RecipePureComputation {
        private final CountDownLatch entered;
        private final List<Thread> threads;
        private boolean complete;

        private OverlapComputation(CountDownLatch entered, List<Thread> threads) {
            this.entered = entered;
            this.threads = threads;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            if (!budget.take()) {
                return false;
            }
            threads.add(Thread.currentThread());
            entered.countDown();
            await(entered);
            complete = true;
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Object world = new Object();
        private final UUID player = new UUID(0, 1);
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime network;
        private final TaskScheduler scheduler;
        private final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"),
            machine -> new MachineOwnership(machine, _ -> identities), List.of());
        private final ComponentType<MachineInstance, MachineNetworkDevice> role;
        private final ComponentType<MachineInstance, NetworkRecipeWork> work;
        private final Reference2ObjectOpenHashMap<MachineInstance, Worker> workers = new Reference2ObjectOpenHashMap<>();
        private final List<Worker> orderedWorkers = new ObjectArrayList<>();
        private final MachineRecipe recipe;
        private final RecipeCatalog catalog;
        private final MachineInstance center;
        private final MachineInstance computer;
        private final AtomicBoolean tickGate;
        private final CountDownLatch pureEntered = new CountDownLatch(2);
        private final List<Thread> pureThreads = Collections.synchronizedList(new ObjectArrayList<>());
        private long tick;
        private boolean tickOpen;
        private boolean tickExecuted;
        private int position;
        private Runnable onRefresh = () -> {
        };

        private Fixture(long threads, long compute, int duration) {
            this(threads, compute, duration, false);
        }

        private Fixture(long threads, long compute, int duration, boolean pure) {
            this(threads, compute, duration, pure, UnaryOperator.identity());
        }

        private Fixture(long threads, long compute, int duration, boolean pure, UnaryOperator<MachineRecipe> customize) {
            this(threads, compute, duration, pure, customize, new AtomicBoolean(true));
        }

        private Fixture(long threads, long compute, int duration, boolean pure, UnaryOperator<MachineRecipe> customize, AtomicBoolean tickGate) {
            this.tickGate = tickGate;
            var resources = new NetworkResourceRegistry();
            var research = new ResearchRegistry();
            resources.freeze();
            research.freeze(resources);
            network = new NetworkRuntime(identities, resources,
                identity -> OwnerTeamResolution.resolved(identity, "team", OwnerTeamType.SERVER, ObjectSets.singleton(identity), Optional.empty()), this::requireOwner);
            var factory = new MachineRecipeExecutionFactory(this::candidates, () -> this::refresh, () -> tick, () -> tickOpen && tickGate.get(), machine -> machine.components().assemblyBindings().stream()
                                                                                                                                                                   .anyMatch(binding -> binding.component() instanceof NetworkRecipeWork));
            scheduler = new TaskScheduler(owner, 2, 2, factory, Duration.ofSeconds(5));
            role = MachineNetworkDevice.component(id("role"), ownership,
                (machine, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DEVICE, 1,
                    Objects.requireNonNull(machine.lifecycle()).formed(), identity.loadOrder(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            var source = new RecipeRequirementSystem<String>() {
                @Override
                @SuppressWarnings("ReturnOfInnerClass") // Exercises a captured owner binding.
                public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<String> requirement, RecipeStage stage) {
                    if (!pure || stage != RecipeStage.START || process.probing()) {
                        return null;
                    }
                    var computation = new OverlapComputation(pureEntered, pureThreads);
                    return new RequirementPreparation() {
                        @Override
                        public RecipePureComputation computation() {
                            return computation;
                        }

                        @Override
                        public RequirementTransaction acquire() {
                            return start(process, requirement);
                        }
                    };
                }

                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                    requireOwner(); // This fixture also installs owner-only PN budget requirements.
                    var worker = Objects.requireNonNull(workers.get(process.owner()));
                    var token = Objects.requireNonNull(worker.energy.reserveExtractEnergy(worker.energy.energySnapshot(), 10).reservation());
                    if (!process.probing()) {
                        token = worker.adapter.apply(new ReservationAdapter(token) {
                            @Override
                            public void apply() {
                                worker.sourceThread.set(Thread.currentThread());
                                worker.sourceApplies.incrementAndGet();
                                super.apply();
                            }
                        });
                    } else {
                        token = new ReservationAdapter(token) {
                            @Override
                            public void apply() {
                                throw new AssertionError("Flat admission probe must not apply FE");
                            }

                            @Override
                            public void complete() {
                                throw new AssertionError("Admission probe must not complete FE");
                            }

                            @Override
                            public void rollback() {
                                worker.probeRollbackThread.set(Thread.currentThread());
                                worker.probeRollbacks.incrementAndGet();
                                super.rollback();
                                worker.onProbeRollback.run();
                            }
                        };
                    }
                    return new ReservedEnergy(token);
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                    requireOwner();
                    return RequirementTransaction.SUCCESS;
                }
            };
            var requirement = new RecipeRequirementType<>(id("source"), source).requirement(id("source"), "energy");
            recipe = customize.apply(new MachineRecipe(id("recipe"), duration, List.of(requirement)));
            catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "network_execution_test.pool", List.of(recipe))));
            var extensions = new NetworkRecipeExtensions();
            extensions.publish(catalog, research, resources);
            work = NetworkRecipeWork.component(id("work"), role,
                NetworkWorkRequirements.builder().resource(NetworkResources.COMPUTE, 3).build(), extensions, research, resources);
            network.beginTick(tick);
            var supply = new Object2LongOpenHashMap<NetworkResourceType>();
            supply.put(NetworkResources.THREAD, threads);
            supply.put(NetworkResources.COMPUTE, compute);
            var centerRole = MachineNetworkDevice.component(id("center_role"), ownership,
                (machine, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1,
                    Objects.requireNonNull(machine.lifecycle()).formed(), identity.loadOrder(), Object2LongMaps.singleton(NetworkResources.THREAD, 1), NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            var type = type("center").component(ownership).component(centerRole).build();
            center = MachineAssembly.builder(type, "center-v1").component(ownership, MachineOwnership.CODEC)
                                    .recreated(centerRole).build().create(host(type.id()), new MachineLifecycleRegistry());
            Objects.requireNonNull(center.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            var components = new NetworkComputerComponentRegistry();
            var computeCard = ComputerSupplyFixtures.key("compute_card");
            var threadCard = ComputerSupplyFixtures.key("thread_card");
            ComputerSupplyFixtures.fixed(components, computeCard, Object2LongMaps.singleton(NetworkResources.COMPUTE, supply.getLong(NetworkResources.COMPUTE)));
            ComputerSupplyFixtures.fixed(components, threadCard, Object2LongMaps.singleton(NetworkResources.THREAD, 1));
            components.freeze(resources);
            var installed = MachineComputerComponents.component(id("computer_supply"), components, ComputerSupplyFixtures::warehouses);
            var computerRole = MachineNetworkDevice.capturedComponent(id("computer_role"), ownership,
                (machine, identity) -> Objects.requireNonNull(machine.components().get(installed)).networkCapture(identity, 1,
                    Objects.requireNonNull(machine.lifecycle()).formed(), Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
            var computerType = type("computer").component(ownership).component(installed).component(computerRole).build();
            computer = MachineAssembly.builder(computerType, "computer-v1").component(ownership, MachineOwnership.CODEC)
                                      .recreated(installed, computerRole).build().create(host(computerType.id()), new MachineLifecycleRegistry());
            var warehouse = new BufferedResourceHatch(HatchMode.INPUT, 2, Reference2LongMaps.singleton(ComputerSupplyFixtures.TYPE, Long.MAX_VALUE), () -> {
            });
            var inventory = Objects.requireNonNull(warehouse.input());
            assertEquals(1, inventory.insert(computeCard, 1, Actionable.MODULATE));
            assertEquals(threads, inventory.insert(threadCard, threads, Actionable.MODULATE));
            Objects.requireNonNull(computer.lifecycle()).form(new MachineHatches(List.of(warehouse), List.of(), List.of()));
        }

        private List<TaskScheduler.PlannedCandidate> candidates() {
            return scheduler.plannedCandidates();
        }

        private void refresh() {
            onRefresh.run();
            network.refresh();
        }

        private Worker worker(boolean scanning, int capacity, ExecutionMode mode, Layout layout) {
            return worker(scanning, capacity, mode, layout, MachineInstance::new, false, null);
        }

        private Worker worker(boolean scanning, int capacity, ExecutionMode mode, Layout layout,
                              Function<MachineType, MachineInstance> instanceFactory, boolean defaultScanner, ControllerExecutionPolicy policy) {
            var events = new ObjectArrayList<String>();
            var before = new ComponentType<>(id("before"), _ -> events, List.of(new MachineSystem<ObjectArrayList<String>>() {
                @Override
                public ResourceLocation id() {
                    return NetworkRecipeExecutionTest.id("before_system");
                }

                @Override
                public Set<ResourceLocation> runBefore() {
                    return ObjectSets.singleton(SCANNING_ID);
                }

                @Override
                public void onPreTick(MachineInstance machine, ObjectArrayList<String> component) {
                    requireWorkThread(machine);
                    component.add("pre");
                    Objects.requireNonNull(workers.get(machine)).onPre.run();
                }

                @Override
                public void onTick(MachineInstance machine, ObjectArrayList<String> component) {
                    requireWorkThread(machine);
                    component.add("before");
                }

                @Override
                public void onPostTick(MachineInstance machine, ObjectArrayList<String> component) {
                    requireWorkThread(machine);
                    component.add("post");
                }
            }));
            var after = new ComponentType<>(id("after"), _ -> events, List.of(new MachineSystem<ObjectArrayList<String>>() {
                @Override
                public ResourceLocation id() {
                    return NetworkRecipeExecutionTest.id("after_system");
                }

                @Override
                public Set<ResourceLocation> runAfter() {
                    return ObjectSets.singleton(WorkSlotProcessingSystem.ID);
                }

                @Override
                public void onTick(MachineInstance machine, ObjectArrayList<String> component) {
                    requireWorkThread(machine);
                    component.add("after");
                    Objects.requireNonNull(workers.get(machine)).onAfter.run();
                }
            }));
            var scan = defaultScanner ? RecipeScanning.component(id("scanner"), () -> catalog, new ScanLimits(10, 10))
                : RecipeScanning.component(id("scanner"), slots -> new RecipeScanner(slots, catalog,
                (machine, definition, seed, tuning) -> track(new RecipeProcess(machine, definition, seed, tuning)),
                List.of(), List.of(), _ -> false, () -> 7), new ScanLimits(10, 10), _ -> true);
            var extra = new ComponentType<>(id("extra_slots"), machine -> layout == Layout.ALIAS
                ? Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE)) : new MachineWorkSlots(machine, List.of()),
                MachineWorkSlots.TYPE.systems(), List.of(MachineWorkSlots.TYPE));
            var failing = new ComponentType<>(id("failing"), _ -> new Object(), List.of(new MachineSystem<>() {
                @Override
                public ResourceLocation id() {
                    return NetworkRecipeExecutionTest.id("failing_system");
                }

                @Override
                public Set<ResourceLocation> runBefore() {
                    return ObjectSets.singleton(WorkSlotProcessingSystem.ID);
                }

                @Override
                public void onTick(MachineInstance machine, Object component) {
                    assertTrue(Objects.requireNonNull(machine.lifecycle()).deferRecipeWork(Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE))));
                    throw new IllegalStateException("failure after exact deferral");
                }
            }));
            var builder = type("worker_" + position).component(ownership).component(role).component(work).component(before)
                                                    .component(MachineWorkSlots.TYPE).component(after);
            if (mode != null) builder.executionMode(mode);
            if (policy != null) builder.executionPolicy(policy);
            for (int index = 0; index < capacity; index++) {
                builder.workSlot(new WorkSlotDefinition("slot_" + index));
            }
            if (scanning) {
                builder.recipePool(id("pool")).component(scan);
            }
            if (layout == Layout.ALIAS || layout == Layout.SECONDARY) {
                builder.component(extra);
            }
            if (layout == Layout.FAIL_AFTER_DEFER) {
                builder.component(failing);
            }
            var type = builder.build();
            var codec = new MachineWorkSlotsNbt("slots-v1", List.of(RecipeProcessNbt.builder(recipe, "recipe-v1").build()));
            var assembly = MachineAssembly.builder(type, "worker-v1").instanceFactory(instanceFactory).component(ownership, MachineOwnership.CODEC)
                                          .recreated(role, work, before, after).workSlots(codec);
            if (scanning) {
                assembly.recreated(scan);
            }
            if (layout == Layout.ALIAS) {
                assembly.recreated(extra);
            }
            if (layout == Layout.SECONDARY) {
                assembly.component(extra, new MachineComponentCodec<>() {
                    @Override
                    public NBTTagCompound encode(MachineInstance machine, MachineWorkSlots slots) {
                        return codec.encode(slots);
                    }

                    @Override
                    public MachineWorkSlots restore(MachineInstance machine, MachineWorkSlots slots, NBTTagCompound saved) {
                        codec.restore(slots, saved);
                        return slots;
                    }
                });
            }
            if (layout == Layout.FAIL_AFTER_DEFER) {
                assembly.recreated(failing);
            }
            var controller = new MachineController(assembly.build(), host(type.id()), scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            var machine = Objects.requireNonNull(controller.machine());
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, capacity);
            var energy = new EnergyBuffer(100, () -> {
            });
            energy.receiveEnergy(100, Actionable.MODULATE);
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(energy), List.of()));
            var worker = new Worker(controller, machine, Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE)),
                layout == Layout.SECONDARY ? machine.components().get(extra) : null, energy, events);
            workers.put(machine, worker);
            orderedWorkers.add(worker);
            assertTrue(network.refresh());
            return worker;
        }

        private RecipeProcess process(Worker worker, MachineWorkSlots slots, int parallelism) {
            var process = track(new RecipeProcess(worker.machine, recipe, 7));
            process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
            assertTrue(slots.start(process));
            return process;
        }

        private RecipeProcess track(RecipeProcess process) {
            var worker = Objects.requireNonNull(workers.get(process.owner()));
            worker.processes.add(process);
            process.components().create(new ComponentType<>(id("process_hooks"), _ -> new Object(), List.of(new ComponentSystem<>() {
                @Override
                public ResourceLocation id() {
                    return NetworkRecipeExecutionTest.id("process_hooks");
                }

                @Override
                public void onPreTick(RecipeProcess ownerProcess, Object component) {
                    requireOwner();
                    worker.processPre.incrementAndGet();
                }

                @Override
                public void onPostTick(RecipeProcess ownerProcess, Object component) {
                    requireOwner();
                    worker.processPost.incrementAndGet();
                }
            })), process);
            return process;
        }

        private long availableCompute(Worker worker) {
            return permit(worker).availableResources().getLong(NetworkResources.COMPUTE);
        }

        private NetworkRuntime.WorkPermit permit(Worker worker) {
            return Objects.requireNonNull(Objects.requireNonNull(worker.machine.components().get(role)).workPermit());
        }

        private void tick() {
            if (tickExecuted) {
                network.endTick();
                network.beginTick(++tick);
            }
            tickExecuted = true;
            tickOpen = true;
            tickGate.set(true);
            try {
                scheduler.tick(this::refresh);
            } finally {
                tickOpen = false;
            }
        }

        private Host host(ResourceLocation type) {
            return new Host(this, type, new BlockPos(position++, 0, 0));
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Not the server owner");
            }
        }

        private void requireWorkThread(MachineInstance machine) {
            var policy = machine.executionPolicy();
            if (policy != null && policy.workAsync()) assertNotSame(owner, Thread.currentThread());
            else requireOwner();
        }

        @Override
        public void close() {
            for (var worker : orderedWorkers) {
                worker.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
            scheduler.close();
            Objects.requireNonNull(center.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            Objects.requireNonNull(computer.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            network.close();
            identities.close();
        }
    }

    private static final class Worker {
        private final MachineController controller;
        private final MachineInstance machine;
        private final MachineWorkSlots slots;
        private final MachineWorkSlots secondary;
        private final ObjectArrayList<String> events;
        private final List<RecipeProcess> processes = new ObjectArrayList<>();
        private final AtomicInteger processPre = new AtomicInteger();
        private final AtomicInteger processPost = new AtomicInteger();
        private final AtomicInteger sourceApplies = new AtomicInteger();
        private final AtomicReference<Thread> sourceThread = new AtomicReference<>();
        private final AtomicInteger probeRollbacks = new AtomicInteger();
        private final AtomicReference<Thread> probeRollbackThread = new AtomicReference<>();
        private Runnable onProbeRollback = () -> {
        };
        private EnergyBuffer energy;
        private Runnable onPre = () -> {
        };
        private Runnable onAfter = () -> {
        };
        private UnaryOperator<ResourceReservation> adapter = UnaryOperator.identity();

        private Worker(MachineController controller, MachineInstance machine, MachineWorkSlots slots, MachineWorkSlots secondary,
                       EnergyBuffer energy, ObjectArrayList<String> events) {
            this.controller = controller;
            this.machine = machine;
            this.slots = slots;
            this.secondary = secondary;
            this.energy = energy;
            this.events = events;
        }
    }

    private record Host(Fixture fixture, ResourceLocation type, BlockPos position) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, position);
        }

        @Override
        public Object worldIdentity() {
            return fixture.world;
        }

        @Override
        public UUID placementPlayer() {
            return fixture.player;
        }

        @Override
        public World world() {
            throw new AssertionError("Pure library test must not initialize a Minecraft world");
        }

        @Override
        public void requireServerThread() {
            fixture.requireOwner();
        }
    }

    private record ReservedEnergy(ResourceReservation token) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<ResourceReservation> reservations() {
            return List.of(token);
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private static class ReservationAdapter implements ResourceReservation {
        private final ResourceReservation actual;

        private ReservationAdapter(ResourceReservation actual) {
            this.actual = actual;
        }

        @Override
        public StorageContext context() {
            return actual.context();
        }

        @Override
        public boolean canApply() {
            return actual.canApply();
        }

        @Override
        public void apply() {
            actual.apply();
        }

        @Override
        public void complete() {
            actual.complete();
        }

        @Override
        public void rollback() {
            actual.rollback();
        }

        @Override
        public Resolution resolution() {
            return actual.resolution();
        }
    }
}
