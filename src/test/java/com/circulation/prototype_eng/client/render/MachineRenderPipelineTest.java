package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Collection;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MachineRenderPipelineTest {
    @TempDir
    Path root;

    private BuiltModelBuffers buffers(ModelClientThread client, AtomicInteger releases) {
        return new BuiltModelBuffers(BlockPos.ORIGIN, Map.of(), Map.of(), Map.of(), new ModelBufferPool(), new DirectVertexBufferPool(), client, releases::incrementAndGet);
    }

    @Test
    void transparentBothKindsUseActualCenterDistanceAndRecheckCapturedCurrentViews() {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var result = buffers(client, new AtomicInteger());
        try (var lease = Objects.requireNonNull(result.borrow())) {
            var near = new MachineModelPasses.Entry(lease, new Vec3d(1, 0, 0), () -> true);
            var far = new MachineModelPasses.Entry(lease, new Vec3d(8, 0, 0), () -> true);
            var revoked = new MachineModelPasses.Entry(lease, new Vec3d(32, 0, 0), () -> false);
            var entries = List.of(near, revoked, far);
            assertEquals(List.of(near, far), MachineModelPasses.selected(entries, Vec3d.ZERO, MachineRenderPass.DEFAULT));
            assertEquals(List.of(far, near), MachineModelPasses.selected(entries, Vec3d.ZERO, MachineRenderPass.TRANSPARENT));
            assertEquals(List.of(far, near), MachineModelPasses.selected(entries, Vec3d.ZERO, MachineRenderPass.BLOOM_TRANSPARENT));
            var previewEye = new Vec3d(16, 0, 0);
            assertEquals(List.of(near, far), MachineModelPasses.selected(entries, previewEye, MachineRenderPass.TRANSPARENT));
            assertEquals(List.of(near, far), MachineModelPasses.selected(entries, previewEye, MachineRenderPass.BLOOM_TRANSPARENT));
        } finally {
            result.retire();
        }
    }

    @Test
    void twoNativePhasesKeepTheSameReaderUntilTheSecondCallbackThenReleaseItOnce() throws Exception {
        phaseLifetime(2);
    }

    @Test
    void zeroAndOneCallbackFramesReleaseReadersAtTheirExplicitFrameEnd() throws Exception {
        phaseLifetime(0);
        phaseLifetime(1);
    }

    private void phaseLifetime(int callbacks) throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var releases = new AtomicInteger();
        try (var assets = new ModelAssets(client, BModelFixtures.resources("{}"), root);
             var cache = new ModelBuildCache(client, assets, new ModelBuildExecutor(1), new ModelBufferPool(), new DirectVertexBufferPool(), new BModelFixtures.CpuUploader(client), false)) {
            var result = buffers(client, releases);
            var input = Objects.requireNonNull(result.borrow());
            MachineModelBloom.submit(cache, List.of(new MachineModelPasses.Entry(input, Vec3d.ZERO, () -> true)));
            result.retire();
            input.close();
            assertEquals(0, releases.get());
            assertTrue(MachineModelBloom.hasPending());
            for (int phase = 0; phase < callbacks; phase++) {
                MachineModelBloom.finishPhase(true);
                assertEquals(phase == 0 ? 0 : 1, releases.get());
            }
            MachineModelBloom.clear();
            assertEquals(1, releases.get());
            assertFalse(MachineModelBloom.hasPending());
            MachineModelBloom.clear();
            assertEquals(1, releases.get());
        } finally {
            MachineModelBloom.clear();
        }
    }

    @Test
    void lateBloomFailureRevokesOwnedNativeHideAndAttemptsAllCleanupWithoutLosingErrorIdentity() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var releases = new AtomicInteger();
        var nativeEntries = new Object2ObjectOpenHashMap<BlockPos, Collection<BlockPos>>();
        var hides = new MachineModelHides() {
            @Override
            protected boolean enabled() {
                return true;
            }

            @Override
            protected Map<BlockPos, Collection<BlockPos>> entries() {
                return nativeEntries;
            }

            @Override
            protected void add(BlockPos pos, Collection<BlockPos> positions) {
                nativeEntries.put(pos, positions);
            }

            @Override
            protected void remove(BlockPos pos) {
                nativeEntries.remove(pos);
            }
        };
        var active = new AtomicBoolean(true);
        var foreignPos = new BlockPos(3, 4, 5);
        var foreign = List.of(foreignPos);
        nativeEntries.put(foreignPos, foreign);
        var hidden = Objects.requireNonNull(hides.acquire(new Object(), new Object(), BlockPos.ORIGIN, List.of(BlockPos.ORIGIN), active::get));
        var cleanupError = new AssertionError("original owner cleanup");
        var secondCleanup = new IllegalStateException("second owner cleanup");
        var attempted = new AtomicInteger();
        try (hides; hidden;
             var assets = new ModelAssets(client, BModelFixtures.resources("{}"), root);
             var cache = new ModelBuildCache(client, assets, new ModelBuildExecutor(1), new ModelBufferPool(), new DirectVertexBufferPool(), new BModelFixtures.CpuUploader(client), false)) {
            var result = buffers(client, releases);
            var input = Objects.requireNonNull(result.borrow());
            var entries = List.of(new MachineModelPasses.Entry(input, Vec3d.ZERO, active::get));
            MachineModelBloom.submit(cache, entries, () -> {
                active.set(false);
                hidden.close();
                attempted.incrementAndGet();
                throw cleanupError;
            });
            MachineModelBloom.submit(cache, entries, () -> {
                attempted.incrementAndGet();
                throw secondCleanup;
            });
            result.retire();
            input.close();
            assertEquals(0, releases.get());
            assertSame(cleanupError, assertThrows(AssertionError.class, () -> MachineModelBloom.finishPhase(false)));
            assertEquals(2, attempted.get());
            assertArrayEquals(new Throwable[]{secondCleanup}, cleanupError.getSuppressed());
            assertFalse(active.get());
            assertFalse(nativeEntries.containsKey(BlockPos.ORIGIN));
            assertSame(foreign, nativeEntries.get(foreignPos));
            assertEquals(1, releases.get());
            assertFalse(MachineModelBloom.hasPending());
        } finally {
            MachineModelBloom.clear();
        }
    }

    @Test
    void nativeDrawFailureEndsTheWholeDeferredBatchWithoutLeavingAnUnpairedPhase() throws Exception {
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        var releases = new AtomicInteger();
        try (var assets = new ModelAssets(client, BModelFixtures.resources("{}"), root);
             var cache = new ModelBuildCache(client, assets, new ModelBuildExecutor(1), new ModelBufferPool(), new DirectVertexBufferPool(), new BModelFixtures.CpuUploader(client), false)) {
            var result = buffers(client, releases);
            var input = Objects.requireNonNull(result.borrow());
            MachineModelBloom.submit(cache, List.of(new MachineModelPasses.Entry(input, Vec3d.ZERO, () -> true)));
            result.retire();
            input.close();
            MachineModelBloom.finishPhase(false);
            assertEquals(1, releases.get());
            assertFalse(MachineModelBloom.hasPending());
        } finally {
            MachineModelBloom.clear();
        }
    }
}
