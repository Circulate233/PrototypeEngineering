package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MachineSchedulerTest {
    @Test
    void loadedUnformedMachineRunsAllComponentPhasesAndUnloadStopsExecution() {
        Thread owner = Thread.currentThread();
        var fixture = new Fixture("ordinary");
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        var system = new MachineSystem<String>() {
            @Override public ResourceLocation id() { return MachineSchedulerTest.id("phases"); }
            @Override public void onPreTick(MachineInstance machine, String component) { record("pre"); }
            @Override public void onTick(MachineInstance machine, String component) {
                assertFalse(machine.canProcessRecipes());
                record("normal");
            }
            @Override public void onPostTick(MachineInstance machine, String component) { record("post"); }
            private void record(String phase) {
                log.add(phase);
                threads.add(Thread.currentThread());
            }
        };
        fixture.machine.components().create(new ComponentType<>(id("phase_component"), _ -> "state", List.of(system)),
            fixture.machine);
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            scheduler.register(fixture.machine);
            scheduler.tick();
            assertTrue(log.isEmpty());
            fixture.load();
            scheduler.tick();
            assertEquals(List.of("pre", "normal", "post"), List.copyOf(log));
            assertNotSame(owner, threads.element());
            fixture.machine.requirePersistenceBoundary();

            fixture.machine.components().put(MachineScheduling.TYPE, new MachineScheduling(ExecutionMode.MAIN_THREAD));
            log.clear();
            threads.clear();
            scheduler.tick();
            assertEquals(List.of("pre", "normal", "post"), List.copyOf(log));
            assertEquals(Set.of(owner), Set.copyOf(threads));
            fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            scheduler.tick();
            assertEquals(3, log.size());
            scheduler.unregister(fixture.machine);
        }
    }

    @Test
    void resourceAndEnergyAliasesConnectThreeMachinesIntoOneSerialStableLane() {
        Thread owner = Thread.currentThread();
        var keyType = new TestResources.KeyType("scheduler_resources");
        var shared = new SlottedResourceBuffer(1, Map.of(keyType, 32L), () -> {});
        var firstView = new InputView(shared);
        var secondView = new InputView(shared);
        var energy = new EnergyBuffer(32, () -> {});
        var first = new Fixture("resource");
        var bridge = new Fixture("bridge");
        var third = new Fixture("energy");
        var log = new ConcurrentLinkedQueue<String>();
        var threads = new ConcurrentLinkedQueue<Thread>();
        first.load();
        bridge.load();
        third.load();
        first.lifecycle.form(new MachineHatches(List.of(firstView), List.of(), List.of()));
        bridge.lifecycle.form(new MachineHatches(List.of(secondView), List.of(energy), List.of()));
        third.lifecycle.form(new MachineHatches(List.of(), List.of(), List.of(energy)));
        for (Fixture fixture : List.of(first, bridge, third)) {
            installTick(fixture.machine, machine -> {
                assertNotSame(owner, Thread.currentThread());
                assertThrows(IllegalStateException.class, machine::requirePersistenceBoundary);
                log.add(fixture.name);
                threads.add(Thread.currentThread());
            });
        }
        try (var scheduler = new TaskScheduler(owner, 3, 4)) {
            scheduler.register(first.machine);
            scheduler.register(bridge.machine);
            scheduler.register(third.machine);
            scheduler.tick();
            scheduler.tick();
            assertEquals(List.of("resource", "bridge", "energy", "resource", "bridge", "energy"), List.copyOf(log));
            assertEquals(1, Set.copyOf(threads).size());
            for (Fixture fixture : List.of(first, bridge, third)) { fixture.machine.requirePersistenceBoundary(); }

            bridge.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            log.clear();
            scheduler.tick();
            assertEquals(Set.of("resource", "energy"), Set.copyOf(log));
            assertEquals(2, log.size());
            first.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            third.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
        }
    }

    @Test
    void componentFaultCannotBeReactivatedByRegisteringMachineWithANewScheduler() {
        var fixture = new Fixture("fault");
        var log = new ConcurrentLinkedQueue<String>();
        var failure = new IllegalStateException("component failed");
        fixture.load();
        installTick(fixture.machine, _ -> {
            log.add("ran");
            throw failure;
        });
        try (var first = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            first.register(fixture.machine);
            assertSame(failure, assertThrows(IllegalStateException.class, first::tick));
            first.unregister(fixture.machine);
        }
        try (var second = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            second.register(fixture.machine);
            second.tick();
            assertEquals(List.of("ran"), List.copyOf(log));
            fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            second.tick();
            assertEquals(1, log.size());
        }
    }

    @Test
    void mainUnloadAndBreakRemoveOldMachinesWhileFreshRegistrationRunsThisTick() {
        Thread owner = Thread.currentThread();
        var unloaded = new Fixture("main_unloaded");
        var broken = new Fixture("main_broken");
        var placed = new Fixture("main_placed");
        var log = new ConcurrentLinkedQueue<String>();
        unloaded.load();
        broken.load();
        installTick(unloaded.machine, _ -> fail("Unloaded machine must not reach a worker"));
        installTick(broken.machine, _ -> fail("Broken machine must not reach a worker"));
        installTick(placed.machine, _ -> {
            assertNotSame(owner, Thread.currentThread());
            log.add("placed");
        });
        try (var scheduler = new TaskScheduler(owner, 2, 2)) {
            scheduler.register(unloaded.machine);
            scheduler.register(broken.machine);
            scheduler.submit(() -> {
                assertSame(owner, Thread.currentThread());
                unloaded.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                scheduler.unregister(unloaded.machine);
                broken.lifecycle.controllerBroken();
                scheduler.unregister(broken.machine);
                placed.load();
                scheduler.register(placed.machine);
                assertEquals(1, scheduler.registeredCount());
                log.add("main");
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(List.of("main", "placed"), List.copyOf(log));
            placed.machine.requirePersistenceBoundary();
            scheduler.tick();
            assertEquals(List.of("main", "placed", "placed"), List.copyOf(log));
            placed.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            scheduler.unregister(placed.machine);
        }
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("machine_scheduler_test", path); }

    private static void installTick(MachineInstance machine, Consumer<MachineInstance> action) {
        var system = new MachineSystem<String>() {
            @Override public ResourceLocation id() { return MachineSchedulerTest.id("record_tick"); }
            @Override public void onTick(MachineInstance owner, String component) { action.accept(owner); }
        };
        machine.components().create(new ComponentType<>(id("record_component"), _ -> "state", List.of(system)), machine);
    }

    private static final class Fixture {
        private final String name;
        private final MachineInstance machine = new MachineInstance();
        private final MachineLifecycle lifecycle;

        private Fixture(String name) {
            this.name = name;
            lifecycle = new MachineLifecycle(machine, new Host(Thread.currentThread(), name), new MachineLifecycleRegistry());
        }

        private void load() { lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH); }
    }

    private record Host(Thread owner, String name) implements MachineLifecycleHost {
        @Override public MachineIdentity identity() { return new MachineIdentity(id(name), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new UnsupportedOperationException("No live World in scheduler logic tests"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Not the fixture owner thread"); }
        }
    }

    @SuppressWarnings("ClassCanBeRecord") // Distinct adapter identities must not become equal and hide storage-alias scheduling bugs.
    private static final class InputView implements ResourceHatch {
        private final ResourceStorage storage;
        private InputView(ResourceStorage storage) { this.storage = storage; }
        @Override public ResourceStorage input() { return storage; }
        @Override @Nullable public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return storage.isValid(); }
    }
}
