package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessAbortListener;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;

@SuppressWarnings("resource") // Fixture-local registries own these listeners for the whole scenario.
class MachineLifecycleProcessTest {
    private static final ResourceLocation GROUP = id("group");
    private static final RecipeRequirement<EnergyRequirement> ENERGY = RecipeRequirements.ENERGY.requirement(id("energy"),
        new EnergyRequirement(3, 2, 0, 5, false));
    private static final MachineRecipe RECIPE = new MachineRecipe(id("process"), 3, List.of(ENERGY), Set.of(GROUP));
    private static final MachineWorkSlotsNbt CODEC = new MachineWorkSlotsNbt("layout-v1",
        List.of(RecipeProcessNbt.builder(RECIPE, "recipe-v1").build()));

    @Test
    void structureLossPausesExistingWorkAndAdmissionButOtherComponentsKeepTicking() {
        var fixture = new Fixture();
        fixture.machine.onSchedule();
        assertEquals(0, fixture.auxiliaryTicks);
        fixture.lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        fixture.machine.onSchedule();
        assertEquals(1, fixture.auxiliaryTicks);
        assertEquals(0, fixture.admissionChecks);
        assertTrue(fixture.slots.activeProcesses().isEmpty());
        fixture.lifecycle.form(fixture.hatches());
        fixture.machine.onSchedule();
        var process = fixture.process();
        assertEquals(1, process.state().progress());
        assertEquals(15, fixture.input.storedEnergy());
        fixture.lifecycle.invalidateStructure();
        fixture.machine.onSchedule();
        fixture.machine.onSchedule();
        assertSame(process, fixture.process());
        assertEquals(1, process.state().progress());
        assertEquals(15, fixture.input.storedEnergy());
        assertEquals(0, fixture.output.storedEnergy());
        assertEquals(1, fixture.admissionChecks);
        assertEquals(4, fixture.auxiliaryTicks);
        assertTrue(MachineHatches.get(fixture.machine).energyInputs().isEmpty());
        fixture.lifecycle.form(fixture.hatches());
        fixture.machine.onSchedule();
        fixture.machine.onSchedule();
        assertTrue(fixture.slots.activeProcesses().isEmpty());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(11, fixture.input.storedEnergy());
        assertEquals(5, fixture.output.storedEnergy());
    }

