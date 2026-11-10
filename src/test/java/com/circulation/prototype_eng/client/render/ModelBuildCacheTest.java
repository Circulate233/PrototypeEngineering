package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.gecko.GeckoGeoModelInstances;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBuildTask;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.circulation.prototype_eng.client.render.BModelFixtures.CpuUploader;
import static com.circulation.prototype_eng.client.render.BModelFixtures.change;
import static com.circulation.prototype_eng.client.render.BModelFixtures.resources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBuildCacheTest {
    @TempDir
    Path root;

    private static int marker(BuiltModelBuffers.Lease lease) {
        return lease.buffers().packed().get(MachineRenderPass.DEFAULT).getInt(0);
    }

    @Test
    void currentFrameContinuesOnlyForTimeChangesUntilACompleteNextFramePromotes() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.request(GeckoModelBaker.BakeMode.ANIMATED_ONLY);
            assertNull(fixture.cache.request(first));
            fixture.executor.runNext();
            var current = Objects.requireNonNull(fixture.cache.request(first));
            var future = change(first, first.layers(), 2);
            var previous = Objects.requireNonNull(fixture.cache.request(future));
            assertEquals(0, marker(previous));
            assertEquals(1, fixture.executor.queued.size());
            fixture.executor.runNext();
            var next = Objects.requireNonNull(fixture.cache.request(future));
            assertEquals(2, marker(next));
            assertEquals(0, fixture.releases.get());
            current.close();
            previous.close();
            assertEquals(1, fixture.releases.get());
            next.close();
        }
    }

    @Test
    void permanentStaticBuffersSurviveLayerAndTimeChangesWhileTemporaryBuffersRebuild() throws Exception {
        try (var fixture = new Fixture()) {
            var permanent = fixture.request(GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY);
            assertNull(fixture.cache.request(permanent));
            fixture.executor.runNext();
            var first = Objects.requireNonNull(fixture.cache.request(permanent));
            var changed = Objects.requireNonNull(fixture.cache.request(change(permanent, List.of("overlay"), 7)));
            assertSame(first.buffers(), changed.buffers());
            assertEquals(1, fixture.builds.get());
            first.close();
            changed.close();
            var temporary = fixture.request(GeckoModelBaker.BakeMode.TEMP_STATIC_ONLY);
            assertNull(fixture.cache.request(temporary));
            fixture.executor.runNext();
            var old = Objects.requireNonNull(fixture.cache.request(temporary));
            assertNull(fixture.cache.request(change(temporary, List.of("overlay"), 7)));
            assertEquals(1, fixture.executor.queued.size());
            old.close();
        }
    }

    @Test
    void aNewBindingBaseNeverReturnsThePreviousCompletedResult() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.request(GeckoModelBaker.BakeMode.ANIMATED_ONLY);
            assertNull(fixture.cache.request(first));
            fixture.executor.runNext();
            var held = Objects.requireNonNull(fixture.cache.request(first));
            var replacement = new GeckoRenderRequest(first.owner(), first.part(), new GeckoRenderRequest.Epoch(), first.resourceEpoch(),
                first.binding(), first.layers(), first.mode(), first.position(), first.origin(), first.orientation(), first.localOffset(), first.sample());
            assertNull(fixture.cache.request(replacement));
            assertEquals(0, fixture.releases.get());
            held.close();
            assertEquals(1, fixture.releases.get());
        }
    }

    @Test
    void anActiveWorkerCompletesItsCleanupAfterOwnerRetirementWithoutPublishing() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.started = new CountDownLatch(1);
            fixture.resume = new CountDownLatch(1);
            var request = fixture.request(GeckoModelBaker.BakeMode.ANIMATED_ONLY);
            assertNull(fixture.cache.request(request));
            var task = fixture.executor.queued.removeFirst();
            var worker = Thread.ofPlatform().start(task);
            try {
                assertTrue(fixture.started.await(5, TimeUnit.SECONDS));
                fixture.cache.retire(request.owner());
                assertNull(fixture.cache.request(request));
                assertEquals(0, fixture.releases.get());
            } finally {
                fixture.resume.countDown();
            }
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(1, fixture.releases.get());
        }
    }

    @Test
    void retiringAQueuedTaskPreventsItFromBuildingOrReacquiringTheSameOwner() throws Exception {
        try (var fixture = new Fixture()) {
            var request = fixture.request(GeckoModelBaker.BakeMode.ANIMATED_ONLY);
            assertNull(fixture.cache.request(request));
            fixture.cache.retire(request.owner());
            fixture.executor.runNext();
            assertEquals(0, fixture.builds.get());
            assertNull(fixture.cache.request(request));
        }
    }

    @Test
    void executorRejectionLeavesNoLiveBuildingSlotOrNativeOutput() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.executor.shutdown();
            var request = fixture.request(GeckoModelBaker.BakeMode.ANIMATED_ONLY);
            assertThrows(RejectedExecutionException.class, () -> fixture.cache.request(request));
            assertEquals(0, fixture.builds.get());
            assertEquals(0, fixture.gpu.next);
            assertThrows(RejectedExecutionException.class, () -> fixture.cache.request(request));
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final ObjectArrayList<Runnable> queued = new ObjectArrayList<>();
        boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public @NotNull List<Runnable> shutdownNow() {
            shutdown = true;
            var result = List.copyOf(queued);
            queued.clear();
            return result;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queued.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(@NotNull Runnable command) {
            if (shutdown) throw new RejectedExecutionException("fixture executor closed");
            queued.add(command);
        }

        void runNext() {
            queued.removeFirst().run();
        }
    }

    private final class Fixture implements AutoCloseable {
        final ManualExecutor executor = new ManualExecutor();
        final ModelClientThread client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        final ModelBufferPool builders = new ModelBufferPool();
        final DirectVertexBufferPool direct = new DirectVertexBufferPool();
        final CpuUploader gpu = new CpuUploader(client);
        final ModelAssets assets;
        final AtomicInteger builds = new AtomicInteger(), releases = new AtomicInteger();
        final ModelBuildCache cache;
        CountDownLatch started, resume;

        Fixture() throws Exception {
            assets = new ModelAssets(client, resources("{\"animations\":{\"base\":{},\"overlay\":{}}}"), root);
            cache = new ModelBuildCache(client, assets, new ModelBuildExecutor(executor), builders, direct, gpu, false) {
                @SuppressWarnings("ReturnOfInnerClass")
                // This task retains the fixture's actual blocked-worker and release counters.
                @Override
                protected GeckoModelBuildTask createTask(GeckoRenderRequest request, GeckoGeoModelInstances.Instance instance) {
                    return new GeckoModelBuildTask(request, instance, assets, builders, direct, client, gpu, false) {
                        @Override
                        protected BuiltModelBuffers build() throws Exception {
                            builds.incrementAndGet();
                            if (started != null) {
                                started.countDown();
                                if (!resume.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("fixture resume timed out");
                            }
                            var bytes = direct.borrow(112);
                            bytes.putInt((int) request.sample().timeKey());
                            bytes.position(0);
                            bytes.limit(112);
                            return new BuiltModelBuffers(request.origin(), Map.of(), Map.of(MachineRenderPass.DEFAULT, bytes), Map.of(),
                                builders, direct, client, releases::incrementAndGet);
                        }
                    };
                }
            };
        }

        GeckoRenderRequest request(GeckoModelBaker.BakeMode mode) {
            return BModelFixtures.request(assets, new GeckoRenderRequest.Owner(), mode, List.of("base"), 0);
        }

        @Override
        public void close() {
            cache.close();
        }
    }
}
