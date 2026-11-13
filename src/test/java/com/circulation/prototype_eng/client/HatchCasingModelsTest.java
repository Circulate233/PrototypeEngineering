package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.client.render.CasingTextureType;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelBlock;
import net.minecraft.client.renderer.block.model.ModelRotation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.Test;
import team.chisel.ctm.api.model.IModelCTM;
import team.chisel.ctm.api.texture.ICTMTexture;
import team.chisel.ctm.api.util.TextureInfo;
import team.chisel.ctm.client.model.ModelBakedCTM;
import team.chisel.ctm.client.texture.ctx.TextureContextCTM;
import team.chisel.ctm.client.texture.render.TextureCTM;
import team.chisel.ctm.client.texture.type.TextureTypeNormal;
import team.chisel.ctm.client.util.CTMLogic;
import team.chisel.ctm.client.util.Dir;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchCasingModelsTest {
    private static IBakedModel model(String name, ModelRotation rotation) throws Exception {
        var resource = "/assets/prototype_eng/models/block/" + name + ".json";
        ModelBlock definition;
        try (var input = new InputStreamReader(Objects.requireNonNull(HatchCasingModelsTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8)) {
            definition = ModelBlock.deserialize(input);
        }
        var textures = new HashMap<String, ICTMTexture<?>>();
        var quads = new ArrayList<BakedQuad>();
        var bakery = new FaceBakery();
        for (var element : definition.getElements()) {
            for (var face : element.mapFaces.entrySet()) {
                var sprite = new Sprite(definition.resolveTextureName(face.getValue().texture));
                try (var input = HatchCasingModelsTest.class.getResourceAsStream("/assets/prototype_eng/textures/" + sprite.getIconName().split(":")[1] + ".png.mcmeta")) {
                    boolean casing = input != null && new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("prototype_eng:casing");
                    textures.put(sprite.getIconName(), casing ? new CasingTextureType().makeTexture(info(sprite.getIconName(), BlockRenderLayer.CUTOUT))
                        : TextureTypeNormal.INSTANCE.makeTexture(info(sprite.getIconName(), BlockRenderLayer.CUTOUT)));
                }
                quads.add(bakery.makeBakedQuad(element.positionFrom, element.positionTo, face.getValue(), sprite, face.getKey(),
                    rotation, element.partRotation, false, element.shade));
            }
        }
        IModelCTM ctm = proxy(IModelCTM.class, (method, arguments) -> "getTexture".equals(method) ? textures.get((String) arguments[0]) : null);
        IBakedModel parent = proxy(IBakedModel.class, (method, arguments) -> "getQuads".equals(method)
            ? arguments[1] == null ? quads : List.of() : null);
        return wrapped(ctm, parent);
    }

    private static ModelBakedCTM wrapped(IModelCTM model, IBakedModel parent) {
        return new ModelBakedCTM(model, parent) {
            @Override
            public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
                throw new AssertionError("Membership must not recursively enter CTM rendering: " + state + ", " + side + ", " + rand);
            }
        };
    }

    private static TextureInfo info(String name, BlockRenderLayer layer) {
        return new TextureInfo(new TextureAtlasSprite[]{new Sprite(name), new Sprite(name + "-ctm")}, Optional.empty(), layer);
    }

    private static IBlockState state(String name) {
        return proxy(IBlockState.class, (method, ignoredArguments) -> "toString".equals(method) ? name : null);
    }

    private static IBlockAccess world(Map<BlockPos, IBlockState> states, IBlockState empty) {
        return proxy(IBlockAccess.class, (method, arguments) -> "getBlockState".equals(method) ? states.getOrDefault((BlockPos) arguments[0], empty) : null);
    }

    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == arguments[0];
            return call.invoke(method.getName(), arguments);
        }));
    }

    @Test
    void actualSixtyFourHatchModelsKeepAllCasingFacesAcrossRotationsAndCacheOnlyOnce() throws Exception {
        var sources = new ArrayList<String>(64);
        for (var family : List.of("material_hatch", "energy_hatch"))
            for (var mode : List.of("input", "output", "io"))
                for (int tier = 1; tier <= 10; tier++) sources.add(family + "/" + mode + "/lv" + tier);
        sources.addAll(List.of("me_input_assembly", "me_output_assembly", "me_interface", "me_pattern_assembly"));
        var models = new IdentityHashMap<IBlockState, IBakedModel>(192);
        for (var source : sources) {
            for (var rotation : List.of(ModelRotation.X0_Y0, ModelRotation.X90_Y90, ModelRotation.X270_Y180)) {
                var state = state(source + "/" + rotation);
                models.put(state, model(source, rotation));
            }
        }
        var reads = new AtomicInteger();
        var cache = new HatchCasingModels.FaceCache(state -> {
            reads.incrementAndGet();
            return HatchCasingModels.findFaces(state, models.get(state));
        });
        for (int repeat = 0; repeat < 3; repeat++)
            for (var state : models.keySet())
                for (var face : EnumFacing.VALUES) assertTrue(cache.casingFace(state, face));
        assertEquals(192, reads.get());
        cache.clear();
        for (var state : models.keySet()) assertTrue(cache.casingFace(state, EnumFacing.NORTH));
        assertEquals(384, reads.get());
    }

    @Test
    void nativeContextsConnectDifferentFamiliesAndLayersSymmetricallyAndPreserveObscuredCorners() throws Exception {
        var state = state("shell");
        var neighbor = state("me_output");
        var empty = state("empty");
        var source = model("me_input_assembly", ModelRotation.X0_Y0);
        var cache = new HatchCasingModels.FaceCache(candidate -> candidate == empty ? 0 : HatchCasingModels.findFaces(candidate, source));
        for (var layer : List.of(BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT)) {
            var texture = new TextureCTM<>(new CasingTextureType(), info("casing", layer)) {
                @Override
                public boolean connectTo(CTMLogic ignoredLogic, IBlockState from, IBlockState to, EnumFacing face) {
                    return cache.casingFace(from, face) && cache.casingFace(to, face);
                }
            };
            for (var face : EnumFacing.VALUES) {
                var origin = BlockPos.ORIGIN;
                var top = Dir.TOP.applyConnection(origin, face);
                var right = Dir.RIGHT.applyConnection(origin, face);
                var corner = Dir.TOP_RIGHT.applyConnection(origin, face);
                var blocks = new BlockPos2ObjectMap<IBlockState>();
                blocks.put(origin, state);
                blocks.put(top, neighbor);
                blocks.put(right, neighbor);
                var world = world(blocks, empty);
                var context = new TextureContextCTM(state, world, origin, texture);
                assertTrue(context.getCTM(face).connected(Dir.TOP));
                assertTrue(context.getCTM(face).connected(Dir.RIGHT));
                assertFalse(context.getCTM(face).connected(Dir.TOP_RIGHT));
                assertTrue(new TextureContextCTM(neighbor, world, top, texture).getCTM(face).connected(Dir.BOTTOM));
                blocks.put(corner, neighbor);
                assertTrue(new TextureContextCTM(state, world, origin, texture).getCTM(face).connected(Dir.TOP_RIGHT));
                blocks.put(top.offset(face), neighbor);
                assertFalse(new TextureContextCTM(state, world, origin, texture).getCTM(face).connected(Dir.TOP));
            }
        }
    }

    @Test
    void faceMembershipAndReloadCannotPublishAnOldLookup() throws Exception {
        var source = model("me_input_assembly", ModelRotation.X0_Y0);
        var fixture = state("only-east-casing");
        var ctm = (ModelBakedCTM) source;
        var parent = ctm.getParent(0);
        IBakedModel filtered = proxy(IBakedModel.class, (method, arguments) -> {
            if (!"getQuads".equals(method)) return null;
            if (arguments[1] != null) return List.of();
            return parent.getQuads(fixture, null, 0).stream()
                         .filter(quad -> quad.getFace() == EnumFacing.EAST).toList();
        });
        var onlyEast = wrapped(ctm.getModel(), filtered);
        assertEquals(1 << EnumFacing.EAST.ordinal(), HatchCasingModels.findFaces(fixture, onlyEast));

        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var cache = new HatchCasingModels.FaceCache(ignored -> {
            if (reads.getAndIncrement() > 0) return 0;
            started.countDown();
            try {
                assertTrue(finish.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return 63;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> cache.casingFace(fixture, EnumFacing.NORTH));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            cache.clear();
            assertFalse(cache.casingFace(fixture, EnumFacing.NORTH));
            finish.countDown();
            assertTrue(old.get(10, TimeUnit.SECONDS));
            assertFalse(cache.casingFace(fixture, EnumFacing.NORTH));
        }
    }

    private interface Call {
        Object invoke(String name, Object[] arguments);
    }

    private static final class Sprite extends TextureAtlasSprite {
        private Sprite(String name) {
            super(name);
            setIconWidth(16);
            setIconHeight(16);
            initSprite(16, 16, 0, 0, false);
        }
    }
}
