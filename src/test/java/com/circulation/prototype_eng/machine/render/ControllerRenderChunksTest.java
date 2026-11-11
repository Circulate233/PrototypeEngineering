package com.circulation.prototype_eng.machine.render;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import it.unimi.dsi.fastutil.ints.IntList;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.client.render.ClientMachineRenderSnapshots;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("resource") // Logical registrations stay in this local registry through each scenario.
class ControllerRenderChunksTest {
    static MachineRenderSnapshot snapshot(long generation, long sequence, long catalog, int count) {
        var positions = new ObjectArrayList<BlockPos>(count);
        for (int i = 0; i < count; i++) positions.add(new BlockPos(i * 3, i % 40, -i));
        var root = new MachineRenderSnapshot.Occurrence(-1, -1, MachineRenderSnapshotTest.id("root"), BlockPos.ORIGIN, 1,
            StructurePreviewProvider.Kind.OPAQUE, BlockPos.ORIGIN, true, positions);
        return new MachineRenderSnapshot(new ControllerDisplayData(MachineRenderSnapshotTest.MACHINE, BlockOrientation.NORTH_UP, true, generation, sequence),
            new MachineRenderSnapshot.CatalogToken(MachineRenderSnapshotTest.SESSION, catalog), BlockPos.ORIGIN,
            root.structure(), List.of(root), positions, List.of(), null);
    }

    static void installCatalog(ClientStructureCatalog client, Object connection, long sequence) {
        var root = new StructureCatalogView.Node(MachineRenderSnapshotTest.id("root"), BlockOrientation.NORTH_UP, true, StructurePreviewProvider.Kind.OPAQUE, BlockPos.ORIGIN, 0, 0, BlockPos.ORIGIN, List.of(), IntList.of());
        var view = new StructureCatalogView(MachineRenderSnapshotTest.SESSION, sequence, List.of(root),
            Map.of(root.id(), new StructureCatalogView.Entry(0, null, Map.of())));
        byte[] bytes = StructureCatalogCodec.encode(view);
        assertTrue(client.receive(connection, new StructureCatalogPacket(view.session(), view.sequence(), bytes.length, 0, bytes)));
    }

    @Test
    void largePayloadUsesNativeTePacketsAndOutOfOrderDuplicatesDoNotPublishPartialData() throws Exception {
        var snapshot = snapshot(1, 1, 0, 12000);
        var parts = ControllerRenderChunks.split(snapshot);
        assertTrue(parts.size() > 1);
        var assembler = new ControllerRenderChunks.Assembler();
        for (int i = parts.size() - 1; i >= 0; i--) {
            var part = parts.get(i);
            assertTrue(part.bytes().length <= 16384);
            var tag = new NBTTagCompound();
            tag.setTag("Display", part.display().encode());
            tag.setTag("Render", part.encode());
            var packet = new SPacketUpdateTileEntity(BlockPos.ORIGIN, 0, tag);
            var buffer = new PacketBuffer(Unpooled.buffer());
            try {
                packet.writePacketData(buffer);
                var restored = new SPacketUpdateTileEntity();
                restored.readPacketData(buffer);
                var nbt = restored.getNbtCompound();
                var received = ControllerRenderChunks.Part.decode(ControllerDisplayData.decode(nbt.getCompoundTag("Display")), nbt.getCompoundTag("Render"));
                var complete = assembler.receive(received);
                if (i > 0) {
                    assertNull(complete);
                    assertNull(assembler.receive(received));
                } else assertEquals(snapshot, complete);
            } finally {
                buffer.release();
            }
        }
        assertNull(assembler.receive(parts.getFirst()));
        assertTrue(assembler.current(snapshot));
    }

    @Test
    void conflictPoisonsOnlyItsSequenceAndNewerGenerationReplacesPending() {
        var initial = snapshot(1, 1, 0, 9000);
        var parts = ControllerRenderChunks.split(initial);
        var assembler = new ControllerRenderChunks.Assembler();
        assertNull(assembler.receive(parts.getFirst()));
        var bytes = parts.getFirst().bytes();
        bytes[bytes.length - 1] ^= 1;
        var conflicting = new ControllerRenderChunks.Part(initial.display(), initial.catalog(), parts.getFirst().totalBytes(), 0, bytes);
        assertThrows(IllegalArgumentException.class, () -> assembler.receive(conflicting));
        for (var part : parts) assertNull(assembler.receive(part));
        var next = snapshot(2, 0, 0, 9000);
        MachineRenderSnapshot complete = null;
        for (var part : ControllerRenderChunks.split(next)) complete = assembler.receive(part);
        assertEquals(next, complete);
        for (var part : parts) assertNull(assembler.receive(part));
        var foreign = new ControllerRenderChunks.Part(next.display(), new MachineRenderSnapshot.CatalogToken(UUID.randomUUID(), 0),
            parts.getFirst().totalBytes(), 0, parts.getFirst().bytes());
        assertNull(assembler.receive(foreign));
        assertTrue(assembler.current(next));
    }

