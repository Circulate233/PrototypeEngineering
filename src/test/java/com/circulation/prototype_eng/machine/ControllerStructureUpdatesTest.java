package com.circulation.prototype_eng.machine;

import static org.junit.jupiter.api.Assertions.*;
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
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.structure.StructureCheckPolicy;
import com.circulation.prototype_eng.structure.StructureRefresh;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.circulation.prototype_eng.api.structure.StructureReadWindow;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
class ControllerStructureUpdatesTest {
    @Test
    void shorterStructurePublishesBeforeTheLongerStructureReopensItsThirdReadRound() {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var longer = new Fixture(scheduler); var shorter = new Fixture(scheduler);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            longer.updates.tick(0);
            shorter.updates.tick(0);
            var pattern = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>();
            var predicate = StructurePredicates.test(MARKER, "body"::equals);
            for (int x = 1; x <= 600; x++) pattern.put(new BlockPos(x, 0, 0), predicate);
            longer.host.multiplePositions = true;
            longer.host.configuration = new StructureConfiguration(new TemplateStructure(id("longer"), BlockOrientation.NORTH_UP,
                BlockPos.ORIGIN, false, new StructurePattern(pattern), List.of(), List.of()), BlockOrientation.NORTH_UP);
            for (var fixture : List.of(longer, shorter)) {
                fixture.updates.checkPolicy(new StructureCheckPolicy(1_000_000, Duration.ofSeconds(2), Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.COOPERATIVE));
                fixture.host.workerRead = () -> { };
            }
            shorter.onChanged = () -> {
                assertEquals(512, longer.host.windows.size(), "The third read round must not bind before this owner publishes");
                var borrowed = List.copyOf(longer.host.windows);
                assertDoesNotThrow(() -> observer.submit(() -> {
                    for (var window : borrowed) assertThrows(IllegalStateException.class, window::read);
                }).get(5, TimeUnit.SECONDS));
            };
            longer.updates.enqueueTick(5);
            shorter.updates.enqueueTick(5);
            scheduler.tick();
            assertEquals(600, longer.host.windows.size(), "The longer structure actually required three read rounds");
            assertTrue(longer.lifecycle().formed());
            assertTrue(shorter.lifecycle().formed());
            assertEquals(2, longer.changes);
            assertEquals(2, shorter.changes);
        }
    }

    @Test
    void queuedTicksOfOneHostNeverDispatchTheSameCaptureCursorTwice() {
        try (var fixture = new Fixture()) {
            fixture.tick(0);
            fixture.updates.checkPolicy(new StructureCheckPolicy(4096, Duration.ofSeconds(2), Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.COOPERATIVE));
            var reads = new AtomicInteger();
            fixture.host.workerRead = reads::incrementAndGet;
            fixture.updates.enqueueTick(5);
            fixture.updates.enqueueTick(6);
            fixture.updates.enqueueTick(7);
            fixture.scheduler.tick();
            assertEquals(1, reads.get());
            assertEquals(2, fixture.changes);
            assertEquals(StructureCheckPolicy.Status.MATCHED, fixture.updates.checkStatus());
        }
    }

    @Test
    void periodicControllersReadAndMatchInParallelAndCloseAllWindowsBeforePublication() throws Exception {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var first = new Fixture(scheduler); var second = new Fixture(scheduler);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            first.updates.enqueueTick(81);
            second.updates.enqueueTick(81);
            assertEquals(1, first.changes);
            assertEquals(1, second.changes);
            var reading = new CountDownLatch(2);
            var matching = new CountDownLatch(2);
            var readsExited = new AtomicInteger();
            var matchesExited = new AtomicInteger();
            var observations = new AtomicInteger();
            for (var fixture : List.of(first, second)) {
                fixture.updates.checkPolicy(new StructureCheckPolicy(4096, Duration.ofSeconds(2), Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.COOPERATIVE));
                fixture.host.workerRead = () -> {
                    assertNotSame(fixture.owner, Thread.currentThread());
                    reading.countDown();
                    await(reading);
                    readsExited.incrementAndGet();
                };
                var invocations = new AtomicInteger();
                fixture.host.configuration = configuration(() -> {
                    if (invocations.getAndIncrement() == 0) {
                        assertEquals(2, readsExited.get());
                        matching.countDown();
                        await(matching);
                        matchesExited.incrementAndGet();
                    }
                });
                fixture.onChanged = () -> {
                    assertEquals(2, readsExited.get());
                    assertEquals(2, matchesExited.get());
                };
                fixture.updates.enqueueTick(101, () -> {
                    assertEquals(2, matchesExited.get());
                    assertEquals(2, first.changes);
                    assertEquals(2, second.changes);
                    observations.incrementAndGet();
                });
            }
            assertEquals(1, first.changes, "Periodic calls gather until the original scheduler boundary");
            scheduler.tick();
            assertEquals(2, observations.get());
            assertEquals(2, first.changes);
            assertEquals(2, second.changes);
            assertTrue(first.machine().canProcessRecipes());
            assertTrue(second.machine().canProcessRecipes());
            var windows = new ArrayList<>(first.host.windows);
            windows.addAll(second.host.windows);
            assertEquals(2, windows.size());
            observer.submit(() -> {
                for (var window : windows) assertThrows(IllegalStateException.class, window::read);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aRevokedPreparedOwnerStillClosesItsBorrowedReadWindow() throws Exception {
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1);
             var first = new Fixture(scheduler); var second = new Fixture(scheduler);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            first.updates.tick(0);
            second.updates.tick(0);
            first.host.workerRead = () -> { };
            second.host.onRead = first.updates::invalidate;
            first.updates.enqueueTick(5);
            second.updates.enqueueTick(5);
            scheduler.tick();
            assertFalse(first.lifecycle().formed());
            assertEquals(1, first.host.windows.size());
            observer.submit(() -> assertThrows(IllegalStateException.class, first.host.windows.getFirst()::read)).get(5, TimeUnit.SECONDS);
        }
    }
    @Test
    void firstRealHostTickAtNonzeroWorldTimeCompletesOnTheOwnerBeforeNotification() {
        try (var fixture = new Fixture()) {
            fixture.host.available = false;
            var loadProbe = new StructureRefresh(fixture.lifecycle(), fixture.host);
            var unavailable = Objects.requireNonNull(loadProbe.prepare()).evaluate();
            assertFalse(loadProbe.publish(unavailable));
            assertFalse(fixture.lifecycle().formed());
            assertEquals(0, fixture.changes);
            fixture.host.available = true;
            fixture.host.configuration = configuration(() -> assertSame(fixture.owner, Thread.currentThread()));
            fixture.onChanged = () -> assertTrue(fixture.lifecycle().formed());
            fixture.updates.tick(173);
            assertTrue(fixture.lifecycle().formed());
            assertEquals(StructureCheckPolicy.Status.MATCHED, fixture.updates.checkStatus());
            assertEquals(1, fixture.changes);
        }
    }

    @Test
    void strictFirstTickReportsInsufficientWorkAndAChangedAllowanceCanComplete() {
        try (var fixture = new Fixture()) {
            fixture.updates.checkPolicy(new StructureCheckPolicy(1, Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.STRICT));
            fixture.tick(73);
            assertEquals(StructureCheckPolicy.Status.NEEDS_MORE_BUDGET, fixture.updates.checkStatus());
            assertFalse(fixture.lifecycle().formed());
            assertEquals(0, fixture.changes);
            fixture.tick(74);
            assertEquals(0, fixture.changes);
            fixture.updates.checkPolicy(new StructureCheckPolicy(4096, Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.STRICT));
            fixture.tick(75);
            assertTrue(fixture.lifecycle().formed());
            assertEquals(StructureCheckPolicy.Status.MATCHED, fixture.updates.checkStatus());
        }
    }

    @Test
    void namedDefaultFactoryReportsItsLiveReadMinimumAndResumesOnRealTicksWithNewAllowance() {
        try (var fixture = new Fixture()) {
            fixture.tick(10);
            fixture.host.configuration = new StructureConfiguration(TemplateStructure.create(id("budgeted_default"),
                BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
                new StructurePattern(Map.of(BODY, StructurePredicates.test(MARKER, "body"::equals))), List.of(), List.of()),
                BlockOrientation.NORTH_UP);
            var accepted = fixture.lifecycle().structureMatch();
            fixture.updates.checkPolicy(new StructureCheckPolicy(1, Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.COOPERATIVE));
            long now = 30;
            do {
                fixture.tick(now++);
                assertSame(accepted, fixture.lifecycle().structureMatch());
                assertTrue(fixture.lifecycle().formed());
                assertFalse(fixture.machine().canProcessRecipes());
            } while (fixture.updates.checkStatus() == StructureCheckPolicy.Status.YIELD && now < 150);
            assertEquals(StructureCheckPolicy.Status.NEEDS_MORE_BUDGET, fixture.updates.checkStatus());
            assertEquals(3, fixture.updates.requiredAtomicUnits());
            fixture.updates.checkPolicy(new StructureCheckPolicy(3, Duration.ofSeconds(5), StructureCheckPolicy.FinalMode.COOPERATIVE));
            var discoveries = new AtomicInteger();
            var replacement = new ExternalHatch();
            fixture.host.onDiscovery = _ -> {
                if (discoveries.incrementAndGet() == 1) fixture.host.binding = binding(replacement);
            };
            do {
                fixture.tick(now++);
            } while (fixture.updates.checkStatus() == StructureCheckPolicy.Status.YIELD && now < 300);
            assertEquals(StructureCheckPolicy.Status.MATCHED, fixture.updates.checkStatus());
            assertTrue(fixture.machine().canProcessRecipes());
            assertTrue(discoveries.get() >= 3, "Preparation discovery, current discovery and final provider proof all execute");
            assertEquals(List.of(replacement), MachineHatches.get(fixture.machine()).resources());
            assertTrue(fixture.updates.lastWorkUnits() > 3, "The complete cooperative tail reports its actual excess cost");
        }
    }

    @Test
    void relevantUnavailableRefreshPreservesAcceptedTreeAndHatchesButPausesRecipes() {
        try (var fixture = new Fixture()) {
            fixture.tick(31);
            var match = fixture.lifecycle().structureMatch();
            var hatches = fixture.machine().components().get(MachineHatches.TYPE);
            var calls = new AtomicInteger();
            var slots = new MachineWorkSlots(fixture.machine(), List.of(new WorkSlotDefinition("main")), Set.of(), process -> {
                calls.incrementAndGet();
                process.tick();
            });
            fixture.machine().components().put(MachineWorkSlots.TYPE, slots);
            var process = new RecipeProcess(fixture.machine(), new MachineRecipe(id("running"), 10, List.of()), 17);
            assertTrue(slots.start(process));
            fixture.machine().onSchedule();
            var before = process.state();
            assertEquals(1, calls.get());
            fixture.host.available = false;
            long nextTick = 51;
            do {
                fixture.tick(nextTick++);
                assertTrue(fixture.lifecycle().formed());
                assertSame(match, fixture.lifecycle().structureMatch());
                assertSame(hatches, fixture.machine().components().get(MachineHatches.TYPE));
                assertFalse(fixture.machine().canProcessRecipes());
                fixture.machine().onSchedule();
                assertEquals(1, calls.get());
                assertEquals(before, process.state());
                assertSame(process, slots.requireSlot("main").process());
                assertSame(slots, process.host());
            } while (fixture.updates.checkStatus() == StructureCheckPolicy.Status.YIELD && nextTick < 71);
            assertEquals(StructureCheckPolicy.Status.UNAVAILABLE, fixture.updates.checkStatus());
            assertEquals(1, fixture.changes);
            fixture.host.available = true;
            fixture.tick(nextTick);
            assertTrue(fixture.machine().canProcessRecipes());
            assertEquals(2, calls.get());
            assertEquals(before.successfulTicks() + 1, process.state().successfulTicks());
        }
    }
    private static final BlockPos BODY = new BlockPos(1, 0, 0);
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"),
        (_, _) -> fail("Logical tests never read a game world"), UnaryOperator.identity(), String::equals);

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
    void unstablePublicationPreservesAcceptedBindingsAndRetriesOnTheVeryNextTick() {
        try (var fixture = new Fixture()) {
            var old = new ExternalHatch();
            var replacement = new ExternalHatch();
            fixture.host.binding = binding(old);
            fixture.tick(0);
            assertEquals(List.of(old), MachineHatches.get(fixture.machine()).resources());
            var accepted = fixture.lifecycle().structureMatch();
            var acceptedHatches = MachineHatches.get(fixture.machine());
            fixture.host.onDiscovery = _ -> fixture.host.binding = binding(replacement);
            fixture.tick(5);
            assertFalse(fixture.machine().canProcessRecipes());
            assertSame(acceptedHatches, fixture.machine().components().get(MachineHatches.TYPE));
            assertSame(accepted, fixture.lifecycle().structureMatch());
            assertTrue(fixture.lifecycle().formed());
            assertEquals(StructureCheckPolicy.Status.UNAVAILABLE, fixture.updates.checkStatus());
            assertEquals(1, fixture.changes);
            fixture.host.onDiscovery = _ -> {
            };
            fixture.tick(6);
            assertTrue(fixture.machine().canProcessRecipes());
            assertEquals(List.of(replacement), MachineHatches.get(fixture.machine()).resources());
            assertEquals(2, fixture.changes);
        }
    }

    @Test
    void oldSuccessAndMismatchCompletionsCannotInvalidateOrRetireANewlyCapturedRequest() {
        for (String oldValue : List.of("body", "missing")) {
            try (var fixture = new Fixture()) {
                fixture.tick(0);
                var refresh = new StructureRefresh(fixture.lifecycle(), fixture.host);
                fixture.host.value = oldValue;
                var oldRequest = Objects.requireNonNull(refresh.prepare());
                var oldResult = oldRequest.evaluate();
                    fixture.scheduler.runAtBoundary(() -> {
                        fixture.updates.invalidate();
                        fixture.host.configuration = configuration(() -> {});
                        fixture.host.value = "body";
                });
                var replacement = Objects.requireNonNull(refresh.prepare());
                assertFalse(refresh.publish(oldResult));
                assertFalse(fixture.machine().canProcessRecipes());
                assertNull(fixture.lifecycle().structureMatch());
                assertNull(fixture.machine().components().get(MachineHatches.TYPE));
                assertTrue(fixture.controller.active());
                assertTrue(refresh.publish(replacement.evaluate()));
                var accepted = fixture.lifecycle().structureMatch();
                var hatches = MachineHatches.get(fixture.machine());
                assertFalse(refresh.publish(oldResult));
                assertSame(accepted, fixture.lifecycle().structureMatch());
                assertSame(hatches, MachineHatches.get(fixture.machine()));
                assertEquals(2, fixture.changes);
                assertTrue(fixture.machine().canProcessRecipes());
            }
        }
    }

    @Test
    void retirementDuringEvaluationSuppressesPublicationAndFurtherRefreshAdmission() throws Exception {
        try (var fixture = new Fixture();
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            fixture.tick(0);
            var matching = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.host.configuration = configuration(() -> {
                assertNotSame(fixture.owner, Thread.currentThread());
                matching.countDown(); await(release); });
            var retirement = observer.submit(() -> {
                await(matching);
                try {
                    fixture.controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                    assertTrue(fixture.lifecycle().retired());
                    assertEquals(1, fixture.changes);
                } finally {
                    release.countDown();
            }
            });
            try { fixture.updates.tick(5);
                retirement.get(5, TimeUnit.SECONDS); }
            finally { release.countDown(); }
            assertEquals(MachineLifecycle.State.UNLOADED, fixture.lifecycle().state());
            assertEquals(1, fixture.changes);
            int reads = fixture.host.reads;
            fixture.tick(25);
            assertEquals(reads, fixture.host.reads);
            assertEquals(1, fixture.changes);
        }
    }

    @Test
    void obsoleteCapturedFailureCannotFaultOrRetireTheReplacementConfiguration() {
        try (var fixture = new Fixture()) {
            fixture.tick(0);
            var failure = new IllegalArgumentException("old evaluation failed");
            fixture.host.configuration = configuration(() -> {
                throw failure;
            });
            var refresh = new StructureRefresh(fixture.lifecycle(), fixture.host);
            var oldRequest = Objects.requireNonNull(refresh.prepare());
            assertSame(failure, assertThrows(IllegalArgumentException.class, oldRequest::evaluate));
            fixture.scheduler.runAtBoundary(() -> {
                fixture.updates.invalidate();
                fixture.host.configuration = configuration(() -> {
                });
            });
            var replacement = Objects.requireNonNull(refresh.prepare());
            refresh.fail(oldRequest, failure);
            assertTrue(fixture.controller.active());
            assertTrue(fixture.lifecycle().schedulingAllowed());
            assertFalse(fixture.machine().canProcessRecipes());
            assertNull(fixture.machine().components().get(MachineHatches.TYPE));
            assertTrue(refresh.publish(replacement.evaluate()));
            var accepted = fixture.lifecycle().structureMatch();
            var hatches = MachineHatches.get(fixture.machine());
            refresh.fail(oldRequest, failure);
            assertSame(accepted, fixture.lifecycle().structureMatch());
            assertSame(hatches, MachineHatches.get(fixture.machine()));
            assertTrue(fixture.machine().canProcessRecipes());
            assertEquals(2, fixture.changes);
        }
    }

    @Test
    void captureFailureFaultsOnlyItsExactStillCurrentPreparation() {
        try (var fixture = new Fixture()) {
            var failure = new AssertionError("current capture failed");
            fixture.host.onRead = () -> {
                throw failure;
            };
            assertSame(failure, assertThrows(AssertionError.class, () -> fixture.updates.tick(173)));
            assertFalse(fixture.controller.active());
            assertEquals(0, fixture.changes);
        }
        try (var fixture = new Fixture()) {
            var failure = new AssertionError("revoked capture failed");
            var refresh = new StructureRefresh(fixture.lifecycle(), fixture.host);
            var obsolete = refresh.beginPrepare(null);
            fixture.host.onRead = () -> {
                fixture.host.configuration = configuration(() -> {
                });
                throw failure;
            };
            assertSame(failure, assertThrows(AssertionError.class, obsolete::run));
            assertTrue(fixture.controller.active());
            assertTrue(fixture.lifecycle().schedulingAllowed());
            fixture.host.onRead = () -> {
            };
            var replacement = Objects.requireNonNull(refresh.prepare());
            assertNull(obsolete.run());
            assertTrue(refresh.publish(replacement.evaluate()));
            assertTrue(fixture.lifecycle().formed());
            assertTrue(fixture.machine().canProcessRecipes());
            assertNotNull(fixture.lifecycle().structureMatch());
            assertNotNull(fixture.machine().components().get(MachineHatches.TYPE));
        }
    }

    @Test
    void currentWorkerFailureAndRejectedSubmissionFaultThroughTheRefreshFailureBoundary() {
        try (var fixture = new Fixture()) {
            fixture.tick(0);
            var failure = new AssertionError("current evaluation failed");
            fixture.host.configuration = configuration(() -> {
                assertNotSame(fixture.owner, Thread.currentThread());
                throw failure; });
            assertSame(failure, assertThrows(AssertionError.class, () -> fixture.updates.tick(5)));
            assertFalse(fixture.controller.active());
            assertEquals(1, fixture.changes);
        }
        try (var fixture = new Fixture()) {
            fixture.tick(0);
            fixture.scheduler.submit(() -> { throw new IllegalStateException("unrelated task failed"); }, ExecutionMode.CONCURRENT);
            assertThrows(IllegalStateException.class, fixture.scheduler::tick);
            assertThrows(IllegalStateException.class, () -> fixture.updates.tick(5));
            assertFalse(fixture.controller.active());
            assertEquals(1, fixture.changes);
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

    @Test
    void joinedEvaluationRetainsBindingsAndExitsBeforeOwnerPublicationAndOrdinaryProcessing() throws Exception {
        try (var fixture = new Fixture();
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            fixture.tick(0);
            var matching = new CountDownLatch(1);
            var processing = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var processExited = new CountDownLatch(1);
            var matchExited = new CountDownLatch(1);
            var accepted = fixture.machine().components().get(MachineHatches.TYPE);
            fixture.host.configuration = configuration(() -> {
                assertNotSame(fixture.owner, Thread.currentThread());
                matching.countDown();
                try {
                    await(release);
                } finally {
                    matchExited.countDown();
                }
            });
            fixture.onChanged = () -> {
                assertSame(fixture.owner, Thread.currentThread());
                assertEquals(1, processExited.getCount());
                assertEquals(0, matchExited.getCount());
            };
            fixture.scheduler.submit(() -> fixture.lifecycle().runScheduled(() -> {
                assertTrue(fixture.machine().canProcessRecipes());
                assertNotNull(fixture.machine().components().get(MachineHatches.TYPE));
                processing.countDown();
                processExited.countDown();
            }), ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> {
                await(matching);
                try {
                    assertEquals(1, fixture.changes);
                    assertEquals(1, processExited.getCount());
                    assertEquals(1, processing.getCount());
                    assertSame(accepted, fixture.machine().components().get(MachineHatches.TYPE));
                } finally {
                    release.countDown();
                }
            });
            try {
                fixture.updates.tick(5);
                assertEquals(2, fixture.changes);
                assertEquals(0, matchExited.getCount());
                assertEquals(1, processing.getCount());
                fixture.scheduler.tick();
                observation.get(5, TimeUnit.SECONDS);
                assertEquals(2, fixture.changes);
                assertEquals(0, processExited.getCount());
                assertTrue(fixture.machine().canProcessRecipes());
            } finally {
                release.countDown();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Host host = new Host();
        private final TaskScheduler scheduler;
        private final boolean ownsScheduler;
        private final MachineController controller;
        private final ControllerStructureUpdates updates;
        private volatile int changes;
        private Runnable onChanged = () -> {};

        private Fixture() {
            this(new TaskScheduler(Thread.currentThread(), 2, 1), true);
        }

        private Fixture(TaskScheduler scheduler) { this(scheduler, false); }

        @SuppressWarnings("NonAtomicOperationOnVolatileField")
        // Only the asserted owner writes; observer threads read the count.
        private Fixture(TaskScheduler scheduler, boolean ownsScheduler) {
            this.scheduler = scheduler;
            this.ownsScheduler = ownsScheduler;
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

        @Override
        public void close() {
            try {
                controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            } finally {
                if (ownsScheduler) scheduler.close();
            }
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
        private Runnable onRead = () -> {
        };
        private int reads;
        private boolean available = true;
        private boolean multiplePositions;
        @Nullable private Runnable workerRead;
        private final List<StructureReadWindow<?>> windows = new ArrayList<>();

        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("No World in logical controller tests"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Not the owner thread"); }
        }
        @Override public StructureConfiguration structureConfiguration() { requireServerThread(); return configuration; }
        @Override public boolean isAreaLoaded(StructureBounds bounds) { requireServerThread(); return available; }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // Only the fixture's String descriptor is captured.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(MARKER, query);
            if (!multiplePositions) assertEquals(BODY, position);
            reads++;
            onRead.run();
            return (T) value;
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // Only the fixture's String descriptor is borrowed.
        public <T> StructureReadWindow<T> beginWorkerRead(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            var callback = workerRead;
            if (callback == null) return MachineStructureHost.super.beginWorkerRead(query, position);
            assertSame(MARKER, query);
            if (!multiplePositions) assertEquals(BODY, position);
            String captured = value;
            var window = StructureReadWindow.joined(() -> { callback.run(); return (T) captured; });
            windows.add(window);
            return window;
        }
        @Override public StructureHatchBinding hatchesAt(BlockPos position) {
            requireServerThread();
            var found = binding;
            onDiscovery.accept(position);
            return found;
        }
    }
}
