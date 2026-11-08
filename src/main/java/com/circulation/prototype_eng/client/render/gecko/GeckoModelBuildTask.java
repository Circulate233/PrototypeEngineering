package com.circulation.prototype_eng.client.render.gecko;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.BuiltModelBuffers;
import com.circulation.prototype_eng.client.render.DirectVertexBufferPool;
import com.circulation.prototype_eng.client.render.ModelAssets;
import com.circulation.prototype_eng.client.render.ModelBufferPool;
import com.circulation.prototype_eng.client.render.ModelClientThread;
import com.circulation.prototype_eng.client.render.ReusableModelVboUploader;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.AxisAlignedBB;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** A single publication state owns every successful or late result. Retiring an active task preserves its cleanup duty. */
public class GeckoModelBuildTask implements Runnable {
    private enum Status {PENDING, RUNNING, READY, FAILED, RETIRED_RUNNING, RETIRED}

    private record State(Status status, @Nullable BuiltModelBuffers result, @Nullable Throwable failure) {
    }

    private State state = new State(Status.PENDING, null, null);
    private final GeckoRenderRequest request;
    private final GeckoGeoModelInstances.Instance instance;
    private final ModelAssets assets;
    private final ModelBufferPool builders;
    private final DirectVertexBufferPool direct;
    private final ModelClientThread client;
    private final ReusableModelVboUploader uploader;
    private final boolean mapped;

    public GeckoModelBuildTask(GeckoRenderRequest request, GeckoGeoModelInstances.Instance instance, ModelAssets assets,
                               ModelBufferPool builders, DirectVertexBufferPool direct, ModelClientThread client,
                               ReusableModelVboUploader uploader, boolean mapped) {
        this.request = request;
        this.instance = instance;
        this.assets = assets;
        this.builders = builders;
        this.direct = direct;
        this.client = client;
        this.uploader = uploader;
        this.mapped = mapped;
    }

    public GeckoRenderRequest request() {
        return request;
    }

    public synchronized boolean done() {
        return state.status != Status.PENDING && state.status != Status.RUNNING && state.status != Status.RETIRED_RUNNING;
    }

    public synchronized @Nullable Throwable failure() {
        return state.failure;
    }

    public synchronized BuiltModelBuffers.@Nullable Lease borrow() {
        return state.status == Status.READY ? Objects.requireNonNull(state.result).borrow() : null;
    }

    private synchronized boolean alive() {
        return !request.owner().retired() && (state.status == Status.RUNNING || state.status == Status.PENDING);
    }

    public void retire() {
        BuiltModelBuffers previous;
        synchronized (this) {
            if (state.status == Status.RETIRED || state.status == Status.RETIRED_RUNNING) return;
            previous = state.result;
            state = new State(state.status == Status.RUNNING ? Status.RETIRED_RUNNING : Status.RETIRED, null, null);
        }
        if (previous != null) previous.retire();
    }

    @Override
    public void run() {
        synchronized (this) {
            if (state.status != Status.PENDING) return;
            if (request.owner().retired()) {
                state = new State(Status.RETIRED, null, null);
                return;
            }
            state = new State(Status.RUNNING, null, null);
        }
        BuiltModelBuffers built = null;
        Throwable failure = null;
        try {
            built = build();
        } catch (Exception | Error exception) {
            failure = exception;
        }
        boolean discard;
        synchronized (this) {
            discard = state.status == Status.RETIRED_RUNNING || request.owner().retired();
            state = discard ? new State(Status.RETIRED, null, null) : new State(failure == null ? Status.READY : Status.FAILED, built, failure);
        }
        if (discard && built != null) built.retire();
        if (failure instanceof Error error) throw error;
    }

