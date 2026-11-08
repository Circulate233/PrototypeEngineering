package com.circulation.prototype_eng.client.render;

import java.nio.ByteOrder;
import java.util.concurrent.RejectedExecutionException;
import java.util.Set;
import java.util.Objects;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.gecko.GeckoGeoModelInstances;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBuildTask;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static com.circulation.prototype_eng.client.render.BModelFixtures.*;

@SuppressWarnings("TryFinallyCanBeTryWithResources") // These assertions exercise task/cache retirement before each reader and native resource exits.
class GeckoBuildOutputsTest {
    @TempDir
    Path root;
    static final String ANIMATIONS = "{\"animations\":{\"move\":{\"loop\":true,\"animation_length\":2,\"bones\":{\"root\":{\"position\":[0,0,0]}}}}}";
    static final String TWO_PASSES = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
        "bones":[{"name":"root","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]},
        {"name":"bloom_child","parent":"root","pivot":[0,0,0],"cubes":[{"origin":[0,16,0],"size":[16,16,16],"uv":[0,0]}]}]}]}
        """;

    static final class CapturingUploader extends CpuUploader {
        final Map<MachineRenderPass, ByteBuffer> mapped = new Object2ObjectOpenHashMap<>();
        ByteBuffer latest;

        CapturingUploader(ModelClientThread client) {
            super(client);
        }

        @Override
        protected ByteBuffer mapBuffer(int id, int capacity) {
            latest = super.mapBuffer(id, capacity);
            return latest;
        }

        @Override
        public Ticket tryMap(GeckoRenderRequest request, MachineRenderPass pass, int capacity) {
            var ticket = super.tryMap(request, pass, capacity);
            if (ticket != null) mapped.put(pass, latest);
            return ticket;
        }
    }

    private static int[] ints(ByteBuffer bytes, int count) {
        int[] values = new int[count];
        bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(values);
        return values;
    }

    private GeckoModelBuildTask task(GeckoRenderRequest request, ModelAssets assets, ModelBufferPool builders,
                                     DirectVertexBufferPool direct, ModelClientThread client, CpuUploader gpu, boolean mapped) {
        return new GeckoModelBuildTask(request, new GeckoGeoModelInstances.Instance(request, assets, new GeckoModelBaker()), assets, builders, direct, client, gpu, mapped);
    }

    @Test
    void loadedGeckoGeometryReachesOrdinaryPackedAndNativeWriterOutputsWithTheSameRoutedVertices() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var resources = resources(ANIMATIONS);
        resources.put(GEO, TWO_PASSES);
        var builders = new ModelBufferPool();
        var direct = new DirectVertexBufferPool();
        var gpu = new CapturingUploader(client);
        try (var assets = new ModelAssets(client, resources, root)) {
            assets.select(List.of(GEO, TEXTURE, ANIMATION));
            var all = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ALL, List.of("move"), 0);
            var packed = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("move"), 0);
            var mapped = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("move"), 0);
            var ordinaryTask = task(all, assets, builders, direct, client, gpu, false);
            ordinaryTask.run();
            var packedTask = task(packed, assets, builders, direct, client, gpu, false);
            packedTask.run();
            var mappedTask = task(mapped, assets, builders, direct, client, gpu, true);
            mappedTask.run();
            assertNull(ordinaryTask.failure());
            assertNull(packedTask.failure());
            assertNull(mappedTask.failure());
            var ordinaryLease = Objects.requireNonNull(ordinaryTask.borrow());
            var packedLease = Objects.requireNonNull(packedTask.borrow());
            var mappedLease = Objects.requireNonNull(mappedTask.borrow());
            try {
                assertEquals(Set.of(MachineRenderPass.DEFAULT, MachineRenderPass.BLOOM), ordinaryLease.buffers().builders().keySet());
                for (var pass : ordinaryLease.buffers().builders().keySet()) {
                    var buffer = ordinaryLease.buffers().builders().get(pass);
                    int count = buffer.getVertexCount() * 7;
                    assertEquals(24 * 7, count);
                    assertArrayEquals(ints(buffer.getByteBuffer(), count), ints(packedLease.buffers().packed().get(pass), count));
                    assertArrayEquals(ints(buffer.getByteBuffer(), count), ints(gpu.mapped.get(pass), count));
                }
                assertEquals(2, mappedLease.buffers().gpu().size());
            } finally {
                ordinaryTask.retire();
                packedTask.retire();
                mappedTask.retire();
                gpu.retire(mapped.owner());
                ordinaryLease.close();
                packedLease.close();
                mappedLease.close();
            }
        } finally {
            gpu.close();
            builders.close();
            direct.close();
        }
    }

    @Test
    void oneFailedMappedPassRejectsTheWholeTaskAndReleasesEverySuccessfulAndPendingPass() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var resources = resources(ANIMATIONS);
        resources.put(GEO, TWO_PASSES);
        var builders = new ModelBufferPool();
        var direct = new DirectVertexBufferPool();
        var gpu = new CpuUploader(client);
        gpu.failUnmapId = 2;
        try (var assets = new ModelAssets(client, resources, root)) {
            assets.select(List.of(GEO, TEXTURE, ANIMATION));
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("move"), 0);
            var task = task(request, assets, builders, direct, client, gpu, true);
            task.run();
            assertInstanceOf(IllegalStateException.class, task.failure());
            assertNull(task.borrow());
            task.retire();
            gpu.retire(request.owner());
            assertEquals(1, gpu.unmaps.get(1));
            assertEquals(1, gpu.unmaps.get(2));
            assertEquals(1, gpu.deletes.get(1));
            assertEquals(1, gpu.deletes.get(2));
        } finally {
            gpu.close();
            builders.close();
            direct.close();
        }
    }

    @Test
    void aRejectedFinishWakeupKeepsTheOriginalTicketUntilOwnerAbortConfirmsUnmap() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var dispatches = new AtomicInteger();
        var rejection = new RejectedExecutionException("first finish rejected");
        var client = new ModelClientThread(Thread.currentThread(), action -> {
            if (dispatches.incrementAndGet() == 2) throw rejection;
            queue.add(action);
        });
        var resources = resources(ANIMATIONS);
        var builders = new ModelBufferPool();
        var direct = new DirectVertexBufferPool();
        var gpu = new CpuUploader(client);
        try (var assets = new ModelAssets(client, resources, root)) {
            assets.select(List.of(GEO, TEXTURE, ANIMATION));
            var request = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("move"), 0);
            var task = task(request, assets, builders, direct, client, gpu, true);
            var worker = Thread.ofPlatform().start(task);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker.isAlive() && System.nanoTime() < deadline) {
                var action = queue.poll(10, TimeUnit.MILLISECONDS);
                if (action != null) action.run();
            }
            worker.join(1000);
            client.drain();
            assertFalse(worker.isAlive());
            assertSame(rejection, task.failure());
            assertNull(task.borrow());
            assertEquals(1, gpu.unmaps.get(1));
            gpu.retire(request.owner());
            assertEquals(1, gpu.deletes.get(1));
        } finally {
            gpu.close();
            builders.close();
            direct.close();
        }
    }
}
