package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineDeclarationExecutionTest {
    private static RecipeProcess process(MachineInstance owner, ResourceLocation group) {
        return new RecipeProcess(owner, new MachineRecipe(id("recipe_" + group.getPath()), 3, List.of(), Set.of(group)), 1);
    }

    private static MachineType.Builder declaration(String name) {
        return MachineType.builder(id(name), GuiText.ResourceStorage, id("structure"));
    }

    private static MachineLifecycle attach(MachineInstance machine) {
        var thread = Thread.currentThread();
        var identity = new MachineIdentity(Objects.requireNonNull(machine.type()).id(), 0, BlockPos.ORIGIN);
        return new MachineLifecycle(machine, new MachineLifecycleHost() {
            @Override
            public MachineIdentity identity() {
                return identity;
            }

            @Override
            public World world() {
                throw new AssertionError("Logical tests do not create a game World");
            }

            @Override
            public void requireServerThread() {
                assertSame(thread, Thread.currentThread());
            }
        }, new MachineLifecycleRegistry());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("declaration_test", path);
    }

    @Test
    void declarationConstructionDoesNotRunFactoriesOrGrantSchedulingBeforeAttachedLoad() {
        var calls = new ObjectArrayList<String>();
        var component = new ComponentType<MachineInstance, String>(id("component"), owner -> {
            assertEquals(Objects.requireNonNull(owner.type()).id(), Objects.requireNonNull(owner.lifecycle()).host().identity().machineType());
            calls.add("create");
            return "state";
        }, List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return MachineDeclarationExecutionTest.id("system");
            }

            @Override
            public void onTick(MachineInstance owner, String state) {
                calls.add("tick:" + state);
            }
        }));
        var type = declaration("non_recipe").component(component).executionMode(ExecutionMode.MAIN_THREAD).build();
        var machine = new MachineInstance(type);
        assertTrue(calls.isEmpty());
        assertFalse(machine.active());
        assertFalse(machine.canProcessRecipes());
        assertThrows(IllegalStateException.class, machine::onSchedule);
        assertThrows(IllegalStateException.class, machine::requirePersistenceBoundary);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            scheduler.register(machine);
            scheduler.tick();
            assertTrue(calls.isEmpty());
            var lifecycle = attach(machine);
            machine.components().create(component, machine);
            assertEquals(List.of("create"), calls);
            scheduler.tick();
            assertEquals(List.of("create"), calls);
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            scheduler.tick();
            assertEquals(List.of("create", "tick:state"), calls);
            assertNull(machine.components().get(MachineWorkSlots.TYPE));
            lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            scheduler.tick();
            assertEquals(List.of("create", "tick:state"), calls);
        }
    }

    @Test
    void declaredDefaultSelectsTheThreadAndExplicitSchedulingComponentOverridesIt() {
        var threads = new ConcurrentLinkedQueue<Thread>();
        var type = declaration("mode").executionMode(ExecutionMode.MAIN_THREAD).build();
        var machine = new MachineInstance(type);
        var lifecycle = attach(machine);
        var component = new ComponentType<MachineInstance, String>(id("observe"), _ -> "state", List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return MachineDeclarationExecutionTest.id("observe_system");
            }

            @Override
            public void onTick(MachineInstance owner, String state) {
                threads.add(Thread.currentThread());
            }
        }));
        machine.components().create(component, machine);
        machine.components().create(MachineScheduling.TYPE, machine);
        lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            scheduler.register(machine);
            scheduler.tick();
            assertSame(Thread.currentThread(), threads.remove());
            machine.components().put(MachineScheduling.TYPE, new MachineScheduling(ExecutionMode.CONCURRENT));
            scheduler.tick();
            assertNotSame(Thread.currentThread(), threads.remove());
        }
        lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    @Test
    void declaredWorkSlotsAndImplicitMachineScopeDriveAdmission() {
        var groupA = id("a");
        var groupB = id("b");
        var explicit = declaration("explicit").component(MachineWorkSlots.TYPE)
                                              .workSlot(new WorkSlotDefinition("a", Set.of(groupA)))
                                              .workSlot(new WorkSlotDefinition("b", Set.of(groupB))).build();
        var machine = new MachineInstance(explicit);
        var lifecycle = attach(machine);
        var slots = machine.components().create(MachineWorkSlots.TYPE, machine);
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        lifecycle.form(new MachineHatches(List.of(), List.of(), List.of()));
        assertFalse(slots.startIn("a", process(machine, groupB)));
        assertTrue(slots.startIn("a", process(machine, groupA)));
        assertTrue(slots.startIn("b", process(machine, groupB)));
        machine.onSchedule();
        assertEquals(1, Objects.requireNonNull(slots.requireSlot("a").process()).state().successfulTicks());
        assertEquals(1, Objects.requireNonNull(slots.requireSlot("b").process()).state().successfulTicks());
        lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);

        var implicit = new MachineInstance(declaration("implicit").component(MachineWorkSlots.TYPE).recipeGroup(groupA).build());
        var implicitLifecycle = attach(implicit);
        var implicitSlots = implicit.components().create(MachineWorkSlots.TYPE, implicit);
        implicitLifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        implicitLifecycle.form(new MachineHatches(List.of(), List.of(), List.of()));
        assertFalse(implicitSlots.startIn("main", process(implicit, groupB)));
        assertTrue(implicitSlots.startIn("main", process(implicit, groupA)));
        implicitLifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }
}
