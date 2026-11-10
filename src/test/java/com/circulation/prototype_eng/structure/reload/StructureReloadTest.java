package com.circulation.prototype_eng.structure.reload;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.machine.ControllerStructureUpdates;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.structure.StructureRefresh;
import com.circulation.prototype_eng.structure.TemplateStructure;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureReloadTest {
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"), (_, _) -> fail("No native world"), UnaryOperator.identity(), String::equals);

    private static ResourceLocation id(String name) {
        return new ResourceLocation("reload_test", name);
    }

    private static TemplateStructure shape(String name, int x, Runnable matched) {
        return new TemplateStructure(id(name), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of(new BlockPos(x, 0, 0), StructurePredicates.test(MARKER, value -> {
                matched.run();
                return "body".equals(value);
            }))), List.of(), List.of());
    }

    private static StructureCatalog catalog(TemplateStructure... shapes) {
        return new StructureCatalog(Arrays.stream(shapes).map(StructureDefinition::new).toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void missingRequiredIdAndParseFailureRetainRuntimeHostAndClientGeneration() {
        try (var fixture = new Fixture()) {
            var before = fixture.registry.structureCatalog();
            var configuration = fixture.host.configuration;
            fixture.next.set(new StructureCatalog(List.of()));
            fixture.coordinator.reload(fixture.result::set);
            assertFalse(fixture.result.get().published());
            assertSame(before, fixture.registry.structureCatalog());
            assertSame(configuration, fixture.host.configuration);
            assertEquals(0, fixture.deliveries);
            assertEquals(0, fixture.coordinator.publication().view().sequence());
            fixture.loadFailure = new IllegalArgumentException("bad JSON file");
            fixture.coordinator.reload(fixture.result::set);
            assertSame(fixture.loadFailure, fixture.result.get().failure());
            assertSame(configuration, fixture.host.configuration);
            assertEquals(0, fixture.coordinator.publication().view().sequence());
        }
    }

    @Test
    void sameOrientationReloadRebuildsExternalHatchesAndRejectsBothOldSuccessAndFailure() {
        try (var fixture = new Fixture()) {
            fixture.updates.tick(0);
            fixture.scheduler.tick();
            assertEquals(List.of(fixture.host.first), MachineHatches.get(fixture.machine).resources());
            var refresh = new StructureRefresh(fixture.lifecycle, fixture.host);
            var oldRequest = Objects.requireNonNull(refresh.prepare());
            var oldResult = oldRequest.evaluate();
            var oldConfig = fixture.host.configuration;
            fixture.next.set(catalog(shape("shape", 2, () -> {
            }), shape("unused", 3, () -> {
            })));
            var originalAssembly = fixture.registry.assembly(fixture.type);
            fixture.coordinator.reload(fixture.result::set);
            assertTrue(fixture.result.get().published());
            assertNotSame(oldConfig, fixture.host.configuration);
            assertSame(fixture.registry.structureCatalog(), fixture.host.configuration.catalog());
            assertSame(BlockOrientation.NORTH_UP, fixture.host.configuration.orientation());
            assertNull(fixture.machine.components().get(MachineHatches.TYPE));
            assertFalse(refresh.publish(oldResult));
            refresh.fail(oldRequest, new IllegalStateException("obsolete failure"));
            assertTrue(fixture.lifecycle.schedulingAllowed());
            fixture.updates.tick(1);
            fixture.scheduler.tick();
            assertEquals(List.of(fixture.host.second), MachineHatches.get(fixture.machine).resources());
            assertSame(originalAssembly, fixture.registry.assembly(fixture.type));
            assertTrue(fixture.coordinator.publication().view().entries().containsKey(id("unused")));
        }
    }

    @Test
    void everyExternalHostPreflightsBeforeAnyGenerationIsRevokedEvenWithSamePhysicalIdentity() {
        try (var fixture = new Fixture()) {
            var extra = new Host();
            extra.configuration = new StructureConfiguration(fixture.registry.structureCatalog(),
                Objects.requireNonNull(fixture.registry.structureCatalog().get(id("unused"))).shape(), BlockOrientation.NORTH_UP);
            var machine = new MachineInstance(fixture.type);
            var lifecycle = new MachineLifecycle(machine, extra, new MachineLifecycleRegistry());
            extra.ownerLifecycle = lifecycle;
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            var updates = new ControllerStructureUpdates(machine, extra, fixture.scheduler, () -> extra.present, () -> {
            });
            var registration = fixture.coordinator.register(updates);
            try (registration) {
                var before = fixture.host.configuration;
                var extraBefore = extra.configuration;
                fixture.next.set(catalog(shape("shape", 2, () -> {
                })));
                fixture.coordinator.reload(fixture.result::set);
                assertFalse(fixture.result.get().published());
                assertSame(before, fixture.host.configuration);
                assertSame(extraBefore, extra.configuration);
                assertEquals(0, fixture.host.revocations);
                assertEquals(0, extra.revocations);
                assertEquals(0, fixture.deliveries);
            } finally {
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void detachedHandleCannotRemoveReplacementRegistrationAndOwnerChangeCannotRefreshANewHost() {
        try (var fixture = new Fixture()) {
            fixture.registration.close();
            var replacement = fixture.coordinator.register(fixture.updates);
            fixture.registration.close();
            fixture.next.set(catalog(shape("shape", 2, () -> {
            })));
            fixture.coordinator.reload(fixture.result::set);
            assertEquals(1, fixture.host.revocations);
            replacement.close();
            fixture.host.present = false;
            var replacementHost = new Host();
            var replacementConfig = replacementHost.configuration;
            fixture.next.set(catalog(shape("shape", 3, () -> {
            })));
            fixture.coordinator.reload(fixture.result::set);
            assertSame(replacementConfig, replacementHost.configuration);
            assertEquals(0, replacementHost.revocations);
        }
    }

    @Test
    void queuedReloadCannotPublishUntilTheRealMatchingWorkerHasExited() throws Exception {
        try (var fixture = new Fixture(); var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            fixture.updates.tick(0);
            fixture.scheduler.tick();
            assertEquals(List.of(fixture.host.first), MachineHatches.get(fixture.machine).resources());
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var slow = shape("shape", 1, () -> {
                assertNotSame(fixture.host.owner, Thread.currentThread());
                started.countDown();
                try {
                    await(release);
                } finally {
                    exited.countDown();
                }
            });
            fixture.host.configuration = new StructureConfiguration(slow, BlockOrientation.NORTH_UP);
            fixture.next.set(catalog(shape("shape", 2, () -> {
            })));
            var before = fixture.registry.structureCatalog();
            var request = observer.submit(() -> {
                await(started);
                fixture.coordinator.reload(result -> {
                    assertSame(fixture.host.owner, Thread.currentThread());
                    assertEquals(0, exited.getCount());
                    fixture.result.set(result);
                });
                assertSame(before, fixture.registry.structureCatalog());
                release.countDown();
            });
            try {
                fixture.updates.tick(5);
                fixture.scheduler.tick();
                request.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertTrue(fixture.result.get().published());
            assertSame(fixture.registry.structure(fixture.type), fixture.host.configuration.definition());
            fixture.updates.tick(6);
            fixture.scheduler.tick();
            assertEquals(List.of(fixture.host.second), MachineHatches.get(fixture.machine).resources());
        }
    }

    @Test
    void staleReplacementCannotPublishAfterAnotherRuntimeWasCommitted() {
        try (var fixture = new Fixture()) {
            var first = fixture.registry.prepareReplacement(catalog(shape("shape", 2, () -> {
            })));
            var stale = fixture.registry.prepareReplacement(catalog(shape("shape", 3, () -> {
            })));
            fixture.registry.publishReplacement(first);
            assertThrows(IllegalStateException.class, () -> fixture.registry.publishReplacement(stale));
            assertSame(first.catalog(), fixture.registry.structureCatalog());
        }
    }

    @Test
    void pauseCallbackFailureRetainsTheOldDirectoryButRevokesEveryOldConfigurationBeforeCallbacks() {
        try (var fixture = new Fixture()) {
            fixture.updates.tick(0);
            fixture.scheduler.tick();
            var extra = new Host();
            var machine = new MachineInstance(fixture.type);
            var lifecycle = new MachineLifecycle(machine, extra, new MachineLifecycleRegistry());
            extra.ownerLifecycle = lifecycle;
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            var updates = new ControllerStructureUpdates(machine, extra, fixture.scheduler, () -> extra.present, () -> {
            });
            var registration = fixture.coordinator.register(updates);
            var before = fixture.registry.structureCatalog();
            var subscription = fixture.listeners.register(new MachineLifecycleListener() {
                @Override
                public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
                    if (!event.context().formed()) {
                        assertNull(fixture.host.configuration);
                        assertNull(extra.configuration);
                        throw new IllegalStateException("pause callback failed");
                    }
                }
            });
            try {
                fixture.next.set(catalog(shape("shape", 2, () -> {
                })));
                assertThrows(IllegalStateException.class, () -> fixture.coordinator.reload(fixture.result::set));
                assertFalse(fixture.result.get().published());
                assertSame(before, fixture.registry.structureCatalog());
                assertNull(fixture.host.configuration);
                assertNull(extra.configuration);
                assertNull(fixture.machine.components().get(MachineHatches.TYPE));
                assertNull(machine.components().get(MachineHatches.TYPE));
                assertEquals(0, fixture.coordinator.publication().view().sequence());
                assertEquals(0, fixture.deliveries);
            } finally {
                subscription.close();
                registration.close();
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void externalOwnerRevocationInsideDiscoveryCannotPublishEvenIfHostStillReturnsTheOldConfiguration() {
        try (var fixture = new Fixture()) {
            fixture.host.reportAbsent = false;
            fixture.host.onDiscovery = () -> fixture.host.present = false;
            fixture.updates.tick(0);
            fixture.scheduler.tick();
            assertNotNull(fixture.host.configuration);
            assertFalse(fixture.lifecycle.formed());
            assertNull(fixture.machine.components().get(MachineHatches.TYPE));
        }
    }

    @Test
    void actualLoadedCallbackEnrollsIndependentHostsWithoutChangingBindingsBeforeBadJson() {
        try (var fixture = new Fixture()) {
            var host = new Host();
            var machine = new MachineInstance(fixture.type);
            var listeners = new MachineLifecycleRegistry();
            var lifecycle = new MachineLifecycle(machine, host, listeners);
            host.ownerLifecycle = lifecycle;
            var subscription = listeners.register(new MachineLifecycleListener() {
                @Override
                public void onLoaded(MachineLifecycleEvent.Loaded event) {
                    fixture.coordinator.loaded(event.context().machine(), host);
                    assertFalse(fixture.coordinator.registered(lifecycle));
                }
            });
            try (subscription) {
                var before = host.configuration;
                lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
                fixture.coordinator.enrollLoaded();
                var automatic = fixture.coordinator.updates(lifecycle);
                assertNotNull(automatic);
                assertSame(before, host.configuration);
                assertNull(machine.components().get(MachineHatches.TYPE));
                assertEquals(0, host.revocations);
                fixture.loadFailure = new IllegalArgumentException("bad JSON file");
                fixture.coordinator.reload(fixture.result::set);
                assertFalse(fixture.result.get().published());
                assertSame(before, host.configuration);
                assertEquals(0, host.revocations);
                fixture.coordinator.tickLoaded(0);
                fixture.scheduler.tick();
                assertEquals(List.of(host.first), MachineHatches.get(machine).resources());
                int reads = host.reads;
                fixture.coordinator.loaded(machine, host);
                fixture.coordinator.enrollLoaded();
                assertSame(automatic, fixture.coordinator.updates(lifecycle));
                fixture.coordinator.tickLoaded(20);
                fixture.scheduler.tick();
                assertTrue(host.reads > reads);
                fixture.loadFailure = null;
                fixture.next.set(catalog(shape("shape", 2, () -> {
                })));
                fixture.coordinator.reload(fixture.result::set);
                assertTrue(fixture.result.get().published());
                assertSame(fixture.registry.structureCatalog(), host.configuration.catalog());
                fixture.coordinator.tickLoaded(21);
                fixture.scheduler.tick();
                assertEquals(List.of(host.second), MachineHatches.get(machine).resources());
            } finally {
                fixture.coordinator.unloaded(lifecycle);
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    void loadedTrackingDoesNotDuplicateOrTickAnExistingExplicitUpdateOwner() {
        try (var fixture = new Fixture()) {
            var before = fixture.host.configuration;
            fixture.coordinator.loaded(fixture.machine, fixture.host);
            fixture.coordinator.tickLoaded(0);
            fixture.scheduler.tick();
            assertSame(fixture.updates, fixture.coordinator.updates(fixture.lifecycle));
            assertSame(before, fixture.host.configuration);
            assertEquals(0, fixture.host.reads);
            assertNull(fixture.machine.components().get(MachineHatches.TYPE));
            fixture.updates.tick(0);
            fixture.scheduler.tick();
            assertEquals(List.of(fixture.host.first), MachineHatches.get(fixture.machine).resources());
        }
    }

    @Test
    void reusedHostRejectsOldWorkerAndLateUnloadWithoutRemovingTheReplacementLifecycle() {
        try (var fixture = new Fixture()) {
            var refresh = new StructureRefresh(fixture.lifecycle, fixture.host, fixture.updates::loaded);
            var oldRequest = Objects.requireNonNull(refresh.prepare());
            var oldResult = oldRequest.evaluate();
            fixture.coordinator.loaded(fixture.machine, fixture.host);
            var machine = new MachineInstance(fixture.type);
            var lifecycle = new MachineLifecycle(machine, fixture.host, new MachineLifecycleRegistry());
            fixture.host.ownerLifecycle = lifecycle;
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            try {
                fixture.coordinator.loaded(machine, fixture.host);
                fixture.coordinator.enrollLoaded();
                var replacement = Objects.requireNonNull(fixture.coordinator.updates(lifecycle));
                var before = fixture.host.configuration;
                assertFalse(fixture.updates.loaded());
                assertFalse(refresh.publish(oldResult));
                refresh.fail(oldRequest, new IllegalStateException("old host worker"));
                assertTrue(lifecycle.schedulingAllowed());
                fixture.coordinator.unloaded(fixture.lifecycle);
                fixture.registration.close();
                assertSame(replacement, fixture.coordinator.updates(lifecycle));
                assertSame(before, fixture.host.configuration);
                fixture.next.set(catalog(shape("shape", 2, () -> {
                })));
                fixture.coordinator.reload(fixture.result::set);
                assertTrue(fixture.result.get().published());
                fixture.coordinator.tickLoaded(1);
                fixture.scheduler.tick();
                assertEquals(List.of(fixture.host.second), MachineHatches.get(machine).resources());
                assertNull(fixture.machine.components().get(MachineHatches.TYPE));
            } finally {
                fixture.coordinator.unloaded(lifecycle);
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    @Test
    @SuppressWarnings("resource")
        // The expected rejection creates no handle; the accepted owner is scoped below.
    void foreignSchedulerRejectionLeavesNoOwnerAndTheCorrectSchedulerCanStillRegister() {
        try (var fixture = new Fixture(); var foreignScheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            var host = new Host();
            var machine = new MachineInstance(fixture.type);
            var lifecycle = new MachineLifecycle(machine, host, new MachineLifecycleRegistry());
            host.ownerLifecycle = lifecycle;
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            try {
                fixture.coordinator.loaded(machine, host);
                var before = host.configuration;
                // Without a native server session this is a valid independent logical owner.
                // The live-session constructor forwards to the same rejecting register operation.
                var foreign = new ControllerStructureUpdates(machine, host, foreignScheduler, () -> host.present, () -> {
                });
                assertThrows(IllegalStateException.class, () -> fixture.coordinator.register(foreign));
                assertNull(fixture.coordinator.updates(lifecycle));
                assertSame(before, host.configuration);
                assertEquals(0, host.revocations);
                assertNull(machine.components().get(MachineHatches.TYPE));
                foreign.detach();
                var proper = new ControllerStructureUpdates(machine, host, fixture.scheduler, () -> host.present, () -> {
                });
                try (var _ = fixture.coordinator.register(proper)) {
                    fixture.coordinator.enrollLoaded();
                    fixture.coordinator.tickLoaded(0);
                    fixture.scheduler.tick();
                    assertSame(proper, fixture.coordinator.updates(lifecycle));
                    assertEquals(0, host.reads);
                    proper.tick(0);
                    fixture.scheduler.tick();
                    assertEquals(List.of(host.first), MachineHatches.get(machine).resources());
                }
            } finally {
                fixture.coordinator.unloaded(lifecycle);
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final TaskScheduler scheduler = new TaskScheduler(Thread.currentThread(), 2, 2);
        private final MachineType type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).executionMode(ExecutionMode.MAIN_THREAD).build();
        private final MachineTypeRegistry registry = new MachineTypeRegistry();
        private final Host host = new Host();
        private final MachineInstance machine = new MachineInstance(type);
        private final MachineLifecycleRegistry listeners = new MachineLifecycleRegistry();
        private final MachineLifecycle lifecycle = new MachineLifecycle(machine, host, listeners);
        private final AtomicReference<StructureCatalog> next = new AtomicReference<>();
        private final AtomicReference<StructureReloadCoordinator.Result> result = new AtomicReference<>();
        private final StructureReloadCoordinator coordinator;
        private final ControllerStructureUpdates updates;
        private final StructureReloadCoordinator.Registration registration;
        private RuntimeException loadFailure;
        private int deliveries;

        private Fixture() {
            var initial = catalog(shape("shape", 1, () -> {
            }), shape("unused", 3, () -> {
            }));
            registry.register(type);
            registry.freeze();
            registry.resolveRuntime(initial, List.of(MachineAssembly.builder(type, "v1").build()));
            host.configuration = new StructureConfiguration(initial, registry.structure(type), BlockOrientation.NORTH_UP);
            host.ownerLifecycle = lifecycle;
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            next.set(initial);
            coordinator = new StructureReloadCoordinator(scheduler, registry, () -> {
                if (loadFailure != null) throw loadFailure;
                return next.get();
            }, _ -> deliveries++);
            updates = new ControllerStructureUpdates(machine, host, scheduler, () -> host.present, () -> {
            });
            registration = coordinator.register(updates);
        }

        @Override
        public void close() {
            registration.close();
            coordinator.close();
            try {
                lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            } finally {
                scheduler.close();
            }
        }
    }

    private static final class Host implements ReloadableStructureHost {
        private final Thread owner = Thread.currentThread();
        private final Hatch first = new Hatch(), second = new Hatch();
        private StructureConfiguration configuration = new StructureConfiguration(shape("shape", 1, () -> {
        }), BlockOrientation.NORTH_UP);
        private boolean present = true;
        private boolean reportAbsent = true;
        private Runnable onDiscovery = () -> {
        };
        private int revocations;
        private int reads;
        private MachineLifecycle ownerLifecycle;

        @Override
        public void installStructureConfiguration(@Nullable StructureConfiguration value) {
            requireServerThread();
            configuration = value;
            if (value == null) revocations++;
        }

        @Override
        public boolean structureLifecycleCurrent(MachineLifecycle lifecycle) {
            requireServerThread();
            return ownerLifecycle == lifecycle;
        }

        @Override
        public @Nullable StructureConfiguration structureConfiguration() {
            requireServerThread();
            return present || !reportAbsent ? configuration : null;
        }

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No native world");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong owner");
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(MARKER, query);
            reads++;
            return (T) "body";
        }

        @Override
        public StructureHatchBinding hatchesAt(BlockPos position) {
            requireServerThread();
            onDiscovery.run();
            var hatch = position.getX() == 1 ? first : second;
            return new StructureHatchBinding(hatch, hatch, null, null);
        }
    }

    private static final class Hatch implements ResourceHatch {
        @Override
        public @Nullable ResourceStorage input() {
            return null;
        }

        @Override
        public @Nullable ResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
