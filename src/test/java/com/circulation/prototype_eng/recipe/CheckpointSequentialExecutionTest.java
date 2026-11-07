package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointSequentialExecutionTest {
    private static final KeyType TYPE = new KeyType("checkpoint_sequence");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");

    @Test void aFullEnergyBufferFundsChildStartAndTickBeforeChildEndRefillsIt() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var process = process(machine(null, energy), 0,
            checkpoint("energy_cycle", 0, energy("child_energy", 40, 10, 0, 50)));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(100, energy.savedEnergy());
        assertEquals(100, energy.energySnapshot().availableEnergy());
        assertEquals(0, energy.energySnapshot().availableCapacity());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(100, energy.savedEnergy(), "A completed checkpoint cannot replay its lifecycle");
    }

    @Test void startProbeObservesTheSequentialCycleThenRefundsItBeforeRealExecution() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var process = process(machine(storage, energy), 0,
            checkpoint("probe_energy", 0, energy("probe_child_energy", 40, 10, 0, 50)),
            checkpoint("probe_resource", 0, resources("probe_child_resource", List.of(stack(MATERIAL, 100)),
                List.of(), List.of(stack(PRODUCT, 25)), List.of(stack(PRODUCT, 75)))));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertEquals(100, energy.savedEnergy());
        assertEquals(new GenericStack(MATERIAL, 100), storage.getStack(0));
        assertEquals(100, storage.availableAmount(MATERIAL));
        assertEquals(0, storage.availableAmount(PRODUCT));
        process.requirePersistenceBoundary();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(100, energy.savedEnergy());
        assertEquals(new GenericStack(PRODUCT, 100), storage.getStack(0));
    }

    @Test void aFullResourceSlotIsConsumedThenReassignedByChildTickAndEndOutputs() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var child = resources("child_resource", List.of(stack(MATERIAL, 100)), List.of(),
            List.of(stack(PRODUCT, 25)), List.of(stack(PRODUCT, 75)));
        var process = process(machine(storage, null), 0, checkpoint("resource_cycle", 0, child));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(new GenericStack(PRODUCT, 100), storage.getStack(0));
        assertEquals(0, storage.amount(MATERIAL));
        assertEquals(100, storage.availableAmount(PRODUCT));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, process.state().progress());
        assertEquals(0, process.state().successfulTicks());
    }

    @Test void aPrecedingCheckpointOutputFeedsALaterCheckpointInputOnTheSamePhysicalSources() {
        var storage = storage();
        var energy = new EnergyBuffer(100, () -> {});
        var process = process(machine(storage, energy), 0,
            checkpoint("produce_energy", 0, energy("energy_output", 0, 0, 20, 80)),
            checkpoint("consume_energy", 0, energy("energy_input", 70, 10, 0, 0)),
            checkpoint("produce_material", 0, resources("material_output", List.of(), List.of(),
                List.of(stack(PRODUCT, 25)), List.of(stack(PRODUCT, 75)))),
            checkpoint("consume_material", 0, resources("material_input", List.of(stack(PRODUCT, 70)),
                List.of(stack(PRODUCT, 10)), List.of(), List.of())));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(20, energy.savedEnergy());
        assertEquals(20, energy.energySnapshot().availableEnergy());
        assertEquals(20, storage.availableAmount(PRODUCT));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
    }

    @Test void aLaterOuterBlockRefundsTheCurrentCheckpointWhileKeepingHistoricalSuccessfulStages() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        boolean[] blocked = {true};
        var rejection = new ProcessResult.Blocked("outer successor rejected");
        var process = process(machine(storage, energy), 3,
            checkpoint("second_tick_energy", 2, energy("child_energy", 40, 5, 0, 10)),
            checkpoint("second_tick_material", 2, resources("child_material", List.of(stack(MATERIAL, 50)),
                List.of(), List.of(), List.of(stack(MATERIAL, 30)))),
            energy("historical_energy", 10, 10, 0, 0),
            resources("historical_material", List.of(stack(MATERIAL, 20)), List.of(stack(MATERIAL, 5)), List.of(), List.of()),
            external("outer_block", (current, phase) -> {
                if (phase == RecipeStage.TICK && current.tickContext().indexAfter() == 2 && blocked[0]) {
                    assertEquals(45, energy.savedEnergy(), "The child energy effects precede the outer successor");
                    assertEquals(55, storage.amount(MATERIAL), "The child resource effects precede the outer successor");
                    return rejected(rejection);
                }
                return RequirementTransaction.SUCCESS;
            }));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, process.state().progress());
        assertEquals(1, process.state().successfulTicks());
        assertEquals(80, energy.savedEnergy());
        assertEquals(75, storage.availableAmount(MATERIAL));
        assertSame(rejection, process.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(1, process.state().progress());
        assertEquals(1, process.state().successfulTicks());
        assertEquals(80, energy.savedEnergy(), "Committed START and first TICK consumption remain spent");
        assertEquals(80, energy.energySnapshot().availableEnergy());
        assertEquals(75, storage.availableAmount(MATERIAL));
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(2, process.state().progress());
        assertEquals(2, process.state().successfulTicks());
        assertEquals(35, energy.savedEnergy());
        assertEquals(50, storage.availableAmount(MATERIAL));
    }

    @Test void realPlannedSourceLanesRunEachChildEnergyTokenOnceAcrossAliasHatchesAndRefillBothBuffers() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var observations = new SourceObservations();
        var machine = machine(storage, energy, observations);
        var host = new MachineWorkSlots(machine, List.of());
        var process = process(machine, 1,
            checkpoint("energy_cycle", 0, energy("child_energy", 60, 10, 0, 70)),
            checkpoint("resource_cycle", 0, resources("child_resource", List.of(stack(MATERIAL, 100)), List.of(),
                List.of(stack(PRODUCT, 25)), List.of(stack(PRODUCT, 75)))));
        assertTrue(host.start(process));
        Thread owner = Thread.currentThread();
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(),
                new PlannedCancellationToken(), () -> true);
            assertTrue(coordinator.advance(deadline()));
            assertTrue(coordinator.finished());
            assertNull(coordinator.failure());
            assertNull(coordinator.stopReason());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(1, process.state().progress());
            assertEquals(1, process.state().successfulTicks());
            assertNull(process.host());
            assertEquals(100, energy.savedEnergy());
            assertEquals(new GenericStack(PRODUCT, 100), storage.getStack(0));
            assertEquals(3, observations.applies.size());
            assertEquals(3, observations.exposures.get());
            assertEquals(3, observations.completions.get());
            assertEquals(0, observations.rollbacks.get());
            var lane = observations.applies.peek();
            assertNotNull(lane);
            assertNotSame(owner, lane);
            assertTrue(observations.applies.stream().allMatch(thread -> thread == lane));
            assertTrue(coordinator.advance(deadline()));
            assertEquals(3, observations.applies.size(), "Finished source actions cannot replay");
        }
    }

    @Test void aPlannedOuterBlockCompensatesAllStagedChildrenOnTheirSourceLaneWithoutAdvancingTheParent() {
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var observations = new SourceObservations();
        var machine = machine(storage, energy, observations);
        var host = new MachineWorkSlots(machine, List.of());
        var rejection = new ProcessResult.Blocked("planned outer successor rejected");
        var process = process(machine, 1,
            checkpoint("energy_cycle", 0, energy("child_energy", 60, 10, 0, 20)),
            checkpoint("resource_cycle", 0, resources("child_resource", List.of(stack(MATERIAL, 100)), List.of(),
                List.of(stack(PRODUCT, 25)), List.of(stack(PRODUCT, 35)))),
            external("block_start", (_, phase) -> {
                if (phase != RecipeStage.START) { return RequirementTransaction.SUCCESS; }
                assertEquals(50, energy.savedEnergy());
                assertEquals(60, storage.availableAmount(PRODUCT));
                return rejected(rejection);
            }));
        assertTrue(host.start(process));
        try (var backend = new Backend()) {
            var coordinator = new PlannedRecipeCoordinator(backend.tasks, List.of(host), deadline(),
                new PlannedCancellationToken(), () -> true);
            assertTrue(coordinator.advance(deadline()));
            assertTrue(coordinator.finished());
            assertNull(coordinator.failure());
            assertSame(rejection, process.state().result());
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(0, process.state().progress());
            assertEquals(0, process.state().successfulTicks());
            assertEquals(100, energy.savedEnergy());
            assertEquals(100, energy.energySnapshot().availableEnergy());
            assertEquals(new GenericStack(MATERIAL, 100), storage.getStack(0));
            assertEquals(0, storage.amount(PRODUCT));
            assertEquals(3, observations.applies.size());
            assertEquals(3, observations.rollbacks.get());
            assertEquals(0, observations.completions.get());
            assertSame(host, process.host());
            host.snapshot();
        }
    }

    @Test void aliasesDoNotMultiplyTheAvailableEnergyOrResourceQuantityDuringChildAdmission() {
        var energy = new EnergyBuffer(100, () -> {});
        energy.receiveEnergy(100, Actionable.MODULATE);
        var energyProcess = process(machine(null, energy), 0,
            checkpoint("oversized_energy", 0, energy("child_energy", 150, 0, 0, 0)));
        assertInstanceOf(ProcessResult.Blocked.class, energyProcess.tick());
        assertEquals(100, energy.savedEnergy());
        assertEquals(100, energy.energySnapshot().availableEnergy());
        assertEquals(RecipeProcess.Lifecycle.NEW, energyProcess.state().lifecycle());
        var storage = storage();
        storage.insert(MATERIAL, 100, Actionable.MODULATE);
        var resourceProcess = process(machine(storage, null), 0,
            checkpoint("oversized_material", 0, resources("child_material", List.of(stack(MATERIAL, 150)),
                List.of(), List.of(), List.of())));
        assertInstanceOf(ProcessResult.Blocked.class, resourceProcess.tick());
        assertEquals(100, storage.availableAmount(MATERIAL));
        assertEquals(RecipeProcess.Lifecycle.NEW, resourceProcess.state().lifecycle());
    }

    private static RecipeRequirement<CheckpointRequirement> checkpoint(String name, int time, RecipeRequirement<?> child) {
        return RecipeRequirements.CHECKPOINT.requirement(id(name), new CheckpointRequirement(time, child));
    }

    private static RecipeRequirement<EnergyRequirement> energy(String name, long input, long tickInput, long tickOutput, long output) {
        return RecipeRequirements.ENERGY.requirement(id(name), new EnergyRequirement(input, tickInput, tickOutput, output, false));
    }

    private static RecipeRequirement<ResourceRequirement> resources(String name, List<GenericStack> input,
            List<GenericStack> tickInput, List<GenericStack> tickOutput, List<GenericStack> output) {
        return RecipeRequirements.RESOURCES.requirement(id(name), new ResourceRequirement(input, tickInput, tickOutput, output, false));
    }

    private static GenericStack stack(Key key, long amount) { return new GenericStack(key, amount); }

    private static RecipeRequirement<String> external(String name,
            BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquisition) {
        var typeId = new ResourceLocation("zz_checkpoint_sequence", name);
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(typeId, system).requirement(id(name), "observer");
    }

    private static RequirementTransaction rejected(ProcessResult result) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return result; }
            @Override public void commit() { fail("Blocked outer successor cannot commit"); }
            @Override public void rollback() { }
        };
    }

    private static MachineInstance machine(SlottedResourceBuffer storage, EnergyBuffer energy) {
        return machine(storage, energy, null);
    }

    private static MachineInstance machine(SlottedResourceBuffer storage, EnergyBuffer energy, SourceObservations observations) {
        var machine = new MachineInstance();
        List<ResourceHatch> resources = storage == null ? List.of() : List.of(new LoopHatch(storage), new LoopHatch(storage));
        List<EnergyHatch> energies = energy == null ? List.of() : observations == null ? List.of(energy, energy)
            : List.of(new ObservedEnergy(energy, observations), new ObservedEnergy(energy, observations));
        machine.components().put(MachineHatches.TYPE, new MachineHatches(resources, energies, energies));
        return machine;
    }

    private static RecipeProcess process(MachineInstance machine, int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(machine, new MachineRecipe(id("recipe"), duration, List.of(requirements)), 71);
    }

    private static SlottedResourceBuffer storage() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100L);
        return new SlottedResourceBuffer(1, capacities, () -> {});
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("checkpoint_sequence_test", name); }
    private static PlannedDeadline deadline() { return PlannedDeadline.after(Duration.ofSeconds(10)); }

    private record LoopHatch(SlottedResourceBuffer storage) implements ResourceHatch {
        @Override public SlottedResourceBuffer input() { return storage; }
        @Override public SlottedResourceBuffer output() { return storage; }
        @Override public boolean isValid() { return storage.isValid(); }
    }

    private static final class SourceObservations {
        private final ConcurrentLinkedQueue<Thread> applies = new ConcurrentLinkedQueue<>();
        private final AtomicInteger exposures = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
    }

    private record ObservedEnergy(EnergyBuffer delegate, SourceObservations observations) implements EnergyHatch {
        @Override public StorageContext context() { return delegate.context(); }
        @Override public EnergySnapshot energySnapshot() { return delegate.energySnapshot(); }
        @Override public long storedEnergy() { return delegate.storedEnergy(); }
        @Override public long capacity() { return delegate.capacity(); }
        @Override public long extractEnergy(long amount, Actionable mode) { return delegate.extractEnergy(amount, mode); }
        @Override public long receiveEnergy(long amount, Actionable mode) { return delegate.receiveEnergy(amount, mode); }
        @Override public boolean isValid() { return delegate.isValid(); }
        @Override public ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount) {
            return observed(delegate.reserveExtractEnergy(snapshot, amount));
        }
        @Override public ReservationResult reserveReceiveEnergy(EnergySnapshot snapshot, long amount) {
            return observed(delegate.reserveReceiveEnergy(snapshot, amount));
        }
        private ReservationResult observed(ReservationResult reserved) {
            if (reserved.status() != ReservationResult.Status.ACCEPTED) { return reserved; }
            var token = Objects.requireNonNull(reserved.reservation());
            return new ReservationResult(reserved.status(), new ResourceReservation() {
                @Override public StorageContext context() { return token.context(); }
                @Override public boolean canApply() { return token.canApply(); }
                @Override public void apply() { observations.applies.add(Thread.currentThread()); token.apply(); }
                @Override public boolean supportsSequentialUse() { return token.supportsSequentialUse(); }
                @Override public void exposeApplied() { observations.exposures.incrementAndGet(); token.exposeApplied(); }
                @Override public void complete() { assertOriginalLane(); observations.completions.incrementAndGet(); token.complete(); }
                @Override public void rollback() { assertOriginalLane(); observations.rollbacks.incrementAndGet(); token.rollback(); }
                @Override public Resolution resolution() { return token.resolution(); }
                private void assertOriginalLane() { assertSame(observations.applies.peek(), Thread.currentThread()); }
            });
        }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
        @Override public void close() {
            workers.shutdownNow();
            lane.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}
