package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class StructureRefreshTest {
    private static final BlockPos FIRST = new BlockPos(1, 0, 0);
    private static final BlockPos SECOND = new BlockPos(2, 0, 0);
    private static final StructureQuery<String> MARKER = new StructureQuery<>(id("marker"),
        (_, _) -> fail("Logical tests never read a game world"), UnaryOperator.identity(), String::equals);

    @Test
    void workerUsesDetachedFactsAndPublishesTreeAndAllPublicRolesBeforeTheFormedEvent() throws Exception {
        var fixture = new Fixture();
        fixture.host.values.put(SECOND, "body");
        var resource = new ExternalHatch();
        var energy = new EnergyBuffer(100, () -> {});
        var binding = new StructureHatchBinding(new Object(), resource, energy, energy);
        fixture.host.bindings.put(FIRST, binding);
        fixture.host.bindings.put(SECOND, binding);
        var events = new ObjectArrayList<Boolean>();
        try (var ignored = fixture.registry.register(new MachineLifecycleListener() {
            @Override public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
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
        var formed = new ObjectArrayList<Boolean>();
        try (var ignored = fixture.registry.register(new MachineLifecycleListener() {
            @Override public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) { formed.add(event.context().formed()); }
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
        fixture.host.onDiscovery = _ -> {};
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
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.refresh.publish(result));
            } finally { release.countDown(); }
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
        fixture.host.onDiscovery = _ -> {};
        assertTrue(fixture.refresh.publish(fixture.result()));
        assertTrue(fixture.machine.canProcessRecipes());
        var pending = fixture.request();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> fixture.refresh.fail(pending, failure)));
        assertFalse(fixture.machine.canProcessRecipes());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
    }

    private static StructureHatchBinding binding(ResourceHatch hatch) { return new StructureHatchBinding(new Object(), hatch, null, null); }
    private static ResourceLocation id(String path) { return new ResourceLocation("structure_refresh_test", path); }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final Host host = new Host();
        private final MachineLifecycleRegistry registry = new MachineLifecycleRegistry();
        private final MachineLifecycle lifecycle = new MachineLifecycle(machine, host, registry);
        private final StructureRefresh refresh = new StructureRefresh(lifecycle, host);

        private Fixture() { lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH); }
        private StructureRefresh.Request request() { return Objects.requireNonNull(refresh.prepare()); }
        private StructureRefresh.Result result() { return request().evaluate(); }
    }

    private static final class ExternalHatch implements ResourceHatch {
        private boolean valid = true;
        @Override @Nullable public ResourceStorage input() { return null; }
        @Override @Nullable public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return valid; }
    }

    private static final class Host implements MachineStructureHost {
        private final Thread serverThread = Thread.currentThread();
        private final Map<BlockPos, String> values = new Object2ObjectOpenHashMap<>();
        private final Map<BlockPos, StructureHatchBinding> bindings = new Object2ObjectOpenHashMap<>();
        private StructureConfiguration configuration = new StructureConfiguration(
            new SliceStructure(id("shape"), BlockOrientation.NORTH_UP, FIRST, false,
                new StructurePattern(Map.of(BlockPos.ORIGIN, StructurePredicates.test(MARKER, "body"::equals))),
                1, 3, FIRST, List.of(), List.of()), BlockOrientation.NORTH_UP);
        private boolean present = true;
        private int reads;
        @Nullable private RuntimeException readFailure;
        private Consumer<BlockPos> onDiscovery = _ -> {};

        private Host() { values.put(FIRST, "body"); }
        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new UnsupportedOperationException("No World in logic tests"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != serverThread) { throw new IllegalStateException("Not the owner thread"); }
        }
        @Override @Nullable public StructureConfiguration structureConfiguration() { requireServerThread(); return present ? configuration : null; }
        @Override public boolean isAreaLoaded(StructureBounds bounds) { requireServerThread(); return present; }
        @Override @Nullable @SuppressWarnings("unchecked") // Only this String descriptor belongs to the fixture.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertSame(MARKER, query);
            reads++;
            if (readFailure != null) { throw readFailure; }
            return (T) values.get(position);
        }
        @Override public StructureHatchBinding hatchesAt(BlockPos position) {
            requireServerThread();
            var found = bindings.getOrDefault(position, StructureHatchBinding.EMPTY);
            onDiscovery.accept(position);
            return found;
        }
    }
}
