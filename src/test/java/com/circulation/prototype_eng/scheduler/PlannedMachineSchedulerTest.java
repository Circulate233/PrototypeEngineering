package com.circulation.prototype_eng.scheduler;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedMachineTask;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannedMachineSchedulerTest {
    private static final KeyType KEYS = new KeyType("planned_machine_scheduler");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("planned_machine_test", path);
    }

    private static ScheduledTask ordinary(ExecutionMode mode, Runnable action) {
        return new ScheduledTask() {
            @Override
            public void onSchedule() {
                action.run();
            }

            @Override
            public ExecutionMode executionMode() {
                return mode;
            }

            @Override
            public boolean active() {
                return true;
            }
        };
    }

    @Test
    void publicSchedulerLoadsFormalControllersWithoutPnAndJoinsOrdinaryBeforePureWorkers() {
        var log = new ConcurrentLinkedQueue<String>();
        var bothPure = new CountDownLatch(2);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 2)) {
            var first = new Fixture(scheduler, "first", log, bothPure);
            var second = new Fixture(scheduler, "second", log, bothPure);
            scheduler.register(ordinary(ExecutionMode.MAIN_THREAD, () -> log.add("main")));
            scheduler.register(ordinary(ExecutionMode.CONCURRENT, () -> log.add("concurrent")));
            var currentAuthority = new AtomicBoolean();
            Runnable requireFresh = () -> assertTrue(currentAuthority.getAndSet(false), "Each owner component consumes refreshed authority");
            first.checkPreparation = requireFresh;
            second.checkPreparation = requireFresh;
            scheduler.tick(() -> {
                assertSame(Thread.currentThread(), first.owner);
                assertThrows(IllegalStateException.class, () -> scheduler.runAtBoundary(() -> log.add("unexpected boundary")));
                currentAuthority.set(true);
            });
            first.assertCompleted();
            second.assertCompleted();
            var events = List.copyOf(log);
            assertTrue(events.indexOf("main") < events.indexOf("concurrent"));
            assertTrue(events.indexOf("concurrent") < events.indexOf("first:pre"));
            assertTrue(events.indexOf("concurrent") < events.indexOf("second:pre"));
            assertEquals(0, bothPure.getCount());
            first.unload();
            second.unload();
        }
    }

    @Test
    void externalMachineWrapperUsesSamePublicEntryAfterRegistrationReplacement() {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            var fixture = new Fixture(scheduler, "wrapped", log, new CountDownLatch(0));
            scheduler.unregister(fixture.controller);
            var wrapper = new Wrapper(fixture.machine);
            scheduler.register(wrapper);
            scheduler.register(ordinary(ExecutionMode.MAIN_THREAD, () -> {
                scheduler.unregister(wrapper);
                scheduler.register(wrapper);
            }));
            scheduler.tick();
            fixture.assertCompleted();
            assertEquals(0, wrapper.ordinaryCalls.get());
            scheduler.unregister(wrapper);
            fixture.unload();
        }
    }

    @Test
    void duplicateMachineWrappersRejectBeforeAnyLifecycleOrComponentAcquisition() {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            var fixture = new Fixture(scheduler, "duplicate", log, new CountDownLatch(0));
            scheduler.register(new Wrapper(fixture.machine));
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertTrue(log.isEmpty());
            fixture.machine.requirePersistenceBoundary();
            assertEquals(RecipeProcess.Lifecycle.NEW, fixture.process.state().lifecycle());
            fixture.unload();
        }
    }

    @Test
    void ownerPreparationRevokesCapturedMachineBeforeTheNextComponentAndReleasesItsGate() {
        var log = new ConcurrentLinkedQueue<String>();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
            var fixture = new Fixture(scheduler, "prepare_revoke", log, new CountDownLatch(0));
            scheduler.unregister(fixture.controller);
            var wrapper = new Wrapper(fixture.machine);
            scheduler.register(wrapper);
            var preExecuted = new AtomicBoolean();
            fixture.onPre = () -> preExecuted.set(true);
            scheduler.tick(() -> {
                if (preExecuted.get()) {
                    wrapper.enabled = false;
                }
            });
            assertEquals(List.of("pre"), List.copyOf(fixture.phases));
            assertTrue(fixture.stages.isEmpty());
            assertEquals(RecipeProcess.Lifecycle.NEW, fixture.process.state().lifecycle());
            assertEquals(3, Objects.requireNonNull(fixture.warehouse.input()).amount(fixture.raw));
            assertEquals(0, Objects.requireNonNull(fixture.warehouse.output()).amount(fixture.product));
            assertEquals(10, fixture.energy.savedEnergy());
            assertEquals(0, wrapper.ordinaryCalls.get());
            fixture.machine.requirePersistenceBoundary();
            scheduler.unregister(wrapper);
            fixture.unload();
            assertEquals(MachineLifecycle.State.UNLOADED, Objects.requireNonNull(fixture.machine.lifecycle()).state());
        }
    }

    @Test
    void capturedModeActiveAndBindingChangesRevokeTheOldMachineBeforeRecipeWork() {
        for (int change = 0; change < 4; change++) {
            var log = new ConcurrentLinkedQueue<String>();
            try (var scheduler = new TaskScheduler(Thread.currentThread(), 2, 1)) {
                var fixture = new Fixture(scheduler, "changed_" + change, log, new CountDownLatch(0));
                scheduler.unregister(fixture.controller);
                var wrapper = new Wrapper(fixture.machine);
                scheduler.register(wrapper);
                int selected = change;
                fixture.onPre = () -> {
                    if (selected == 0) wrapper.mode = ExecutionMode.CONCURRENT;
                    else if (selected == 1) wrapper.enabled = false;
                    else if (selected == 2) wrapper.machine = new MachineInstance();
                    else
                        fixture.machine.components().put(MachineScheduling.TYPE, new MachineScheduling(ExecutionMode.CONCURRENT));
                };
                scheduler.tick();
                assertEquals(RecipeProcess.Lifecycle.NEW, fixture.process.state().lifecycle());
                assertEquals(3, Objects.requireNonNull(fixture.warehouse.input()).amount(fixture.raw));
                assertEquals(10, fixture.energy.savedEnergy());
                assertEquals(0, wrapper.ordinaryCalls.get());
                fixture.machine.requirePersistenceBoundary();
                scheduler.unregister(wrapper);
                fixture.unload();
            }
        }
    }

    private static final class Wrapper implements PlannedMachineTask {
        private final AtomicInteger ordinaryCalls = new AtomicInteger();
        private MachineInstance machine;
        private ExecutionMode mode = ExecutionMode.PLANNED_PARALLEL;
        private boolean enabled = true;

        private Wrapper(MachineInstance machine) {
            this.machine = machine;
        }

        @Override
        public MachineInstance plannedMachine() {
            return machine;
        }

        @Override
        public ExecutionMode executionMode() {
            return mode;
        }

        @Override
        public boolean active() {
            return enabled;
        }

        @Override
        public void onSchedule() {
            ordinaryCalls.incrementAndGet();
            machine.onSchedule();
        }
    }

    private static final class Fixture {
        private final Thread owner = Thread.currentThread();
        private final MachineController controller;
        private final @NotNull MachineInstance machine;
        private final RecipeProcess process;
        private final Key raw = new Key(KEYS, "raw");
        private final Key product = new Key(KEYS, "product");
        private final BufferedResourceHatch warehouse = new BufferedResourceHatch(HatchMode.MIXED, 2, Map.of(KEYS, 10L), () -> {
        });
        private final EnergyBuffer energy = new EnergyBuffer(20, () -> {
        });
        private final ConcurrentLinkedQueue<RecipeStage> stages = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> phases = new ConcurrentLinkedQueue<>();
        private Runnable onPre = () -> {
        };
        private Runnable checkPreparation = () -> {
        };

        private Fixture(TaskScheduler scheduler, String name, ConcurrentLinkedQueue<String> log, CountDownLatch bothPure) {
            ComponentType<MachineInstance, String> callbacks = new ComponentType<>(id("callbacks_" + name), _ -> name, List.of(new MachineSystem<>() {
                @Override
                public ResourceLocation id() {
                    return PlannedMachineSchedulerTest.id("callbacks_" + name);
                }

                @Override
                public void onPreTick(MachineInstance machine, String value) {
                    requireOwner();
                    checkPreparation.run();
                    phases.add("pre");
                    log.add(name + ":pre");
                    onPre.run();
                }

                @Override
                public void onTick(MachineInstance machine, String value) {
                    requireOwner();
                    checkPreparation.run();
                    phases.add("normal");
                }

                @Override
                public void onPostTick(MachineInstance machine, String value) {
                    requireOwner();
                    checkPreparation.run();
                    phases.add("post");
                }
            }));
            var type = MachineType.builder(id(name), RecipeText.Cancelled, id("shape"))
                                  .executionMode(ExecutionMode.PLANNED_PARALLEL).component(callbacks).component(MachineWorkSlots.TYPE).build();
            var assembly = MachineAssembly.builder(type, "formal-v1").recreated(callbacks)
                                          .workSlots(new MachineWorkSlotsNbt("slots-v1", List.of())).build();
            controller = new MachineController(assembly, new Host(owner, type.id()), scheduler, new MachineLifecycleRegistry());
            controller.load(null);
            machine = Objects.requireNonNull(controller.machine());
            energy.receiveEnergy(10, Actionable.MODULATE);
            Objects.requireNonNull(warehouse.input()).insert(raw, 3, Actionable.MODULATE);
            Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(warehouse), List.of(energy), List.of(energy)));
            var observer = new RecipeRequirementSystem<String>() {
                @Override
                public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<String> requirement, RecipeStage stage) {
                    if (stage != RecipeStage.START) return null;
                    return new StartPreparation(Fixture.this, bothPure);
                }

                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                    return observed(RecipeStage.START);
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                    return observed(RecipeStage.TICK);
                }

                @Override
                public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                    return observed(RecipeStage.END);
                }
            };
            var observed = new RecipeRequirementType<>(id("observer"), observer).requirement(id("observer"), name);
            var materials = RecipeRequirements.RESOURCES.requirement(id("materials"), new ResourceRequirement(
                List.of(new GenericStack(raw, 1)), List.of(new GenericStack(raw, 1)), List.of(), List.of(new GenericStack(product, 1)), false));
            var power = RecipeRequirements.ENERGY.requirement(id("power"), new EnergyRequirement(2, 3, 0, 1, false));
            process = new RecipeProcess(machine, new MachineRecipe(id("recipe_" + name), 1, List.of(observed, materials, power)), 19);
            assertTrue(Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE)).start(process));
        }

        private RequirementTransaction observed(RecipeStage stage) {
            requireOwner();
            stages.add(stage);
            return RequirementTransaction.SUCCESS;
        }

        private void requireOwner() {
            assertSame(owner, Thread.currentThread());
        }

        private void assertCompleted() {
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), List.copyOf(stages));
            assertEquals(List.of("pre", "normal", "post"), List.copyOf(phases));
            assertEquals(1, Objects.requireNonNull(warehouse.input()).amount(raw));
            assertEquals(1, Objects.requireNonNull(warehouse.output()).amount(product));
            assertEquals(6, energy.savedEnergy());
            machine.requirePersistenceBoundary();
        }

        private void unload() {
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }

        private static final class StartPreparation implements RequirementPreparation {
            private final RecipePureComputation kernel;
            private final Fixture fixture;

            private StartPreparation(Fixture fixture, CountDownLatch bothPure) {
                this.fixture = fixture;
                kernel = budget -> {
                    assertNotSame(fixture.owner, Thread.currentThread());
                    if (!budget.take()) {
                        return false;
                    }
                    bothPure.countDown();
                    try {
                        assertTrue(bothPure.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return true;
                };
            }

            @Override
            public RecipePureComputation computation() {
                return kernel;
            }

            @Override
            public RequirementTransaction acquire() {
                return fixture.observed(RecipeStage.START);
            }
        }
    }

    private record Host(Thread owner, ResourceLocation type) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No live World in independent scheduler tests");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong lifecycle owner");
        }
    }
}
