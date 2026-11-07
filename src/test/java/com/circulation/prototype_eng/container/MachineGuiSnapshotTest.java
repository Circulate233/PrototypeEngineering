package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineGuiSnapshotTest {
    @Test void pageCrossesHostsAndClampsAfterLayoutChangesWithoutRetainingLiveProgress() {
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

    @Test void lastFailureSurvivesDetachmentAndPacketRoundTrip() {
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
        } finally { buffer.release(); }
    }

    @Test void packetBoundsApplyBeforeAllocatingRowsAndArguments() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeBoolean(true).writeBoolean(true).writeInt(20).writeInt(0).writeByte(255);
            assertThrows(IllegalArgumentException.class, () -> new MachineGuiSnapshot(buffer));
        } finally { buffer.release(); }
        var arguments = new ObjectArrayList<String>();
        for (int i = 0; i < 20; i++) { arguments.add("a".repeat(1500)); }
        var entry = new MachineGuiSnapshot.Entry("slot", "recipe", WorkSlotStatus.BLOCKED, 30, "reason", arguments);
        arguments.clear();
        assertEquals(16, entry.arguments().size());
        assertEquals(1024, entry.arguments().getFirst().length());
        assertThrows(UnsupportedOperationException.class, () -> entry.arguments().clear());
    }

    private static MachineWorkSlots install(MachineInstance machine, String name, int count) {
        var definitions = new ObjectArrayList<WorkSlotDefinition>();
        for (int i = 0; i < count; i++) { definitions.add(new WorkSlotDefinition(name + i, Set.of())); }
        var host = new MachineWorkSlots(machine, definitions);
        machine.components().put(type(name), host);
        return host;
    }

    private static ComponentType<MachineInstance, MachineWorkSlots> type(String name) {
        return new ComponentType<>(id(name), owner -> new MachineWorkSlots(owner, List.of()), List.of());
    }
    private static ResourceLocation id(String name) { return new ResourceLocation("prototype_eng_test", name); }
}
