package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.TemplateStructure;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ControllerStructureUpdatesTest {
    private static final BlockPos BODY = new BlockPos(1, 0, 0);
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"),
        (_, _) -> fail("Logical tests never read a game world"), UnaryOperator.identity(), String::equals);

    @Test
    void detachedEvaluationPreservesProcessingAndPublicationWaitsForEveryAcceptedWorkerToExit() throws Exception {
        try (var fixture = new Fixture();
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            fixture.tick(0);
            var matching = new CountDownLatch(1);
            var processing = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var processExited = new CountDownLatch(1);
            var matchExited = new CountDownLatch(1);
            fixture.host.configuration = configuration(() -> {
                assertNotSame(fixture.owner, Thread.currentThread());
                matching.countDown();
                try { await(release); }
                finally { matchExited.countDown(); }
            });
            fixture.onChanged = () -> {
                assertSame(fixture.owner, Thread.currentThread());
                assertEquals(0, processExited.getCount());
                assertEquals(0, matchExited.getCount());
            };
            fixture.updates.tick(5);
            fixture.scheduler.submit(() -> fixture.lifecycle().runScheduled(() -> {
                assertTrue(fixture.machine().canProcessRecipes());
                assertNotNull(fixture.machine().components().get(MachineHatches.TYPE));
                processing.countDown();
                try { await(release); }
                finally { processExited.countDown(); }
            }), ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> {
                await(matching);
                await(processing);
                try {
                    assertEquals(1, fixture.changes);
                    assertEquals(1, processExited.getCount());
                } finally { release.countDown(); }
            });
            try {
                fixture.scheduler.tick();
                observation.get(5, TimeUnit.SECONDS);
                assertEquals(2, fixture.changes);
                assertTrue(fixture.machine().canProcessRecipes());
            } finally { release.countDown(); }
        }
    }

    @Test
    void tickZeroAndFormationChangesUseTheOriginalFiveAndTwentyTickCadence() {
        try (var fixture = new Fixture()) {
            fixture.tick(0);
            assertEquals(1, fixture.changes);
            fixture.tick(4);
            assertEquals(1, fixture.changes);
            fixture.tick(5);
            assertEquals(2, fixture.changes);
            fixture.tick(24);
            assertEquals(2, fixture.changes);
            fixture.host.value = "missing";
            fixture.tick(25);
            assertEquals(3, fixture.changes);
            assertFalse(fixture.machine().canProcessRecipes());
            fixture.tick(44);
            assertEquals(3, fixture.changes);
            fixture.tick(45);
            assertEquals(4, fixture.changes);
            fixture.host.value = "body";
            fixture.tick(49);
            assertEquals(4, fixture.changes);
            fixture.tick(50);
            assertEquals(5, fixture.changes);
            assertTrue(fixture.machine().canProcessRecipes());
        }
    }

    @Test
    void unstablePublicationClearsOldBindingsAndRetriesOnTheVeryNextTick() {
        try (var fixture = new Fixture()) {
            var old = new ExternalHatch();
            var replacement = new ExternalHatch();
            fixture.host.binding = binding(old);
            fixture.tick(0);
            assertEquals(List.of(old), MachineHatches.get(fixture.machine()).resources());
            fixture.host.onDiscovery = _ -> fixture.host.binding = binding(replacement);
            fixture.tick(5);
            assertFalse(fixture.machine().canProcessRecipes());
            assertNull(fixture.machine().components().get(MachineHatches.TYPE));
            assertNull(fixture.lifecycle().structureMatch());
            assertEquals(2, fixture.changes);
            fixture.host.onDiscovery = _ -> {};
            fixture.tick(6);
            assertTrue(fixture.machine().canProcessRecipes());
            assertEquals(List.of(replacement), MachineHatches.get(fixture.machine()).resources());
            assertEquals(3, fixture.changes);
        }
    }

    @Test
    void oldSuccessAndMismatchCompletionsCannotInvalidateOrRetireANewlyCapturedRequest() {
        for (String oldValue : List.of("body", "missing")) {
            try (var fixture = new Fixture()) {
                var matching = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                fixture.host.value = oldValue;
                fixture.host.configuration = configuration(() -> { matching.countDown(); await(release); });
                fixture.updates.tick(0);
                fixture.scheduler.submit(() -> {
                    await(matching);
                    fixture.scheduler.runAtBoundary(() -> {
                        fixture.updates.invalidate();
                        fixture.host.configuration = configuration(() -> {});
                        fixture.host.value = "body";
                        fixture.updates.tick(1);
                    });
                    release.countDown();
                }, ExecutionMode.CONCURRENT);
                try { fixture.scheduler.tick(); }
                finally { release.countDown(); }
                assertEquals(1, fixture.changes);
                assertFalse(fixture.machine().canProcessRecipes());
                fixture.scheduler.tick();
                assertEquals(2, fixture.changes);
                assertTrue(fixture.machine().canProcessRecipes());
                fixture.tick(2);
                assertEquals(2, fixture.changes);
            }
        }
    }

    @Test
    void retirementDuringEvaluationSuppressesPublicationAndFurtherRefreshAdmission() {
        try (var fixture = new Fixture()) {
            var matching = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.host.configuration = configuration(() -> { matching.countDown(); await(release); });
            fixture.updates.tick(0);
            fixture.scheduler.submit(() -> {
                await(matching);
                fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                release.countDown();
            }, ExecutionMode.CONCURRENT);
            try { fixture.scheduler.tick(); }
            finally { release.countDown(); }
            assertEquals(MachineLifecycle.State.UNLOADED, fixture.lifecycle().state());
            assertEquals(0, fixture.changes);
            int reads = fixture.host.reads;
            fixture.tick(5);
            assertEquals(reads, fixture.host.reads);
            assertEquals(0, fixture.changes);
        }
    }

    @Test
    void obsoleteWorkerFailureIsReportedButCannotFaultTheReplacementConfiguration() {
        try (var fixture = new Fixture()) {
            var matching = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var failure = new IllegalArgumentException("old evaluation failed");
            fixture.host.configuration = configuration(() -> { matching.countDown(); await(release); throw failure; });
            fixture.updates.tick(0);
            fixture.scheduler.submit(() -> {
                await(matching);
                fixture.scheduler.runAtBoundary(() -> {
                    fixture.updates.invalidate();
                    fixture.host.configuration = configuration(() -> {});
                });
                release.countDown();
            }, ExecutionMode.CONCURRENT);
            try { assertSame(failure, assertThrows(IllegalArgumentException.class, fixture.scheduler::tick)); }
            finally { release.countDown(); }
            assertTrue(fixture.controller.active());
            assertEquals(1, fixture.changes);
        }
    }

    @Test
    void currentWorkerFailureAndRejectedSubmissionFaultThroughTheRefreshFailureBoundary() {
        try (var fixture = new Fixture()) {
            var failure = new AssertionError("current evaluation failed");
            fixture.host.configuration = configuration(() -> { throw failure; });
            fixture.updates.tick(0);
            assertSame(failure, assertThrows(AssertionError.class, fixture.scheduler::tick));
            assertFalse(fixture.controller.active());
            assertEquals(0, fixture.changes);
        }
        try (var fixture = new Fixture()) {
            fixture.scheduler.submit(() -> { throw new IllegalStateException("unrelated task failed"); }, ExecutionMode.CONCURRENT);
            assertThrows(IllegalStateException.class, fixture.scheduler::tick);
            assertThrows(IllegalStateException.class, () -> fixture.updates.tick(0));
            assertFalse(fixture.controller.active());
            assertEquals(0, fixture.changes);
        }
    }

    private static StructureConfiguration configuration(Runnable matching) {
        var predicate = StructurePredicates.test(MARKER, value -> { matching.run(); return "body".equals(value); });
        return new StructureConfiguration(new TemplateStructure(id("shape"), BlockOrientation.NORTH_UP,
            BlockPos.ORIGIN, false, new StructurePattern(Map.of(BODY, predicate)), List.of(), List.of()), BlockOrientation.NORTH_UP);
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("controller_structure_test", name); }
    private static StructureHatchBinding binding(ResourceHatch hatch) { return new StructureHatchBinding(new Object(), hatch, null, null); }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test handshake"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Host host = new Host();
        private final TaskScheduler scheduler = new TaskScheduler(owner, 2, 1);
        private final MachineController controller;
        private final ControllerStructureUpdates updates;
        private volatile int changes;
        private Runnable onChanged = () -> {};

        @SuppressWarnings("NonAtomicOperationOnVolatileField") // Only the asserted owner writes; observer threads read the count.
        private Fixture() {
            var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).executionMode(ExecutionMode.MAIN_THREAD).build();
            controller = new MachineController(MachineAssembly.builder(type, "v1").build(), host, scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            updates = new ControllerStructureUpdates(controller, host, scheduler, () -> {
                assertSame(owner, Thread.currentThread());
                onChanged.run();
                changes++;
            });
        }
        private MachineInstance machine() { return Objects.requireNonNull(controller.machine()); }
        private MachineLifecycle lifecycle() { return Objects.requireNonNull(machine().lifecycle()); }
        private void tick(long now) { updates.tick(now); scheduler.tick(); }
        @Override public void close() {
            try { controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD); }
            finally { scheduler.close(); }
        }
    }

    private static final class ExternalHatch implements ResourceHatch {
        @Override @Nullable public ResourceStorage input() { return null; }
        @Override @Nullable public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return true; }
    }

    private static final class Host implements MachineStructureHost {
        private final Thread owner = Thread.currentThread();
        private StructureConfiguration configuration = configuration(() -> {});
        private String value = "body";
        private StructureHatchBinding binding = StructureHatchBinding.EMPTY;
        private Consumer<BlockPos> onDiscovery = _ -> {};
        private int reads;

        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("No World in logical controller tests"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Not the owner thread"); }
        }
        @Override public StructureConfiguration structureConfiguration() { requireServerThread(); return configuration; }
        @Override public boolean isAreaLoaded(StructureBounds bounds) { requireServerThread(); return true; }
        @Override @Nullable @SuppressWarnings("unchecked") // Only the fixture's String descriptor is captured.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(MARKER, query);
            assertEquals(BODY, position);
            reads++;
            return (T) value;
        }
        @Override public StructureHatchBinding hatchesAt(BlockPos position) {
            requireServerThread();
            var found = binding;
            onDiscovery.accept(position);
            return found;
        }
    }
}
