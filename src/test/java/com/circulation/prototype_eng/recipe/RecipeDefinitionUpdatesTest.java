package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeDefinitionUpdatesTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("definition_updates", name);
    }

    private static MachineRecipe recipe(int duration) {
        return new MachineRecipe(id("recipe"), duration, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for worker handoff");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Test
    void adoptedRegistryRejectsStandaloneIdleMainAndWorkerInstallationButAcceptsActualBoundary() {
        var old = recipe(2);
        var next = recipe(4);
        var fixture = new Fixture(old, false);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            try (var publication = fixture.registry.bindPublication(scheduler, UUID.randomUUID())) {
                var prepared = fixture.registry.prepareReplacement(fixture.registry.snapshot(), List.of(new RecipeRegistry.Definition(next, "v2")));
                assertThrows(IllegalStateException.class, () -> fixture.registry.install(prepared));
                assertThrows(IllegalStateException.class, () -> publication.install(prepared));
                scheduler.submit(() -> assertThrows(IllegalStateException.class, () -> publication.install(prepared)), ExecutionMode.MAIN_THREAD);
                scheduler.submit(() -> assertThrows(IllegalStateException.class, () -> publication.install(prepared)), ExecutionMode.CONCURRENT);
                scheduler.tick();
                assertSame(old, fixture.registry.catalog().recipes().getFirst());
                scheduler.runAtBoundary(() -> publication.install(prepared));
                assertSame(next, fixture.registry.catalog().recipes().getFirst());
            }
        }
    }

    @Test
    void updateWaitsForWorkerExitAndMakesBothCodecAndPnAvailableBeforeNotification() throws Exception {
        var old = recipe(2);
        var next = recipe(4);
        var fixture = new Fixture(old, true);
        var nextCodec = RecipeProcessNbt.builder(next, "v2").build();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var future = new AtomicReference<CompletableFuture<RecipeDefinitionUpdates.Result>>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
             var updates = new RecipeDefinitionUpdates(fixture.registry, fixture.extensions, scheduler, UUID.randomUUID(), notice -> {
                 assertEquals(0, exited.getCount());
                 assertSame(next, notice.current().catalog().recipes().getFirst());
                 assertSame(nextCodec, fixture.codecs.snapshot().require(next));
                 assertEquals(4, fixture.extensions.require(next).resources().getDouble(ComputingResourceFixtures.COMPUTE));
             })) {
            var before = fixture.registry.snapshot();
            scheduler.submit(() -> {
                future.set(updates.replace(updates.sessionId(), before, List.of(new RecipeRegistry.Definition(next, "v2")), List.of(nextCodec)));
                entered.countDown();
                try {
                    await(release);
                } finally {
                    exited.countDown();
                }
            }, ExecutionMode.CONCURRENT);
            var check = observer.submit(() -> {
                await(entered);
                try {
                    assertSame(before, fixture.registry.snapshot());
                    assertFalse(future.get().isDone());
                    assertThrows(IllegalArgumentException.class, () -> fixture.extensions.require(next));
                } finally {
                    release.countDown();
                }
            });
            try {
                scheduler.tick();
                check.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertEquals(RecipeDefinitionUpdates.Status.PUBLISHED, future.get().join().status());
            assertSame(old, fixture.codecs.snapshot().require(old.id(), "v1").recipe());
        }
    }

    @Test
    void closeAndReopenWithUnchangedCatalogRejectsOldPreparedHandleSessionAndQueuedRequest() {
        var old = recipe(2);
        var next = recipe(4);
        var fixture = new Fixture(old, false);
        var expected = fixture.registry.snapshot();
        RecipeRegistry.Prepared oldPrepared;
        RecipeDefinitionUpdates oldUpdates;
        var queued = new AtomicReference<CompletableFuture<RecipeDefinitionUpdates.Result>>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            oldUpdates = new RecipeDefinitionUpdates(fixture.registry, fixture.extensions, scheduler, UUID.randomUUID(), _ -> {
            });
            oldPrepared = fixture.registry.prepareReplacement(expected, List.of(new RecipeRegistry.Definition(next, "v2")));
            scheduler.submit(() -> {
                queued.set(oldUpdates.submit(oldUpdates.sessionId(), oldPrepared));
                oldUpdates.stopAccepting();
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(RecipeDefinitionUpdates.Status.REJECTED_SESSION, queued.get().join().status());
        }
        oldUpdates.close(); // Actual server cleanup runs after scheduler termination.
        assertSame(expected, fixture.registry.snapshot());
        assertThrows(IllegalStateException.class, () -> fixture.registry.install(oldPrepared));
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1);
             var updates = new RecipeDefinitionUpdates(fixture.registry, fixture.extensions, scheduler, UUID.randomUUID(), _ -> {
             })) {
            assertEquals(RecipeDefinitionUpdates.Status.REJECTED_STALE, updates.submit(updates.sessionId(), oldPrepared).join().status());
            assertEquals(RecipeDefinitionUpdates.Status.REJECTED_SESSION, updates.submit(oldUpdates.sessionId(), oldPrepared).join().status());
            assertEquals(RecipeDefinitionUpdates.Status.REJECTED_SESSION, oldUpdates.submit(oldUpdates.sessionId(), oldPrepared).join().status());
            assertEquals(RecipeDefinitionUpdates.Status.PUBLISHED,
                updates.replace(updates.sessionId(), expected, List.of(new RecipeRegistry.Definition(next, "v2")), List.of()).join().status());
        }
    }

    @Test
    void configurationAndNotificationFailuresDoNotFaultSchedulerOrReplayCallbacks() {
        var old = recipe(2);
        var failed = recipe(99);
        var next = recipe(4);
        var fixture = new Fixture(old, false);
        var callbackFailure = fixture.configurationFailure;
        var notificationFailure = new IllegalStateException("notification failed");
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1);
             var updates = new RecipeDefinitionUpdates(fixture.registry, fixture.extensions, scheduler, UUID.randomUUID(), _ -> {
                 throw notificationFailure;
             })) {
            var expected = fixture.registry.snapshot();
            for (int i = 0; i < 2; i++) {
                var result = updates.replace(updates.sessionId(), expected, List.of(new RecipeRegistry.Definition(failed, "bad")), List.of()).join();
                assertEquals(RecipeDefinitionUpdates.Status.PREPARATION_FAILED, result.status());
                assertSame(callbackFailure, result.failure());
            }
            assertEquals(2, fixture.calls);
            assertSame(expected, fixture.registry.snapshot());
            assertThrows(IllegalArgumentException.class, () -> fixture.extensions.require(failed));
            var result = updates.replace(updates.sessionId(), expected, List.of(new RecipeRegistry.Definition(next, "v2")), List.of()).join();
            assertEquals(RecipeDefinitionUpdates.Status.NOTIFICATION_FAILED, result.status());
            assertTrue(result.published());
            assertSame(notificationFailure, result.failure());
            assertSame(next, fixture.registry.catalog().recipes().getFirst());
            assertEquals(RecipeDefinitionUpdates.Status.REJECTED_STALE,
                updates.replace(updates.sessionId(), expected, List.of(new RecipeRegistry.Definition(recipe(8), "v3")), List.of()).join().status());
            assertEquals(3, fixture.calls);
            assertFalse(scheduler.status().faulted());
            scheduler.tick();
        }
    }

    @Test
    void occupiedNetworkPublisherDoesNotAdoptASecondRegistryOnFailedConstruction() {
        var old = recipe(2);
        var next = recipe(4);
        var fixture = new Fixture(old, false);
        var independent = new RecipeRegistry();
        independent.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(old)));
        independent.declareRevision(old, "v1");
        independent.freeze();
        var prepared = independent.prepareReplacement(independent.snapshot(), List.of(new RecipeRegistry.Definition(next, "v2")));
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1);
             var active = new RecipeDefinitionUpdates(fixture.registry, fixture.extensions, scheduler, UUID.randomUUID(), _ -> {
             })) {
            assertTrue(active.accepting());
            assertThrows(IllegalStateException.class,
                () -> new RecipeDefinitionUpdates(independent, fixture.extensions, scheduler, UUID.randomUUID(), _ -> {
                }));
            independent.install(prepared);
            assertSame(next, independent.catalog().recipes().getFirst());
        }
    }

    @Test
    void closedSchedulerCannotPermanentlyAdoptAStandaloneRegistry() {
        var fixture = new Fixture(recipe(2), false);
        TaskScheduler stopped;
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            stopped = scheduler;
        }
        assertThrows(IllegalStateException.class, () -> fixture.registry.bindPublication(stopped, UUID.randomUUID()));
        var next = recipe(4);
        var prepared = fixture.registry.prepareReplacement(fixture.registry.snapshot(), List.of(new RecipeRegistry.Definition(next, "v2")));
        fixture.registry.install(prepared);
        assertSame(next, fixture.registry.catalog().recipes().getFirst());
    }

    @Test
    void differentSchedulerCannotAdoptUntilThePreviousOwnerProvesTermination() {
        var fixture = new Fixture(recipe(2), false);
        RecipeRegistry.Publication last;
        try (var first = new TaskScheduler(Thread.currentThread(), 1, 1);
             var second = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var original = fixture.registry.bindPublication(first, UUID.randomUUID());
            original.close();
            assertThrows(IllegalStateException.class, () -> fixture.registry.bindPublication(second, UUID.randomUUID()));
            // Reopening on the same scheduler still uses its original joined boundary.
            last = fixture.registry.bindPublication(first, UUID.randomUUID());
            last.close();
        }
        last.close(); // Refreshes the proof after the borrowed scheduler has actually stopped.
        try (var next = new TaskScheduler(Thread.currentThread(), 1, 1);
             var publication = fixture.registry.bindPublication(next, UUID.randomUUID())) {
            assertTrue(publication.live());
        }
    }

    private static final class Fixture {
        private final RecipeRegistry registry = new RecipeRegistry();
        private final NetworkRecipeExtensions extensions = new NetworkRecipeExtensions();
        private final RecipeProcessCodecs codecs;
        private final IllegalArgumentException configurationFailure = new IllegalArgumentException("new definition failed");
        private int calls;

        private Fixture(MachineRecipe old, boolean persistent) {
            var owner = Thread.currentThread();
            registry.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(old)));
            registry.declareRevision(old, "v1");
            codecs = new RecipeProcessCodecs(List.of(RecipeProcessNbt.builder(old, "v1").build()));
            if (persistent) registry.bindProcessCodecs(codecs);
            registry.freeze();
            var research = new ResearchRegistry();
            research.freeze();
            extensions.register((recipe, builder) -> {
                assertSame(owner, Thread.currentThread());
                assertFalse(Thread.holdsLock(registry));
                assertFalse(Thread.holdsLock(extensions));
                calls++;
                if (recipe.durationTicks() == 99) throw configurationFailure;
                builder.resource(ComputingResourceFixtures.COMPUTE, recipe.durationTicks());
            });
            extensions.publish(registry.snapshot(), research);
        }
    }
}
