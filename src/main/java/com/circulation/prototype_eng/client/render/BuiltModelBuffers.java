package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * One cache ownership plus explicit readers cover builders, packed buffers and concrete GPU slots together.
 * Construction transfers the three completed pass maps; producers must not modify them afterwards.
 */
public final class BuiltModelBuffers {
    private final BlockPos origin;
    private final @Nullable AxisAlignedBB bounds;
    private final Map<MachineRenderPass, BufferBuilder> builders;
    private final Map<MachineRenderPass, ByteBuffer> packed;
    private final Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpu;
    private final Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> uploads = new Object2ObjectOpenHashMap<>();
    private final ModelBufferPool buffers;
    private final DirectVertexBufferPool direct;
    private final ModelClientThread client;
    private final Runnable releaseExtra;
    private final @Nullable ModelAssets assets;
    private final ModelAssets.@Nullable TextureLease texture;
    private int readers = 1;
    private boolean retired;
    private boolean releaseClaimed;

    public BuiltModelBuffers(BlockPos origin, Map<MachineRenderPass, BufferBuilder> builders,
                             Map<MachineRenderPass, ByteBuffer> packed, Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpu,
                             ModelBufferPool buffers, DirectVertexBufferPool direct, ModelClientThread client, Runnable releaseExtra) {
        this(origin, builders, packed, gpu, buffers, direct, client, releaseExtra, null, null);
    }

    public BuiltModelBuffers(BlockPos origin, Map<MachineRenderPass, BufferBuilder> builders,
                             Map<MachineRenderPass, ByteBuffer> packed, Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpu,
                             ModelBufferPool buffers, DirectVertexBufferPool direct, ModelClientThread client, Runnable releaseExtra,
                             @Nullable ModelAssets assets, ModelAssets.@Nullable TextureLease texture) {
        this(origin, builders, packed, gpu, buffers, direct, client, releaseExtra, assets, texture, null);
    }

    public BuiltModelBuffers(BlockPos origin, Map<MachineRenderPass, BufferBuilder> builders,
                             Map<MachineRenderPass, ByteBuffer> packed, Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpu,
                             ModelBufferPool buffers, DirectVertexBufferPool direct, ModelClientThread client, Runnable releaseExtra,
                             @Nullable ModelAssets assets, ModelAssets.@Nullable TextureLease texture, @Nullable AxisAlignedBB bounds) {
        this.origin = origin.toImmutable();
        this.builders = builders;
        this.packed = packed;
        this.gpu = gpu;
        this.buffers = buffers;
        this.direct = direct;
        this.client = client;
        this.releaseExtra = releaseExtra;
        this.assets = assets;
        this.texture = texture;
        this.bounds = bounds;
    }

    static Throwable append(@Nullable Throwable prior, Throwable next) {
        if (prior == null) return next;
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }

    static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure != null) throw new IllegalStateException(failure);
    }

    public synchronized @Nullable Lease borrow() {
        if (retired) return null;
        readers = Math.incrementExact(readers);
        return new Lease(this);
    }

    public void retire() {
        boolean release;
        synchronized (this) {
            if (retired) return;
            retired = true;
            readers--;
            release = claimRelease();
        }
        if (release) dispose();
    }

    private boolean claimRelease() {
        if (!retired || readers != 0 || releaseClaimed) return false;
        releaseClaimed = true;
        return true;
    }

    private void releaseReader() {
        boolean release;
        synchronized (this) {
            readers--;
            release = claimRelease();
        }
        if (release) dispose();
    }

    private synchronized Lease forkReader() {
        if (releaseClaimed || readers == 0) throw new IllegalStateException("Model result already released");
        readers = Math.incrementExact(readers);
        return new Lease(this);
    }

    private void dispose() {
        Throwable failure = null;
        for (var builder : builders.values())
            try {
                buffers.release(builder);
            } catch (RuntimeException | Error exception) {
                failure = append(failure, exception);
            }
        for (var bytes : packed.values())
            try {
                direct.release(bytes);
            } catch (RuntimeException | Error exception) {
                failure = append(failure, exception);
            }
        for (var draw : gpu.values())
            try {
                draw.close();
            } catch (RuntimeException | Error exception) {
                failure = append(failure, exception);
            }
        // All readers have exited before disposal, so no later upload can be added.
        try {
            client.execute(() -> {
                Throwable uploadFailure = null;
                for (var draw : uploads.values())
                    try {
                        draw.close();
                    } catch (RuntimeException | Error exception) {
                        uploadFailure = append(uploadFailure, exception);
                    }
                rethrow(uploadFailure);
            });
        } catch (RuntimeException | Error exception) {
            failure = append(failure, exception);
        }
        try {
            releaseExtra.run();
        } catch (RuntimeException | Error exception) {
            failure = append(failure, exception);
        }
        rethrow(failure);
    }

    public BlockPos origin() {
        return origin;
    }

    public @Nullable AxisAlignedBB bounds() {
        return bounds;
    }

    public boolean hasVertices() {
        if (!gpu.isEmpty()) return true;
        for (var builder : builders.values()) if (builder.getVertexCount() > 0) return true;
        for (var buffer : packed.values()) if (buffer.hasRemaining()) return true;
        return false;
    }

    @SuppressWarnings("DataFlowIssue") // Asset-backed buffers are constructed with their captured texture identifier.
    public boolean bindTexture() {
        return assets == null || assets.bind(texture);
    }

    @SuppressWarnings("DataFlowIssue") // Asset-backed buffers are constructed with their captured texture identifier.
    public @Nullable Object textureIdentity() {
        return assets == null ? null : assets.textureIdentity(texture);
    }

    Map<MachineRenderPass, BufferBuilder> builders() {
        return builders;
    }

    Map<MachineRenderPass, ByteBuffer> packed() {
        return packed;
    }

    Map<MachineRenderPass, ReusableModelVboUploader.GpuDraw> gpu() {
        return gpu;
    }

    @Nullable ReusableModelVboUploader.GpuDraw uploaded(MachineRenderPass pass) {
        client.requireOwner();
        return uploads.get(pass);
    }

    boolean hasUploaded(MachineRenderPass pass) {
        client.requireOwner();
        return uploads.containsKey(pass);
    }

    void uploaded(MachineRenderPass pass, ReusableModelVboUploader.GpuDraw draw) {
        client.requireOwner();
        uploads.put(pass, draw);
    }

    public static final class Lease implements AutoCloseable {
        private final BuiltModelBuffers value;
        private boolean closed;

        private Lease(BuiltModelBuffers value) {
            this.value = value;
        }

        public synchronized BuiltModelBuffers buffers() {
            if (closed) throw new IllegalStateException("Model result lease closed");
            return value;
        }

        /**
         * Existing readers may hand lifetime to a deferred phase after cache retirement.
         */
        public synchronized Lease fork() {
            if (closed) throw new IllegalStateException("Model result lease closed");
            return value.forkReader();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            value.releaseReader();
        }
    }
}
