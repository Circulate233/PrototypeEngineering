package com.circulation.prototype_eng.scheduler.planned;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class PlannedMaterialCycleCoordinatorTest {
    @Test void realDetachedCycleCompletesStartTickEndOnCapturedHatchesWithOwnerGrantsAndOnePost() {
        var fixture = new Fixture();
        fixture.machine.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
        var grant = new Effect();
        var completion = new Effect();
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> {
                assertEquals(1, fixture.acquired.getInt(stage.phase()));
                assertThrows(NullPointerException.class, fixture.process::tickContext);
                if (stage.phase() != RecipeStage.END) { return List.of(); }
                grant.onCommit = () -> {
                    assertSame(fixture.owner, Thread.currentThread());
                    assertEquals(1, fixture.committed.getInt(RecipeStage.END));
                    assertEquals(90, fixture.energy.savedEnergy());
                };
                return List.of(grant, completion);
            });
            assertEquals(0, fixture.pre.get(), "Construction must not acquire a TickSession or call participants");
            fixture.machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy()), List.of()));
            assertTrue(coordinator.advance(deadline()));
            assertNull(fixture.process.host());
            assertSame(fixture.machine, fixture.process.owner());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, fixture.process.state().lifecycle());
            assertEquals(1, fixture.process.state().successfulTicks());
            assertEquals(0, fixture.process.state().progress());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), fixture.phases);
            assertEquals(1, fixture.pre.get());
            assertEquals(1, fixture.post.get());
            assertEquals(1, grant.commits);
            assertEquals(1, completion.completes);
            assertNotSame(fixture.owner, fixture.sourceThread.get());
            assertEquals(90, fixture.energy.savedEnergy());
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, fixture.post.get());
            fixture.process.requirePersistenceBoundary();
        }
    }

    @Test void blockedEndRollsBackItsMaterialsNormallyAndNextCycleOnlyRetriesEnd() {
        var fixture = new Fixture();
        var hookCalls = new Object2IntOpenHashMap<RecipeStage>();
        try (var backend = new Backend()) {
            var blocked = fixture.coordinator(backend, stage -> {
                hookCalls.addTo(stage.phase(), 1);
                return stage.phase() == RecipeStage.END ? null : List.of();
            });
            assertTrue(blocked.advance(deadline()));
            assertNull(blocked.failure());
            assertEquals(95, fixture.energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.RUNNING, fixture.process.state().lifecycle());
            assertInstanceOf(ProcessResult.Blocked.class, fixture.process.state().result());
            assertEquals(1, fixture.process.state().successfulTicks());
            assertEquals(1, fixture.post.get());
            var grant = new Effect();
            var retried = fixture.coordinator(backend, stage -> {
                assertEquals(RecipeStage.END, stage.phase());
                hookCalls.addTo(stage.phase(), 1);
                return List.of(grant);
            });
            assertTrue(retried.advance(deadline()));
            assertEquals(1, hookCalls.getInt(RecipeStage.START));
            assertEquals(1, hookCalls.getInt(RecipeStage.TICK));
            assertEquals(2, hookCalls.getInt(RecipeStage.END));
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, fixture.process.state().lifecycle());
            assertEquals(90, fixture.energy.savedEnergy());
            assertEquals(2, fixture.post.get());
        }
    }

    @Test void busyBindingKeepsTheOriginalEndGrantUntilOneCombinedRollbackWithoutReacquisition() {
        var fixture = new Fixture();
        var grant = new Effect();
        var captures = new AtomicInteger();
        var busy = new AtomicReference<StorageFence>();
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> {
                if (stage.phase() != RecipeStage.END) { return List.of(); }
                captures.incrementAndGet();
                busy.set(Objects.requireNonNull(StorageFence.tryAcquire(List.of(fixture.energy.context()))));
                return List.of(grant);
            });
            try {
                assertFalse(coordinator.advance(deadline()));
                assertEquals(1, captures.get());
                assertEquals(0, grant.rollbacks);
                assertEquals(0, fixture.post.get());
                assertThrows(IllegalStateException.class, fixture.process::requirePersistenceBoundary);
                busy.getAndSet(null).release();
                assertTrue(coordinator.advance(deadline()));
                assertEquals(1, captures.get());
                assertEquals(0, grant.commits);
                assertEquals(1, grant.rollbacks);
                assertEquals(1, fixture.rolledBack.getInt(RecipeStage.END));
                assertEquals(95, fixture.energy.savedEnergy());
                assertEquals(1, fixture.post.get());
            } finally { if (busy.get() != null) { busy.get().release(); } }
        }
    }

    @Test void cancellationAfterEndAcquisitionRollsBackTheExactReturnedGrantWithoutPhysicalApply() {
        var fixture = new Fixture();
        var grant = new Effect();
        var captures = new AtomicInteger();
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> {
                if (stage.phase() != RecipeStage.END) { return List.of(); }
                captures.incrementAndGet();
                fixture.token.cancel(Reason.EXPLICIT_CANCEL);
                return List.of(grant);
            });
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, captures.get());
            assertEquals(0, grant.commits);
            assertEquals(1, grant.rollbacks);
            assertEquals(95, fixture.energy.savedEnergy());
            assertEquals(2, fixture.sourceApplies.get());
            assertEquals(1, fixture.post.get());
        }
    }

    @Test void completionCommitFailureCompensatesGrantAndEndRequirementsInReverseOrder() {
        var fixture = new Fixture();
        var order = new ObjectArrayList<String>();
        var grant = new Effect();
        var completion = new Effect();
        grant.onRollback = () -> order.add("grant");
        completion.onCommit = () -> { throw new IllegalStateException("completion rejected"); };
        completion.onRollback = () -> order.add("completion");
        fixture.onRequirementRollback = phase -> { if (phase == RecipeStage.END) { order.add("material"); } };
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> stage.phase() == RecipeStage.END ? List.of(grant, completion) : List.of());
            assertEquals("completion rejected", assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())).getMessage());
            assertTrue(coordinator.finished());
            assertEquals(List.of("completion", "grant", "material"), order);
            assertEquals(0, grant.value);
            assertEquals(0, completion.value);
            assertEquals(95, fixture.energy.savedEnergy());
            assertEquals(1, fixture.post.get());
        }
    }

    @Test void unknownGrantCompletionPinsEndAndPostUntilExactProofWithoutCommitOrCompleteReplay() {
        var fixture = new Fixture();
        var proof = new AtomicBoolean();
        Effect grant = new Effect() {
            @Override public void complete() { super.complete(); throw new IllegalStateException("grant complete unknown"); }
            @Override public Resolution resolution() { return proof.get() ? super.resolution() : Resolution.UNKNOWN; }
        };
        var captures = new AtomicInteger();
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> {
                if (stage.phase() != RecipeStage.END) { return List.of(); }
                captures.incrementAndGet(); return List.of(grant);
            });
            assertEquals("grant complete unknown", assertThrows(IllegalStateException.class, () -> coordinator.advance(deadline())).getMessage());
            assertFalse(coordinator.finished());
            assertEquals(0, fixture.post.get());
            assertFalse(coordinator.advance(deadline()));
            assertEquals(1, captures.get());
            assertEquals(1, grant.commits);
            assertEquals(1, grant.completes);
            assertEquals(0, grant.rollbacks);
            proof.set(true);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, grant.completes);
            assertEquals(1, fixture.post.get());
            assertEquals(90, fixture.energy.savedEnergy());
        }
    }

    @Test void unknownGrantRollbackRetainsExactBatchUntilProofAndNeverRepeatsItsMutation() {
        var fixture = new Fixture();
        var proof = new AtomicBoolean();
        Effect grant = new Effect() {
            @Override public void rollback() { super.rollback(); throw new IllegalStateException("grant rollback unknown"); }
            @Override public Resolution resolution() { return proof.get() ? super.resolution() : Resolution.UNKNOWN; }
        };
        grant.onCommit = () -> { throw new IllegalArgumentException("grant rejected"); };
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> stage.phase() == RecipeStage.END ? List.of(grant) : List.of());
            assertEquals("grant rejected", assertThrows(IllegalArgumentException.class, () -> coordinator.advance(deadline())).getMessage());
            assertFalse(coordinator.finished());
            assertFalse(coordinator.advance(deadline()));
            assertEquals(1, grant.rollbacks);
            assertEquals(0, grant.value);
            assertEquals(0, fixture.post.get());
            proof.set(true);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, grant.rollbacks);
            assertEquals(95, fixture.energy.savedEnergy());
            assertEquals(1, fixture.post.get());
        }
    }

    @Test void failedBindingRetainsAdditionalAcquisitionAndCleansItBeforeLocalStageFallback() {
        var fixture = new Fixture();
        var proof = new AtomicBoolean();
        var contextReads = new AtomicInteger();
        Effect grant = new Effect() {
            @Override public void rollback() { super.rollback(); throw new IllegalStateException("local grant unknown"); }
            @Override public Resolution resolution() { return proof.get() ? super.resolution() : Resolution.UNKNOWN; }
        };
        fixture.endAdapter = actual -> new ReservationAdapter(actual) {
            @Override public StorageContext context() {
                if (contextReads.incrementAndGet() == 1) { throw new IllegalArgumentException("binding context failed"); }
                return super.context();
            }
        };
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, stage -> stage.phase() == RecipeStage.END ? List.of(grant) : List.of());
            assertEquals("binding context failed", assertThrows(IllegalArgumentException.class, () -> coordinator.advance(deadline())).getMessage());
            assertFalse(coordinator.finished());
            assertFalse(coordinator.advance(deadline()));
            assertEquals(1, grant.rollbacks);
            assertEquals(0, fixture.rolledBack.getInt(RecipeStage.END), "Material holds remain until the later acquisition is accurately compensated");
            proof.set(true);
            assertTrue(coordinator.advance(deadline()));
            assertEquals(1, grant.rollbacks);
            assertEquals(1, fixture.rolledBack.getInt(RecipeStage.END));
            assertEquals(95, fixture.energy.savedEnergy());
            assertEquals(1, fixture.post.get());
        }
    }

    @Test void detachedCaptureRejectsOrdinaryOrAttachedProcessesWithoutStartingCallbacks() {
        var fixture = new Fixture();
        try (var backend = new Backend()) {
            var ordinary = new RecipeProcess(fixture.machine, new MachineRecipe(id("ordinary"), 1, List.of()), 1);
            assertThrows(IllegalArgumentException.class, () -> PlannedRecipeCoordinator.forProcess(backend.tasks, ordinary, deadline(),
                fixture.token, () -> true, _ -> List.of()));
            var slots = new MachineWorkSlots(fixture.machine, List.of());
            assertTrue(slots.start(fixture.process));
            assertThrows(IllegalArgumentException.class, () -> fixture.coordinator(backend, _ -> List.of()));
            assertEquals(0, fixture.pre.get());
            assertEquals(0, fixture.post.get());
            assertTrue(slots.detach(fixture.process));
        }
    }

    @Test void cancelledSourceRequiresActualExitBeforeDetachedPostAndAdditionalRollback() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var sourceExited = new AtomicBoolean();
        var fixture = new Fixture();
        var grant = new Effect();
        fixture.startAdapter = actual -> new ReservationAdapter(actual) {
            @Override public void apply() {
                super.apply(); entered.countDown(); awaitIgnoringInterrupt(release, interrupted); sourceExited.set(true);
            }
        };
        try (var backend = new Backend()) {
            var coordinator = fixture.coordinator(backend, _ -> List.of(grant));
            var cancel = new FutureTask<Void>(() -> {
                await(entered); fixture.token.cancel(Reason.EXPLICIT_CANCEL); await(interrupted); return null;
            });
            Thread.ofPlatform().daemon().start(cancel);
            try {
                assertFalse(coordinator.advance(deadline()));
                cancel.get(5, TimeUnit.SECONDS);
                assertFalse(sourceExited.get());
                assertEquals(0, fixture.post.get());
                assertEquals(0, grant.rollbacks);
                release.countDown(); coordinator.awaitExit();
                assertTrue(sourceExited.get());
                assertTrue(coordinator.advance(deadline()));
                assertEquals(1, grant.rollbacks);
                assertEquals(0, grant.commits);
                assertEquals(1, fixture.post.get());
                assertEquals(100, fixture.energy.savedEnergy());
                assertEquals(List.of(RecipeStage.START), fixture.phases);
            } finally { release.countDown(); cancel.get(5, TimeUnit.SECONDS); }
        } finally { release.countDown(); }
    }

    private static final class Fixture {
        private final Thread owner = Thread.currentThread();
        private final MachineInstance machine = new MachineInstance();
        private final EnergyBuffer energy = energy();
        private final PlannedCancellationToken token = new PlannedCancellationToken();
        private final ObjectArrayList<RecipeStage> phases = new ObjectArrayList<>();
        private final Object2IntOpenHashMap<RecipeStage> acquired = new Object2IntOpenHashMap<>();
        private final Object2IntOpenHashMap<RecipeStage> committed = new Object2IntOpenHashMap<>();
        private final Object2IntOpenHashMap<RecipeStage> rolledBack = new Object2IntOpenHashMap<>();
        private final AtomicInteger pre = new AtomicInteger();
        private final AtomicInteger post = new AtomicInteger();
        private final AtomicInteger sourceApplies = new AtomicInteger();
        private final AtomicReference<Thread> sourceThread = new AtomicReference<>();
        private UnaryOperator<ResourceReservation> startAdapter = UnaryOperator.identity();
        private UnaryOperator<ResourceReservation> endAdapter = UnaryOperator.identity();
        private Consumer<RecipeStage> onRequirementRollback = _ -> { };
        private final RecipeProcess process;

        private Fixture() {
            var requirement = requirement((current, phase) -> {
                assertSame(owner, Thread.currentThread());
                phases.add(phase); acquired.addTo(phase, 1);
                if (phase == RecipeStage.TICK) {
                    assertEquals(new RecipeProcess.Tick(0, Math.max(0, current.attributes().value(StandardMachineAttributes.PROCESS_SPEED, 1)), 0, 1), current.tickContext());
                }
                var original = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), switch (phase) {
                    case START -> 2; case TICK -> 3; case END -> 5;
                }).reservation());
                ResourceReservation observed = new ReservationAdapter(original) {
                    @Override public void apply() { sourceThread.set(Thread.currentThread()); sourceApplies.incrementAndGet(); super.apply(); }
                };
                if (phase == RecipeStage.START) { observed = startAdapter.apply(observed); }
                if (phase == RecipeStage.END) { observed = endAdapter.apply(observed); }
                var held = observed;
                return new RequirementTransaction() {
                    @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                    @Override public List<ResourceReservation> reservations() { return List.of(held); }
                    @Override public void commit() {
                        assertSame(owner, Thread.currentThread());
                        if (phase == RecipeStage.TICK) { assertEquals(1, current.tickContext().indexAfter()); }
                        committed.addTo(phase, 1);
                    }
                    @Override public void rollback() { assertSame(owner, Thread.currentThread()); rolledBack.addTo(phase, 1); onRequirementRollback.accept(phase); }
                };
            });
            process = RecipeProcess.materialCycle(machine, id("material"), List.of(requirement),
                new MachineHatches(List.of(), List.of(energy), List.of()), 7);
            process.components().create(new ComponentType<>(id("callbacks"), _ -> new Object(), List.of(new ComponentSystem<RecipeProcess, Object>() {
                @Override public ResourceLocation id() { return PlannedMaterialCycleCoordinatorTest.id("callbacks"); }
                @Override public void onPreTick(RecipeProcess current, Object component) { assertSame(owner, Thread.currentThread()); pre.incrementAndGet(); }
                @Override public void onPostTick(RecipeProcess current, Object component) { assertSame(owner, Thread.currentThread()); post.incrementAndGet(); }
            })), process);
        }

        private PlannedRecipeCoordinator coordinator(Backend backend, Function<RecipeProcess.Stage, List<? extends ReversibleTransaction>> participants) {
            return PlannedRecipeCoordinator.forProcess(backend.tasks, process, deadline(), token, () -> true, participants);
        }
    }

    private static class Effect implements ReversibleTransaction {
        private int value;
        private int commits;
        private int completes;
        private int rollbacks;
        private Resolution resolution = Resolution.REVERSIBLE;
        private Runnable onCommit = () -> { };
        private Runnable onRollback = () -> { };
        @Override public void commit() { commits++; value++; onCommit.run(); }
        @Override public void complete() { completes++; resolution = Resolution.COMPLETED; }
        @Override public void rollback() { rollbacks++; value = 0; onRollback.run(); resolution = Resolution.ROLLED_BACK; }
        @Override public Resolution resolution() { return resolution; }
    }

    private static RecipeRequirement<String> requirement(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.START); }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.TICK); }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) { return action.apply(process, RecipeStage.END); }
        };
        return new RecipeRequirementType<>(id("energy"), system).requirement(id("energy"), "value");
    }

    private static class ReservationAdapter implements ResourceReservation {
        private final ResourceReservation original;
        private ReservationAdapter(ResourceReservation original) { this.original = original; }
        @Override public StorageContext context() { return original.context(); }
        @Override public boolean canApply() { return original.canApply(); }
        @Override public void apply() { original.apply(); }
        @Override public void complete() { original.complete(); }
        @Override public void rollback() { original.rollback(); }
        @Override public Resolution resolution() { return original.resolution(); }
    }

    private static final class Backend implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        private final ExecutorService lane = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
        @Override public void close() {
            workers.shutdownNow(); lane.shutdownNow();
            try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); assertTrue(lane.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }

    private static PlannedDeadline deadline() { return PlannedDeadline.after(Duration.ofSeconds(5)); }
    private static ResourceLocation id(String name) { return new ResourceLocation("planned_material_cycle_test", name); }
    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> { });
        assertEquals(100, energy.receiveEnergy(100, Actionable.MODULATE)); return energy;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch, CountDownLatch interrupted) {
        boolean exited = false;
        while (!exited) {
            try { assertTrue(latch.await(5, TimeUnit.SECONDS)); exited = true; }
            catch (InterruptedException ignored) { interrupted.countDown(); }
        }
    }
}
