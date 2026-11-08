package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Concrete PM BLOCK_MODEL path. Every Minecraft model callback and GL operation stays on the client owner.
 */
public final class PreviewBlockModels implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final StructurePreviews previews;
    private final PreviewBlockBufferPool buffers = new PreviewBlockBufferPool();
    private final PreviewBlockModelCache cache;
    private final PreviewSampleBlockModels sampleModels;
    private final WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();
    private final FloatBuffer colors = BufferUtils.createFloatBuffer(4);
    private StructurePreviewModel bakingModel;
    private PreviewBlockAccess sceneAccess;
    private Object sceneToken;
    private Integer sceneLayer;
    private boolean closed;

    public PreviewBlockModels(StructurePreviews previews) {
        this.previews = Objects.requireNonNull(previews);
        cache = new PreviewBlockModelCache(this::current, () -> new Baker(Objects.requireNonNull(bakingModel)), 8);
        sampleModels = new PreviewSampleBlockModels((model, cell) -> {
            var token = cache.epochToken();
            var cursor = new PreviewBlockQuadCursor(model, cell, () -> cache.currentEpoch(token) && current(model), sceneAccess);
            return sink -> cursor.step((_, data) -> sink.accept(data));
        });
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Block-model presentation requires its client owner thread");
    }

    private boolean current(StructurePreviewModel model) {
        return !closed && !previews.closed() && previews.current(model);
    }

    public void invalidate() {
        requireOwner();
        Throwable failure = null;
        try {
            cache.invalidate();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            sampleModels.invalidate();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        PreviewBlockModelCache.rethrow(failure);
    }

    /**
     * Static VBOs only when no C sample filter is active; filtered/no-VBO/pending paths consume C samples exactly.
     */
    public List<StructureProjectionState.Sample> render(StructurePreviewModel model, List<StructureProjectionState.Sample> samples,
                                                        StructurePreviews.Projection intent, double x, double y, double z) {
        requireOwner();
        if (!current(model) || previews.projection() != intent) return List.of();
        var options = intent.options();
        PreviewBlockModelCache.Ready ready = null;
        boolean staticVbo = staticGeometry(options) && OpenGlHelper.useVbo();
        if (staticVbo) {
            bakingModel = model;
            try {
                ready = cache.advance(model, options.blockModelBuildVisitsPerFrame());
            } finally {
                bakingModel = null;
            }
        }
        if (!current(model) || previews.projection() != intent) return List.of();
        // A pending static cache never causes a second synchronous bake through the fallback path.
        if (staticVbo && ready == null) return samples;
        var boxes = new ObjectArrayList<StructureProjectionState.Sample>();
        var represented = new ObjectArrayList<StructureProjectionState.Sample>();
        for (var sample : samples)
            if (PreviewBlockStates.represented(sample.cell())) represented.add(sample);
            else boxes.add(sample);
        var token = cache.epochToken();
        var before = State.capture(colors);
        Throwable failure = null;
        try {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.enableTexture2D();
                GlStateManager.disableLighting();
                GlStateManager.disableCull();
                GlStateManager.enableBlend();
                GlStateManager.enableDepth();
                GlStateManager.depthMask(false);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GlStateManager.enablePolygonOffset();
                GlStateManager.doPolygonOffset(-1.0f, -10.0f);
                Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                GlStateManager.color(1, 1, 1, 1);
                if (ready != null) drawChunks(ready, intent, x, y, z);
                else boxes.addAll(drawSamples(model, represented, intent, token, x, y, z));
            } finally {
                GlStateManager.popMatrix();
            }
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            try {
                before.restore();
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            if (failure != null) try {
                invalidate();
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            PreviewBlockModelCache.rethrow(failure);
        }
        return boxes;
    }

    /** Same actual Baker, four layer meshes, sample cursor and GL restoration as world rendering. */
    public void renderScene(StructurePreviewView view, PreviewBlockAccess access, double cameraX, double cameraY, double cameraZ) {
        requireOwner();
        var model = view.model();
        if (model == null || !access.ready() || !current(model)) return;
        Integer layer = view.threeDimensional ? null : view.layer;
        if (sceneToken != view.token() || !Objects.equals(sceneLayer, layer) || sceneAccess != access) {
            invalidate(); sceneToken = view.token(); sceneLayer = layer; sceneAccess = access;
        }
        var token = sceneToken;
        BooleanSupplier valid = () -> !closed && token == view.token() && access.current() && current(model);
        var options = new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false,
            layer == null ? null : new StructurePreviewModel.Layer(layer), 2048, 0, 4096, Double.MAX_VALUE, 4096, 96, 128);
        var intent = new StructurePreviews.Projection(model.structureId(), model.settings(), BlockPos.ORIGIN, options);
        PreviewBlockModelCache.Ready ready = null;
        if (OpenGlHelper.useVbo() && layer == null && !view.formingModelsVisible()) {
            bakingModel = model;
            try { ready = cache.advance(model, options.blockModelBuildVisitsPerFrame()); }
            finally { bakingModel = null; }
            if (ready == null) return;
        }
        if (!valid.getAsBoolean()) return;
        var before = State.capture(colors);
        Throwable failure = null;
        try {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.enableTexture2D(); GlStateManager.disableLighting(); GlStateManager.disableCull();
                GlStateManager.enableBlend(); GlStateManager.enableDepth(); GlStateManager.depthMask(true);
                GlStateManager.enableAlpha(); GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                if (ready != null) drawChunks(ready, intent, cameraX, cameraY, cameraZ, valid);
                else {
                    var samples = new ObjectArrayList<StructureProjectionState.Sample>();
                    for (var cell : view.sceneSamples(options.renderVisitsPerFrame()))
                        samples.add(new StructureProjectionState.Sample(cell, cell.position(), StructureProjectionState.Status.UNKNOWN));
                    drawSamples(model, samples, intent, cache.epochToken(), cameraX, cameraY, cameraZ, valid);
                }
            } finally { GlStateManager.popMatrix(); }
        } catch (RuntimeException | Error caught) { failure = caught; }
        finally {
            try { before.restore(); } catch (RuntimeException | Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
            if (failure != null) try { invalidate(); } catch (RuntimeException | Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
            PreviewBlockModelCache.rethrow(failure);
        }
    }

    static boolean staticGeometry(StructureProjectionOptions options) {
        return !options.hideDisplayedMatches() && options.layer() == null;
    }

    private void drawChunks(PreviewBlockModelCache.Ready ready, StructurePreviews.Projection intent, double x, double y, double z) {
        drawChunks(ready, intent, x, y, z, () -> previews.projection() == intent);
                }

    private void drawChunks(PreviewBlockModelCache.Ready ready, StructurePreviews.Projection intent, double x, double y, double z, BooleanSupplier guard) {
        var options = intent.options();
        int visits = ready.chunks().size() <= options.blockModelRenderAllChunksIfUnder()
            ? ready.chunks().size() : options.blockModelChunkVisitsPerFrame();
        var selected = cache.select(ready, visits, intent.anchor(), x, y, z, options.maxDistance());
        if (sceneAccess == null) GlStateManager.translate(intent.anchor().getX() - x, intent.anchor().getY() - y, intent.anchor().getZ() - z);
        // Native client-array stack preserves the caller's pointers/enables/texture-coordinate units.
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        int oldArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            for (int layer = 0; layer < 3; layer++) {
                for (var mesh : selected) {
                    if (!cache.valid(ready) || !guard.getAsBoolean()) return;
                    var drawable = mesh.layers[layer];
                    if (drawable != null) drawable.draw();
                }
            }
            for (var mesh : PreviewBlockModelCache.translucentOrder(selected, intent.anchor(), x, y, z)) {
                if (!cache.valid(ready) || !guard.getAsBoolean()) return;
                mesh.layers[3].draw();
            }
        } finally {
            GL11.glPopClientAttrib();
            OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, oldArrayBuffer);
        }
    }

    /**
     * Samples already paid C's visited-cell budget and layer/status filters. No new whole-model scan occurs.
     */
    private List<StructureProjectionState.Sample> drawSamples(StructurePreviewModel model, List<StructureProjectionState.Sample> samples,
                                                              StructurePreviews.Projection intent, Object token, double x, double y, double z) {
        return drawSamples(model, samples, intent, token, x, y, z, () -> previews.projection() == intent);
                }

    private List<StructureProjectionState.Sample> drawSamples(StructurePreviewModel model, List<StructureProjectionState.Sample> samples,
                                                              StructurePreviews.Projection intent, Object token, double x, double y, double z, BooleanSupplier guard) {
        BooleanSupplier valid = () -> cache.currentEpoch(token) && current(model) && guard.getAsBoolean();
        var ordered = new ObjectArrayList<>(samples);
        ordered.sort((left, right) -> Double.compare(distance(right.position(), x, y, z), distance(left.position(), x, y, z)));
        var buffer = buffers.borrow();
        boolean building = false;
        Throwable failure = null;
        List<StructureProjectionState.Sample> pending = List.of();
        try {
            if (sceneAccess == null) GlStateManager.translate(intent.anchor().getX() - x, intent.anchor().getY() - y, intent.anchor().getZ() - z);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            building = true;
            // C selected/paid cell visits already. Preparation and emission pay a separate, bounded quad work budget.
            var visible = new ObjectArrayList<StructureProjectionState.Sample>();
            for (var sample : ordered)
                if (distance(sample.position(), x, y, z) <= intent.options().maxDistance() * intent.options().maxDistance())
                    visible.add(sample);
            pending = sampleModels.present(model, visible, intent.options().blockModelBuildVisitsPerFrame(), valid, buffer::addVertexData);
            buffer.finishDrawing();
            building = false;
            if (valid.getAsBoolean()) {
                GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
                int oldArray = OpenGlHelper.vboSupported ? GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0;
                try {
                    if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
                    uploader.draw(buffer);
                } finally {
                    GL11.glPopClientAttrib();
                    if (OpenGlHelper.vboSupported) OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, oldArray);
                }
            }
        } catch (RuntimeException | Error caught) {
            failure = caught;
        } finally {
            if (building) try {
                buffer.finishDrawing();
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            try {
                buffers.recycle(buffer);
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            PreviewBlockModelCache.rethrow(failure);
        }
        return pending;
    }

    private static double distance(BlockPos position, double x, double y, double z) {
        double dx = position.getX() + 0.5 - x, dy = position.getY() + 0.5 - y, dz = position.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private final class Baker implements PreviewBlockModelCache.ChunkBaker {
        private final StructurePreviewModel model;
        private final Object token;
        private final BufferBuilder[] layerBuffers = new BufferBuilder[4];
        private boolean released;

        private Baker(StructurePreviewModel model) {
            this.model = model;
            token = cache.epochToken();
        }

        private boolean valid() {
            return !released && cache.currentEpoch(token) && current(model);
        }

        private StructurePreviewModel.Cell activeCell;
        private PreviewBlockQuadCursor cursor;

        @Override
        public boolean appendStep(StructurePreviewModel.Cell cell) {
            requireOwner();
            if (!valid()) return false;
            if (activeCell != cell) {
                activeCell = cell;
                cursor = new PreviewBlockQuadCursor(model, cell, this::valid, sceneAccess);
            }
            boolean done = cursor.step((layer, data) -> {
                if (!valid()) return;
                var buffer = layerBuffers[layer];
                if (buffer == null) {
                    buffer = buffers.borrow();
                    layerBuffers[layer] = buffer;
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                }
                buffer.addVertexData(data);
            });
            if (done) {
                activeCell = null;
                cursor = null;
            }
            return done;
        }

        @Override
        public PreviewBlockModelCache.LayerMesh upload(int layer) {
            requireOwner();
            if (!valid()) return null;
            var buffer = layerBuffers[layer];
            layerBuffers[layer] = null;
            if (buffer == null) return null;
            Vbo created = null;
            Throwable failure = null;
            int oldArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                buffer.finishDrawing();
                if (buffer.getVertexCount() > 0 && valid()) {
                    created = new Vbo(new VertexBuffer(DefaultVertexFormats.BLOCK));
                    created.buffer.bufferData(buffer.getByteBuffer());
                    if (!valid()) {
                        var obsolete = created;
                        created = null;
                        obsolete.close();
                    }
                }
            } catch (RuntimeException | Error caught) {
                failure = caught;
            }
            try {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, oldArrayBuffer);
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            try {
                buffers.recycle(buffer);
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
            if (failure != null && created != null) {
                try {
                    created.close();
                } catch (RuntimeException | Error caught) {
                    failure = PreviewBlockModelCache.merge(failure, caught);
                }
            }
            PreviewBlockModelCache.rethrow(failure);
            return created;
        }

        @Override
        public void close() {
            requireOwner();
            if (released) return;
            released = true;
            activeCell = null;
            cursor = null;
            Throwable failure = null;
            for (int layer = 0; layer < PreviewBlockModelCache.LAYERS; layer++) {
                var buffer = layerBuffers[layer];
                layerBuffers[layer] = null;
                if (buffer != null) {
                    try {
                        buffer.finishDrawing();
                    } catch (RuntimeException | Error caught) {
                        failure = PreviewBlockModelCache.merge(failure, caught);
                    }
                    try {
                        buffers.recycle(buffer);
                    } catch (RuntimeException | Error caught) {
                        failure = PreviewBlockModelCache.merge(failure, caught);
                    }
                }
            }
            PreviewBlockModelCache.rethrow(failure);
        }
    }

    private static final class Vbo implements PreviewBlockModelCache.LayerMesh {
        private VertexBuffer buffer;

        private Vbo(VertexBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void draw() {
            var owned = Objects.requireNonNull(buffer);
            owned.bindBuffer();
            try {
                GL11.glVertexPointer(3, GL11.GL_FLOAT, PreviewBlockVertices.STRIDE, 0L);
                GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, PreviewBlockVertices.STRIDE, 12L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, PreviewBlockVertices.STRIDE, 16L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glTexCoordPointer(2, GL11.GL_SHORT, PreviewBlockVertices.STRIDE, 24L);
                OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                owned.drawArrays(GL11.GL_QUADS);
            } finally {
                owned.unbindBuffer();
            }
        }

        @Override
        public void close() {
            var owned = buffer;
            buffer = null;
            if (owned != null) owned.deleteGlBuffers();
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            cache.close();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            sampleModels.invalidate();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        try {
            buffers.close();
        } catch (RuntimeException | Error caught) {
            failure = PreviewBlockModelCache.merge(failure, caught);
        }
        PreviewBlockModelCache.rethrow(failure);
    }

    /**
     * Changed server state restores through GlStateManager, including atlas bindings and both texture units.
     */
    public record State(boolean lighting, boolean cull, boolean blend, boolean depth, boolean depthMask, boolean alpha,
                         int alphaFunc, float alphaRef, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
                         boolean polygonOffset, float factor, float units, int activeTexture, int matrixMode,
                         int atlasBinding, boolean atlasEnabled, int lightBinding, boolean lightEnabled,
                         float r, float g, float b, float a) {
        public static State capture(FloatBuffer colors) {
            colors.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colors);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            int atlas = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean atlasEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            int light = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean lightEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(active);
            return new State(GL11.glIsEnabled(GL11.GL_LIGHTING), GL11.glIsEnabled(GL11.GL_CULL_FACE), GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glIsEnabled(GL11.GL_ALPHA_TEST),
                GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL), GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS),
                active, GL11.glGetInteger(GL11.GL_MATRIX_MODE), atlas, atlasEnabled, light, lightEnabled, colors.get(0), colors.get(1), colors.get(2), colors.get(3));
        }

        public void restore() {
            if (lighting) GlStateManager.enableLighting();
            else GlStateManager.disableLighting();
            if (cull) GlStateManager.enableCull();
            else GlStateManager.disableCull();
            if (blend) GlStateManager.enableBlend();
            else GlStateManager.disableBlend();
            if (depth) GlStateManager.enableDepth();
            else GlStateManager.disableDepth();
            GlStateManager.depthMask(depthMask);
            if (alpha) GlStateManager.enableAlpha();
            else GlStateManager.disableAlpha();
            GlStateManager.alphaFunc(alphaFunc, alphaRef);
            GlStateManager.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (polygonOffset) GlStateManager.enablePolygonOffset();
            else GlStateManager.disablePolygonOffset();
            GlStateManager.doPolygonOffset(factor, units);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(atlasBinding);
            if (atlasEnabled) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.bindTexture(lightBinding);
            if (lightEnabled) GlStateManager.enableTexture2D();
            else GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(activeTexture);
            GlStateManager.matrixMode(matrixMode);
            // A VBO color array invalidates the native current color even when MC's cache still says white.
            GlStateManager.resetColor();
            GlStateManager.color(r, g, b, a);
        }
    }
}