    @Test
    void unloadKeepsCommittedProgressAndRestoreWaitsForFreshLoadAndStructureWithoutReplayingStart() {
        for (var reason : List.of(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD, MachineLifecycleEvent.UnloadReason.INVALIDATED)) {
            var original = new Fixture();
            original.loadAndForm();
            original.machine.onSchedule();
            var state = original.process().state();
            assertTrue(original.lifecycle.unload(reason));
            assertFalse(original.lifecycle.unload(reason));
            original.machine.onSchedule();
            assertEquals(state, original.process().state());
            var saved = CODEC.encode(original.slots);
            var restored = new Fixture(original.input, original.output);
            CODEC.restore(restored.slots, saved);
            var resumed = restored.process();
            restored.machine.onSchedule();
            assertEquals(state, resumed.state());
            var loaded = new ObjectArrayList<MachineLifecycleEvent.LoadBoundary>();
            restored.registry.register(new MachineLifecycleListener() {
                @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
                    loaded.add(event.boundary());
                    assertEquals(state, restored.process().state());
                    assertFalse(event.context().formed());
                }
            });
            restored.lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED);
            restored.machine.onSchedule();
            assertEquals(state, resumed.state());
            assertEquals(List.of(MachineLifecycleEvent.LoadBoundary.RESTORED), loaded);
            restored.lifecycle.form(restored.hatches());
            restored.machine.onSchedule();
            restored.machine.onSchedule();
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, resumed.state().lifecycle());
            assertEquals(11, restored.input.storedEnergy());
            assertEquals(5, restored.output.storedEnergy());
            assertEquals(state, original.process().state());
            assertThrows(IllegalStateException.class, () -> original.lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED));
        }
    }

    @Test
    void breakCancelsNewRunningAndBlockedWorkWithoutEndOrHistoricalRefund() {
        for (int point = 0; point < 3; point++) {
            var fixture = new Fixture();
            fixture.loadAndForm();
            if (point == 2) { fixture.input.extractEnergy(17, Actionable.MODULATE); }
            var process = new RecipeProcess(fixture.machine, RECIPE, 1);
            int[] aborts = {0};
            var abortType = new ComponentType<RecipeProcess, ProcessAbortListener>(id("abort"), _ -> ignored -> aborts[0]++, List.of());
            process.components().create(abortType, process);
            assertTrue(fixture.slots.start(process));
            if (point != 0) { fixture.machine.onSchedule(); }
            var state = process.state();
            long energy = fixture.input.storedEnergy();
            var events = new ObjectArrayList<String>();
            fixture.registry.register(new MachineLifecycleListener() {
                @Override public void onControllerBroken(MachineLifecycleEvent.ControllerBroken event) {
                    assertFalse(fixture.machine.canProcessRecipes());
                    assertEquals(state, process.state());
                    events.add("broken");
                }
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
                    assertEquals(MachineLifecycleEvent.UnloadReason.INVALIDATED, event.reason());
                    assertTrue(fixture.slots.activeProcesses().isEmpty());
                    events.add("unloaded");
                }
            });
            assertTrue(fixture.lifecycle.controllerBroken());
            assertEquals(RecipeProcess.Lifecycle.CANCELLED, process.state().lifecycle());
            assertEquals(state.progress(), process.state().progress());
            assertEquals(state.started(), process.state().started());
            assertEquals(RecipeText.ControllerBroken.failure(), process.state().result());
            assertEquals(energy, fixture.input.storedEnergy());
            assertEquals(0, fixture.output.storedEnergy());
            assertEquals(1, aborts[0]);
            assertEquals(List.of("broken", "unloaded"), events);
            assertFalse(fixture.lifecycle.controllerBroken());
            assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
            fixture.machine.onSchedule();
            CODEC.encode(fixture.slots);
            assertEquals(1, aborts[0]);
        }
    }

    @Test
    void breakFailureStillCancelsOtherSlotsAndUnloadsCapturedParticipantsEvenAfterComponentRemoval() {
        var fixture = new Fixture();
        fixture.machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        var slots = new MachineWorkSlots(fixture.machine,
            List.of(new WorkSlotDefinition("one", Set.of(GROUP)), new WorkSlotDefinition("two", Set.of(GROUP))));
        fixture.machine.components().put(MachineWorkSlots.TYPE, slots);
        fixture.loadAndForm();
        var first = new RecipeProcess(fixture.machine, RECIPE, 1);
        var second = new RecipeProcess(fixture.machine, RECIPE, 2);
        var cancellationFault = new IllegalStateException("owned effect failed");
        var abortType = new ComponentType<RecipeProcess, ProcessAbortListener>(id("failed_abort"),
            _ -> ignored -> { throw cancellationFault; }, List.of());
        second.components().create(abortType, second);
        assertTrue(slots.start(first));
        assertTrue(slots.start(second));
        var events = new ObjectArrayList<String>();
        var brokenFault = new IllegalArgumentException("broken hook failed");
        fixture.registry.register(new MachineLifecycleListener() {
            @Override public void onControllerBroken(MachineLifecycleEvent.ControllerBroken event) {
                fixture.machine.components().remove(MachineWorkSlots.TYPE);
                events.add("broken");
                throw brokenFault;
            }
            @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { events.add("unloaded"); }
        });
        assertSame(brokenFault, assertThrows(IllegalArgumentException.class, fixture.lifecycle::controllerBroken));
        assertEquals(List.of(cancellationFault), List.of(brokenFault.getSuppressed()));
        assertEquals(RecipeProcess.Lifecycle.CANCELLED, first.state().lifecycle());
        assertNull(first.host());
        assertSame(slots, second.host());
        assertEquals(List.of("broken", "unloaded"), events);
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, () -> CODEC.encode(fixture.slots));
        assertFalse(fixture.lifecycle.controllerBroken());
        assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    @Test
    void openAcquisitionRejectsWorldTransitionsBeforeStateMutationAndCanThenBeRolledBack() {
        var fixture = new Fixture();
        fixture.loadAndForm();
        var process = new RecipeProcess(fixture.machine, RECIPE, 1);
        assertTrue(fixture.slots.start(process));
        var held = ENERGY.acquire(process, RecipeStage.START);
        assertThrows(IllegalStateException.class, fixture.lifecycle::invalidateStructure);
        assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        assertThrows(IllegalStateException.class, () -> CODEC.encode(fixture.slots));
        assertTrue(fixture.lifecycle.formed());
        assertSame(process, fixture.process());
        assertEquals(20, fixture.input.storedEnergy());
        ResourceTransactions.rollback(held.reservations(), held::rollback);
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        CODEC.encode(fixture.slots);
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertEquals(20, fixture.input.storedEnergy());
    }

    @Test
    void faultedProcessStillAllowsWorldCleanupButNeverSavingOrFurtherExecution() {
        var fixture = new Fixture();
        fixture.loadAndForm();
        var process = new RecipeProcess(fixture.machine, RECIPE, 1);
        var fault = new IllegalStateException("process callback failed");
        var system = new ComponentSystem<RecipeProcess, String>() {
            @Override public ResourceLocation id() { return MachineLifecycleProcessTest.id("faulting_system"); }
            @Override public void onTick(RecipeProcess owner, String component) { throw fault; }
        };
        var type = new ComponentType<>(id("faulting_component"), _ -> "owned-state", List.of(system));
        process.components().create(type, process);
        assertTrue(fixture.slots.start(process));
        int[] unloads = {0};
        fixture.registry.register(new MachineLifecycleListener() {
            @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { unloads[0]++; }
        });
        assertSame(fault, assertThrows(IllegalStateException.class, fixture.machine::onSchedule));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, () -> CODEC.encode(fixture.slots));
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
        assertEquals(1, unloads[0]);
        assertSame(process, fixture.process());
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, () -> CODEC.encode(fixture.slots));
        assertEquals(20, fixture.input.storedEnergy());
        assertEquals(0, fixture.output.storedEnergy());
    }

    @Test
    void brokenComponentGraphStillCleansAllIdentifiableListenersAndOwnedSlotsWithoutClaimingSuccess() {
        for (boolean destroy : List.of(false, true)) {
            var fixture = new Fixture();
            var events = new ObjectArrayList<String>();
            MachineLifecycleListener first = new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { events.add("first"); }
            };
            MachineLifecycleListener second = new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { events.add("second"); }
            };
            var firstType = new ComponentType<MachineInstance, MachineLifecycleListener>(id("first"), _ -> first, List.of());
            var secondType = new ComponentType<MachineInstance, MachineLifecycleListener>(id("second"), _ -> second, List.of());
            fixture.machine.components().put(firstType, first);
            fixture.machine.components().put(secondType, second);
            fixture.registry.register(new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { events.add("global"); }
            });
            var removedGlobal = fixture.registry.register(new MachineLifecycleListener() {
                @Override public void onUnloaded(MachineLifecycleEvent.Unloaded event) { events.add("removed-global"); }
            });
            fixture.loadAndForm();
            assertTrue(removedGlobal.cancel());
            fixture.machine.onSchedule();
            var process = fixture.process();
            var before = process.state();
            fixture.machine.components().addDependency(firstType, secondType);
            fixture.machine.components().addDependency(secondType, firstType);
            assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
            if (destroy) { assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken); }
            else {
                assertThrows(IllegalStateException.class,
                    () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
            }
            assertEquals(List.of("first", "second", "global"), events);
            assertEquals(MachineLifecycle.State.UNLOADED, fixture.lifecycle.state());
            if (destroy) {
                assertEquals(RecipeProcess.Lifecycle.CANCELLED, process.state().lifecycle());
                assertNull(process.host());
            } else { assertEquals(before, process.state()); }
            assertEquals(15, fixture.input.storedEnergy());
            assertEquals(0, fixture.output.storedEnergy());
            assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
            assertThrows(IllegalStateException.class, () -> CODEC.encode(fixture.slots));
            assertFalse(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
            assertEquals(3, events.size());
        }
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("lifecycle_process_test", path); }

    private static final class Fixture {
        final MachineInstance machine = new MachineInstance();
        final MachineLifecycleRegistry registry = new MachineLifecycleRegistry();
        final MachineLifecycle lifecycle = new MachineLifecycle(machine, new Host(), registry);
        final MachineWorkSlots slots = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("main", Set.of(GROUP))));
        final EnergyBuffer input;
        final EnergyBuffer output;
        int auxiliaryTicks;
        int admissionChecks;

        Fixture() {
            this(new EnergyBuffer(100, () -> {}), new EnergyBuffer(100, () -> {}));
            input.receiveEnergy(20, Actionable.MODULATE);
        }

        Fixture(EnergyBuffer input, EnergyBuffer output) {
            this.input = input;
            this.output = output;
            machine.components().put(MachineWorkSlots.TYPE, slots);
            var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "lifecycle.pool", List.of(RECIPE))));
            var scanner = new RecipeScanner(slots, catalog, () -> 1);
            var scanning = new RecipeScanningSystem(new ScanLimits(10, 10), _ -> { admissionChecks++; return true; });
            var scanType = new ComponentType<>(id("scanner"), _ -> scanner, List.of(scanning));
            machine.components().put(scanType, scanner);
            var auxiliary = new MachineSystem<int[]>() {
                @Override public ResourceLocation id() { return MachineLifecycleProcessTest.id("auxiliary"); }
                @Override public void onTick(MachineInstance owner, int[] component) { auxiliaryTicks++; }
            };
            var auxiliaryType = new ComponentType<>(id("auxiliary"), _ -> new int[0], List.of(auxiliary));
            machine.components().create(auxiliaryType, machine);
        }

        void loadAndForm() {
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            lifecycle.form(hatches());
        }

        MachineHatches hatches() { return new MachineHatches(List.of(), List.of(input), List.of(output)); }
        RecipeProcess process() { return Objects.requireNonNull(slots.requireSlot("main").process()); }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread serverThread = Thread.currentThread();
        @Override public MachineIdentity identity() { return new MachineIdentity(id("controller"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new UnsupportedOperationException("Logical fixture has no Minecraft world"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != serverThread) { throw new IllegalStateException("Not the test server thread"); }
        }
    }
}
