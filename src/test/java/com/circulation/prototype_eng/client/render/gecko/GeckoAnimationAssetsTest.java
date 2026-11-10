package com.circulation.prototype_eng.client.render.gecko;

import com.circulation.prototype_eng.client.render.ModelAssets;
import com.circulation.prototype_eng.client.render.ModelClientThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.bernie.geckolib3.file.AnimationFileLoader;
import software.bernie.shadowed.eliotlash.molang.MolangParser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.circulation.prototype_eng.client.render.BModelFixtures.ANIMATION;
import static com.circulation.prototype_eng.client.render.BModelFixtures.GEO;
import static com.circulation.prototype_eng.client.render.BModelFixtures.TEXTURE;
import static com.circulation.prototype_eng.client.render.BModelFixtures.bone;
import static com.circulation.prototype_eng.client.render.BModelFixtures.change;
import static com.circulation.prototype_eng.client.render.BModelFixtures.model;
import static com.circulation.prototype_eng.client.render.BModelFixtures.request;
import static com.circulation.prototype_eng.client.render.BModelFixtures.resources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeckoAnimationAssetsTest {
    static final String LAYERS = """
        {"animations":{"base":{"loop":true,"animation_length":2,"bones":{"root":{"position":[16,0,0],"rotation":[0,0,90]}}},
        "overlay":{"loop":true,"animation_length":2,"bones":{"root":{"position":[32,0,0]}}},"a_idle":{"loop":true,"animation_length":2,"bones":{}}}}
        """;
    @TempDir
    Path root;

    @Test
    void emptyIdleNamesRemainTheSameFallbackAsTheActualAnimationLoader() throws Exception {
        var index = GeckoAnimatedBoneIndex.read(new StringReader(LAYERS));
        assertEquals(List.of("a_idle"), index.pickAnimationNames(List.of("unknown")));
        assertTrue(index.animatedBonesFor(List.of()).isEmpty());
        var resources = resources(LAYERS);
        try (var assets = new ModelAssets(new ModelClientThread(Thread.currentThread(), Runnable::run), resources, root)) {
            assets.select(List.of(ANIMATION));
            var parser = new MolangParser();
            var loaded = new AnimationFileLoader().loadAllAnimations(parser, ANIMATION, assets);
            assertEquals(index.pickAnimationNames(List.of("unknown")), GeckoAnimationDriver.pickAnimationNames(loaded, List.of("unknown")));
        }
    }

    @Test
    void orderedLayersOverwriteSharedChannelsAndRetainEarlierUnsharedRotation() throws Exception {
        var resources = resources(LAYERS);
        try (var assets = new ModelAssets(new ModelClientThread(Thread.currentThread(), Runnable::run), resources, root)) {
            assets.select(List.of(ANIMATION));
            var bone = bone("root", true);
            bone.saveInitialSnapshot();
            var model = model(bone);
            var driver = new GeckoAnimationDriver(assets, ANIMATION, List.of("base", "overlay"));
            driver.apply(model, 0);
            driver.apply(model, 1);
            assertEquals(32, bone.getPositionX(), 1e-5);
            assertEquals(Math.PI / 2, bone.getRotationZ(), 1e-5);
            var reversedBone = bone("root", true);
            reversedBone.saveInitialSnapshot();
            var reversed = model(reversedBone);
            var reversedDriver = new GeckoAnimationDriver(assets, ANIMATION, List.of("overlay", "base"));
            reversedDriver.apply(reversed, 0);
            reversedDriver.apply(reversed, 1);
            assertEquals(16, reversedBone.getPositionX(), 1e-5);
            driver.apply(model, 1);
            assertEquals(32, bone.getPositionX(), 1e-5);
        }
    }

    @Test
    void exactModelInstancesOwnIndependentParsersAndResetSnapshotsForTheSameSample() throws Exception {
        var resources = resources(LAYERS);
        try (var assets = new ModelAssets(new ModelClientThread(Thread.currentThread(), Runnable::run), resources, root)) {
            assets.select(List.of(GEO, ANIMATION, TEXTURE));
            var first = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("overlay"), 0);
            var second = request(assets, new GeckoRenderRequest.Owner(), GeckoModelBaker.BakeMode.ANIMATED_ONLY, List.of("base"), 0);
            var a = new GeckoGeoModelInstances.Instance(first, assets, new GeckoModelBaker());
            var b = new GeckoGeoModelInstances.Instance(second, assets, new GeckoModelBaker());
            a.bake(first, (_, _, _) -> null);
            b.bake(second, (_, _, _) -> null);
            float x1 = a.bake(change(first, first.layers(), 1), (geo, _, _) -> geo.topLevelBones.getFirst().getPositionX());
            float x2 = b.bake(change(second, second.layers(), 1), (geo, _, _) -> geo.topLevelBones.getFirst().getPositionX());
            assertEquals(32, x1, 1e-5);
            assertEquals(16, x2, 1e-5);
            float repeated = a.bake(change(first, first.layers(), 1), (geo, _, _) -> geo.topLevelBones.getFirst().getPositionX());
            assertEquals(x1, repeated);
        }
    }

    @Test
    void ownerSelectsSourcesBeforeAPackManagerMutatesAndWorkersNeverReadTheLiveManager() throws Exception {
        var resources = resources(LAYERS);
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        try (var assets = new ModelAssets(client, resources, root)) {
            assets.select(List.of(ANIMATION));
            int chosen = resources.selections;
            resources.put(ANIMATION, "{\"animations\":{\"foreign\":{}}}");
            var read = new CompletableFuture<String>();
            Thread worker = Thread.ofPlatform().start(() -> {
                try (var resource = assets.getResource(ANIMATION); var input = resource.getInputStream()) {
                    read.complete(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception failure) {
                    read.completeExceptionally(failure);
                }
            });
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(LAYERS, read.get());
            assertEquals(chosen, resources.selections);
            assertEquals(1, resources.closes);
            assertThrows(IOException.class, () -> assets.getResource(GEO));
        }
    }

    @Test
    void retiredAssetsCannotRegisterAnOldDecodedTextureOrReopenPendingSources() throws Exception {
        var resources = resources(LAYERS);
        var calls = new AtomicInteger();
        var assets = new ModelAssets(new ModelClientThread(Thread.currentThread(), Runnable::run), resources, root) {
            @Override
            protected void register(Texture entry) {
                calls.incrementAndGet();
                throw new AssertionError("Old texture reached native upload");
            }
        };
        assets.select(List.of(TEXTURE, GEO));
        var texture = assets.texture(TEXTURE);
        assets.close();
        assertFalse(assets.bind(texture));
        assertEquals(0, calls.get());
        assertThrows(IOException.class, () -> assets.getResource(GEO));
        assertEquals(2, resources.closes);
        texture.close();
        texture.close();
    }

    @Test
    void malformedAnimationDataPropagatesInsteadOfCachingAValidEmptyIndex() {
        assertThrows(RuntimeException.class, () -> GeckoAnimatedBoneIndex.read(new StringReader("{\"animations\":[]}")));
        assertThrows(RuntimeException.class, () -> GeckoAnimatedBoneIndex.read(new StringReader("{\"animations\":{\"bad\":3}}")));
    }
}
