package com.circulation.prototype_eng.client.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.ArrayList;
import java.util.Objects;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static com.circulation.prototype_eng.client.render.BModelFixtures.*;

class ModelBufferOwnershipTest {
    @TempDir
    Path root;

    @Test
    void retirementWaitsForReadersAndAnExistingReaderCanHandoffToALaterPhase() {
        var releases = new AtomicInteger();
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var result = new BuiltModelBuffers(BlockPos.ORIGIN, Map.of(), Map.of(), Map.of(),
            new ModelBufferPool(), new DirectVertexBufferPool(), client, releases::incrementAndGet);
        var lease = Objects.requireNonNull(result.borrow());
        result.retire();
        assertNull(result.borrow());
        var deferred = lease.fork();
        lease.close();
        lease.close();
        assertEquals(0, releases.get());
        assertSame(result, deferred.buffers());
        deferred.close();
        assertEquals(1, releases.get());
        assertThrows(IllegalStateException.class, deferred::fork);
        result.retire();
        assertEquals(1, releases.get());
    }

    @Test
    void oldMappedWriterIsNotUnmappedOrDeletedUntilItExitsAndCannotPublishIntoANewEntry() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var gpu = new CpuUploader(client);
        try (var assets = new ModelAssets(client, resources("{\"animations\":{}}"), root)) {
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of(), 0);
            var old = Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            old.writerView().put(new int[28]);
            gpu.retire(request.owner());
            assertEquals(0, gpu.deletes.get(1));
            assertEquals(0, gpu.unmaps.get(1));
            var newer = Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            assertNull(gpu.finish(old, 112, true));
            assertEquals(1, gpu.unmaps.get(1));
            assertEquals(1, gpu.deletes.get(1));
            var draw = Objects.requireNonNull(gpu.finish(newer, 112, true));
            draw.draw();
            assertEquals(List.of(4), gpu.draws);
            draw.close();
            gpu.retire(request.owner());
            gpu.close();
            assertTrue(gpu.deletes.values().intStream().allMatch(count -> count == 1));
        }
    }

    @Test
    void twoDrawnSlotsForcePackedFallbackAndOnlyAnExitedReaderMakesASlotReusable() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var gpu = new CpuUploader(client);
        try (var assets = new ModelAssets(client, resources("{\"animations\":{}}"), root)) {
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of(), 0);
            var one = Objects.requireNonNull(gpu.finish(Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112)), 112, true));
            var two = Objects.requireNonNull(gpu.finish(Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112)), 112, true));
            assertNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            one.close();
            var next = Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            assertNull(gpu.finish(next, 0, false));
            two.close();
            gpu.close();
            assertEquals(2, gpu.next);
            assertEquals(1, gpu.deletes.get(1));
            assertEquals(1, gpu.deletes.get(2));
        }
    }

    @Test
    void positiveUnmapFailureRejectsTheOutputAndClosesTheOriginalSlotExactlyOnce() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var gpu = new CpuUploader(client);
        gpu.failUnmapId = 1;
        try (var assets = new ModelAssets(client, resources("{\"animations\":{}}"), root)) {
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of(), 0);
            var ticket = Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            assertThrows(IllegalStateException.class, () -> gpu.finish(ticket, 112, true));
            assertEquals(1, gpu.unmaps.get(1));
            assertEquals(1, gpu.deletes.get(1));
            assertThrows(IllegalStateException.class, () -> gpu.finish(ticket, 0, false));
            gpu.close();
        }
    }

    @Test
    void thrownUnmapErrorRemainsTheOriginalFailureAndAggregatesEveryNativeDeleteFailure() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var original = new AssertionError("native unmap error");
        var cleanup = new IllegalStateException("native delete failure");
        var gpu = new CpuUploader(client) {
            @Override
            protected boolean unmapBuffer(int id) {
                super.unmapBuffer(id);
                throw original;
            }

            @Override
            protected void deleteBuffer(int id) {
                super.deleteBuffer(id);
                throw cleanup;
            }
        };
        try (var assets = new ModelAssets(client, resources("{\"animations\":{}}"), root)) {
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of(), 0);
            var ticket = Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112));
            assertSame(original, assertThrows(AssertionError.class, () -> gpu.finish(ticket, 112, true)));
            assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
            assertTrue(ticket.finished());
            assertEquals(1, gpu.deletes.get(1));
            gpu.close();
            assertEquals(1, gpu.deletes.get(1));
        }
    }

    @Test
    void lastReaderCleanupAttemptsEveryResourceAndPreservesTheFirstError() {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var visits = new AtomicInteger();
        var original = new AssertionError("builder release");
        var packedFailure = new IllegalStateException("packed release");
        var extraFailure = new IllegalArgumentException("texture release");
        var builders = new ModelBufferPool() {
            @Override
            public synchronized void release(BufferBuilder buffer) {
                visits.incrementAndGet();
                throw original;
            }
        };
        var direct = new DirectVertexBufferPool() {
            @Override
            public synchronized void release(ByteBuffer bytes) {
                visits.incrementAndGet();
                throw packedFailure;
            }
        };
        var result = new BuiltModelBuffers(BlockPos.ORIGIN,
            Map.of(MachineRenderPass.DEFAULT, builders.borrow(32)), Map.of(MachineRenderPass.DEFAULT, ByteBuffer.allocateDirect(28)), Map.of(),
            builders, direct, client, () -> {
            visits.incrementAndGet();
            throw extraFailure;
        });
        var reader = Objects.requireNonNull(result.borrow());
        result.retire();
        assertSame(original, assertThrows(AssertionError.class, reader::close));
        assertEquals(3, visits.get());
        assertArrayEquals(new Throwable[]{packedFailure, extraFailure}, original.getSuppressed());
        reader.close();
        result.retire();
        assertEquals(3, visits.get());
    }

    @Test
    void queuedGpuCleanupSurvivesARejectedWakeupAndRunsOnTheCapturedOwner() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), ignored -> {
            throw new RejectedExecutionException("fixture rejection");
        });
        var gpu = new CpuUploader(client);
        try (var assets = new ModelAssets(client, resources("{\"animations\":{}}"), root)) {
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of(), 0);
            var draw = Objects.requireNonNull(gpu.finish(Objects.requireNonNull(gpu.tryMap(request, MachineRenderPass.DEFAULT, 112)), 112, true));
            gpu.retire(request.owner());
            var failure = new AtomicReference<Throwable>();
            var worker = Thread.ofPlatform().start(() -> {
                try {
                    draw.close();
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            });
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertInstanceOf(RejectedExecutionException.class, failure.get());
            assertEquals(0, gpu.deletes.get(1));
            client.drain();
            assertEquals(1, gpu.deletes.get(1));
            client.drain();
            gpu.close();
        }
    }

    @Test
    void bufferBudgetsKeepUsefulSmallerEntriesAndOversizeBorrowDoesNotPinEverything() {
        var pool = new ModelBufferPool(new ModelBufferPool.Limits(32 * 1024, 128 * 1024, 1, 96 * 1024, 0, 128 * 1024, 0));
        var small = pool.borrow(32 * 1024);
        var large = pool.borrow(64 * 1024);
        var newer = pool.borrow(96 * 1024);
        pool.release(small);
        pool.release(large);
        pool.release(newer);
        assertSame(small, pool.borrow(32 * 1024));
        assertSame(newer, pool.borrow(96 * 1024));
        pool.close();
        pool.release(large);
        assertNotSame(large, pool.borrow(64 * 1024));
        var direct = new DirectVertexBufferPool(64 * 1024);
        var first = direct.borrow(32 * 1024);
        var big = direct.borrow(64 * 1024);
        direct.release(first);
        direct.release(big);
        assertSame(first, direct.borrow(32 * 1024));
        direct.close();
    }

    @Test
    void uncachedCpuResultsUseTheSynchronousMergedPathAndClientArrayFallback() {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var gpu = new CpuUploader(client);
        gpu.vbo = false;
        var direct = new DirectVertexBufferPool();
        var releases = new AtomicInteger();
        var results = new ArrayList<BuiltModelBuffers>();
        var leases = new ArrayList<BuiltModelBuffers.Lease>();
        for (int i = 0; i < 3; i++) {
            var bytes = direct.borrow(112);
            bytes.limit(112);
            var result = new BuiltModelBuffers(BlockPos.ORIGIN, Map.of(), Map.of(MachineRenderPass.DEFAULT, bytes), Map.of(),
                new ModelBufferPool(), direct, client, releases::incrementAndGet);
            results.add(result);
            leases.add(Objects.requireNonNull(result.borrow()));
        }
        gpu.drawMultiple(leases, MachineRenderPass.DEFAULT);
        assertEquals(List.of(12), gpu.draws);
        results.forEach(BuiltModelBuffers::retire);
        leases.forEach(BuiltModelBuffers.Lease::close);
        assertEquals(3, releases.get());
        gpu.close();
        direct.close();
    }
}
