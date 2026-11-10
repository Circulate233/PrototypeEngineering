package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots.SlotSnapshot;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotRestartPolicy;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineWorkSlotsTest {
    private static final ResourceLocation GROUP_A = id("group_a");
    private static final ResourceLocation GROUP_B = id("group_b");
    private static final KeyType KEY_TYPE = new KeyType("work_slots");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final Key PRODUCT = new Key(KEY_TYPE, "product");
    private static final Runnable NO_OP = () -> {
    };

    private static MachineInstance machine(int capacity) {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, capacity);
        return machine;
    }

    private static MachineWorkSlots installedHost(MachineInstance machine, List<WorkSlotDefinition> definitions) {
        var host = new MachineWorkSlots(machine, definitions);
        machine.components().put(MachineWorkSlots.TYPE, host);
        return host;
    }

    private static List<WorkSlotDefinition> definitions(String... names) {
        var definitions = new ObjectArrayList<WorkSlotDefinition>();
        for (var name : names) {
            definitions.add(new WorkSlotDefinition(name));
        }
        return definitions;
    }

    private static MachineRecipe recipe(String name, int duration, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id(name), duration, ObjectArrayList.of(requirements));
    }

    private static MachineRecipe groupedRecipe(String name, ResourceLocation group) {
        return new MachineRecipe(id(name), 4, List.of(), Set.of(group));
    }

    private static RecipeProcess process(MachineInstance owner, MachineRecipe recipe) {
        return new RecipeProcess(owner, recipe, 17);
    }

    private static RecipeRequirement<Boolean> outcome(String name, Supplier<ProcessResult> result) {
        return new RecipeRequirementType<Boolean>(id(name + "_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return new Outcome(result.get());
            }
        }).requirement(id(name + "_instance"), true);
    }

    private static void addPostCallback(RecipeProcess process, Runnable callback) {
        var type = new ComponentType<RecipeProcess, Runnable>(id("callback"), _ -> callback, List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return MachineWorkSlotsTest.id("callback_system");
            }

            @Override
            public void onPostTick(RecipeProcess owner, Runnable component) {
                component.run();
            }
        }));
        process.components().create(type, process);
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, 100);
        return new SlottedResourceBuffer(2, capacities, NO_OP);
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return List.of(new GenericStack(key, amount));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("work_slots_test", name);
    }

    @Test
    void machineScheduleInvokesTheSelectedExecutorOncePerProcessAndAdvancesOnlyOnce() {
        var machine = machine(2);
        var calls = new ObjectArrayList<RecipeProcess>();
        var host = new MachineWorkSlots(machine, definitions("first", "second"), Set.of(), process -> {
            calls.add(process);
            process.tick();
        });
        machine.components().put(MachineWorkSlots.TYPE, host);
        var first = process(machine, recipe("first", 4));
        var second = process(machine, recipe("second", 4));
        assertTrue(host.start(first));
        assertTrue(host.start(second));
        machine.onSchedule();
        assertEquals(List.of(first, second), calls);
        assertEquals(1, first.state().successfulTicks());
        assertEquals(1, second.state().successfulTicks());
        machine.onSchedule();
        assertEquals(List.of(first, second, first, second), calls);
        assertEquals(2, first.state().successfulTicks());
        assertEquals(2, second.state().successfulTicks());
    }

    @Test
    void blockedWorkStaysAttachedWhileFailureAndCompletionReleaseTheirSlotsAndKeepResults() {
        var machine = machine(3);
        var host = installedHost(machine, definitions("blocked", "failed", "completed"));
        var blockedResult = new ProcessResult.Blocked("waiting", List.of("input"));
        var failureResult = new ProcessResult.Failure("invalid", List.of("recipe"));
        ProcessResult[] outcome = {blockedResult};
        var blocked = process(machine, recipe("blocked", 2, outcome("blocked", () -> outcome[0])));
        var failed = process(machine, recipe("failed", 2, outcome("failed", () -> failureResult)));
        var completed = process(machine, recipe("completed", 1));
        assertTrue(host.start(blocked));
        assertTrue(host.start(failed));
        assertTrue(host.start(completed));
        machine.onSchedule();
        assertSame(blocked, host.requireSlot("blocked").process());
        assertSame(host, blocked.host());
        assertEquals(WorkSlotStatus.BLOCKED, host.requireSlot("blocked").status());
        assertEquals(blockedResult, host.requireSlot("blocked").result());
        assertEquals(0, blocked.state().successfulTicks());
        assertNull(host.requireSlot("failed").process());
        assertNull(failed.host());
        assertEquals(WorkSlotStatus.FAILED, host.requireSlot("failed").status());
        assertEquals(failureResult, host.requireSlot("failed").result());
        assertNull(host.requireSlot("completed").process());
        assertNull(completed.host());
        assertEquals(WorkSlotStatus.IDLE, host.requireSlot("completed").status());
        assertSame(ProcessResult.SUCCESS, host.requireSlot("completed").result());
        assertEquals(1, completed.state().successfulTicks());
        outcome[0] = ProcessResult.SUCCESS;
        machine.onSchedule();
        assertEquals(1, blocked.state().successfulTicks());
        assertEquals(WorkSlotStatus.RUNNING, host.requireSlot("blocked").status());
        assertEquals(failureResult, host.requireSlot("failed").result());
        machine.onSchedule();
        assertTrue(host.activeProcesses().isEmpty());
        assertNull(blocked.host());
        assertEquals(2, blocked.state().successfulTicks());
    }

    @Test
    void concurrencyLimitOnlyControlsNewAdmissionAndNeverEvictsExistingProcesses() {
        var machine = machine(1);
        var host = installedHost(machine, definitions("first", "second", "third"));
        var recipe = recipe("work", 5);
        var first = process(machine, recipe);
        var second = process(machine, recipe);
        var third = process(machine, recipe);
        assertTrue(host.start(first));
        assertFalse(host.start(second));
        assertNull(second.host());
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        assertTrue(host.start(second));
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 0);
        assertFalse(host.start(third));
        machine.onSchedule();
        assertEquals(List.of(first, second), host.activeProcesses());
        assertEquals(1, first.state().successfulTicks());
        assertEquals(1, second.state().successfulTicks());
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        assertFalse(host.start(third));
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 3);
        assertTrue(host.start(third));
        machine.onSchedule();
        assertEquals(2, first.state().successfulTicks());
        assertEquals(2, second.state().successfulTicks());
        assertEquals(1, third.state().successfulTicks());
    }

    @Test
    void admissionRejectsForeignOwnersIncompatibleGroupsAndDuplicateAttachments() {
        var machine = machine(2);
        var firstDefinition = new WorkSlotDefinition("first", Set.of(GROUP_A));
        var secondDefinition = new WorkSlotDefinition("second", Set.of(GROUP_B));
        var host = new MachineWorkSlots(machine, List.of(firstDefinition, secondDefinition));
        var otherHost = new MachineWorkSlots(machine, List.of());
        var groupA = process(machine, groupedRecipe("a", GROUP_A));
        var groupB = process(machine, groupedRecipe("b", GROUP_B));
        var incompatible = process(machine, recipe("ungrouped", 3));
        assertFalse(host.start(incompatible));
        assertFalse(host.startIn("first", groupB));
        assertNull(incompatible.host());
        assertNull(groupB.host());
        assertThrows(IllegalArgumentException.class, () -> host.start(process(new MachineInstance(), groupedRecipe("foreign", GROUP_A))));
        assertTrue(host.start(groupA));
        assertThrows(IllegalArgumentException.class, () -> host.startIn("second", groupA));
        assertThrows(IllegalArgumentException.class, () -> otherHost.start(groupA));
        assertSame(groupA, host.requireSlot("first").process());
        assertTrue(host.start(groupB));
        assertSame(groupB, host.requireSlot("second").process());
        assertThrows(IllegalArgumentException.class, () -> new MachineWorkSlots(machine, List.of(firstDefinition, firstDefinition)));
        var terminal = process(machine, recipe("terminal", 0));
        terminal.tick();
        assertThrows(IllegalArgumentException.class, () -> otherHost.start(terminal));
        assertSame(host, groupA.host());
        assertSame(host, groupB.host());
    }

    @Test
    void detachTransfersTheSameProcessWithoutEndExecutionOrRefundingCommittedResourceAndEnergy() {
        var machine = machine(1);
        var input = buffer();
        var output = buffer();
        var energy = new EnergyBuffer(100, NO_OP);
        var generated = new EnergyBuffer(100, NO_OP);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(energy), List.of(generated)));
        input.insert(MATERIAL, 20, Actionable.MODULATE);
        energy.receiveEnergy(20, Actionable.MODULATE);
        var resources = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
            stacks(MATERIAL, 2), stacks(MATERIAL, 1), List.of(), stacks(PRODUCT, 5), false));
        var energyRequirement = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(3, 2, 0, 7, false));
        var work = process(machine, recipe("transfer", 3, resources, energyRequirement));
        var firstHost = new MachineWorkSlots(machine, List.of());
        var secondHost = new MachineWorkSlots(machine, List.of());
        assertTrue(firstHost.start(work));
        firstHost.tickProcesses();
        var committed = work.state();
        assertEquals(17, input.amount(MATERIAL));
        assertEquals(15, energy.storedEnergy());
        assertTrue(firstHost.detach(work));
        assertFalse(firstHost.detach(work));
        assertNull(work.host());
        assertTrue(firstHost.activeProcesses().isEmpty());
        assertEquals(committed, work.state());
        assertEquals(17, input.amount(MATERIAL));
        assertEquals(15, energy.storedEnergy());
        assertEquals(0, output.amount(PRODUCT));
        assertEquals(0, generated.storedEnergy());
        assertTrue(secondHost.start(work));
        firstHost.tickProcesses();
        assertEquals(committed, work.state());
        secondHost.tickProcesses();
        assertEquals(2, work.state().successfulTicks());
        assertEquals(16, input.amount(MATERIAL));
        assertEquals(13, energy.storedEnergy());
        secondHost.tickProcesses();
        assertNull(work.host());
        assertTrue(secondHost.activeProcesses().isEmpty());
        assertEquals(15, input.amount(MATERIAL));
        assertEquals(11, energy.storedEnergy());
        assertEquals(5, output.amount(PRODUCT));
        assertEquals(7, generated.storedEnergy());
    }

    @Test
    void processCallbacksCannotReenterHostMutationOrExecution() {
        var machine = machine(2);
        var host = installedHost(machine, definitions("first", "second"));
        var work = process(machine, recipe("work", 3));
        var candidate = process(machine, recipe("candidate", 3));
        assertTrue(host.start(work));
        var saved = host.snapshot();
        int[] calls = {0};
        addPostCallback(work, () -> {
            calls[0]++;
            assertThrows(IllegalStateException.class, () -> host.start(candidate));
            assertThrows(IllegalStateException.class, () -> host.restore(saved));
            assertThrows(IllegalStateException.class, host::tickProcesses);
            assertThrows(IllegalStateException.class, machine::onSchedule);
            assertThrows(IllegalStateException.class, host::snapshot);
            assertThrows(IllegalStateException.class, () -> host.detach(work));
            assertThrows(IllegalStateException.class, () -> work.detachFrom(host));
        });
        machine.onSchedule();
        assertEquals(1, calls[0]);
        assertEquals(1, work.state().successfulTicks());
        assertSame(host, work.host());
        assertNull(candidate.host());
        assertEquals(List.of(work), host.activeProcesses());
        host.snapshot();
    }

    @Test
    void throwingProcessFaultsTheHostAndRejectsFurtherTickOrSaveAfterCommittedProgress() {
        var machine = machine(2);
        var host = installedHost(machine, definitions("first", "second"));
        var first = process(machine, recipe("first", 3));
        var second = process(machine, recipe("second", 3));
        var failure = new IllegalStateException("post tick failed");
        addPostCallback(first, () -> {
            throw failure;
        });
        assertTrue(host.start(first));
        assertTrue(host.start(second));
        var before = host.snapshot();
        assertSame(failure, assertThrows(IllegalStateException.class, machine::onSchedule));
        assertEquals(1, first.state().successfulTicks());
        assertEquals(0, second.state().successfulTicks());
        assertSame(host, first.host());
        assertSame(host, second.host());
        assertThrows(IllegalStateException.class, host::tickProcesses);
        assertThrows(IllegalStateException.class, host::snapshot);
        assertThrows(IllegalStateException.class, () -> host.restore(before));
        assertThrows(IllegalStateException.class, () -> host.detach(first));
        assertThrows(IllegalStateException.class, first::requirePersistenceBoundary);
        assertEquals(1, first.state().successfulTicks());
        assertEquals(0, second.state().successfulTicks());
    }

    @Test
    void invalidFinalRestoreEntryLeavesOldSlotsAndAllAttachmentsIntact() {
        var machine = machine(2);
        var host = installedHost(machine, definitions("first", "second"));
        var recipe = recipe("work", 4);
        var oldFirst = process(machine, recipe);
        var oldSecond = process(machine, recipe);
        assertTrue(host.start(oldFirst));
        assertTrue(host.start(oldSecond));
        machine.onSchedule();
        var previous = host.snapshot();
        var first = process(machine, recipe);
        var second = process(machine, recipe);
        var foreign = process(new MachineInstance(), recipe);
        var otherHost = new MachineWorkSlots(machine, List.of());
        var alreadyAttached = process(machine, recipe);
        assertTrue(otherHost.start(alreadyAttached));
        var firstEntry = new SlotSnapshot(previous.getFirst().definition(), first, ProcessResult.SUCCESS);
        for (var last : List.of(
            new SlotSnapshot(previous.getLast().definition(), second, new ProcessResult.Blocked("inconsistent result")),
            new SlotSnapshot(previous.getLast().definition(), first, ProcessResult.SUCCESS),
            new SlotSnapshot(previous.getLast().definition(), foreign, ProcessResult.SUCCESS),
            new SlotSnapshot(previous.getLast().definition(), alreadyAttached, ProcessResult.SUCCESS))) {
            assertThrows(IllegalArgumentException.class, () -> host.restore(List.of(firstEntry, last)));
            assertEquals(previous, host.snapshot());
            assertSame(host, oldFirst.host());
            assertSame(host, oldSecond.host());
            assertNull(first.host());
            assertNull(second.host());
            assertNull(foreign.host());
            assertSame(otherHost, alreadyAttached.host());
        }
        machine.onSchedule();
        assertEquals(2, oldFirst.state().successfulTicks());
        assertEquals(2, oldSecond.state().successfulTicks());
        assertEquals(0, first.state().successfulTicks());
        assertEquals(0, second.state().successfulTicks());
    }

    @Test
    void restoreRequiresTheActualLayoutPolicyAndGroupsButAllowsCapacityToFall() {
        var machine = machine(2);
        var left = new WorkSlotDefinition("left", Set.of(GROUP_A));
        var right = new WorkSlotDefinition("right", Set.of(GROUP_B), false, WorkSlotRestartPolicy.RESCAN);
        var host = installedHost(machine, List.of(left, right));
        var oldLeft = process(machine, groupedRecipe("old_left", GROUP_A));
        var oldRight = process(machine, groupedRecipe("old_right", GROUP_B));
        assertTrue(host.start(oldLeft));
        assertTrue(host.start(oldRight));
        var previous = host.snapshot();
        var newLeft = process(machine, groupedRecipe("new_left", GROUP_A));
        var newRight = process(machine, groupedRecipe("new_right", GROUP_B));
        var incompatible = process(machine, groupedRecipe("incompatible", GROUP_A));
        var first = new SlotSnapshot(left, newLeft, ProcessResult.SUCCESS);
        for (var changed : List.of(
            new WorkSlotDefinition("renamed", Set.of(GROUP_B), false, WorkSlotRestartPolicy.RESCAN),
            new WorkSlotDefinition("right", Set.of(GROUP_A), false, WorkSlotRestartPolicy.RESCAN),
            new WorkSlotDefinition("right", Set.of(GROUP_B), true, WorkSlotRestartPolicy.RESCAN),
            new WorkSlotDefinition("right", Set.of(GROUP_B), false, WorkSlotRestartPolicy.LOCKED_RETRY))) {
            assertThrows(IllegalArgumentException.class, () -> host.restore(List.of(first, new SlotSnapshot(changed, newRight, ProcessResult.SUCCESS))));
            assertEquals(previous, host.snapshot());
            assertSame(host, oldLeft.host());
            assertSame(host, oldRight.host());
            assertNull(newLeft.host());
            assertNull(newRight.host());
        }
        assertThrows(IllegalArgumentException.class, () -> host.restore(List.of(first)));
        assertThrows(IllegalArgumentException.class, () -> host.restore(List.of(first, new SlotSnapshot(right, incompatible, ProcessResult.SUCCESS))));
        assertEquals(previous, host.snapshot());
        assertNull(incompatible.host());
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 0);
        host.restore(List.of(first, new SlotSnapshot(right, newRight, ProcessResult.SUCCESS)));
        assertNull(oldLeft.host());
        assertNull(oldRight.host());
        assertSame(host, newLeft.host());
        assertSame(host, newRight.host());
        assertEquals(List.of(newLeft, newRight), host.activeProcesses());
        assertFalse(host.start(process(machine, groupedRecipe("extra", GROUP_A))));
        machine.onSchedule();
        assertEquals(1, newLeft.state().successfulTicks());
        assertEquals(1, newRight.state().successfulTicks());
    }

    private record Outcome(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }
}
