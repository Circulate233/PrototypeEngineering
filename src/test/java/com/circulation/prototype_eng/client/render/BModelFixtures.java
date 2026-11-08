package com.circulation.prototype_eng.client.render;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import software.bernie.geckolib3.geo.raw.pojo.Cube;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.raw.pojo.UvUnion;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BModelFixtures {
    public static final ResourceLocation GEO = id("geo/test.geo.json"), ANIMATION = id("animations/test.animation.json"), TEXTURE = id("textures/test.png");

    private BModelFixtures() {
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation("b_test", path);
    }

    public static GeoCube cube(boolean mirror, boolean flat) {
        var cube = new Cube();
        cube.setOrigin(new double[]{0, 0, 0});
        cube.setSize(new double[]{16, flat ? 0 : 16, 16});
        cube.setPivot(new double[]{0, 0, 0});
        cube.setRotation(new double[]{0, 0, 0});
        cube.setMirror(mirror);
        var uv = new UvUnion();
        uv.isBoxUV = true;
        uv.boxUVCoords = new double[]{0, 0};
        cube.setUv(uv);
        var properties = new ModelProperties();
        properties.setTextureWidth(64d);
        properties.setTextureHeight(64d);
        return GeoCube.createFromPojoCube(cube, properties, 0d, mirror);
    }

    public static GeoBone bone(String name, boolean cubes) {
        var bone = new GeoBone();
        bone.name = name;
        if (cubes) bone.childCubes.add(cube(false, false));
        return bone;
    }

    public static GeoModel model(GeoBone root) {
        var model = new GeoModel();
        model.topLevelBones = List.of(root);
        return model;
    }

    public static GeckoRenderRequest request(ModelAssets assets, GeckoRenderRequest.Owner owner, GeckoModelBaker.BakeMode mode, List<String> layers, long time) {
        var binding = new GeckoModelBinding(GEO, TEXTURE, ANIMATION, "idle", List.of(), 0, 0, 0, 0, MachineRenderPass.DEFAULT, false);
        return new GeckoRenderRequest(owner, new StructureRenderAnchors.Part(StructureRenderAnchors.Kind.MACHINE, id("machine"), List.of(), -1),
            new GeckoRenderRequest.Epoch(), assets.epoch(), binding, layers, mode, BlockPos.ORIGIN, BlockPos.ORIGIN,
            BlockOrientation.NORTH_UP, Vec3d.ZERO, new GeckoRenderRequest.Sample(time, 1));
    }

    public static GeckoRenderRequest change(GeckoRenderRequest previous, List<String> layers, long time) {
        return new GeckoRenderRequest(previous.owner(), previous.part(), previous.bindingEpoch(), previous.resourceEpoch(), previous.binding(),
            layers, previous.mode(), previous.position(), previous.origin(), previous.orientation(), previous.localOffset(), new GeckoRenderRequest.Sample(time, 1));
    }

    public static byte[] png() throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff336699);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        image.flush();
        return out.toByteArray();
    }

    public static MemoryResources resources(String animation) throws IOException {
        var resources = new MemoryResources();
        resources.files.put(TEXTURE, png());
        resources.put(ANIMATION, animation);
        resources.put(GEO, """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
            "bones":[{"name":"root","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]}]}]}
            """);
        return resources;
    }

    public static final class MemoryResources implements IResourceManager {
        public final Map<ResourceLocation, byte[]> files = new Object2ObjectOpenHashMap<>();
        public int selections, reads, closes;

        public void put(ResourceLocation location, String contents) {
            files.put(location, contents.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public @NotNull Set<String> getResourceDomains() {
            return Set.of("b_test");
        }

        @SuppressWarnings("ReturnOfInnerClass")
        // The test resource must retain this manager's read and exact-close counters.
        @Override
        public @NotNull IResource getResource(@NotNull ResourceLocation location) throws IOException {
            var bytes = files.get(location);
            if (bytes == null) throw new IOException("Missing test source: " + location);
            selections++;
            var input = new ByteArrayInputStream(bytes) {
                @Override
                public synchronized int read(byte[] data, int offset, int length) {
                    reads++;
                    return super.read(data, offset, length);
                }
            };
            return new IResource() {
                boolean closed;

                @Override
                public @NotNull ResourceLocation getResourceLocation() {
                    return location;
                }

                @Override
                public @NotNull InputStream getInputStream() {
                    return input;
                }

                @Override
                public boolean hasMetadata() {
                    return false;
                }

                @Override
                public <T extends IMetadataSection> @Nullable T getMetadata(@NotNull String section) {
                    return null;
                }

                @Override
                public @NotNull String getResourcePackName() {
                    return "CPU test fixture";
                }

                @Override
                public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    closes++;
                    input.close();
                }
            };
        }

        @Override
        public @NotNull List<IResource> getAllResources(@NotNull ResourceLocation location) throws IOException {
            return List.of(getResource(location));
        }
    }

    /** Native policy fixture: it models maps/slots only and never calls a GL function. */
    public static class CpuUploader extends ReusableModelVboUploader {
        public final Int2IntOpenHashMap deletes = new Int2IntOpenHashMap(), unmaps = new Int2IntOpenHashMap();
        public final List<Integer> draws = new ArrayList<>();
        public final List<byte[]> uploads = new ArrayList<>();
        public int next, failUnmapId;
        public boolean vbo = true;

        public CpuUploader(ModelClientThread client) {
            super(client);
        }

        @Override
        protected boolean useVbo() {
            return vbo;
        }

        @Override
        protected boolean supportsMapped() {
            return true;
        }

        @Override
        protected int createBuffer() {
            return ++next;
        }

        @Override
        protected void deleteBuffer(int id) {
            deletes.addTo(id, 1);
        }

        @Override
        protected ByteBuffer mapBuffer(int id, int capacity) {
            return ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        }

        @Override
        protected boolean unmapBuffer(int id) {
            unmaps.addTo(id, 1);
            return id != failUnmapId;
        }

        @Override
        protected void uploadBuffer(int id, ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            uploads.add(bytes);
        }

        @Override
        protected void drawBuffer(int id, int vertices) {
            draws.add(vertices);
        }

        @Override
        protected void drawClient(ByteBuffer bytes, int vertices) {
            draws.add(vertices);
        }
    }
}
