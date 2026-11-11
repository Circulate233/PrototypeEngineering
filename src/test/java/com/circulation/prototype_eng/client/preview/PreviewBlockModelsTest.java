package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import it.unimi.dsi.fastutil.ints.IntList;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the actual cache continuation and ownership logic through its package-local model/GL seam.
 */
@SuppressWarnings("resource") // Baker ownership transfers to the cache; each test asserts its exact single close.
class PreviewBlockModelsTest {
    private static final ResourceLocation ID = new ResourceLocation("block_models_test", "root");

    private static StructurePreviewModel model(BlockPos... positions) {
        var node = new StructureCatalogView.Node(ID, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.TEMPLATE, BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(positions), IntList.of());
        var view = new StructureCatalogView(UUID.randomUUID(), 0, List.of(node), Map.of(ID, new StructureCatalogView.Entry(0, null, Map.of())));
        var builder = new StructurePreviewModel.Builder(view, ID, new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        for (var position : positions)
            builder.addCell(new StructurePreviewModel.Cell(position, StructurePreviewPath.ROOT, 0, 0, null, false));
        return builder.freeze(new StructureBounds(BlockPos.ORIGIN, BlockPos.ORIGIN));
    }

    private static PreviewBlockModelCache.Ready complete(PreviewBlockModelCache cache, StructurePreviewModel model) {
        PreviewBlockModelCache.Ready ready = null;
        for (int i = 0; ready == null && i < 1000; i++) ready = cache.advance(model, 1);
        assertNotNull(ready);
        return ready;
    }

    @Test
    void negativeChunksFourLayersAndBuildVisitsResumeWithoutDoingUnboundedWork() {
        var input = model(new BlockPos(-17, 0, -1), new BlockPos(-16, 0, -1), new BlockPos(0, 0, 0));
        var bakers = new ObjectArrayList<RecordingBaker>();
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> {
            var baker = new RecordingBaker();
            bakers.add(baker);
            return baker;
        }, 8)) {
            assertNull(cache.advance(input, 3));
            assertTrue(bakers.isEmpty()); // only three grouping visits
            assertNull(cache.advance(input, 1));
            assertEquals(1, bakers.size());
            assertTrue(bakers.getFirst().cells.isEmpty());
            assertNull(cache.advance(input, 1));
            assertEquals(List.of(input.cells().getFirst()), bakers.getFirst().cells);
            assertNull(cache.advance(input, 1));
            assertEquals(List.of(0), bakers.getFirst().uploads);
            var ready = complete(cache, input);
            assertEquals(List.of(new PreviewBlockModelCache.ChunkKey(-2, 0, -1), new PreviewBlockModelCache.ChunkKey(-1, 0, -1),
                new PreviewBlockModelCache.ChunkKey(0, 0, 0)), ready.chunks().stream().map(chunk -> chunk.key).toList());
            for (var baker : bakers) {
                assertEquals(List.of(0, 1, 2, 3), baker.uploads);
                assertEquals(1, baker.closes);
            }
        }
        for (var baker : bakers) for (var mesh : baker.meshes) assertEquals(1, mesh.closes);
    }

    @Test
    void emptyDistantAndVisibleChunksAllConsumeRollingRenderVisits() {
        var input = model(new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0), new BlockPos(1600, 0, 0));
        var index = new int[1];
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> {
            var baker = new RecordingBaker();
            baker.empty = index[0]++ == 0;
            return baker;
        }, 8)) {
            var ready = complete(cache, input);
            assertTrue(cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 10).isEmpty());
            assertEquals(1, cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 10).size());
            assertTrue(cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 10).isEmpty());
            assertTrue(cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 10).isEmpty());
            assertEquals(1, cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 10).size());
        }
    }

    @Test
    void aCellWithPendingLayerQuadWorkRemainsAtItsCursorAcrossBudgetYields() {
        var input = model(BlockPos.ORIGIN, new BlockPos(1, 0, 0));
        var baker = new RecordingBaker();
        baker.appendRemainder = 2;
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> baker, 8)) {
            assertNull(cache.advance(input, 3));
            assertTrue(baker.cells.isEmpty()); // grouping + open chunk
            assertNull(cache.advance(input, 2));
            assertEquals(List.of(input.cells().getFirst(), input.cells().getFirst()), baker.cells);
            assertTrue(baker.uploads.isEmpty());
            assertNull(cache.advance(input, 1));
            assertSame(input.cells().getFirst(), baker.cells.getLast());
            assertNull(cache.advance(input, 1));
            assertSame(input.cells().getLast(), baker.cells.getLast());
            assertNull(cache.advance(input, 1));
            assertEquals(List.of(0), baker.uploads);
            complete(cache, input);
        }
    }

    @Test
    void translucentSelectedChunksSortFarToNearAndChunkRemainderResumes() {
        var input = model(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(32, 0, 0), new BlockPos(96, 0, 0));
        try (var cache = new PreviewBlockModelCache(_ -> true, RecordingBaker::new, 8)) {
            var ready = complete(cache, input);
            var first = cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 1000);
            assertEquals(List.of(0), first.stream().map(chunk -> chunk.key.x()).toList());
            var second = cache.select(ready, 1, BlockPos.ORIGIN, 0, 0, 0, 1000);
            assertEquals(List.of(2), second.stream().map(chunk -> chunk.key.x()).toList());
            var all = cache.select(ready, 3, BlockPos.ORIGIN, 0, 0, 0, 1000);
            assertEquals(List.of(6, 2, 0), PreviewBlockModelCache.translucentOrder(all, BlockPos.ORIGIN, 0, 0, 0).stream().map(chunk -> chunk.key.x()).toList());
        }
    }

    @Test
    void catalogReplacementDuringAppendCannotUploadAndReleasesBuilderOnce() {
        var input = model(BlockPos.ORIGIN);
        var active = new AtomicReference<>(input);
        var baker = new RecordingBaker();
        try (var cache = new PreviewBlockModelCache(model -> model == active.get(), () -> baker, 8)) {
            baker.appendCallback = () -> {
                active.set(model(new BlockPos(1, 0, 0)));
                cache.invalidate();
            };
            assertNull(cache.advance(input, 100));
            assertTrue(baker.uploads.isEmpty());
            assertEquals(1, baker.closes);
            assertNull(cache.advance(input, 100));
            assertEquals(1, baker.closes);
        }
    }

    @Test
    void resourceEpochReplacementRejectsLateUploadAndClosesReturnedHandleExactlyOnce() {
        var input = model(BlockPos.ORIGIN);
        var baker = new RecordingBaker();
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> baker, 8)) {
            baker.uploadCallback = cache::invalidate;
            assertNull(cache.advance(input, 100));
            assertEquals(List.of(0), baker.uploads);
            assertEquals(1, baker.closes);
            assertEquals(1, baker.meshes.getFirst().closes);
        }
        assertEquals(1, baker.closes);
        assertEquals(1, baker.meshes.getFirst().closes);
    }

    @Test
    void externalCloseDuringUploadRevokesRightsBeforeDrainingAndRejectsItsLateResult() {
        var input = model(BlockPos.ORIGIN);
        var baker = new RecordingBaker();
        var cache = new PreviewBlockModelCache(_ -> true, () -> baker, 8);
        baker.uploadCallback = cache::close;
        assertNull(cache.advance(input, 100));
        assertNull(cache.advance(input, 100));
        cache.close();
        assertEquals(List.of(0), baker.uploads);
        assertEquals(1, baker.closes);
        assertEquals(1, baker.meshes.getFirst().closes);
    }

    @Test
    void uploadFailureDrainsPreviouslyUploadedLayersAndNeverPublishesPartialCache() {
        var input = model(BlockPos.ORIGIN);
        var baker = new RecordingBaker();
        baker.failUpload = 2;
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> baker, 8)) {
            assertThrows(IllegalStateException.class, () -> cache.advance(input, 100));
            assertEquals(List.of(0, 1, 2), baker.uploads);
            assertEquals(1, baker.closes);
            for (var mesh : baker.meshes) assertEquals(1, mesh.closes);
        }
        assertEquals(1, baker.closes);
    }

    @Test
    void lruReplacementAndRepeatedCloseReleaseEachLayerOnceEvenIfOneDeleteFails() {
        var bakers = new ObjectArrayList<RecordingBaker>();
        var cache = new PreviewBlockModelCache(_ -> true, () -> {
            var baker = new RecordingBaker();
            bakers.add(baker);
            return baker;
        }, 1);
        complete(cache, model(BlockPos.ORIGIN));
        complete(cache, model(new BlockPos(16, 0, 0)));
        for (var mesh : bakers.getFirst().meshes) assertEquals(1, mesh.closes);
        var failure = new IllegalStateException("failed delete");
        bakers.getLast().meshes.getFirst().closeFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, cache::close));
        cache.close();
        for (var baker : bakers) for (var mesh : baker.meshes) assertEquals(1, mesh.closes);
    }

    @Test
    void vmErrorDuringCleanupKeepsItsIdentityAndOtherLayerCleanupStillRuns() {
        var input = model(BlockPos.ORIGIN);
        var baker = new RecordingBaker();
        baker.failUpload = 2;
        var vmError = new OutOfMemoryError("synthetic ownership failure");
        baker.closeError = vmError;
        try (var cache = new PreviewBlockModelCache(_ -> true, () -> baker, 8)) {
            assertSame(vmError, assertThrows(OutOfMemoryError.class, () -> cache.advance(input, 100)));
            assertEquals(1, vmError.getSuppressed().length);
            assertEquals(1, baker.closes);
            for (var mesh : baker.meshes) assertEquals(1, mesh.closes);
        }
    }

    @Test
    void displayAndPlaneFiltersAlwaysRequireSamplePath() {
        var input = model(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 1, 0), new BlockPos(4, 1, 0), new BlockPos(5, 1, 0));
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        catalog.connected(connection);
        var generation = input.generation();
        var bytes = StructureCatalogCodec.encode(generation);
        for (int offset = 0, index = 0; offset < bytes.length; offset += StructureCatalogPacket.CHUNK_BYTES, index++)
            catalog.receive(connection, new StructureCatalogPacket(generation.session(), generation.sequence(), bytes.length, index,
                Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + StructureCatalogPacket.CHUNK_BYTES))));
        var options = new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, true, new StructurePreviewModel.Layer(1), 100, 100, 2, 64);
        try (var previews = new StructurePreviews(catalog)) {
            assertTrue(previews.project(ID, input.settings(), BlockPos.ORIGIN, options));
            var state = new StructureProjectionState(previews);
            var world = new Object();
            state.tick(world, (cell, _) -> cell.position().getX() == 4 ? StructureProjectionState.Status.DISPLAY_MATCH : StructureProjectionState.Status.DIFFERENT);
            assertFalse(PreviewBlockModels.staticGeometry(options));
            assertTrue(state.frame(world).isEmpty()); // two filtered cells still exhaust visits
            assertEquals(List.of(new BlockPos(3, 1, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
            assertEquals(List.of(new BlockPos(5, 1, 0)), state.frame(world).stream().map(StructureProjectionState.Sample::position).toList());
        }
        assertTrue(PreviewBlockModels.staticGeometry(new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false, null, 1, 0, 1, 64)));
    }

    @Test
    void blockVertexTintAlphaAndFullbrightPreservePositionAtlasUvAndSourceDataInBothByteOrders() {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            int[] source = new int[28];
            for (int vertex = 0; vertex < 4; vertex++) {
                source[vertex * 7] = Float.floatToRawIntBits(vertex + 0.25f);
                source[vertex * 7 + 1] = Float.floatToRawIntBits(0.5f);
                source[vertex * 7 + 2] = Float.floatToRawIntBits(1);
                source[vertex * 7 + 3] = order == ByteOrder.LITTLE_ENDIAN ? 0x80C86432 : 0x3264C880;
                source[vertex * 7 + 4] = 0x3F123456;
                source[vertex * 7 + 5] = 0x3EABCDEF;
                source[vertex * 7 + 6] = 0x00040020;
            }
            var prepared = PreviewBlockVertices.prepare(source, 0x804020, order);
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(order == ByteOrder.LITTLE_ENDIAN ? 0x53191919 : 0x19191953, prepared[vertex * 7 + 3]);
                assertEquals(0x00F000F0, prepared[vertex * 7 + 6]);
                for (int offset : new int[]{0, 1, 2, 4, 5})
                    assertEquals(source[vertex * 7 + offset], prepared[vertex * 7 + offset]);
                assertEquals(0x00040020, source[vertex * 7 + 6]);
            }
        }
    }

    @Test
    void addonCloseRevokesServiceBeforeCallbacksAndDrainsOtherCallbacksAfterFailure() {
        var previews = new StructurePreviews(new ClientStructureCatalog());
        var calls = new ObjectArrayList<String>();
        previews.onClosed(() -> {
            assertTrue(previews.closed());
            calls.add("first");
        });
        var failure = new IllegalStateException("synthetic close callback");
        previews.onClosed(() -> {
            calls.add("second");
            previews.close();
            assertThrows(IllegalStateException.class, () -> previews.onClosed(() -> {
            }));
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, previews::close));
        previews.close();
        assertEquals(List.of("second", "first"), calls);
    }

    @Test
    void filteredSampleCpuPreparationAndQuadEmissionBothResumeAndDoNotRenderOldFilteredCells() {
        var input = model(BlockPos.ORIGIN);
        var sample = new StructureProjectionState.Sample(input.cells().getFirst(), BlockPos.ORIGIN, StructureProjectionState.Status.UNKNOWN);
        var prepared = new int[1];
        var cpu = new PreviewSampleBlockModels((_, _) -> sink -> {
            sink.accept(new int[]{++prepared[0]});
            return prepared[0] == 3;
        });
        var emitted = new IntArrayList();
        assertEquals(List.of(sample), cpu.present(input, List.of(sample), 1, () -> true, data -> emitted.add(data[0])));
        assertEquals(0, prepared[0]); // only admission was permitted
        assertEquals(List.of(sample), cpu.present(input, List.of(sample), 2, () -> true, data -> emitted.add(data[0])));
        assertEquals(2, prepared[0]);
        assertTrue(emitted.isEmpty());
        assertTrue(cpu.present(input, List.of(sample), 2, () -> true, data -> emitted.add(data[0])).isEmpty());
        assertEquals(List.of(1), emitted);
        cpu.present(input, List.of(sample), 1, () -> true, data -> emitted.add(data[0]));
        cpu.present(input, List.of(), 100, () -> true, data -> emitted.add(data[0])); // C has hidden it this frame
        assertEquals(List.of(1, 2), emitted);
        cpu.present(input, List.of(sample), 1, () -> true, data -> emitted.add(data[0]));
        assertEquals(List.of(1, 2, 3), emitted);
        assertEquals(3, prepared[0]);
    }

    @Test
    void resourceReplacementDuringSamplePreparationCannotPublishOrEmitLateCpuQuads() {
        var input = model(BlockPos.ORIGIN);
        var sample = new StructureProjectionState.Sample(input.cells().getFirst(), BlockPos.ORIGIN, StructureProjectionState.Status.UNKNOWN);
        var reference = new PreviewSampleBlockModels[1];
        reference[0] = new PreviewSampleBlockModels((_, _) -> sink -> {
            reference[0].invalidate();
            sink.accept(new int[]{1});
            return true;
        });
        var emitted = new ObjectArrayList<int[]>();
        assertTrue(reference[0].present(input, List.of(sample), 100, () -> true, emitted::add).isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    void lowBudgetPendingPreparationSurvivesChangingSampleRoundsUntilNativeQuadsAreReady() {
        var input = model(BlockPos.ORIGIN, new BlockPos(16, 0, 0));
        var first = new StructureProjectionState.Sample(input.cells().getFirst(), BlockPos.ORIGIN, StructureProjectionState.Status.UNKNOWN);
        var second = new StructureProjectionState.Sample(input.cells().getLast(), new BlockPos(16, 0, 0), StructureProjectionState.Status.UNKNOWN);
        var admitted = new int[1];
        var cpu = new PreviewSampleBlockModels((_, cell) -> {
            admitted[0]++;
            var step = new int[1];
            return sink -> {
                sink.accept(new int[]{cell.position().getX() * 10 + ++step[0]});
                return step[0] == 2;
            };
        });
        var emitted = new IntArrayList();
        for (int round = 0; round < 3; round++) {
            cpu.present(input, List.of(first), 1, () -> true, data -> emitted.add(data[0]));
            cpu.present(input, List.of(second), 1, () -> true, data -> emitted.add(data[0]));
            cpu.present(input, List.of(), 1, () -> true, data -> emitted.add(data[0]));
        }
        assertTrue(emitted.isEmpty());
        assertEquals(2, admitted[0]);
        cpu.present(input, List.of(first), 1, () -> true, data -> emitted.add(data[0]));
        cpu.present(input, List.of(second), 1, () -> true, data -> emitted.add(data[0]));
        assertEquals(List.of(1, 161), emitted);
        assertEquals(2, admitted[0]);
    }

    @Test
    void stableVisibleSampleListDoesNotStarveLaterCellsAtSingleVisitBudget() {
        var input = model(BlockPos.ORIGIN, new BlockPos(16, 0, 0));
        var first = new StructureProjectionState.Sample(input.cells().getFirst(), BlockPos.ORIGIN, StructureProjectionState.Status.UNKNOWN);
        var second = new StructureProjectionState.Sample(input.cells().getLast(), new BlockPos(16, 0, 0), StructureProjectionState.Status.UNKNOWN);
        var cpu = new PreviewSampleBlockModels((_, cell) -> sink -> {
            sink.accept(new int[]{cell.position().getX()});
            return true;
        });
        var emitted = new IntArrayList();
        for (int frame = 0; frame < 8; frame++)
            cpu.present(input, List.of(second, first), 1, () -> true, data -> emitted.add(data[0]));
        assertTrue(emitted.contains(0));
        assertTrue(emitted.contains(16));
    }

    @Test
    void fairSampleWorkStillEmitsChosenQuadsInCallerFarToNearOrder() {
        var input = model(BlockPos.ORIGIN, new BlockPos(16, 0, 0));
        var first = new StructureProjectionState.Sample(input.cells().getFirst(), BlockPos.ORIGIN, StructureProjectionState.Status.UNKNOWN);
        var second = new StructureProjectionState.Sample(input.cells().getLast(), new BlockPos(16, 0, 0), StructureProjectionState.Status.UNKNOWN);
        var cpu = new PreviewSampleBlockModels((_, cell) -> sink -> {
            sink.accept(new int[]{cell.position().getX()});
            return true;
        });
        var emitted = new IntArrayList();
        for (int frame = 0; frame < 6; frame++)
            cpu.present(input, List.of(second, first), 1, () -> true, _ -> {
            });
        cpu.present(input, List.of(second, first), 2, () -> true, data -> emitted.add(data[0]));
        assertEquals(List.of(16, 0), emitted);
    }

    private static final class Mesh implements PreviewBlockModelCache.LayerMesh {
        int closes, draws;
        RuntimeException closeFailure;

        @Override
        public void draw() {
            draws++;
        }

        @Override
        public void close() {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class RecordingBaker implements PreviewBlockModelCache.ChunkBaker {
        final ObjectArrayList<StructurePreviewModel.Cell> cells = new ObjectArrayList<>();
        final IntArrayList uploads = new IntArrayList();
        final ObjectArrayList<Mesh> meshes = new ObjectArrayList<>();
        int closes;
        boolean empty;
        Runnable appendCallback, uploadCallback;
        int failUpload = -1;
        int appendRemainder;
        Error closeError;

        @Override
        public boolean appendStep(StructurePreviewModel.Cell cell) {
            cells.add(cell);
            if (appendCallback != null) appendCallback.run();
            return appendRemainder-- <= 0;
        }

        @Override
        public PreviewBlockModelCache.LayerMesh upload(int layer) {
            uploads.add(layer);
            if (layer == failUpload) throw new IllegalStateException("failed upload");
            if (uploadCallback != null) uploadCallback.run();
            if (empty) return null;
            var mesh = new Mesh();
            meshes.add(mesh);
            return mesh;
        }

        @Override
        public void close() {
            closes++;
            if (closeError != null) throw closeError;
        }
    }
}
