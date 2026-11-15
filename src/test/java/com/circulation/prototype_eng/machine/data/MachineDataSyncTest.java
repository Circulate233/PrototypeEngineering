package com.circulation.prototype_eng.machine.data;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.machine.render.ControllerRenderChunks;
import com.circulation.prototype_eng.machine.render.ControllerRenderSync;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineDataSyncTest {
    private static final ResourceLocation TYPE = new ResourceLocation("prototype_eng_test", "data");

    private static MachineWorkSlots host(MachineInstance machine, int count) {
        var slots = new ObjectArrayList<WorkSlotDefinition>();
        for (int i = 0; i < count; i++) slots.add(new WorkSlotDefinition("slot" + i, Set.of()));
        return machine.createRecipeHost(slots, Set.of());
    }

    private static RecipeProcess process(MachineInstance machine) {
        var process = new RecipeProcess(machine, new MachineRecipe(TYPE, 1, List.of()), 1);
        var data = new ComponentData();
        data.set("value", new NBTTagString("same recipe/seed/data"));
        process.components().put(DataComponents.PROCESS, data);
        return process;
    }

    @Test
    void fullRosterKeepsTransferredIdentityAndRevokesSameSlotReplacementAndTerminalData() {
        var machine = new MachineInstance();
        var machineData = new ComponentData();
        machineData.set("nested", new NBTTagCompound());
        machine.components().put(DataComponents.MACHINE, machineData);
        var first = host(machine, 300);
        var second = host(machine, 1);
        var process = process(machine);
        assertTrue(first.start(process));
        var observer = new MachineDataSnapshots();
        var before = observer.observe(machine, 1);
        assertEquals(300, before.hosts().getFirst().slots().size());
        assertEquals(2, before.hosts().size());
        long identity = Objects.requireNonNull(before.hosts().getFirst().slots().getFirst().process()).identity();
        assertSame(before, observer.observe(machine, 1));
        machineData.set("dirty", new NBTTagString("changed"));
        var dirty = observer.observe(machine, 1);
        assertTrue(dirty.revision() > before.revision());
        assertSame(Objects.requireNonNull(before.hosts().getFirst().slots().getFirst().process()).data(),
            Objects.requireNonNull(dirty.hosts().getFirst().slots().getFirst().process()).data());
        assertTrue(first.detach(process));
        assertTrue(second.start(process));
        var transferred = observer.observe(machine, 1);
        assertNull(transferred.hosts().getFirst().slots().getFirst().process());
        assertEquals(identity, Objects.requireNonNull(transferred.hosts().getLast().slots().getFirst().process()).identity());
        assertTrue(second.detach(process));
        var replacement = process(machine);
        assertTrue(second.start(replacement));
        var replaced = observer.observe(machine, 1);
        assertNotEquals(identity, Objects.requireNonNull(replaced.hosts().getLast().slots().getFirst().process()).identity());
        second.tickProcesses();
        var terminal = observer.observe(machine, 1);
        assertNull(terminal.hosts().getLast().slots().getFirst().process());
        assertEquals(terminal, MachineDataSnapshotCodec.decode(MachineDataSnapshotCodec.encode(terminal)));
        var machineFact = Objects.requireNonNull(terminal.machine());
        var changedNested = new NBTTagCompound();
        changedNested.setString("foreign", "mutation");
        machineData.set("nested", changedNested);
        assertFalse(machineFact.data().getCompoundTag("nested").hasKey("foreign"));
        assertTrue(machineData.snapshot().data().getCompoundTag("nested").hasKey("foreign"));
        assertNotNull(before.hosts().getFirst().slots().getFirst().process());
        var nextData = new ComponentData();
        nextData.set("replacement", new NBTTagInt(9));
        machine.components().put(DataComponents.MACHINE, nextData);
        assertEquals(9, Objects.requireNonNull(observer.observe(machine, 1).machine()).data().getInteger("replacement"));
        machine.components().remove(DataComponents.MACHINE);
        assertNull(observer.observe(machine, 1).machine());
        observer.clear();
        assertTrue(observer.observe(null, 2).hosts().isEmpty());
    }

    @Test
    void nativeDataChunksRequireFullConfirmationAndFenceConflictsWithdrawalAndRetirement() throws Exception {
        var machine = new MachineInstance();
        var data = new ComponentData();
        data.set("large", new NBTTagString("x".repeat(50000)));
        machine.components().put(DataComponents.MACHINE, data);
        var observer = new MachineDataSnapshots();
        var snapshot = observer.observe(machine, 1);
        var display = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 1, 1);
        var session = UUID.randomUUID();
        var parts = ControllerRenderChunks.split(snapshot, display, session);
        assertTrue(parts.size() > 1);
        var assembler = new ControllerRenderChunks.Assembler();
        for (int i = parts.size() - 1; i >= 0; i--) {
            var part = parts.get(i);
            var tag = new NBTTagCompound();
            tag.setTag("Display", display.encode());
            tag.setTag("Data", ControllerRenderSync.dataTag(part));
            var packet = new SPacketUpdateTileEntity(BlockPos.ORIGIN, 0, tag);
            var buffer = new PacketBuffer(Unpooled.buffer());
            try {
                packet.writePacketData(buffer);
                var decoded = new SPacketUpdateTileEntity();
                decoded.readPacketData(buffer);
                var ordinary = decoded.getNbtCompound().getCompoundTag("Data");
                var received = ControllerRenderChunks.Part.decode(ControllerDisplayData.decode(ordinary.getCompoundTag("Display")), ordinary.getCompoundTag("Payload"));
                var complete = assembler.receiveData(received);
                if (i > 0) {
                    assertNull(complete);
                    assertNull(assembler.receiveData(received));
                } else assertEquals(snapshot, complete);
            } finally {
                buffer.release();
            }
        }
        assertTrue(assembler.current(display, parts.getFirst().catalog()));
        var nextDisplay = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 1, 2);
        var next = ControllerRenderChunks.split(snapshot, nextDisplay, session);
        assertNull(assembler.receiveData(next.getLast()));
        assertFalse(assembler.current(display, parts.getFirst().catalog()));
        assertNull(assembler.receiveData(parts.getFirst()));
        var conflictTag = next.getLast().encode().copy();
        var corrupt = conflictTag.getByteArray("Bytes");
        corrupt[corrupt.length - 1] ^= 1;
        var conflict = ControllerRenderChunks.Part.decode(nextDisplay, conflictTag);
        assertThrows(IllegalArgumentException.class, () -> assembler.receiveData(conflict));
        for (var part : next) assertNull(assembler.receiveData(part));
        var withdrawn = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 1, 3);
        assertTrue(assembler.withdraw(withdrawn));
        for (var part : parts) assertNull(assembler.receiveData(part));
        assembler.retire();
        var rejoined = new ControllerRenderChunks.Assembler();
        assertTrue(rejoined.withdraw(withdrawn));
        for (var part : parts) assertNull(rejoined.receiveData(part));
        var replayDisplay = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 1, 4);
        MachineDataSnapshot replayed = null;
        for (var part : parts) {
            var replayTag = ControllerRenderSync.dataTag(part, replayDisplay);
            var replayPart = ControllerRenderChunks.Part.decode(ControllerDisplayData.decode(replayTag.getCompoundTag("Display")), replayTag.getCompoundTag("Payload"));
            replayed = rejoined.receiveData(replayPart);
        }
        assertEquals(snapshot, replayed);
        // Canonical render refreshes still carry the old data head; they cannot revoke this complete replay.
        assertNull(rejoined.receiveData(parts.getFirst()));
        rejoined.observeGeneration(1);
        assertTrue(rejoined.current(replayDisplay, parts.getFirst().catalog()));
        // A new native attachment is seen before any of its Data parts, independent of Render readiness.
        rejoined.observeGeneration(2);
        assertFalse(rejoined.current(replayDisplay, parts.getFirst().catalog()));
        for (var part : parts) assertNull(rejoined.receiveData(part));
        var successor = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 2, 0);
        var replacement = observer.observe(null, 2);
        var successorSession = UUID.randomUUID();
        var replacementParts = ControllerRenderChunks.split(replacement, successor, successorSession);
        assertEquals(replacement, rejoined.receiveData(replacementParts.getFirst()));
        var foreign = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, false, 2, 1);
        assertNull(rejoined.receiveData(ControllerRenderChunks.split(replacement, foreign, UUID.randomUUID()).getFirst()));
        assertTrue(rejoined.current(successor, replacementParts.getFirst().catalog()));
        rejoined.retire();
        assertNull(rejoined.receiveData(replacementParts.getFirst()));
    }
}
