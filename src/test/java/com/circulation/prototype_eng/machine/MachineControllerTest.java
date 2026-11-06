package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessAbortListener;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource") // Fixture registries own subscriptions for their complete scenario.
class MachineControllerTest {
    @Test
    void restoredDataIsPublishedToEveryLoadListenerBeforeTheControllerRegistersAndTicks() {
        var component = counterType(new Action());
        var assembly = assembly(component, ExecutionMode.MAIN_THREAD);
        var source = assembly.create(new Host(), new MachineLifecycleRegistry());
        counter(source, component).progress = 12;
        source.attributes().setBase(id("speed"), 9);
        var saved = assembly.encode(source);
        var log = new ObjectArrayList<String>();
        try (var scheduler = scheduler()) {
            var registry = new MachineLifecycleRegistry();
            for (String name : List.of("first", "second")) {
                registry.register(new MachineLifecycleListener() {
                    @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
                        assertEquals(0, scheduler.registeredCount());
                        assertEquals(12, counter(event.context().machine(), component).progress);
                        assertEquals(9, event.context().machine().attributes().value(id("speed")));
                        assertSame(MachineLifecycleEvent.LoadBoundary.RESTORED, event.boundary());
                        log.add(name);
                    }
                });
            }
            var controller = new MachineController(assembly, new Host(), scheduler, registry);
            controller.load(saved);
            assertEquals(List.of("first", "second"), log);
            assertEquals(1, scheduler.registeredCount());
            scheduler.tick();
            assertEquals(13, counter(runtime(controller), component).progress);
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void plannedRegistrationFailureUnloadsTheLoadedMachineAggregatesCleanupAndLeavesNoRegistration() {
        var component = counterType(new Action());
        var assembly = assembly(component, ExecutionMode.PLANNED_PARALLEL);
        var firstCleanup = new IllegalStateException("first cleanup");
        var secondCleanup = new AssertionError("second cleanup");
        var log = new ObjectArrayList<String>();
        try (var scheduler = scheduler()) {
            var registry = new MachineLifecycleRegistry();
            registry.register(new MachineLifecycleListener() {
                @Override public void onLoaded(MachineLifecycleEvent.Loaded event) { log.add("loaded:first"); }
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                    assertSame(MachineLifecycleEvent.UnloadReason.INVALIDATED, event.reason());
                    log.add("unloaded:first");
                    throw firstCleanup;
                }
            });
            registry.register(new MachineLifecycleListener() {
                @Override public void onLoaded(MachineLifecycleEvent.Loaded event) { log.add("loaded:second"); }
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { log.add("unloaded:second"); throw secondCleanup; }
            });
            var controller = new MachineController(assembly, new Host(), scheduler, registry);
            var failure = assertThrows(IllegalArgumentException.class, () -> controller.load(null));
            assertArrayEquals(new Throwable[]{firstCleanup}, failure.getSuppressed());
            assertArrayEquals(new Throwable[]{secondCleanup}, firstCleanup.getSuppressed());
            assertEquals(List.of("loaded:first", "loaded:second", "unloaded:first", "unloaded:second"), log);
            assertEquals(0, scheduler.registeredCount());
            controller.controllerBroken();
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            assertThrows(IllegalStateException.class, () -> controller.load(null));
            assertEquals(4, log.size());
        }
    }

