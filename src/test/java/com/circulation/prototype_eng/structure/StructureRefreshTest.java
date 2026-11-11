package com.circulation.prototype_eng.structure;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.AffinityKeyProvider;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureComponentProvider;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureRefreshTest {
    private static final BlockPos FIRST = new BlockPos(1, 0, 0);
    private static final BlockPos SECOND = new BlockPos(2, 0, 0);
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"),
        (_, _) -> fail("Logical tests never read a game world"), UnaryOperator.identity(), String::equals);
    private static final ComponentType<MachineInstance, ExternalPort> PORT = new ComponentType<>(id("port"),
        _ -> fail("Structure port discovery must not invoke component factories"), List.of(new MachineSystem<>() {
        @Override
        public ResourceLocation id() {
            return StructureRefreshTest.id("port_system");
        }

        @Override
        public void onTick(MachineInstance owner, ExternalPort component) {
            fail("Lightweight structure ports must not tick their component type systems");
        }
    }));

    private static MachineStructure reusableDefinition(AtomicInteger declarations, AtomicInteger transforms) {
        StructurePredicate predicate = new StructurePredicate() {
            @Override
            public boolean planReusable() {
                return true;
            }

            @Override
            public StructurePredicate transform(StructureTransform rotation) {
                transforms.incrementAndGet();
                return this;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
                declarations.incrementAndGet();
                plan.read(position, MARKER);
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return "body".equals(snapshot.read(MARKER, position));
            }
        };
        return TemplateStructure.create(id("reusable"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of(FIRST, predicate)), List.of(), List.of());
    }

    private static StructureRefresh.Request parallelRequest(Fixture fixture) {
        var preparation = fixture.refresh.beginParallelPrepare();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            scheduler.runAtBoundary(() -> {
                while (!preparation.complete()) {
                    var action = preparation.prepareWorker(new RecipeWorkBudget(Long.MAX_VALUE));
                    if (action != null) {
                        var result = scheduler.runStructureWorker(() -> {
                            action.run();
                            return true;
                        }, PlannedDeadline.after(Duration.ofSeconds(5)));
                        assertInstanceOf(PlannedTaskExecutor.Success.class, result);
                        preparation.finishWorker(true, null);
                    }
                }
            });
        }
        return Objects.requireNonNull(preparation.result());
    }

    private static StructureHatchBinding binding(ResourceHatch hatch) {
        return new StructureHatchBinding(new Object(), hatch, null, null);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("structure_refresh_test", path);
    }

    private static StructureComponentProvider.Entry<ExternalPort> entry(String portId, ExternalPort port) {
        return new StructureComponentProvider.Entry<>(PORT, portId, port);
    }

    private static StructureConfiguration planningFailure(AssertionError failure, Runnable beforeFailure) {
        return new StructureConfiguration(new TemplateStructure(id("legacy_planning_failure"), BlockOrientation.NORTH_UP,
            BlockPos.ORIGIN, false, new StructurePattern(Map.of(FIRST, StructurePredicates.test(MARKER, "body"::equals))),
            List.of(), List.of()) {
            @Override
            public StructureOperation<MachineStructure> beginTransform(StructureTransform rotation) {
                beforeFailure.run();
                throw failure;
            }
        }, BlockOrientation.NORTH_UP);
    }

    @Test
    void anIncompletePlanCannotBeAdoptedByANewerPreparationEvenWithoutACatalog() {
        var fixture = new Fixture();
        var declarations = new AtomicInteger();
        var transforms = new AtomicInteger();
        fixture.host.configuration = new StructureConfiguration(reusableDefinition(declarations, transforms), BlockOrientation.NORTH_UP);
        var obsolete = fixture.refresh.beginPrepare(null);
        int slices = 0;
        while (declarations.get() == 0) {
            assertFalse(obsolete.advance(new RecipeWorkBudget(1)));
            assertTrue(++slices < 1000);
        }
        assertFalse(obsolete.complete());
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertEquals(2, transforms.get());
        assertEquals(2, declarations.get(), "The unfinished declaration/freeze is not a reusable full plan");
        assertTrue(obsolete.advance(new RecipeWorkBudget(Long.MAX_VALUE)));
        assertNull(obsolete.result());
        assertTrue(fixture.refresh.publish(parallelRequest(fixture).evaluate()));
        assertEquals(2, transforms.get(), "A standalone host also retains its completed plan");
        assertEquals(2, declarations.get());
    }

    @Test
    void synchronousAndParallelPreparationReuseOnlyThePurePlanWhileWorldFactsKeepChanging() {
        var fixture = new Fixture();
        var declarations = new AtomicInteger();
        var transforms = new AtomicInteger();
        var shape = reusableDefinition(declarations, transforms);
        var catalog = new StructureCatalog(List.of(new StructureDefinition(shape)));
        fixture.host.configuration = new StructureConfiguration(catalog, shape, BlockOrientation.NORTH_UP);
        var first = fixture.request();
        assertTrue(fixture.refresh.publish(first.evaluate()));
        int initialReads = fixture.host.reads;
        fixture.host.values.put(FIRST, "missing");
        var second = parallelRequest(fixture);
        assertTrue(fixture.refresh.publish(second.evaluate()));
        assertFalse(fixture.lifecycle.formed());
        assertTrue(fixture.host.reads > initialReads);
        assertEquals(1, declarations.get());
        assertEquals(1, transforms.get());
        fixture.host.values.put(FIRST, "body");
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertTrue(fixture.lifecycle.formed());
        assertEquals(1, declarations.get());
        fixture.refresh.invalidate();
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertEquals(2, declarations.get(), "Explicit invalidation clears the one-host plan slot");
        assertEquals(1, transforms.get(), "The canonical pure rotation remains reusable");
    }

    @Test
    void hostPositionConfigurationAbaAndCatalogReplacementCannotReuseAnOldAbsolutePlan() {
        var fixture = new Fixture();
        var declarations = new AtomicInteger();
        var transforms = new AtomicInteger();
        var shape = reusableDefinition(declarations, transforms);
        var catalog = new StructureCatalog(List.of(new StructureDefinition(shape)));
        fixture.host.configuration = new StructureConfiguration(catalog, shape, BlockOrientation.NORTH_UP);
        var obsolete = fixture.result();
        fixture.host.controllerPosition = new BlockPos(10, 0, 0);
        fixture.host.values.put(new BlockPos(11, 0, 0), "body");
        fixture.request();
        assertEquals(2, declarations.get());
        assertEquals(1, transforms.get());
        assertEquals(new BlockPos(11, 0, 0), fixture.host.observedReads.getLast());
        fixture.host.configuration = new StructureConfiguration(catalog, shape, BlockOrientation.SOUTH_UP);
        fixture.request();
        fixture.host.configuration = new StructureConfiguration(catalog, shape, BlockOrientation.NORTH_UP);
        fixture.request();
        assertEquals(4, declarations.get());
        assertEquals(2, transforms.get());
        fixture.host.configuration = new StructureConfiguration(new StructureCatalog(List.of(new StructureDefinition(shape))), shape, BlockOrientation.NORTH_UP);
        fixture.request();
        assertEquals(5, declarations.get());
        assertEquals(3, transforms.get());
        var replacement = reusableDefinition(declarations, transforms);
        fixture.host.configuration = new StructureConfiguration(new StructureCatalog(List.of(new StructureDefinition(replacement))), replacement, BlockOrientation.NORTH_UP);
        fixture.request();
        assertEquals(6, declarations.get());
        assertEquals(4, transforms.get());
        assertFalse(fixture.refresh.publish(obsolete));
    }

    @Test
    void insufficientAtomicNotificationBudgetKeepsTheAcceptedTreeAndEmitsNoPartialEvent() {
        var fixture = new Fixture();
        assertTrue(fixture.refresh.publish(fixture.result()));
        var accepted = fixture.lifecycle.structureMatch();
        var hatches = fixture.machine.components().get(MachineHatches.TYPE);
        fixture.machine.components().orderedBindings();
        var notifications = new AtomicInteger();
        var subscriptions = new ObjectArrayList<MachineLifecycleRegistry.Subscription>();
        try {
            for (int index = 0; index < 30; index++)
                subscriptions.add(fixture.registry.register(new MachineLifecycleListener() {
                    @Override
                    public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
                        notifications.incrementAndGet();
                    }
                }));
            var prepared = new AtomicBoolean();
            assertFalse(fixture.lifecycle.refreshStructure(() -> true, () -> {
                prepared.set(true);
                return StructureAssembly.UNFORMED;
            }, new RecipeWorkBudget(60)));
            assertTrue(prepared.get());
            assertTrue(fixture.lifecycle.formed());
            assertSame(accepted, fixture.lifecycle.structureMatch());
            assertSame(hatches, fixture.machine.components().get(MachineHatches.TYPE));
            assertEquals(0, notifications.get());
            assertTrue(fixture.lifecycle.refreshStructure(() -> true, () -> StructureAssembly.UNFORMED));
            assertEquals(30, notifications.get());
            assertFalse(fixture.lifecycle.formed());
        } finally {
            for (var subscription : subscriptions) subscription.close();
        }
    }

    @Test
    void aMismatchChangedByItsRealPredicateCannotRevokeTheAcceptedFormation() {
        var fixture = new Fixture();
        assertTrue(fixture.refresh.publish(fixture.result()));
        var accepted = fixture.lifecycle.structureMatch();
        var predicate = StructurePredicates.test(MARKER, value -> {
            if (!"body".equals(value)) fixture.host.values.put(FIRST, "body");
            return "body".equals(value);
        });
        fixture.host.configuration = new StructureConfiguration(TemplateStructure.create(id("changing_mismatch"),
            BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false, new StructurePattern(Map.of(FIRST, predicate)),
            List.of(), List.of()), BlockOrientation.NORTH_UP);
        var result = fixture.result();
        fixture.host.values.put(FIRST, "changed");
        assertFalse(fixture.refresh.publish(result));
        assertTrue(fixture.lifecycle.formed());
        assertSame(accepted, fixture.lifecycle.structureMatch());
        assertTrue(fixture.refresh.publish(fixture.result()));
    }

    @Test
    void eagerLegacyPlanningFailureFaultsOnlyItsExactCurrentPreparation() {
        var current = new Fixture();
        var failure = new AssertionError("real legacy planning failure");
        current.host.configuration = planningFailure(failure, () -> {
        });
        var operation = current.refresh.beginPrepare(null);
        assertSame(failure, assertThrows(AssertionError.class, () -> operation.advance(new RecipeWorkBudget(100))));
        assertFalse(current.lifecycle.schedulingAllowed());

        var revoked = new Fixture();
        var replacement = revoked.host.configuration;
        revoked.host.configuration = planningFailure(failure, () -> revoked.host.configuration = replacement);
        var obsolete = revoked.refresh.beginPrepare(null);
        assertSame(failure, assertThrows(AssertionError.class, () -> obsolete.advance(new RecipeWorkBudget(100))));
        assertTrue(revoked.lifecycle.schedulingAllowed());
        assertTrue(revoked.refresh.publish(revoked.result()));
    }

    @Test
    void workerUsesDetachedFactsAndPublishesTreeAndAllPublicRolesBeforeTheFormedEvent() throws Exception {
        var fixture = new Fixture();
        fixture.host.values.put(SECOND, "body");
        var resource = new ExternalHatch();
        var energy = new EnergyBuffer(100, () -> {
        });
        var binding = new StructureHatchBinding(new Object(), resource, energy, energy);
        fixture.host.bindings.put(FIRST, binding);
        fixture.host.bindings.put(SECOND, binding);
        var events = new BooleanArrayList();
        try (var ignored = fixture.registry.register(new MachineLifecycleListener() {
            @Override
            public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
                events.add(event.context().formed());
                assertEquals(List.of(FIRST, SECOND), Objects.requireNonNull(fixture.lifecycle.structureMatch()).allBlocks());
                assertEquals(List.of(resource), MachineHatches.get(fixture.machine).resources());
                assertEquals(List.of(energy), MachineHatches.get(fixture.machine).energyInputs());
                assertEquals(List.of(energy), MachineHatches.get(fixture.machine).energyOutputs());
            }
        })) {
            var request = fixture.request();
            int reads = fixture.host.reads;
            var result = CompletableFuture.supplyAsync(request::evaluate).get(5, TimeUnit.SECONDS);
            assertEquals(reads, fixture.host.reads);
            assertTrue(fixture.refresh.publish(result));
            assertTrue(fixture.machine.canProcessRecipes());
            assertEquals(List.of(true), events);
            assertFalse(fixture.refresh.publish(result));
        }
    }

    @Test
    void sameOrientationRefreshRebuildsPositionsAndReplacedProvidersWithoutAnotherFormedEvent() {
        var fixture = new Fixture();
        var oldHatch = new ExternalHatch();
        fixture.host.bindings.put(FIRST, binding(oldHatch));
        var formed = new BooleanArrayList();
        try (var ignored = fixture.registry.register(new MachineLifecycleListener() {
            @Override
            public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
                formed.add(event.context().formed());
            }
        })) {
            assertTrue(fixture.refresh.publish(fixture.result()));
            var result = fixture.result();
            var replacement = new ExternalHatch();
            var extension = new ExternalHatch();
            oldHatch.valid = false;
            fixture.host.bindings.put(FIRST, binding(replacement));
            fixture.host.bindings.put(SECOND, binding(extension));
            fixture.host.values.put(SECOND, "body");
            assertTrue(fixture.refresh.publish(result));
            assertEquals(List.of(replacement, extension), MachineHatches.get(fixture.machine).resources());
            assertEquals(2, Objects.requireNonNull(fixture.lifecycle.structureMatch()).repetitions());
            assertEquals(List.of(true), formed);
        }
    }

    @Test
    void oldSuccessAndOldMismatchCannotOverwriteNewerResultsButCurrentMismatchPausesAndClearsBindings() {
        var fixture = new Fixture();
        var oldSuccess = fixture.result();
        fixture.host.values.clear();
        var oldMismatch = fixture.result();
        fixture.host.values.put(FIRST, "body");
        var current = fixture.result();
        assertFalse(fixture.refresh.publish(oldSuccess));
        assertTrue(fixture.refresh.publish(current));
        assertFalse(fixture.refresh.publish(oldMismatch));
        assertTrue(fixture.machine.canProcessRecipes());
        var previouslySuccessful = fixture.result();
        fixture.host.values.clear();
        assertTrue(fixture.refresh.publish(previouslySuccessful));
        assertFalse(fixture.machine.canProcessRecipes());
        assertNull(fixture.lifecycle.structureMatch());
        assertNull(fixture.machine.components().get(MachineHatches.TYPE));
    }

    @Test
    void configurationAbaRevocationAndUnloadDoNotReviveAnOldAttempt() {
        var fixture = new Fixture();
        var result = fixture.result();
        var definition = fixture.host.configuration.definition();
        fixture.refresh.invalidate();
        fixture.host.configuration = new StructureConfiguration(definition, BlockOrientation.SOUTH_UP);
        fixture.host.configuration = new StructureConfiguration(definition, BlockOrientation.NORTH_UP);
        assertFalse(fixture.refresh.publish(result));
        var current = fixture.result();
        fixture.host.present = false;
        assertFalse(fixture.refresh.publish(current));
        fixture.host.present = true;
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        int reads = fixture.host.reads;
        assertFalse(fixture.refresh.publish(current));
        assertNull(fixture.refresh.prepare());
        assertFalse(fixture.machine.canProcessRecipes());
        assertEquals(reads, fixture.host.reads);
    }

    @Test
    void providerSwapOrContractInvalidationDuringDiscoveryNeverPublishesAPartialAssembly() {
        var fixture = new Fixture();
        var initial = new ExternalHatch();
        fixture.host.bindings.put(FIRST, binding(initial));
        assertTrue(fixture.refresh.publish(fixture.result()));
        var result = fixture.result();
        var replacementHatch = new ExternalHatch();
        var replacement = binding(replacementHatch);
        fixture.host.onDiscovery = position -> fixture.host.bindings.put(position, replacement);
        assertFalse(fixture.refresh.publish(result));
        assertTrue(fixture.lifecycle.formed());
        fixture.host.onDiscovery = _ -> {
        };
        assertTrue(fixture.refresh.publish(result));
        assertEquals(List.of(replacementHatch), MachineHatches.get(fixture.machine).resources());
        fixture.host.bindings.put(FIRST, binding(initial));
        result = fixture.result();
        fixture.host.onDiscovery = _ -> initial.valid = false;
        assertFalse(fixture.refresh.publish(result));
        assertEquals(List.of(replacementHatch), MachineHatches.get(fixture.machine).resources());
    }

    @Test
    void configurationChangesInsideDiscoveryAreRejectedBeforeInstallingOrEmittingEvents() {
        var fixture = new Fixture();
        var result = fixture.result();
        fixture.host.onDiscovery = _ -> fixture.host.configuration =
            new StructureConfiguration(fixture.host.configuration.definition(), BlockOrientation.NORTH_UP);
        assertFalse(fixture.refresh.publish(result));
        assertFalse(fixture.lifecycle.formed());
        assertNull(fixture.machine.components().get(MachineHatches.TYPE));
    }

    @Test
    void publicationRejectsConcurrentExecutionAndOffThreadAccessThenCanRetryAfterTheJoin() throws Exception {
        var fixture = new Fixture();
        var result = fixture.result();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> fixture.lifecycle.runScheduled(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result));
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            executor.submit(() -> assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result))).get(5, TimeUnit.SECONDS);
        }
        assertTrue(fixture.refresh.publish(result));
        assertTrue(fixture.machine.canProcessRecipes());
    }

    @Test
    void requiredReadFailureAtPublicationFaultsTheHostWithoutPretendingItIsAnOrdinaryMismatch() {
        var fixture = new Fixture();
        assertTrue(fixture.refresh.publish(fixture.result()));
        var result = fixture.result();
        var failure = new IllegalStateException("read failed");
        fixture.host.readFailure = failure;
        var thrown = assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result));
        assertSame(failure, thrown.getCause());
        assertFalse(fixture.machine.canProcessRecipes());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
    }

    @Test
    void reentrantDiscoveryCannotStartANewerRequestInsideThePublicationBoundary() {
        var fixture = new Fixture();
        var result = fixture.result();
        fixture.host.onDiscovery = _ -> fixture.refresh.prepare();
        assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result));
        assertFalse(fixture.machine.canProcessRecipes());
        assertNull(fixture.lifecycle.structureMatch());
    }

    @Test
    void readFailureAfterDiscoveryIsNotHiddenAsAnEndlessStabilityRetry() {
        var fixture = new Fixture();
        var result = fixture.result();
        var failure = new IllegalStateException("final proof failed");
        fixture.host.onDiscovery = _ -> fixture.host.readFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result)).getCause());
        assertFalse(fixture.machine.canProcessRecipes());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
    }

    @Test
    void obsoleteWorkerFailuresAndRevokedDiscoveryFailuresCannotPoisonTheReplacementGeneration() {
        var fixture = new Fixture();
        var obsolete = fixture.request();
        var current = fixture.result();
        var failure = new IllegalArgumentException("old worker failed");
        fixture.refresh.fail(obsolete, failure);
        assertTrue(fixture.refresh.publish(current));
        var result = fixture.result();
        fixture.host.onDiscovery = _ -> {
            fixture.host.configuration = new StructureConfiguration(fixture.host.configuration.definition(), BlockOrientation.NORTH_UP);
            throw failure;
        };
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> fixture.refresh.publish(result)));
        fixture.host.onDiscovery = _ -> {
        };
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertTrue(fixture.machine.canProcessRecipes());
        var pending = fixture.request();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> fixture.refresh.fail(pending, failure)));
        assertFalse(fixture.machine.canProcessRecipes());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
    }

    @Test
    void everyMatchedProviderCreatesTypedPortsOnTheOwnerBeforeTheFormedEventAndPortsNeverTick() {
        var fixture = new Fixture();
        fixture.host.values.put(SECOND, "body");
        var first = new ExternalPort("first");
        var extra = new ExternalPort("extra");
        var second = new ExternalPort("second");
        var contexts = new ObjectArrayList<StructureComponentProvider.Context>();
        fixture.host.providers.put(FIRST, context -> {
            fixture.host.requireServerThread();
            contexts.add(context);
            return List.of(entry("main", first), entry("extra", extra));
        });
        fixture.host.providers.put(SECOND, context -> {
            fixture.host.requireServerThread();
            contexts.add(context);
            return List.of(entry("main", second));
        });
        try (var ignored = fixture.registry.register(new MachineLifecycleListener() {
            @Override
            public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
                assertEquals(List.of(first, extra, second), fixture.machine.structureComponents().get(PORT).stream()
                                                                           .map(binding -> binding.entry().component()).toList());
            }
        })) {
            var request = fixture.request();
            var result = CompletableFuture.supplyAsync(request::evaluate).join();
            assertTrue(contexts.isEmpty());
            assertTrue(fixture.refresh.publish(result));
        }
        assertEquals(List.of(FIRST, SECOND), contexts.stream().map(StructureComponentProvider.Context::providerPos).toList());
        contexts.forEach(context -> {
            assertSame(fixture.machine, context.machine());
            assertEquals(1, context.formationGeneration());
        });
        assertSame(extra, fixture.machine.structureComponents().find(PORT, FIRST, "extra"));
        fixture.machine.onSchedule();
    }

    @Test
    void everyAcceptedRefreshReplacesPortsWithinOneGenerationAndRemovalOrReformationClearsTheOldBindings() {
        var fixture = new Fixture();
        var first = new ExternalPort("old");
        fixture.host.providers.put(FIRST, _ -> List.of(entry("main", first)));
        assertTrue(fixture.refresh.publish(fixture.result()));
        var captured = fixture.machine.structureComponents();
        var replacement = new ExternalPort("replacement");
        fixture.host.providers.put(FIRST, _ -> List.of(entry("main", replacement)));
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertSame(replacement, fixture.machine.structureComponents().find(PORT, FIRST, "main"));
        assertSame(first, captured.find(PORT, FIRST, "main"));
        assertEquals(1, fixture.lifecycle.formationGeneration());
        fixture.host.providers.clear();
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertTrue(fixture.lifecycle.formed());
        assertTrue(fixture.machine.structureComponents().bindings().isEmpty());
        fixture.host.providers.put(FIRST, _ -> List.of(entry("main", replacement)));
        fixture.refresh.invalidate();
        assertTrue(fixture.machine.structureComponents().bindings().isEmpty());
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertEquals(2, fixture.machine.structureComponents().get(PORT).getFirst().context().formationGeneration());
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        assertTrue(fixture.machine.structureComponents().bindings().isEmpty());
        assertNull(fixture.lifecycle.structureMatch());
        assertFalse(fixture.lifecycle.formed());
    }

    @Test
    void providerIdentityChangesAfterCreationRejectTheWholeCandidateAndRetryRebuildsTheReplacement() {
        var fixture = new Fixture();
        var existing = new ExternalPort("existing");
        fixture.host.providers.put(FIRST, _ -> List.of(entry("main", existing)));
        assertTrue(fixture.refresh.publish(fixture.result()));
        var old = fixture.machine.structureComponents();
        var replacement = new ExternalPort("replacement");
        StructureComponentProvider current = _ -> List.of(entry("main", replacement));
        fixture.host.providers.put(FIRST, _ -> {
            fixture.host.providers.put(FIRST, current);
            return List.of(entry("main", new ExternalPort("obsolete")));
        });
        var result = fixture.result();
        assertFalse(fixture.refresh.publish(result));
        assertSame(old, fixture.machine.structureComponents());
        assertEquals(1, fixture.lifecycle.formationGeneration());
        assertTrue(fixture.refresh.publish(result));
        assertSame(replacement, fixture.machine.structureComponents().find(PORT, FIRST, "main"));
    }

    @Test
    void invalidProviderResultsNeverInstallAPartialCollectionAndStillPermitUnloadCleanup() {
        var repeated = new ExternalPort("same");
        List<StructureComponentProvider> invalid = List.of(
            _ -> null,
            _ -> Collections.singletonList(null),
            _ -> List.of(entry("same", new ExternalPort("left")), entry("same", new ExternalPort("right"))),
            _ -> List.of(entry("first", repeated), entry("second", repeated)),
            _ -> {
                throw new IllegalArgumentException("provider failed");
            });
        for (var provider : invalid) {
            var fixture = new Fixture();
            var initial = new ExternalPort("initial");
            fixture.host.providers.put(FIRST, _ -> List.of(entry("main", initial)));
            assertTrue(fixture.refresh.publish(fixture.result()));
            var accepted = fixture.machine.structureComponents();
            fixture.host.providers.put(FIRST, provider);
            assertThrows(RuntimeException.class, () -> fixture.refresh.publish(fixture.result()));
            assertSame(accepted, fixture.machine.structureComponents());
            assertFalse(fixture.machine.canProcessRecipes());
            assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
            assertTrue(fixture.machine.structureComponents().bindings().isEmpty());
        }
    }

    @Test
    void formationCountSurvivesMachinePersistenceButPortsAndFormationAuthorityAreRecreated() {
        var host = new Host();
        var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).build();
        var assembly = MachineAssembly.builder(type, "ports_v1").build();
        var machine = assembly.create(host, new MachineLifecycleRegistry());
        var lifecycle = Objects.requireNonNull(machine.lifecycle());
        var refresh = new StructureRefresh(lifecycle, host);
        host.providers.put(FIRST, context -> List.of(entry("main", new ExternalPort("generation:" + context.formationGeneration()))));
        assertTrue(refresh.publish(Objects.requireNonNull(refresh.prepare()).evaluate()));
        refresh.invalidate();
        assertTrue(refresh.publish(Objects.requireNonNull(refresh.prepare()).evaluate()));
        var saved = assembly.encode(machine);
        assertEquals(2, saved.getLong("FormationGeneration"));
        assertTrue(lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        var restored = assembly.restore(host, saved, new MachineLifecycleRegistry());
        var restoredLifecycle = Objects.requireNonNull(restored.lifecycle());
        assertFalse(restoredLifecycle.formed());
        assertTrue(restored.structureComponents().bindings().isEmpty());
        var restoredRefresh = new StructureRefresh(restoredLifecycle, host);
        assertTrue(restoredRefresh.publish(Objects.requireNonNull(restoredRefresh.prepare()).evaluate()));
        assertEquals(3, restored.structureComponents().get(PORT).getFirst().context().formationGeneration());

    }

    @Test
    void sharedExternalStructurePortsParticipateInTheRealMachineSchedulerLanes() {
        var first = new Fixture();
        var second = new Fixture();
        first.host.providers.put(FIRST, _ -> List.of(entry("main", new ExternalPort("shared:port"))));
        second.host.providers.put(FIRST, _ -> List.of(entry("main", new ExternalPort("shared:port"))));
        assertTrue(first.refresh.publish(first.result()));
        assertTrue(second.refresh.publish(second.result()));
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var work = new ComponentType<MachineInstance, Runnable>(id("port_work"), _ -> fail("Explicit fixture work"),
            List.of(new MachineSystem<>() {
                @Override
                public ResourceLocation id() {
                    return StructureRefreshTest.id("port_consumer");
                }

                @Override
                public void onTick(MachineInstance owner, Runnable component) {
                    assertEquals("shared:port", owner.structureComponents().get(PORT).getFirst().entry().component().name());
                    component.run();
                }
            }));
        first.machine.components().put(work, () -> {
            log.add("first");
            threads.add(Thread.currentThread());
        });
        second.machine.components().put(work, () -> {
            log.add("second");
            threads.add(Thread.currentThread());
        });
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            scheduler.register(first.machine);
            scheduler.register(second.machine);
            scheduler.tick();
            assertEquals(List.of("first", "second"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
        }
    }

    private record ExternalPort(String name) implements AffinityKeyProvider {
        @Override
        public Set<?> affinityKeys() {
            return Set.of(name);
        }
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final Host host = new Host();
        private final MachineLifecycleRegistry registry = new MachineLifecycleRegistry();
        private final MachineLifecycle lifecycle = new MachineLifecycle(machine, host, registry);
        private final StructureRefresh refresh = new StructureRefresh(lifecycle, host);

        private Fixture() {
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        }

        private StructureRefresh.Request request() {
            return Objects.requireNonNull(refresh.prepare());
        }

        private StructureRefresh.Result result() {
            return request().evaluate();
        }
    }

    private static final class ExternalHatch implements ResourceHatch {
        private boolean valid = true;

        @Override
        @Nullable
        public ResourceStorage input() {
            return null;
        }

        @Override
        @Nullable
        public ResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return valid;
        }
    }

    private static final class Host implements MachineStructureHost {
        private final Thread serverThread = Thread.currentThread();
        private final Map<BlockPos, String> values = new Object2ObjectOpenHashMap<>();
        private final Map<BlockPos, StructureHatchBinding> bindings = new Object2ObjectOpenHashMap<>();
        private final Map<BlockPos, StructureComponentProvider> providers = new Object2ObjectOpenHashMap<>();
        private final ObjectArrayList<BlockPos> observedReads = new ObjectArrayList<>();
        private BlockPos controllerPosition = BlockPos.ORIGIN;
        private StructureConfiguration configuration = new StructureConfiguration(
            new SliceStructure(id("shape"), BlockOrientation.NORTH_UP, FIRST, false,
                new StructurePattern(Map.of(BlockPos.ORIGIN, StructurePredicates.test(MARKER, "body"::equals))),
                1, 3, FIRST, List.of(), List.of()), BlockOrientation.NORTH_UP);
        private boolean present = true;
        private int reads;
        @Nullable
        private RuntimeException readFailure;
        private Consumer<BlockPos> onDiscovery = _ -> {
        };

        private Host() {
            values.put(FIRST, "body");
        }

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, controllerPosition);
        }

        @Override
        public World world() {
            throw new UnsupportedOperationException("No World in logic tests");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != serverThread) {
                throw new IllegalStateException("Not the owner thread");
            }
        }

        @Override
        @Nullable
        public StructureConfiguration structureConfiguration() {
            requireServerThread();
            return present ? configuration : null;
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return present;
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // Only this String descriptor belongs to the fixture.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(MARKER, query);
            reads++;
            observedReads.add(position);
            if (readFailure != null) {
                throw readFailure;
            }
            return (T) values.get(position);
        }

        @Override
        public StructureHatchBinding hatchesAt(BlockPos position) {
            requireServerThread();
            var found = bindings.getOrDefault(position, StructureHatchBinding.EMPTY);
            onDiscovery.accept(position);
            return found;
        }

        @Override
        @Nullable
        public StructureComponentProvider structureComponentsAt(BlockPos position) {
            requireServerThread();
            return providers.get(position);
        }
    }
}
