package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.ProcessAbortListener;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProcessCancellationTest {
    private static final KeyType TYPE = new KeyType("cancel");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ProcessResult.Failure REASON = new ProcessResult.Failure("cancel.test.reason");

    @Test
    void cancellationOfNewRunningAndBlockedWorkKeepsCommittedHistoryAndNeverExecutesEnd() {
        for (var point : Point.values()) {
            var env = new Environment();
            if (point == Point.TICK_BLOCKED) { env.input.extract(MATERIAL, 17, Actionable.MODULATE); }
            if (point == Point.END_BLOCKED) { env.output.insert(PRODUCT, 9, Actionable.MODULATE); }
            var process = env.process(2);
            int[] aborted = {0};
            install(process, "state_listener", current -> {
                assertSame(RecipeProcess.Lifecycle.CANCELLED, current.state().lifecycle());
                assertSame(REASON, current.state().result());
                aborted[0]++;
            });
            assertTrue(env.host.start(process));
            if (point != Point.NEW) { env.machine.onSchedule(); }
            if (point == Point.END_BLOCKED) { env.machine.onSchedule(); }
            if (point == Point.TICK_BLOCKED || point == Point.END_BLOCKED) {
                assertInstanceOf(ProcessResult.Blocked.class, process.state().result());
            }
            var before = process.state();
            long input = env.input.amount(MATERIAL);
            long output = env.output.amount(PRODUCT);
            long energy = env.energy.storedEnergy();
            assertTrue(process.cancel(REASON));
            assertNull(process.host());
            assertNull(env.host.requireSlot("main").process());
            assertSame(REASON, env.host.requireSlot("main").result());
            assertEquals(before.progress(), process.state().progress());
            assertEquals(before.successfulTicks(), process.state().successfulTicks());
            assertEquals(before.started(), process.state().started());
            assertEquals(input, env.input.amount(MATERIAL));
            assertEquals(output, env.output.amount(PRODUCT));
            assertEquals(energy, env.energy.storedEnergy());
            env.assertReleased();
            assertFalse(process.cancel());
            assertSame(REASON, process.tick());
            env.machine.onSchedule();
            assertEquals(1, aborted[0]);
            assertThrows(IllegalArgumentException.class, () -> env.host.start(process));
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void detachTransfersRunnableWorkAndCompletionDoesNotReplayCancellationOrOutputs() {
        var env = new Environment();
        var process = env.process(1);
        int[] aborted = {0};
        install(process, "transfer_listener", _ -> aborted[0]++);
        assertTrue(env.host.start(process));
        assertTrue(env.host.detach(process));
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        var destination = new MachineWorkSlots(env.machine, List.of());
        assertTrue(destination.start(process));
        destination.tickProcesses();
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(15, env.input.amount(MATERIAL));
        assertEquals(2, env.output.amount(PRODUCT));
        assertFalse(process.cancel());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, aborted[0]);
        assertEquals(2, env.output.amount(PRODUCT));
    }

    @Test
    void businessFailureAbortsOnceAfterCurrentStageRollbackWithoutRefundingEarlierStages() {
        for (var failedStage : RecipeStage.values()) {
            var env = new Environment();
            int[] cancelledRequirements = {0};
            int[] listeners = {0};
            var guard = guard("failure", failedStage, () -> cancelledRequirements[0]++);
            var process = env.process(1, guard);
            install(process, "failure_listener", current -> {
                assertSame(RecipeProcess.Lifecycle.FAILED, current.state().lifecycle());
                assertSame(REASON, current.state().result());
                listeners[0]++;
            });
            assertTrue(env.host.start(process));
            env.machine.onSchedule();
            assertNull(process.host());
            long expectedInput = switch (failedStage) { case START -> 20; case TICK -> 17; case END -> 15; };
            assertEquals(expectedInput, env.input.amount(MATERIAL));
            assertEquals(0, env.output.amount(PRODUCT));
            env.assertReleased();
            assertSame(REASON, process.tick());
            assertFalse(process.cancel());
            assertEquals(1, listeners[0]);
            assertEquals(1, cancelledRequirements[0]);
            assertSame(REASON, env.host.requireSlot("main").result());
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void cleanupCapturesReverseDependencyOrderAndAggregatesFaultsWithoutSkippingCallbacks() {
        var env = new Environment();
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("last requirement");
        var dependentFailure = new IllegalArgumentException("dependent component");
        var baseFailure = new IllegalStateException("base component");
        var first = guard("a", null, () -> events.add("requirement-a"));
        var last = guard("z", null, () -> { events.add("requirement-z"); throw primary; });
        var process = env.process(2, first, last);
        ComponentType<RecipeProcess, ProcessAbortListener> base = new ComponentType<>(id("base"), _ -> current -> {
            events.add("base");
            assertThrows(IllegalStateException.class, current::tick);
            throw baseFailure;
        }, List.of());
        ComponentType<RecipeProcess, ProcessAbortListener> dependent = new ComponentType<>(id("dependent"), _ -> current -> {
            events.add("dependent");
            current.components().remove(base);
            assertThrows(IllegalStateException.class, current::cancel);
            assertThrows(IllegalStateException.class, env.host::snapshot);
            throw dependentFailure;
        }, List.of(), Set.of(base));
        process.components().create(dependent, process);
        process.components().create(base, process);
        assertTrue(env.host.start(process));
        assertSame(primary, assertThrows(IllegalStateException.class, process::cancel));
        assertEquals(List.of("requirement-z", "requirement-a", "dependent", "base"), events);
        assertEquals(List.of(dependentFailure, baseFailure), List.of(primary.getSuppressed()));
        assertSame(process, env.host.requireSlot("main").process());
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, process::tick);
        assertThrows(IllegalStateException.class, env.host::snapshot);
        assertThrows(IllegalStateException.class, process::cancel);
        assertEquals(4, events.size());
        assertEquals(20, env.input.amount(MATERIAL));
        env.assertReleased();
    }

    @Test
    void cancelAllCleansOtherSlotsAfterFailureAndNeverReactivatesFaultedHost() {
        var healthy = new Environment();
        int[] healthyAborts = {0};
        var running = healthy.process(3);
        install(running, "healthy", _ -> healthyAborts[0]++);
        assertTrue(healthy.host.start(running));
        healthy.machine.onSchedule();
        assertEquals(1, healthy.host.cancelAll());
        assertTrue(healthy.host.activeProcesses().isEmpty());
        assertEquals(15, healthy.input.amount(MATERIAL));
        assertEquals(0, healthy.output.amount(PRODUCT));
        assertEquals(0, healthy.host.cancelAll());
        assertEquals(1, healthyAborts[0]);

        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 3);
        var host = new MachineWorkSlots(machine, List.of(new WorkSlotDefinition("a", Set.of()),
            new WorkSlotDefinition("b", Set.of()), new WorkSlotDefinition("c", Set.of())));
        var events = new ObjectArrayList<String>();
        var middleFailure = new IllegalStateException("middle");
        var processes = new ObjectArrayList<RecipeProcess>();
        for (var name : List.of("a", "b", "c")) {
            var process = new RecipeProcess(machine, new MachineRecipe(id(name), 3, List.of()), 17);
            install(process, name, _ -> {
                events.add(name);
                if (name.equals("b")) { throw middleFailure; }
            });
            assertTrue(host.startIn(name, process));
            processes.add(process);
        }
        assertSame(middleFailure, assertThrows(IllegalStateException.class, host::cancelAll));
        assertEquals(List.of("c", "b", "a"), events);
        assertNull(host.requireSlot("a").process());
        assertSame(processes.get(1), host.requireSlot("b").process());
        assertNull(host.requireSlot("c").process());
        assertThrows(IllegalStateException.class, host::tickProcesses);
        assertThrows(IllegalStateException.class, host::snapshot);
        assertThrows(IllegalStateException.class, host::cancelAll);
        assertEquals(List.of("c", "b", "a"), events);
    }

    @Test
    void openAcquisitionAndForeignHostAreRejectedBeforeCancellationChangesState() {
        var env = new Environment();
        var process = env.process(2);
        assertTrue(env.host.start(process));
        var foreign = new MachineWorkSlots(env.machine, List.of());
        assertFalse(foreign.cancel(process));
        assertThrows(IllegalArgumentException.class, () -> process.cancelFrom(foreign, REASON));
        var held = env.io.acquire(process, RecipeStage.START);
        assertEquals(17, env.input.availableAmount(MATERIAL));
        assertThrows(IllegalStateException.class, process::cancel);
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertSame(process, env.host.requireSlot("main").process());
        ResourceTransactions.rollback(held.reservations(), held::rollback);
        assertTrue(process.cancel(REASON));
        assertTrue(env.host.snapshot().stream().allMatch(slot -> slot.process() == null));
        assertEquals(20, env.input.amount(MATERIAL));
    }

    @Test
    void structuralOverlayCannotRedirectCancellationToAnUnselectedChild() {
        var events = new ObjectArrayList<String>();
        var first = guard("first", null, () -> events.add("first"));
        var second = guard("second", null, () -> events.add("second"));
        var selector = RecipeRequirements.SELECTIVE.requirement(id("selector"), new SelectiveRequirement(List.of(first, second)));
        var process = new RecipeProcess(new MachineInstance(), new MachineRecipe(id("selected"), 5, List.of(selector)), 17);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var overlays = RecipeOverlays.getOrCreate(process);
        RecipeOverlayType<SelectiveRequirement, Boolean> reorder = new RecipeOverlayType<>(id("reorder"), RecipeRequirements.SELECTIVE,
            (base, _) -> new SelectiveRequirement(List.of(base.candidates().getLast(), base.candidates().getFirst()), base.modifiers()));
        assertThrows(IllegalArgumentException.class, () -> overlays.put(selector, "bad-order", reorder, true));
        assertTrue(overlays.snapshot().isEmpty());
        assertTrue(process.cancel());
        assertEquals(List.of("first"), events);
        process.requirePersistenceBoundary();
    }

    private static void install(RecipeProcess process, String name, ProcessAbortListener listener) {
        ComponentType<RecipeProcess, ProcessAbortListener> type = new ComponentType<>(id(name), _ -> listener, List.of());
        process.components().create(type, process);
    }

    private static RecipeRequirement<Boolean> guard(String name, RecipeStage failedStage, Runnable cancel) {
        return new RecipeRequirementType<Boolean>(new ResourceLocation("zzz_cancel_test", name), new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return result(RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return result(RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return result(RecipeStage.END);
            }
            private RequirementTransaction result(RecipeStage stage) {
                return failedStage == stage ? new Rejected(REASON) : RequirementTransaction.SUCCESS;
            }
            @Override public void cancel(RecipeProcess process, RecipeRequirement<Boolean> requirement) { cancel.run(); }
        }).requirement(id(name), true);
    }

    private record Rejected(ProcessResult result) implements RequirementTransaction {
        @Override public void commit() { fail("Rejected stage must not commit"); }
        @Override public void rollback() { }
    }
    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override public boolean isValid() { return input.isValid() && output.isValid(); }
    }
    private enum Point { NEW, RUNNING, TICK_BLOCKED, END_BLOCKED }

    private static final class Environment {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer(100);
        private final SlottedResourceBuffer output = buffer(10);
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> { });
        private final MachineWorkSlots host = new MachineWorkSlots(machine, List.of());
        private final RecipeRequirement<ResourceRequirement> io = RecipeRequirements.RESOURCES.requirement(id("io"),
            new ResourceRequirement(List.of(new GenericStack(MATERIAL, 3)), List.of(new GenericStack(MATERIAL, 2)),
                List.of(), List.of(new GenericStack(PRODUCT, 2)), false));
        private Environment() {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
            energy.restore(30);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(energy), List.of()));
            machine.components().put(MachineWorkSlots.TYPE, host);
        }
        private RecipeProcess process(int duration, RecipeRequirement<?>... extras) {
            var requirements = new ObjectArrayList<RecipeRequirement<?>>();
            requirements.add(io);
            requirements.add(RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(4, 1, 0, 0, false)));
            requirements.addAll(List.of(extras));
            return new RecipeProcess(machine, new MachineRecipe(id("recipe"), duration, requirements), 17);
        }
        private void assertReleased() {
            assertEquals(input.amount(MATERIAL), input.availableAmount(MATERIAL));
            assertEquals(10 - output.amount(PRODUCT), output.insert(PRODUCT, 100, Actionable.SIMULATE));
            assertEquals(energy.storedEnergy(), energy.energySnapshot().availableEnergy());
        }
    }

    private static SlottedResourceBuffer buffer(long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, capacity);
        return new SlottedResourceBuffer(1, capacities, () -> { });
    }
    private static ResourceLocation id(String name) { return new ResourceLocation("cancel_test", name); }
}