    @Test
    void mainSelfUnloadUpgradesToBrokenAndCancelsOwnedWorkOnlyAfterItsCallbackReturns() {
        var action = new Action();
        var component = counterType(action);
        var group = id("group");
        var recipe = new MachineRecipe(id("recipe"), 3, List.of(), Set.of(group));
        var type = declaration(ExecutionMode.MAIN_THREAD).component(component).component(MachineWorkSlots.TYPE)
            .workSlot(new WorkSlotDefinition("main", Set.of(group))).build();
        var assembly = MachineAssembly.builder(type, "v1").component(component, new CounterCodec())
            .workSlots(new MachineWorkSlotsNbt("slots-v1", List.of(RecipeProcessNbt.builder(recipe, "recipe-v1").build()))).build();
        var log = new ObjectArrayList<String>();
        boolean[] inCallback = {false};
        var owned = new RecipeProcess[1];
        try (var scheduler = scheduler()) {
            var registry = new MachineLifecycleRegistry();
            registry.register(new MachineLifecycleListener() {
                @Override public void onControllerBroken(MachineLifecycleEvent.ControllerBroken event) {
                    assertFalse(inCallback[0]);
                    log.add("broken");
                }
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                    assertFalse(inCallback[0]);
                    assertEquals(RecipeProcess.Lifecycle.CANCELLED, owned[0].state().lifecycle());
                    log.add("unloaded:" + event.reason());
                }
            });
            var controller = new MachineController(assembly, new Host(), scheduler, registry);
            controller.load(null);
            var machine = runtime(controller);
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            owned[0] = new RecipeProcess(machine, recipe, 3);
            var abort = new ComponentType<RecipeProcess, ProcessAbortListener>(id("abort"), _ -> process -> {
                assertFalse(inCallback[0]);
                assertEquals(RecipeProcess.Lifecycle.CANCELLED, process.state().lifecycle());
                log.add("cancelled");
            }, List.of());
            owned[0].components().create(abort, owned[0]);
            assertTrue(Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE)).start(owned[0]));
            action.run = _ -> {
                inCallback[0] = true;
                log.add("enter");
                try {
                    controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                    controller.controllerBroken();
                    assertEquals(List.of("enter"), log);
                    assertEquals(RecipeProcess.Lifecycle.NEW, owned[0].state().lifecycle());
                } finally { inCallback[0] = false; log.add("exit"); }
            };
            scheduler.tick();
            assertEquals(List.of("enter", "exit", "broken", "cancelled", "unloaded:INVALIDATED"), log);
            assertEquals(0, scheduler.registeredCount());
            controller.controllerBroken();
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            scheduler.tick();
            assertEquals(5, log.size());
        }
    }

    @Test
    void workerSelfStopAndFailureCleanUpOnOwnerOnlyAfterEveryAcceptedWorkerPhysicallyExits() throws Exception {
        var owner = Thread.currentThread();
        var action = new Action();
        var component = counterType(action);
        var entered = new CountDownLatch(1);
        var otherEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var otherExited = new CountDownLatch(1);
        var cleanup = new CountDownLatch(1);
        var failure = new IllegalStateException("worker callback failed");
        var log = new ObjectArrayList<String>();
        try (var scheduler = new TaskScheduler(owner, 2, 1);
             var observer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            var registry = new MachineLifecycleRegistry();
            registry.register(new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                    assertSame(owner, Thread.currentThread());
                    assertEquals(0, exited.getCount());
                    assertEquals(0, otherExited.getCount());
                    log.add("cleanup");
                    cleanup.countDown();
                }
            });
            var controller = new MachineController(assembly(component, ExecutionMode.CONCURRENT), new Host(), scheduler, registry);
            controller.load(null);
            action.run = _ -> {
                assertNotSame(owner, Thread.currentThread());
                controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                entered.countDown();
                try { await(release); }
                finally { exited.countDown(); }
                throw failure;
            };
            scheduler.submit(() -> {
                otherEntered.countDown();
                try { await(release); }
                finally { otherExited.countDown(); }
            }, ExecutionMode.CONCURRENT);
            var observation = observer.submit(() -> {
                await(entered);
                await(otherEntered);
                try { assertFalse(cleanup.await(100, TimeUnit.MILLISECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                finally { release.countDown(); }
            });
            try {
                assertSame(failure, assertThrows(IllegalStateException.class, scheduler::tick));
                observation.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("cleanup"), log);
                assertEquals(0, scheduler.registeredCount());
                controller.controllerBroken();
                assertThrows(IllegalStateException.class, scheduler::tick);
                assertEquals(1, log.size());
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }

    @Test
    void metadataStopRequestIsRejectedBeforeRetirementAndTheControllerStillRuns() {
        var component = counterType(new Action());
        try (var scheduler = scheduler()) {
            var controller = new MachineController(assembly(component, ExecutionMode.MAIN_THREAD), new Host(), scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            int[] rejected = {0};
            boolean[] inspect = {false};
            var metadata = new ScheduledTask() {
                @Override public void onSchedule() { }
                @Override public ExecutionMode executionMode() {
                    if (inspect[0]) {
                        assertThrows(IllegalStateException.class, () -> controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
                        rejected[0]++;
                    }
                    return ExecutionMode.MAIN_THREAD;
                }
                @Override public boolean active() { return true; }
            };
            scheduler.register(metadata);
            inspect[0] = true;
            scheduler.tick();
            scheduler.tick();
            assertTrue(rejected[0] >= 2);
            assertEquals(2, counter(runtime(controller), component).progress);
            assertEquals(2, scheduler.registeredCount());
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            scheduler.unregister(metadata);
        }
    }

    @Test
    void queuedRestoredLoadRetainsIsolatedRawDataAndAStopBeforeTheBoundarySkipsEveryFactory() {
        int[] factories = {0};
        var action = new Action();
        var component = counterType(action, () -> factories[0]++);
        var assembly = assembly(component, ExecutionMode.MAIN_THREAD);
        var saved = assembly.encode(assembly.create(new Host(), new MachineLifecycleRegistry()));
        var expected = saved.copy();
        factories[0] = 0;
        try (var scheduler = scheduler()) {
            var controller = new MachineController(assembly, new Host(), scheduler, new MachineLifecycleRegistry());
            scheduler.submit(() -> {
                controller.load(saved);
                assertNull(controller.machine());
                var pending = Objects.requireNonNull(controller.save());
                assertEquals(expected, pending);
                pending.setString("Revision", "returned-copy-mutation");
                saved.setString("Revision", "caller-mutation");
                assertEquals(expected, controller.save());
                controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(0, factories[0]);
            assertEquals(0, scheduler.registeredCount());
            assertNull(controller.machine());
            assertEquals(expected, controller.save());
            assertThrows(IllegalStateException.class, () -> controller.load(expected));
        }
    }

    @Test
    void queuedFreshLoadRunsAfterMainCallbackReturnsAndBeginsOnTheFollowingMainTick() {
        var log = new ObjectArrayList<String>();
        var action = new Action();
        action.run = _ -> log.add("tick");
        var component = counterType(action, () -> log.add("factory"));
        var assembly = assembly(component, ExecutionMode.MAIN_THREAD);
        try (var scheduler = scheduler()) {
            var registry = new MachineLifecycleRegistry();
            registry.register(new MachineLifecycleListener() {
                @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
                    assertEquals(List.of("main:enter", "main:exit", "factory"), log);
                    assertEquals(0, scheduler.registeredCount());
                    log.add("loaded");
                }
            });
            var controller = new MachineController(assembly, new Host(), scheduler, registry);
            scheduler.submit(() -> {
                log.add("main:enter");
                controller.load(null);
                assertNull(controller.save());
                assertNull(controller.machine());
                assertThrows(IllegalStateException.class, () -> controller.load(null));
                log.add("main:exit");
            }, ExecutionMode.MAIN_THREAD);
            scheduler.tick();
            assertEquals(List.of("main:enter", "main:exit", "factory", "loaded"), log);
            assertEquals(1, scheduler.registeredCount());
            scheduler.tick();
            assertEquals(List.of("main:enter", "main:exit", "factory", "loaded", "tick"), log);
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void normalUnloadSaveAndNewControllerRestoreRetainProgressWithoutRevivingTheOldRuntime() {
        var component = counterType(new Action());
        var assembly = assembly(component, ExecutionMode.MAIN_THREAD);
        try (var scheduler = scheduler()) {
            var original = new MachineController(assembly, new Host(), scheduler, new MachineLifecycleRegistry());
            original.load(null);
            scheduler.tick();
            scheduler.tick();
            original.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var saved = Objects.requireNonNull(original.save());
            var replacement = new MachineController(assembly, new Host(), scheduler, new MachineLifecycleRegistry());
            replacement.load(saved);
            assertNotSame(runtime(original), runtime(replacement));
            assertEquals(2, counter(runtime(replacement), component).progress);
            scheduler.tick();
            assertEquals(3, counter(runtime(replacement), component).progress);
            assertEquals(2, counter(runtime(original), component).progress);
            assertEquals(1, scheduler.registeredCount());
            replacement.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void retiringAnOldControllerCannotUnregisterANewerGenerationAtTheSameHostIdentity() {
        var action = new Action();
        var component = counterType(action);
        var assembly = assembly(component, ExecutionMode.MAIN_THREAD);
        var log = new ObjectArrayList<String>();
        try (var scheduler = scheduler()) {
            var host = new Host();
            var oldController = new MachineController(assembly, host, scheduler, new MachineLifecycleRegistry());
            var newController = new MachineController(assembly, host, scheduler, new MachineLifecycleRegistry());
            oldController.load(null);
            newController.load(null);
            action.run = owner -> {
                if (owner == runtime(oldController)) {
                    log.add("old");
                    oldController.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
                } else { log.add("new"); }
            };
            scheduler.tick();
            assertEquals(List.of("old", "new"), log);
            assertEquals(1, scheduler.registeredCount());
            oldController.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            oldController.controllerBroken();
            scheduler.tick();
            assertEquals(List.of("old", "new", "new"), log);
            assertEquals(1, counter(runtime(oldController), component).progress);
            assertEquals(2, counter(runtime(newController), component).progress);
            assertEquals(1, scheduler.registeredCount());
            newController.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void cleanupFailureStillUnregistersAndRepeatedStopsOrLoadCannotReviveTheController() {
        var component = counterType(new Action());
        var failure = new IllegalStateException("unload cleanup failed");
        int[] unloads = {0};
        try (var scheduler = scheduler()) {
            var registry = new MachineLifecycleRegistry();
            registry.register(new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { unloads[0]++; throw failure; }
            });
            var controller = new MachineController(assembly(component, ExecutionMode.MAIN_THREAD), new Host(), scheduler, registry);
            controller.load(null);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD)));
            assertEquals(0, scheduler.registeredCount());
            controller.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
            controller.controllerBroken();
            assertThrows(IllegalStateException.class, () -> controller.load(null));
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertEquals(1, unloads[0]);
            assertEquals(0, counter(runtime(controller), component).progress);
        }
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("machine_controller_test", name); }

    private static MachineType.Builder declaration(ExecutionMode mode) {
        return MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).executionMode(mode).attribute(id("speed"), 7);
    }

    private static MachineAssembly assembly(ComponentType<MachineInstance, Counter> component, ExecutionMode mode) {
        return MachineAssembly.builder(declaration(mode).component(component).build(), "v1").component(component, new CounterCodec()).build();
    }

    private static ComponentType<MachineInstance, Counter> counterType(Action action) { return counterType(action, () -> {}); }

    private static ComponentType<MachineInstance, Counter> counterType(Action action, Runnable factory) {
        var system = new MachineSystem<Counter>() {
            @Override public ResourceLocation id() { return MachineControllerTest.id("counter_tick"); }
            @Override public void onTick(MachineInstance owner, Counter counter) {
                action.run.accept(owner);
                counter.progress++;
            }
        };
        return new ComponentType<>(id("counter"), _ -> { factory.run(); return new Counter(); }, List.of(system));
    }

    private static Counter counter(MachineInstance machine, ComponentType<MachineInstance, Counter> type) {
        return Objects.requireNonNull(machine.components().get(type));
    }

    private static MachineInstance runtime(MachineController controller) { return Objects.requireNonNull(controller.machine()); }

    private static TaskScheduler scheduler() { return new TaskScheduler(Thread.currentThread(), 1, 1); }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for test handshake"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static final class Counter { private int progress; }
    private static final class Action { private Consumer<MachineInstance> run = _ -> {}; }

    private static final class CounterCodec implements MachineComponentCodec<Counter> {
        @Override public NBTTagCompound encode(MachineInstance owner, Counter counter) {
            var tag = new NBTTagCompound();
            tag.setInteger("Progress", counter.progress);
            return tag;
        }
        @Override public Counter restore(MachineInstance owner, Counter fresh, NBTTagCompound saved) {
            fresh.progress = saved.getInteger("Progress");
            return fresh;
        }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread owner = Thread.currentThread();
        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("Pure controller tests cannot access a Minecraft World"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Controller requires the fixture owner thread"); }
        }
    }
}
