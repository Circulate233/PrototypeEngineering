package com.circulation.prototype_eng.client.render.gecko;

import net.minecraft.client.resources.IResourceManager;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib3.file.GeoModelLoader;
import software.bernie.geckolib3.geo.render.built.GeoModel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * An instance is held by one exact cache slot; retirement cannot insert it into a successor slot.
 */
public final class GeckoGeoModelInstances {
    private GeckoGeoModelInstances() {
    }

    @FunctionalInterface
    public interface ModelOperation<T> {
        T bake(GeoModel model, GeckoModelBaker baker, @Nullable GeckoAnimatedBoneIndex index) throws Exception;
    }

    public static final class Instance {
        private final GeckoRenderRequest base;
        private final IResourceManager resources;
        private final GeckoModelBaker baker;
        private @Nullable GeoModel model;
        private @Nullable GeckoAnimatedBoneIndex index;
        private @Nullable GeckoAnimationDriver animation;

        public Instance(GeckoRenderRequest base, IResourceManager resources, GeckoModelBaker baker) {
            this.base = base;
            this.resources = resources;
            this.baker = baker;
        }

        /**
         * Serial entry also protects the per-instance primitive cube and estimate caches.
         */
        public synchronized <T> T bake(GeckoRenderRequest request, ModelOperation<T> operation) throws Exception {
            if (!base.sameBase(request)) throw new IllegalArgumentException("Foreign model instance base");
            if (model == null) model = new GeoModelLoader().loadModel(resources, request.binding().geo());
            var location = request.binding().animation();
            if (location != null && index == null) {
                try (var resource = resources.getResource(location);
                     var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    index = GeckoAnimatedBoneIndex.read(reader);
                }
            }
            if (location != null && request.mode() == GeckoModelBaker.BakeMode.ANIMATED_ONLY) {
                if (animation == null) animation = new GeckoAnimationDriver(resources, location, request.layers());
                animation.apply(model, request.sample().ticks());
            }
            return operation.bake(model, baker, index);
        }
    }
}
