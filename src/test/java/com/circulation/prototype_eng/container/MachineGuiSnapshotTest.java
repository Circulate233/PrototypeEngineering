package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineGuiSnapshotTest {
    private static MachineWorkSlots install(MachineInstance machine, String name, int count) {
        var definitions = new ObjectArrayList<WorkSlotDefinition>();
        for (int i = 0; i < count; i++) {
            definitions.add(new WorkSlotDefinition(name + i, Set.of()));
        }
        var host = new MachineWorkSlots(machine, definitions);
        machine.components().put(type(name), host);
        return host;
    }

    private static ComponentType<MachineInstance, MachineWorkSlots> type(String name) {
        return new ComponentType<>(id(name), owner -> new MachineWorkSlots(owner, List.of()), List.of());
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("prototype_eng_test", name);
    }

    @Test
    void pageCrossesHostsAndClampsAfterLayoutChangesWithoutRetainingLiveProgress() {
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 10);
        var first = install(machine, "first", 3);
        var second = install(machine, "second", 7);
        var process = new RecipeProcess(machine, new MachineRecipe(id("work"), 4, List.of()), 1);
        assertTrue(second.start(process));
        second.tickProcesses();
        var page = MachineGuiSnapshot.capture(machine, 2, 4);
        assertEquals(List.of("first2", "second0", "second1", "second2"), page.entries().stream().map(MachineGuiSnapshot.Entry::name).toList());
        assertEquals(25, page.entries().get(1).percent());
        assertEquals(1, page.details().running());
        assertEquals(10, page.details().threadLimit());
        second.tickProcesses();
        assertEquals(25, page.entries().get(1).percent());
        assertEquals(50, MachineGuiSnapshot.capture(machine, 2, 4).entries().get(1).percent());
        assertEquals(6, MachineGuiSnapshot.capture(machine, Integer.MAX_VALUE, 4).offset());
        var secondType = machine.components().orderedBindings().stream()
                                .filter(binding -> binding.component() == second).findFirst().orElseThrow().type();
        machine.components().remove(secondType);
        var shrunk = MachineGuiSnapshot.capture(machine, 6, 4);
        assertEquals(0, shrunk.offset());
        assertEquals(first.slots().size(), shrunk.entries().size());
    }

    @Test
    void crossRecipePageContainsOnlyActualRecipesWithTheirEffectiveQuantitiesAndRoundTrips() {
        var machine = new CrossRecipeController();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 20);
        var host = MachineWorkSlots.crossRecipe(machine, List.of(new WorkSlotDefinition("unused", Set.of())), Set.of());
        machine.components().put(type("cross"), host);
        var first = new RecipeProcess(machine, new MachineRecipe(id("first"), 100, List.of()), 1);
        first.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 3);
        var second = new RecipeProcess(machine, new MachineRecipe(id("second"), 100, List.of()), 2);
        second.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 5);
        assertTrue(host.start(first));
        var runtime = Objects.requireNonNull(host.appendRuntimeSlot(second.recipe()));
        assertTrue(host.startIn(runtime.name(), second));
        host.appendRuntimeSlot(second.recipe());
        var page = MachineGuiSnapshot.capture(machine, 0, 7);
        assertEquals(2, page.totalSlots());
        assertEquals(List.of(id("first").toString(), id("second").toString()), page.entries().stream().map(MachineGuiSnapshot.Entry::recipe).toList());
        assertEquals(8, page.details().usedParallelism());
        assertEquals(3, page.entries().getFirst().parallelism());
        assertTrue(page.details().crossRecipe());
        var packet = Unpooled.buffer();
        try {
            page.writeToPacket(packet);
            assertEquals(page, new MachineGuiSnapshot(packet));
            assertEquals(0, packet.readableBytes());
        } finally {
            packet.release();
        }
        assertTrue(host.cancel(first));
        var shrunk = MachineGuiSnapshot.capture(machine, 50, 7);
        assertEquals(1, shrunk.totalSlots());
        assertEquals(0, shrunk.offset());
        assertEquals(id("second").toString(), shrunk.entries().getFirst().recipe());
    }

    @Test
    void lastFailureSurvivesDetachmentAndPacketRoundTrip() {
        var machine = new MachineInstance();
        var host = install(machine, "main", 1);
        var process = new RecipeProcess(machine, new MachineRecipe(id("work"), 4, List.of()), 1);
        assertTrue(host.start(process));
        assertTrue(host.cancel(process, new ProcessResult.Failure("test.reason", List.of("missing input"))));
        var snapshot = MachineGuiSnapshot.capture(machine, 0, 7);
        assertEquals(WorkSlotStatus.FAILED, snapshot.entries().getFirst().status());
        assertEquals("test.reason", snapshot.entries().getFirst().reason());
        var buffer = Unpooled.buffer();
        try {
            snapshot.writeToPacket(buffer);
            assertEquals(snapshot, new MachineGuiSnapshot(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void overallStatusIncludesProcessesOutsideTheVisiblePage() {
        var machine = new MachineInstance();
        install(machine, "before", 8);
        var host = install(machine, "after", 1);
        var process = new RecipeProcess(machine, new MachineRecipe(id("offscreen"), 10, List.of()), 1);
        assertTrue(host.start(process));
        var snapshot = MachineGuiSnapshot.capture(machine, 0, 7);
        assertTrue(snapshot.entries().stream().allMatch(entry -> entry.status() == WorkSlotStatus.IDLE));
        assertEquals(WorkSlotStatus.RUNNING, snapshot.overallStatus());
        assertTrue(host.cancel(process, new ProcessResult.Failure("test.failure", List.of())));
        assertEquals(WorkSlotStatus.FAILED, MachineGuiSnapshot.capture(machine, 0, 7).overallStatus());
    }

    @Test
    void packetBoundsApplyBeforeAllocatingRowsAndArguments() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeBoolean(true).writeBoolean(true).writeInt(20).writeInt(0).writeByte(255);
            assertThrows(IllegalArgumentException.class, () -> new MachineGuiSnapshot(buffer));
        } finally {
            buffer.release();
        }
        var arguments = new ObjectArrayList<String>();
        for (int i = 0; i < 20; i++) {
            arguments.add("a".repeat(1500));
        }
        var entry = new MachineGuiSnapshot.Entry("slot", "recipe", WorkSlotStatus.BLOCKED, 30, "reason", arguments);
        arguments.clear();
        assertEquals(16, entry.arguments().size());
        assertEquals(1024, entry.arguments().getFirst().length());
        assertThrows(UnsupportedOperationException.class, () -> entry.arguments().clear());
    }
}