    @Test
    void metadataWithdrawalFencesLatePayloadAndRejectsInvalidPartBounds() {
        var initial = snapshot(1, 1, 0, 9000);
        var parts = ControllerRenderChunks.split(initial);
        var assembler = new ControllerRenderChunks.Assembler();
        assembler.receive(parts.getFirst());
        var withdrawn = new ControllerDisplayData(initial.display().type(), initial.display().orientation(), false, 1, 2);
        assertTrue(assembler.withdraw(withdrawn));
        for (var part : parts) assertNull(assembler.receive(part));
        var next = snapshot(1, 3, 0, 1);
        assertEquals(next, assembler.receive(ControllerRenderChunks.split(next).getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new ControllerRenderChunks.Part(next.display(), next.catalog(), 16385, 0, new byte[1]));
        var tag = ControllerRenderChunks.split(next).getFirst().encode();
        tag.setInteger("Count", 99);
        assertThrows(IllegalArgumentException.class, () -> ControllerRenderChunks.Part.decode(next.display(), tag));
    }

    @Test
    void catalogMayArriveAfterCompletePayloadAndPartialReplacementRevokesRightsImmediately() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var installed = new ObjectArrayList<MachineRenderSnapshot>();
            var owner = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, installed::add);
            var first = snapshot(1, 1, 0, 1);
            client.receive(owner, ControllerRenderChunks.split(first).getFirst());
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            assertTrue(installed.isEmpty());
            installCatalog(catalog, connection, 0);
            var before = client.view(world, tile, BlockPos.ORIGIN);
            assertNotNull(before);
            assertEquals(List.of(first), installed);
            var second = snapshot(1, 2, 0, 10000);
            var parts = ControllerRenderChunks.split(second);
            client.receive(owner, parts.getLast());
            assertFalse(client.current(before));
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            assertEquals(1, installed.size());
            client.receive(owner, ControllerRenderChunks.split(first).getFirst());
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            for (int i = 0; i < parts.size() - 1; i++) client.receive(owner, parts.get(i));
            assertEquals(List.of(first, second), installed);
            assertEquals(second, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
        }
    }

    @Test
    void oldTeCloseConnectionAndResourceTokensCannotRevokeOrPublishForSuccessor() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var oldTile = new Object();
            var newTile = new Object();
            var old = client.attach(world, oldTile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, _ -> fail("Old owner cannot publish"));
            var installed = new ObjectArrayList<MachineRenderSnapshot>();
            var next = client.attach(world, newTile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, installed::add);
            old.close();
            client.receive(old, ControllerRenderChunks.split(snapshot(1, 1, 0, 1)).getFirst());
            client.receive(next, ControllerRenderChunks.split(snapshot(2, 1, 0, 1)).getFirst());
            var before = client.view(world, newTile, BlockPos.ORIGIN);
            assertNotNull(before);
            client.invalidateResources();
            assertFalse(client.current(before));
            assertNotNull(client.view(world, newTile, BlockPos.ORIGIN));
            catalog.connected(new Object());
            assertNull(client.view(world, newTile, BlockPos.ORIGIN));
            client.receive(next, ControllerRenderChunks.split(snapshot(2, 2, 0, 1)).getFirst());
            assertEquals(1, installed.size());
            assertThrows(IllegalStateException.class, () -> client.attach(world, newTile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, installed::add));
        }
    }

    @Test
    void withdrawalAndCatalogReplacementDoNotReviveOldFactsAfterResourceChanges() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var installed = new ObjectArrayList<MachineRenderSnapshot>();
            var owner = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, installed::add);
            var first = snapshot(1, 1, 0, 1);
            client.receive(owner, ControllerRenderChunks.split(first).getFirst());
            var token = client.view(world, tile, BlockPos.ORIGIN);
            client.withdraw(owner, new ControllerDisplayData(first.display().type(), first.display().orientation(), false, 1, 2));
            client.invalidateResources();
            assertFalse(client.current(token));
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            var future = snapshot(1, 3, 1, 1);
            client.receive(owner, ControllerRenderChunks.split(future).getFirst());
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            installCatalog(catalog, connection, 1);
            assertEquals(future, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
            assertEquals(List.of(first, future), installed);
            client.unloadWorld(world);
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
        }
    }

    @Test
    void poisonedCompleteHeaderCannotRegainRightsFromBindingOrResourceInvalidation() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var owner = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, _ -> {
            });
            client.receive(owner, ControllerRenderChunks.split(snapshot(1, 1, 0, 1)).getFirst());
            var before = client.view(world, tile, BlockPos.ORIGIN);
            assertNotNull(before);
            var conflict = ControllerRenderChunks.split(snapshot(1, 1, 0, 2)).getFirst();
            assertThrows(IllegalArgumentException.class, () -> client.receive(owner, conflict));
            client.invalidateResources();
            assertFalse(client.current(before));
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
        }
    }

    @Test
    void nativeEncodedTagCannotMutateFrozenPartOrAnotherEncoding() {
        var original = ControllerRenderChunks.split(snapshot(1, 1, 0, 1)).getFirst();
        var input = original.bytes();
        var expected = input.clone();
        var part = new ControllerRenderChunks.Part(original.display(), original.catalog(), original.totalBytes(), original.index(), input);
        input[0] ^= 1;
        var exposed = part.bytes();
        exposed[1] ^= 1;
        var firstTag = part.encode();
        var secondTag = part.encode();
        firstTag.getByteArray("Bytes")[2] ^= 1;
        assertArrayEquals(expected, part.bytes());
        assertArrayEquals(expected, secondTag.getByteArray("Bytes"));
        assertArrayEquals(expected, part.encode().getByteArray("Bytes"));
    }

    @Test
    void invalidationCallbackCompletingNewerSequenceCannotBeOverwrittenByOuterWaiting() throws Exception {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var installed = new ObjectArrayList<MachineRenderSnapshot>();
            var owner = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, installed::add);
            var older = snapshot(1, 1, 0, 1);
            var newer = snapshot(1, 2, 0, 2);
            var once = new boolean[]{true};
            try (@SuppressWarnings("unused") var listener = client.onInvalidated(() -> {
                if (!once[0]) return;
                once[0] = false;
                client.receive(owner, ControllerRenderChunks.split(newer).getFirst());
            })) {
                client.receive(owner, ControllerRenderChunks.split(older).getFirst());
                assertEquals(List.of(newer), installed);
                assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
                client.invalidateResources();
                assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
                bindings.bindMachine(MachineRenderSnapshotTest.MACHINE, MachineRenderBindingsTest.model("reentered"));
                assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
                assertEquals(List.of(newer), installed);
            }
        }
    }

    @Test
    void failedInstallerNeverBecomesFactOnResourceOrBindingInvalidation() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var calls = new int[1];
            var failure = new IllegalStateException("Installer rejected complete state");
            var owner = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, value -> {
                calls[0]++;
                if (value.display().sequence() == 2) throw failure;
            });
            client.receive(owner, ControllerRenderChunks.split(snapshot(1, 1, 0, 1)).getFirst());
            var previous = client.view(world, tile, BlockPos.ORIGIN);
            assertNotNull(previous);
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> client.receive(owner, ControllerRenderChunks.split(snapshot(1, 2, 0, 2)).getFirst())));
            assertFalse(client.current(previous));
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            client.invalidateResources();
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            bindings.bindMachine(MachineRenderSnapshotTest.MACHINE, MachineRenderBindingsTest.model("failed"));
            assertNull(client.view(world, tile, BlockPos.ORIGIN));
            assertEquals(2, calls[0]);
            var next = snapshot(1, 3, 0, 3);
            client.receive(owner, ControllerRenderChunks.split(next).getFirst());
            assertEquals(next, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
            assertEquals(3, calls[0]);
        }
    }

    @Test
    void installerReenteringNewerCompletionCannotHaveItsFactReplacedByOuterInstall() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        installCatalog(catalog, connection, 0);
        var bindings = new MachineRenderBindings();
        try (var client = new ClientMachineRenderSnapshots(catalog, bindings)) {
            var world = new Object();
            var tile = new Object();
            var installed = new LongArrayList();
            var ownerSlot = new ClientMachineRenderSnapshots.Owner[1];
            var newer = snapshot(1, 2, 0, 2);
            ownerSlot[0] = client.attach(world, tile, connection, BlockPos.ORIGIN, MachineRenderSnapshotTest.MACHINE, () -> true, value -> {
                installed.add(value.display().sequence());
                if (value.display().sequence() == 1)
                    client.receive(ownerSlot[0], ControllerRenderChunks.split(newer).getFirst());
            });
            client.receive(ownerSlot[0], ControllerRenderChunks.split(snapshot(1, 1, 0, 1)).getFirst());
            assertEquals(List.of(1L, 2L), installed);
            assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
            client.invalidateResources();
            assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
            bindings.bindMachine(MachineRenderSnapshotTest.MACHINE, MachineRenderBindingsTest.model("installer_reentry"));
            assertEquals(newer, Objects.requireNonNull(client.view(world, tile, BlockPos.ORIGIN)).snapshot());
            assertEquals(2, installed.size());
        }
    }
}
