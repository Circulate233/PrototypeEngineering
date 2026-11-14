package com.circulation.prototype_eng.client.render;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.Loader;

import java.util.List;

/**
 * The two native post-processing draws share forks; retirement cannot return their buffers between phases.
 */
public final class MachineModelBloom {
    private static ObjectArrayList<Batch> pending = new ObjectArrayList<>();
    private static ObjectArrayList<Batch> reusable;
    private static boolean secondPhase;

    private MachineModelBloom() {
    }

    public static boolean enabled() {
        return (Loader.isModLoaded("gregtech") || Loader.isModLoaded("lumenized")) && GregTechMachineBloomHooks.available();
    }

    static void submit(ModelBuildCache cache, List<MachineModelPasses.Entry> entries) {
        submit(cache, entries, () -> {
        });
    }

    static void submit(ModelBuildCache cache, List<MachineModelPasses.Entry> entries, Runnable revoke) {
        var retained = new ObjectArrayList<MachineModelPasses.Entry>();
        try {
            for (int index5 = 0; index5 < entries.size(); index5++) {
                var entry = entries.get(index5);
                if (entry.current().getAsBoolean()) {
                    var lease = entry.lease().fork();
                    try {
                        retained.add(new MachineModelPasses.Entry(lease, entry.center(), entry.current(), entry.combinedLight()));
                    } catch (RuntimeException | Error failure) {
                        try {
                            lease.close();
                        } catch (RuntimeException | Error cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                }
            }
            pending.add(new Batch(cache, retained, revoke));
        } catch (RuntimeException | Error failure) {
            for (int index2 = 0; index2 < retained.size(); index2++) {
                var entry = retained.get(index2);
                try {
                    entry.lease().close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    static boolean hasPending() {
        return !pending.isEmpty();
    }

    static void draw(Vec3d camera) {
        Throwable failure = null;
        boolean pushed = false;
        try {
            GlStateManager.pushMatrix();
            pushed = true;
            GlStateManager.translate(-camera.x, -camera.y, -camera.z);
            for (int index = 0; index < pending.size(); index++) {
                var batch = pending.get(index);
                MachineModelPasses.drawBloom(batch.cache, batch.entries, camera);
            }
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        if (pushed) try {
            GlStateManager.popMatrix();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        try {
            finishPhase(failure == null);
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    static void finishPhase(boolean success) {
        if (success) {
            if (secondPhase) clear();
            else secondPhase = true;
            return;
        }
        Throwable failure = null;
        // Capture the batch identities before a revocation callback can mutate the pending queue.

        var elements = new ObjectArrayList<>(pending);
        for (int index3 = 0; index3 < elements.size(); index3++) {
            var batch = elements.get(index3);
            try {
                batch.revoke.run();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        }

        try {
            clear();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    /**
     * Frame end also releases a skipped second phase, before any next frame begins.
     */
    public static void clear() {
        var old = pending;
        pending = reusable == null ? new ObjectArrayList<>(old.size()) : reusable;
        reusable = null;
        secondPhase = false;
        Throwable failure = null;
        for (int index4 = 0; index4 < old.size(); index4++) {
            var batch = old.get(index4);
            for (int index1 = 0; index1 < batch.entries.size(); index1++) {
                var entry = batch.entries.get(index1);
                try {
                    entry.lease().close();
                } catch (RuntimeException | Error exception) {
                    failure = BuiltModelBuffers.append(failure, exception);
                }
            }
        }
        old.clear();
        if (reusable == null) reusable = old;
        BuiltModelBuffers.rethrow(failure);
    }

    private record Batch(ModelBuildCache cache, List<MachineModelPasses.Entry> entries, Runnable revoke) {
    }
}
