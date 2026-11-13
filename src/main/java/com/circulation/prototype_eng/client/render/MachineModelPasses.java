package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * One concrete pass pipeline shared by the native runtime and the preview's original camera matrices.
 */
public final class MachineModelPasses {
    private MachineModelPasses() {
    }

    /**
     * Callers retain and finally close input leases; a deferred native Bloom phase takes its own forks.
     */
    public static void draw(ModelBuildCache cache, List<Entry> entries, Vec3d camera, boolean worldBloom) {
        draw(cache, entries, camera, camera, worldBloom);
    }

    /**
     * Preview matrices already place the camera, but transparent sorting still needs its model-space eye.
     */
    public static void draw(ModelBuildCache cache, List<Entry> entries, Vec3d translationCamera, Vec3d sortingCamera, boolean worldBloom) {
        draw(cache, entries, translationCamera, sortingCamera, worldBloom, () -> {
        });
    }

    /**
     * Runtime callers capture their actual state/owner scope for a deferred native failure.
     */
    public static void draw(ModelBuildCache cache, List<Entry> entries, Vec3d translationCamera, Vec3d sortingCamera, boolean worldBloom, Runnable revoke) {
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate(-translationCamera.x, -translationCamera.y, -translationCamera.z);
            drawPass(cache, entries, sortingCamera, MachineRenderPass.DEFAULT, !worldBloom);
            drawPass(cache, entries, sortingCamera, MachineRenderPass.TRANSPARENT, !worldBloom);
            if (worldBloom && MachineModelBloom.enabled()) MachineModelBloom.submit(cache, entries, revoke);
            else drawBloom(cache, entries, sortingCamera);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        try {
            GlStateManager.popMatrix();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    static void drawBloom(ModelBuildCache cache, List<Entry> entries, Vec3d camera) {
        drawPass(cache, entries, camera, MachineRenderPass.BLOOM, true);
        drawPass(cache, entries, camera, MachineRenderPass.BLOOM_TRANSPARENT, true);
    }

    static void drawPass(ModelBuildCache cache, List<Entry> entries, Vec3d camera, MachineRenderPass pass, boolean fullbright) {
        boolean transparent = pass == MachineRenderPass.TRANSPARENT || pass == MachineRenderPass.BLOOM_TRANSPARENT;
        float oldX = OpenGlHelper.lastBrightnessX, oldY = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        Throwable failure = null;
        try {
            GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
            if (transparent) {
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(false);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                for (var entry : selected(entries, camera, pass))
                    drawSingle(cache, entry, pass, fullbright ? 0xF000F0 : entry.combinedLight);
            } else {
                Map<Bucket, List<Entry>> groups = new Object2ObjectLinkedOpenHashMap<>();
                for (var entry : entries) {
                    if (!entry.current.getAsBoolean()) continue;
                    var buffers = entry.lease.buffers();
                    var key = new Bucket(buffers.origin(), buffers.textureIdentity(), fullbright ? 0xF000F0 : entry.combinedLight);
                    groups.computeIfAbsent(key, _ -> new ObjectArrayList<>()).add(entry);
                }
                for (var group : groups.entrySet()) drawGroup(cache, group.getValue(), pass, group.getKey().light);
            }
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, oldX, oldY);
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        try {
            GL11.glPopAttrib();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    static List<Entry> selected(List<Entry> entries, Vec3d camera, MachineRenderPass pass) {
        var selected = new ObjectArrayList<Entry>();
        for (var entry : entries) if (entry.current.getAsBoolean()) selected.add(entry);
        if (pass == MachineRenderPass.TRANSPARENT || pass == MachineRenderPass.BLOOM_TRANSPARENT)
            selected.sort(Comparator.comparingDouble((Entry entry) -> entry.center.squareDistanceTo(camera)).reversed());
        return selected;
    }

    private static void drawGroup(ModelBuildCache cache, List<Entry> entries, MachineRenderPass pass, int light) {
        var leases = new ObjectArrayList<BuiltModelBuffers.Lease>();
        for (var entry : entries) if (entry.current.getAsBoolean()) leases.add(entry.lease);
        if (leases.isEmpty()) return;
        var origin = leases.getFirst().buffers().origin();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light & 65535, light >>> 16);
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate(origin.getX(), origin.getY(), origin.getZ());
            cache.uploader().drawMultiple(leases, pass);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            GlStateManager.popMatrix();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    private static void drawSingle(ModelBuildCache cache, Entry entry, MachineRenderPass pass, int light) {
        if (!entry.current.getAsBoolean()) return;
        var origin = entry.lease.buffers().origin();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light & 65535, light >>> 16);
        GlStateManager.pushMatrix();
        Throwable failure = null;
        try {
            GlStateManager.translate(origin.getX(), origin.getY(), origin.getZ());
            cache.uploader().draw(entry.lease, pass);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            GlStateManager.popMatrix();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    public record Entry(BuiltModelBuffers.Lease lease, Vec3d center, BooleanSupplier current, int combinedLight) {
        public Entry(BuiltModelBuffers.Lease lease, Vec3d center, BooleanSupplier current) {
            this(lease, center, current, 0xF000F0);
        }
    }

    private record Bucket(BlockPos origin, Object texture, int light) {
    }
}