    protected BuiltModelBuffers build() throws Exception {
        var texture = assets.texture(request.binding().texture());
        boolean transferred = false;
        BuiltModelBuffers result = null;
        Throwable failure = null;
        try {
            result = instance.bake(request, (model, baker, index) -> buildModel(model, baker, index, texture));
            transferred = true;
        } catch (Exception | Error exception) {
            failure = exception;
        }
        if (!transferred) try {
            texture.close();
        } catch (RuntimeException | Error exception) {
            failure = append(failure, exception);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        return Objects.requireNonNull(result);
    }

    private BuiltModelBuffers buildModel(GeoModel model, GeckoModelBaker baker, @Nullable GeckoAnimatedBoneIndex index,
                                         ModelAssets.TextureLease texture) {
        Set<String> active = index == null ? Set.of() : index.animatedBonesFor(request.layers());
        Set<String> potential = index == null ? Set.of() : index.allAnimatedBones();
        int[] counts = baker.estimateRoutedFilteredVertexCounts(model, active, request.mode(), potential);
        var byteCounts = new Object2IntOpenHashMap<MachineRenderPass>();
        for (int i = 0; i < counts.length; i++) {
            var pass = effective(GeckoModelBaker.pass(i));
            int bytes = Math.multiplyExact(counts[i], ReusableModelVboUploader.STRIDE);
            if (bytes > Integer.MAX_VALUE - byteCounts.getInt(pass))
                throw new ArithmeticException("Model pass size overflow");
            byteCounts.addTo(pass, bytes);
        }
        var builderOut = new Object2ObjectOpenHashMap<MachineRenderPass, BufferBuilder>();
        var packedOut = new Object2ObjectOpenHashMap<MachineRenderPass, ByteBuffer>();
        var gpuOut = new Object2ObjectOpenHashMap<MachineRenderPass, ReusableModelVboUploader.GpuDraw>();
        var targets = new Object2ObjectOpenHashMap<MachineRenderPass, Target>();
        var finished = new ReferenceOpenHashSet<BufferBuilder>();
        var bounds = new Bounds();
        boolean transferred = false;
        Throwable failure = null;
        BuiltModelBuffers result = null;
        try {
            var matrix = GeckoBakerMath.MatrixStack.forRequest(request);
            if (request.mode() == GeckoModelBaker.BakeMode.ANIMATED_ONLY) {
                baker.bakeRoutedFilteredPacked(model, matrix, pass -> {
                    var chosen = effective(pass);
                    var target = targets.get(chosen);
                    if (target == null) {
                        target = target(chosen, capacity(byteCounts.getInt(chosen)), bounds);
                        try {
                            targets.put(chosen, target);
                        } catch (RuntimeException | Error acquisitionFailure) {
                            try {
                                target.abort();
                            } catch (RuntimeException | Error exception) {
                                acquisitionFailure.addSuppressed(exception);
                            }
                            throw acquisitionFailure;
                        }
                    }
                    return target::write;
                }, active, request.mode(), potential);
                for (var item : targets.entrySet()) item.getValue().finish(item.getKey(), packedOut, gpuOut);
            } else {
                baker.bakeRoutedFiltered(model, matrix, pass -> builderOut.computeIfAbsent(effective(pass), chosen -> {
                    var buffer = builders.borrow(capacity(byteCounts.getInt(chosen)));
                    try {
                        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
                        return buffer;
                    } catch (RuntimeException | Error acquisitionFailure) {
                        try {
                            builders.release(buffer);
                        } catch (RuntimeException | Error exception) {
                            acquisitionFailure.addSuppressed(exception);
                        }
                        throw acquisitionFailure;
                    }
                }), active, request.mode(), potential);
                for (var buffer : builderOut.values()) {
                    buffer.finishDrawing();
                    finished.add(buffer);
                    var data = buffer.getByteBuffer().asIntBuffer();
                    for (int vertex = 0; vertex < buffer.getVertexCount(); vertex++)
                        bounds.add(data.get(vertex * 7), data.get(vertex * 7 + 1), data.get(vertex * 7 + 2));
                }
            }
            result = new BuiltModelBuffers(request.origin(), builderOut, packedOut, gpuOut, builders, direct, client,
                texture::close, assets, texture, bounds.world(request));
            transferred = true;
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        if (!transferred) {
            for (var target : targets.values())
                try {
                    target.abort();
                } catch (RuntimeException | Error exception) {
                    failure = append(failure, exception);
                }
            for (var buffer : builderOut.values()) {
                try {
                    if (!finished.contains(buffer)) buffer.finishDrawing();
                } catch (RuntimeException | Error exception) {
                    failure = append(failure, exception);
                }
                try {
                    builders.release(buffer);
                } catch (RuntimeException | Error exception) {
                    failure = append(failure, exception);
                }
            }
            for (var buffer : packedOut.values())
                try {
                    direct.release(buffer);
                } catch (RuntimeException | Error exception) {
                    failure = append(failure, exception);
                }
            for (var draw : gpuOut.values())
                try {
                    draw.close();
                } catch (RuntimeException | Error exception) {
                    failure = append(failure, exception);
                }
        }
        rethrow(failure);
        return result;
    }

    private static int capacity(int bytes) {
        return Math.max(32 * 1024, Math.addExact(bytes, 16 * 1024));
    }

    private MachineRenderPass effective(MachineRenderPass routed) {
        return request.binding().pass() == MachineRenderPass.DEFAULT ? routed : request.binding().pass();
    }

    private Target target(MachineRenderPass pass, int capacity, Bounds bounds) {
        if (mapped) {
            var ticket = client.call(() -> alive() ? uploader.tryMap(request, pass, capacity) : null);
            if (ticket != null) try {
                return new Target(ticket, null, ticket.writerView(), bounds);
            } catch (RuntimeException | Error failure) {
                try {
                    client.execute(() -> {
                        if (!ticket.finished()) uploader.finish(ticket, 0, false);
                    });
                } catch (RuntimeException | Error exception) {
                    failure.addSuppressed(exception);
                }
                throw failure;
            }
        }
        var bytes = direct.borrow(capacity);
        try {
            return new Target(null, bytes, bytes.asIntBuffer(), bounds);
        } catch (RuntimeException | Error failure) {
            try {
                direct.release(bytes);
            } catch (RuntimeException | Error exception) {
                failure.addSuppressed(exception);
            }
            throw failure;
        }
    }

    private final class Target {
        private final ReusableModelVboUploader.@Nullable Ticket ticket;
        private final @Nullable ByteBuffer bytes;
        private final IntBuffer writer;
        private final Bounds bounds;
        private boolean consumed;

        Target(ReusableModelVboUploader.@Nullable Ticket ticket, @Nullable ByteBuffer bytes, IntBuffer writer, Bounds bounds) {
            this.ticket = ticket;
            this.bytes = bytes;
            this.writer = writer;
            this.bounds = bounds;
        }

        void write(int[] source, int offset, int length) {
            writer.put(source, offset, length);
            for (int index = offset; index < offset + length; index += 7)
                bounds.add(source[index], source[index + 1], source[index + 2]);
        }

        void finish(MachineRenderPass pass, Map<MachineRenderPass, ByteBuffer> packedOut,
                    Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpuOut) {
            int count = Math.multiplyExact(writer.position(), 4);
            if (ticket != null) {
                try {
                    var draw = client.call(() -> uploader.finish(ticket, count, alive()));
                    if (draw != null) try {
                        gpuOut.put(pass, draw);
                    } catch (RuntimeException | Error failure) {
                        try {
                            draw.close();
                        } catch (RuntimeException | Error exception) {
                            failure.addSuppressed(exception);
                        }
                        throw failure;
                    }
                } finally {
                    consumed = ticket.finished();
                }
            } else {
                var output = Objects.requireNonNull(bytes);
                if (count == 0) {
                    consumed = true;
                    direct.release(output);
                    return;
                }
                output.position(0);
                output.limit(count);
                packedOut.put(pass, output);
                consumed = true;
            }
        }

        void abort() {
            if (consumed) return;
            if (ticket != null) {
                // If a finish wakeup was rejected, retain the original ticket in the owner's cleanup drain.
                client.execute(() -> {
                    if (!ticket.finished()) uploader.finish(ticket, 0, false);
                });
                consumed = true;
            } else {
                consumed = true;
                direct.release(Objects.requireNonNull(bytes));
            }
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        void add(int packedX, int packedY, int packedZ) {
            double x = Float.intBitsToFloat(packedX), y = Float.intBitsToFloat(packedY), z = Float.intBitsToFloat(packedZ);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Non-finite model vertex");
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        @Nullable AxisAlignedBB world(GeckoRenderRequest request) {
            if (minX == Double.POSITIVE_INFINITY) return null;
            var origin = request.origin();
            return new AxisAlignedBB(minX + origin.getX(), minY + origin.getY(), minZ + origin.getZ(),
                maxX + origin.getX(), maxY + origin.getY(), maxZ + origin.getZ());
        }
    }

    private static Throwable append(@Nullable Throwable prior, Throwable next) {
        if (prior == null) return next;
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }
}
