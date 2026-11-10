package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewCache;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.structure.SliceStructure;
import com.circulation.prototype_eng.structure.TemplateStructure;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureCatalogSyncTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("sync_test", name);
    }

    private static StructureCatalog catalog() {
        var child = new SliceStructure(id("child"), BlockOrientation.NORTH_UP, new BlockPos(1, 0, 0), true,
            new StructurePattern(Map.of(new BlockPos(2, 0, 0), StructurePredicates.all(List.of()))), 0, 5, new BlockPos(0, 1, 0), List.of(), List.of());
        var parent = new TemplateStructure(id("parent"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of()), List.of(), List.of(child, child));
        var display = new StructureDisplay("bom/key", List.of(new StructureDisplay.BlockOption(id("block"), 3, Map.of("amount", "7"))), "{amount:7}");
        return new StructureCatalog(List.of(new StructureDefinition(parent, "addon.structure.parent", Map.of()),
            new StructureDefinition(child, "addon.structure.child", Map.of(new BlockPos(2, 0, 0), display))));
    }

    private static StructureReloadCoordinator.Publication publication(UUID session, long sequence) {
        var view = StructureCatalogView.capture(catalog(), session, sequence);
        return new StructureReloadCoordinator.Publication(view, StructureCatalogCodec.encode(view));
    }

    private static boolean deliver(ClientStructureCatalog directory, Object connection, StructureReloadCoordinator.Publication publication) {
        byte[] payload = publication.bytes();
        boolean changed = false;
        int chunks = (payload.length + StructureCatalogPacket.CHUNK_BYTES - 1) / StructureCatalogPacket.CHUNK_BYTES;
        for (int i = 0; i < chunks; i++)
            changed |= directory.receive(connection, StructureCatalogPacket.part(publication, payload, i));
        return changed;
    }

    @Test
    void resolvedServerHierarchyAndPresentationRoundTripWithoutAnyBlockOrMatcherResolution() {
        var original = publication(UUID.randomUUID(), 2);
        var view = StructureCatalogCodec.decode(original.bytes());
        assertEquals(original.view().session(), view.session());
        assertEquals(2, view.sequence());
        assertEquals(2, view.nodes().size());
        var parent = view.nodes().get(view.entries().get(id("parent")).node());
        assertEquals(parent.children().get(0), parent.children().get(1));
        var child = view.nodes().get(parent.children().getFirst());
        assertEquals(StructurePreviewProvider.Kind.SLICE, child.kind());
        assertEquals(5, child.maxCount());
        assertEquals(new BlockPos(0, 1, 0), child.sliceOffset());
        assertTrue(child.hideWorldBlocks());
        var display = view.entries().get(id("child")).displays().get(new BlockPos(2, 0, 0));
        assertEquals("bom/key", display.key());
        assertEquals("7", display.options().getFirst().nbt().get("amount"));
        assertEquals("addon.structure.parent", view.entries().get(id("parent")).translationKey());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
    }

    @Test
    void completeGenerationWaitsForAllOutOfOrderPartsAndThenInvalidatesExistingPreviewCache() {
        var directory = new ClientStructureCatalog();
        var connection = new Object();
        directory.connected(connection);
        var session = UUID.randomUUID();
        deliver(directory, connection, publication(session, 0));
        var old = directory.view();
        var builds = new AtomicInteger();
        try (var cache = new StructurePreviewCache<String>(directory)) {
            String before = cache.get(id("parent"), "north", (_, _) -> "preview" + builds.incrementAndGet());
            assertSame(before, cache.get(id("parent"), "north", (_, _) -> fail("Must reuse current cache")));
            var base = publication(session, 1);
            // A valid large translation key produces multiple actual network chunks.
            var entries = new LinkedHashMap<>(base.view().entries());
            entries.compute(id("parent"), (_, entry) -> new StructureCatalogView.Entry(Objects.requireNonNull(entry).node(), "x".repeat(40000), entry.displays()));
            var view = new StructureCatalogView(session, 1, base.view().nodes(), entries);
            var next = new StructureReloadCoordinator.Publication(view, StructureCatalogCodec.encode(view));
            byte[] bytes = next.bytes();
            int chunks = (bytes.length + StructureCatalogPacket.CHUNK_BYTES - 1) / StructureCatalogPacket.CHUNK_BYTES;
            assertTrue(chunks > 1);
            for (int i = chunks - 1; i > 0; i--) {
                assertFalse(directory.receive(connection, StructureCatalogPacket.part(next, bytes, i)));
                assertSame(old, directory.view());
                assertSame(before, cache.get(id("parent"), "north", (_, _) -> fail("Partial state cannot clear cache")));
            }
            assertTrue(directory.receive(connection, StructureCatalogPacket.part(next, bytes, 0)));
            assertEquals(1, Objects.requireNonNull(directory.view()).sequence());
            assertNotSame(old, directory.view());
            assertNotEquals(before, cache.get(id("parent"), "north", (_, _) -> "preview" + builds.incrementAndGet()));
        }
    }

    @Test
    void oldSequenceWrongServerSessionAndDisconnectedLatePacketsCannotReplaceANewConnection() {
        var directory = new ClientStructureCatalog();
        var first = new Object();
        var second = new Object();
        var firstSession = UUID.randomUUID();
        var secondSession = UUID.randomUUID();
        directory.connected(first);
        deliver(directory, first, publication(firstSession, 2));
        var old = directory.view();
        assertFalse(deliver(directory, first, publication(firstSession, 1)));
        assertSame(old, directory.view());
        assertFalse(deliver(directory, first, publication(secondSession, 3)));
        assertSame(old, directory.view());
        directory.disconnected(first);
        assertNull(directory.view());
        directory.connected(second);
        deliver(directory, second, publication(secondSession, 0));
        var current = directory.view();
        assertFalse(deliver(directory, first, publication(firstSession, 100)));
        directory.disconnected(first);
        assertSame(current, directory.view());
        assertFalse(deliver(directory, second, publication(secondSession, 0)));
        assertSame(current, directory.view());
    }

    @Test
    void malformedCompletePayloadKeepsPreviousGenerationAndNeverCreatesAVisiblePartialCatalog() {
        var directory = new ClientStructureCatalog();
        var connection = new Object();
        var session = UUID.randomUUID();
        directory.connected(connection);
        deliver(directory, connection, publication(session, 0));
        var old = directory.view();
        var good = publication(session, 1);
        byte[] malformed = good.bytes();
        malformed[0] = 127;
        assertThrows(IllegalArgumentException.class, () -> directory.receive(connection,
            new StructureCatalogPacket(session, 1, malformed.length, 0, malformed)));
        assertSame(old, directory.view());
        assertTrue(deliver(directory, connection, good));
    }

    @Test
    void actualPacketRejectsTrailingOrOversizedChunksAndCopiesPayloadOwnership() {
        var publication = publication(UUID.randomUUID(), 1);
        byte[] payload = publication.bytes();
        var packet = new StructureCatalogPacket(publication.view().session(), 1, payload.length, 0, payload);
        Arrays.fill(payload, (byte) 0);
        assertNotEquals(0, packet.bytes()[3]);
        var buffer = Unpooled.buffer();
        try {
            packet.toBytes(buffer);
            var decoded = new StructureCatalogPacket();
            var copy = buffer.copy();
            try {
                decoded.fromBytes(copy);
            } finally {
                copy.release();
            }
            assertArrayEquals(packet.bytes(), decoded.bytes());
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> new StructureCatalogPacket().fromBytes(buffer));
            assertThrows(IllegalArgumentException.class, () -> new StructureCatalogPacket(UUID.randomUUID(), 0, StructureCatalogCodec.MAX_BYTES + 1, 0, new byte[StructureCatalogPacket.CHUNK_BYTES]));
        } finally {
            buffer.release();
        }
    }

    @Test
    void conflictingChunksAndPayloadHeaderMismatchRetainThePreviousView() {
        var directory = new ClientStructureCatalog();
        var connection = new Object();
        var session = UUID.randomUUID();
        directory.connected(connection);
        var oldPublication = publication(session, 0);
        deliver(directory, connection, oldPublication);
        var old = directory.view();
        var newPublication = publication(session, 1);
        byte[] bytes = newPublication.bytes();
        assertThrows(IllegalArgumentException.class, () -> directory.receive(connection, new StructureCatalogPacket(session, 2, bytes.length, 0, bytes)));
        assertSame(old, directory.view());
        byte[] oversized = new byte[StructureCatalogPacket.CHUNK_BYTES + 4];
        Arrays.fill(oversized, (byte) 0);
        var part = new StructureCatalogPacket(session, 3, oversized.length, 0, Arrays.copyOf(oversized, StructureCatalogPacket.CHUNK_BYTES));
        assertFalse(directory.receive(connection, part));
        byte[] conflict = part.bytes();
        conflict[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> directory.receive(connection, new StructureCatalogPacket(session, 3, oversized.length, 0, conflict)));
        assertSame(old, directory.view());
    }

    @Test
    void invalidGraphsAndOversizedPresentationFailBeforeTheyCanBeBroadcast() {
        var node = new StructureCatalogView.Node(id("cyclic"), BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE,
            BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(), List.of(0));
        assertThrows(IllegalArgumentException.class, () -> new StructureCatalogView(UUID.randomUUID(), 0, List.of(node), Map.of(id("cyclic"), new StructureCatalogView.Entry(0, null, Map.of()))));
        var base = publication(UUID.randomUUID(), 0).view();
        var entry = base.entries().get(id("parent"));
        var tooLarge = new StructureCatalogView(base.session(), 1, base.nodes(), Map.of(id("parent"), new StructureCatalogView.Entry(entry.node(), "x".repeat(65537), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> StructureCatalogCodec.encode(tooLarge));
        byte[] trailing = Arrays.copyOf(publication(UUID.randomUUID(), 0).bytes(), publication(UUID.randomUUID(), 0).bytes().length + 1);
        assertThrows(IllegalArgumentException.class, () -> StructureCatalogCodec.decode(trailing));
    }

    @Test
    void repeatedConnectionNotificationPreservesTheCompletedGenerationAndItsPreviewCache() {
        var directory = new ClientStructureCatalog();
        var connection = new Object();
        directory.connected(connection);
        deliver(directory, connection, publication(UUID.randomUUID(), 0));
        var current = directory.view();
        try (var cache = new StructurePreviewCache<String>(directory)) {
            var before = cache.get(id("parent"), "north", (_, _) -> "preview");
            directory.connected(connection);
            assertSame(current, directory.view());
            assertSame(before, cache.get(id("parent"), "north", (_, _) -> fail("Same connection must retain cache")));
        }
    }

    @Test
    @SuppressWarnings("TryFinallyCanBeTryWithResources")
        // Explicit repeated closes are the behavior under test.
    void identicalCallbacksHaveIndependentSubscriptionIdentities() throws Exception {
        var directory = new ClientStructureCatalog();
        var connection = new Object();
        var session = UUID.randomUUID();
        directory.connected(connection);
        var calls = new AtomicInteger();
        Runnable callback = calls::incrementAndGet;
        var old = directory.onInvalidated(callback);
        var replacement = directory.onInvalidated(callback);
        try {
            old.close();
            old.close();
            deliver(directory, connection, publication(session, 0));
            assertEquals(1, calls.get());
            replacement.close();
            deliver(directory, connection, publication(session, 1));
            assertEquals(1, calls.get());
        } finally {
            old.close();
            replacement.close();
        }
    }
}
