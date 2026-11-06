package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Each fixture-local registry owns subscriptions for the entire scenario; cancellation is under test.
@SuppressWarnings("resource")
class MachineLifecycleDispatchTest {
    @Test
    void assemblyAndSaveBoundariesAllowNestedOwnerCodecsButRejectPrematureOrRecursiveExecution() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        fixture.registry.register(new Recorder("observer", log));
        try (var worker = Executors.newSingleThreadExecutor()) {
            fixture.lifecycle.assemble(() -> {
                fixture.machine.requirePersistenceBoundary();
                assertFalse(fixture.machine.active());
                assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH));
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.assemble(() -> {}));
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.withPersistence(() -> "nested"));
            });
            assertTrue(log.isEmpty());
            assertThrows(IllegalStateException.class, () -> fixture.lifecycle.assemble(() -> {}));
            fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            assertEquals(List.of("load:observer"), log);
            var checked = fixture.lifecycle.withPersistence(() -> {
                fixture.machine.requirePersistenceBoundary();
                assertFalse(fixture.machine.active());
                assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.withPersistence(() -> "nested"));
                assertThrows(IllegalStateException.class, () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                var other = worker.submit(() -> assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary));
                assertDoesNotThrow(() -> other.get(5, TimeUnit.SECONDS));
                return "saved";
            });
            assertEquals("saved", checked);
            assertTrue(fixture.machine.active());
        }
    }

    @Test
    void failedUnpublishedAssemblyCannotLoadRunSaveOrNotifyNeverLoadedListeners() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        fixture.registry.register(new Recorder("observer", log));
        var failure = new AssertionError("assembly failed");
        assertSame(failure, assertThrows(AssertionError.class, () -> fixture.lifecycle.assemble(() -> { throw failure; })));
        assertFalse(fixture.machine.active());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH));
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.withPersistence(() -> "invalid"));
        assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
        assertTrue(log.isEmpty());
    }

    @Test
    void failedSavePermanentlyStopsExecutionButStillAllowsLoadedListenerCleanup() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        fixture.registry.register(new Recorder("observer", log));
        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        var failure = new AssertionError("codec failed");
        assertSame(failure, assertThrows(AssertionError.class, () -> fixture.lifecycle.withPersistence(() -> { throw failure; })));
        assertFalse(fixture.machine.active());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.withPersistence(() -> "retry"));
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        assertEquals(List.of("load:observer", "unload:observer:CHUNK_UNLOAD"), log);
    }

    @Test
    void componentDependencyOrderPrecedesGlobalOrderAndCallbackEditsWaitForTheNextEvent() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var first = new Recorder("component_first", log);
        var second = new Recorder("component_second", log);
        var replacement = new Recorder("component_replacement", log);
        var firstType = type("component_first", first);
        var secondType = new ComponentType<>(id("component_second"), _ -> second,
            List.of(), List.of(firstType));
        fixture.machine.components().put(secondType, second);
        fixture.machine.components().put(firstType, first);
        var globalFirst = new Recorder("global_first", log);
        var globalSecond = new Recorder("global_second", log);
        var globalAdded = new Recorder("global_added", log);
        fixture.registry.register(globalFirst);
        var removed = fixture.registry.register(globalSecond);
        first.action = event -> {
            if (event instanceof MachineLifecycleEvent.Loaded) {
                fixture.machine.components().put(secondType, replacement);
                assertTrue(removed.cancel());
                fixture.registry.register(globalAdded);
            }
        };

        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED);
        assertEquals(List.of("load:component_first", "load:component_second", "load:global_first", "load:global_second"), log);
        assertFalse(fixture.machine.canProcessRecipes());
        log.clear();
        fixture.lifecycle.form(emptyHatches());
        assertEquals(List.of("form:component_first:true", "form:component_replacement:true",
            "form:global_first:true", "form:global_added:true"), log);
        assertTrue(fixture.machine.canProcessRecipes());
        log.clear();
        fixture.lifecycle.form(emptyHatches());
        assertTrue(log.isEmpty());
        fixture.lifecycle.invalidateStructure();
        assertEquals(List.of("form:component_first:false", "form:component_replacement:false",
            "form:global_first:false", "form:global_added:false"), log);
        assertFalse(fixture.machine.canProcessRecipes());
    }

    @Test
    void failedLoadUnloadsEveryCapturedListenerAndPreservesPrimaryAndCleanupFailures() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var first = new Recorder("first", log);
        var failing = new Recorder("failing", log);
        var unvisited = new Recorder("unvisited", log);
        var added = new Recorder("added", log);
        fixture.machine.components().put(type("first", first), first);
        fixture.registry.register(failing);
        var removed = fixture.registry.register(unvisited);
        var primary = new AssertionError("load failure");
        var firstCleanup = new IllegalStateException("first unload failure");
        var secondCleanup = new AssertionError("second unload failure");
        first.action = event -> {
            if (event instanceof MachineLifecycleEvent.Loaded) {
                assertTrue(removed.cancel());
                fixture.registry.register(added);
                fixture.machine.components().clear();
            } else if (event instanceof MachineLifecycleEvent.Unloaded unloaded) {
                assertSame(MachineLifecycleEvent.UnloadReason.LOAD_FAILED, unloaded.reason());
                assertFalse(fixture.machine.canProcessRecipes());
                throw firstCleanup;
            }
        };
        failing.action = event -> {
            if (event instanceof MachineLifecycleEvent.Loaded) {
                throw primary;
            }
            if (event instanceof MachineLifecycleEvent.Unloaded) {
                throw secondCleanup;
            }
        };

        assertSame(primary, assertThrows(AssertionError.class,
            () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH)));
        assertArrayEquals(new Throwable[]{firstCleanup, secondCleanup}, primary.getSuppressed());
        assertEquals(List.of("load:first", "load:failing", "unload:first:LOAD_FAILED",
            "unload:failing:LOAD_FAILED", "unload:unvisited:LOAD_FAILED"), log);
        installTick(fixture.machine, _ -> log.add("unexpected_tick"));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class,
            () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED));
        assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        assertEquals(5, log.size());
    }

    @Test
    void unloadClosesWorkBeforeEveryCleanupAndRemainsIdempotentAfterCleanupFailure() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var first = new Recorder("first", log);
        var second = new Recorder("second", log);
        var third = new Recorder("third", log);
        fixture.registry.register(first);
        fixture.registry.register(second);
        fixture.registry.register(third);
        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        fixture.lifecycle.form(emptyHatches());
        log.clear();
        var primary = new IllegalStateException("first unload fault");
        var additional = new AssertionError("second unload fault");
        first.action = event -> {
            if (event instanceof MachineLifecycleEvent.Unloaded) {
                assertFalse(fixture.machine.canProcessRecipes());
                assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
                assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
                throw primary;
            }
        };
        second.action = event -> {
            if (event instanceof MachineLifecycleEvent.Unloaded) {
                assertFalse(fixture.machine.canProcessRecipes());
                throw additional;
            }
        };

        assertSame(primary, assertThrows(IllegalStateException.class,
            () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD)));
        assertArrayEquals(new Throwable[]{additional}, primary.getSuppressed());
        assertEquals(List.of("unload:first:CHUNK_UNLOAD", "unload:second:CHUNK_UNLOAD", "unload:third:CHUNK_UNLOAD"), log);
        assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertEquals(3, log.size());
    }

    @Test
    void controllerBreakAttemptsEveryBreakAndUnloadCallbackWithOneClosedWorkBoundary() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var first = new Recorder("first", log);
        var second = new Recorder("second", log);
        fixture.registry.register(first);
        fixture.registry.register(second);
        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        fixture.lifecycle.form(emptyHatches());
        log.clear();
        var primary = new AssertionError("break fault");
        var additional = new IllegalStateException("unload fault");
        first.action = event -> {
            assertFalse(fixture.machine.canProcessRecipes());
            assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
            assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken);
            if (event instanceof MachineLifecycleEvent.ControllerBroken) {
                throw primary;
            }
        };
        second.action = event -> {
            assertFalse(fixture.machine.canProcessRecipes());
            if (event instanceof MachineLifecycleEvent.Unloaded) {
                throw additional;
            }
        };

        assertSame(primary, assertThrows(AssertionError.class, fixture.lifecycle::controllerBroken));
        assertArrayEquals(new Throwable[]{additional}, primary.getSuppressed());
        assertEquals(List.of("break:first", "break:second", "unload:first:INVALIDATED", "unload:second:INVALIDATED"), log);
        assertFalse(fixture.lifecycle.controllerBroken());
        assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertEquals(4, log.size());
    }

    @Test
    void lifecycleCallsRejectForeignThreadsWhileWorkerSchedulingUsesTransferredOwnership() throws Exception {
        var fixture = new Fixture();
        var callbackThreads = new ObjectArrayList<Thread>();
        fixture.registry.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                callbackThreads.add(Thread.currentThread());
            }
        });
        try (var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            try {
                worker.submit(() -> {
                    assertThrows(IllegalStateException.class,
                        () -> new MachineLifecycle(new MachineInstance(), fixture.host, fixture.registry));
                    assertThrows(IllegalStateException.class,
                        () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH));
                    assertThrows(IllegalStateException.class, () -> fixture.lifecycle.form(emptyHatches()));
                    assertThrows(IllegalStateException.class,
                        () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                    assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken);
                }).get(5, TimeUnit.SECONDS);
                assertTrue(callbackThreads.isEmpty());
                fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
                fixture.lifecycle.form(emptyHatches());
                assertEquals(List.of(Thread.currentThread()), callbackThreads);
                var tickThreads = new ObjectArrayList<Thread>();
                installTick(fixture.machine, _ -> tickThreads.add(Thread.currentThread()));
                worker.submit(fixture.machine::onSchedule).get(5, TimeUnit.SECONDS);
                assertEquals(1, tickThreads.size());
                assertNotSame(Thread.currentThread(), tickThreads.getFirst());
                fixture.machine.requirePersistenceBoundary();
                assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                fixture.machine.onSchedule();
                assertEquals(1, tickThreads.size());
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void executionAndLifecycleRejectOverlapAndRecursionUntilTheWorkerJoin() throws Exception {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var listener = new Recorder("listener", log);
        listener.action = _ -> {
            assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
            assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
            assertThrows(IllegalStateException.class, fixture.lifecycle::invalidateStructure);
            assertThrows(IllegalStateException.class,
                () -> fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH));
        };
        fixture.registry.register(listener);
        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        fixture.lifecycle.form(emptyHatches());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        installTick(fixture.machine, owner -> {
            assertThrows(IllegalStateException.class, owner::onSchedule);
            assertThrows(IllegalStateException.class, owner::requirePersistenceBoundary);
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
        try (var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            try {
                var execution = worker.submit(fixture.machine::onSchedule);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
                assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
                assertThrows(IllegalStateException.class, fixture.lifecycle::invalidateStructure);
                assertThrows(IllegalStateException.class,
                    () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken);
                release.countDown();
                execution.get(5, TimeUnit.SECONDS);
                fixture.machine.requirePersistenceBoundary();
                assertTrue(fixture.machine.canProcessRecipes());
                fixture.lifecycle.invalidateStructure();
                assertFalse(fixture.machine.canProcessRecipes());
                assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
        assertEquals(List.of("load:listener", "form:listener:true", "form:listener:false", "unload:listener:CHUNK_UNLOAD"), log);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("lifecycle_dispatch_test", name);
    }

    private static ComponentType<MachineInstance, Recorder> type(String name, Recorder recorder) {
        return new ComponentType<>(id(name), _ -> recorder, List.of());
    }

    private static MachineHatches emptyHatches() {
        return new MachineHatches(List.of(), List.of(), List.of());
    }

    private static void installTick(MachineInstance machine, Consumer<MachineInstance> action) {
        var system = new MachineSystem<String>() {
            @Override
            public ResourceLocation id() {
                return MachineLifecycleDispatchTest.id("recording_tick");
            }

            @Override
            public void onTick(MachineInstance owner, String component) {
                action.accept(owner);
            }
        };
        var type = new ComponentType<>(id("tick_component"), _ -> "state", List.of(system));
        machine.components().create(type, machine);
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final FakeHost host = new FakeHost(Thread.currentThread());
        private final MachineLifecycleRegistry registry = new MachineLifecycleRegistry();
        private final MachineLifecycle lifecycle = new MachineLifecycle(machine, host, registry);
    }

    private record FakeHost(Thread serverThread) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, new BlockPos(3, 4, 5));
        }

        @Override
        public World world() {
            throw new UnsupportedOperationException("No game world in lifecycle logic tests");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != serverThread) {
                throw new IllegalStateException("Lifecycle callback requires the fixture server thread");
            }
        }
    }

    private static final class Recorder implements MachineLifecycleListener {
        private final String name;
        private final List<String> log;
        private Consumer<MachineLifecycleEvent> action = _ -> {};

        private Recorder(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onLoaded(MachineLifecycleEvent.Loaded event) {
            log.add("load:" + name);
            action.accept(event);
        }

        @Override
        public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
            log.add("unload:" + name + ":" + event.reason());
            action.accept(event);
        }

        @Override
        public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
            log.add("form:" + name + ":" + event.context().formed());
            assertNotEquals(event.previousFormed(), event.context().formed());
            action.accept(event);
        }

        @Override
        public void onControllerBroken(MachineLifecycleEvent.ControllerBroken event) {
            log.add("break:" + name);
            action.accept(event);
        }
    }
}
