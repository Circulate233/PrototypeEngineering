package com.circulation.prototype_eng.client.render.gecko;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.file.AnimationFileLoader;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.shadowed.eliotlash.molang.MolangParser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Mutable parser/controller/processor belong to one serially borrowed owner/part model instance.
 */
public class GeckoAnimationDriver implements IAnimatable {
    private static boolean initialized;
    private final MolangParser parser = new MolangParser();
    private final RuntimeModel model = new RuntimeModel();
    private final List<String> selected;
    private final OrderedData data = new OrderedData();
    private final AnimationFactory factory = new AnimationFactory(this) {
        @Override
        public AnimationData getOrCreateAnimationData(Integer ignored) {
            return data;
        }
    };

    public GeckoAnimationDriver(IResourceManager resources, ResourceLocation location, List<String> names) {
        initialize();
        model.file = new AnimationFileLoader().loadAllAnimations(parser, location, resources);
        selected = pickAnimationNames(model.file, names);
        registerControllers(data);
    }

    /**
     * Register the precise type fetcher before submitting workers; no global mutable model or parser.
     */
    public static synchronized void initialize() {
        if (initialized) return;
        AnimationController.ModelFetcher<GeckoAnimationDriver> fetcher = animatable ->
            animatable instanceof GeckoAnimationDriver driver ? driver.model : null;
        AnimationController.addModelFetcher(fetcher);
        initialized = true;
    }

    public static List<String> pickAnimationNames(AnimationFile file, List<String> preferred) {
        var chosen = new ObjectArrayList<String>();
        for (int index3 = 0; index3 < preferred.size(); index3++) {
            var value = preferred.get(index3);
            var name = value.trim();
            if (!name.isEmpty() && file.getAnimation(name) != null) chosen.add(name);
        }
        if (!chosen.isEmpty()) return chosen;
        String first = null;
        for (var animation : file.getAllAnimations()) {
            var name = animation.animationName;
            if (name != null && (first == null || name.compareTo(first) < 0)) first = name;
        }
        return first == null ? List.of() : List.of(first);
    }

    @Override
    public AnimationFactory getFactory() {
        return factory;
    }

    @Override
    public void registerControllers(AnimationData target) {
        target.setResetSpeedInTicks(1);
        for (int i = 0; i < selected.size(); i++) {
            String animation = selected.get(i);
            var sequence = new AnimationBuilder().addAnimation(animation);
            target.addAnimationController(new AnimationController<>(this, "pe_gecko_" + i, .001f, event -> {
                event.getController().setAnimation(sequence);
                return PlayState.CONTINUE;
            }));
        }
    }

    public void apply(GeoModel geo, double ticks) {
        if (selected.isEmpty()) return;
        data.clearSnapshotCache();
        model.processor.clearModelRendererList();
        for (int index = 0; index < geo.topLevelBones.size(); index++) {
            var root = geo.topLevelBones.get(index);
            register(root);
        }
        var event = new AnimationEvent<>(this, 0, 0, 0, false, List.of());
        model.processor.preAnimationSetup(this, ticks);
        model.processor.tickAnimation(this, 0, ticks, event, parser, false);
    }

    private void register(GeoBone bone) {
        model.processor.registerModelRenderer(bone);
        for (int index2 = 0; index2 < bone.childBones.size(); index2++) {
            var child = bone.childBones.get(index2);
            register(child);
        }
    }

    /**
     * The dependency's ABI returns HashMap. LinkedHashMap preserves the required later-layer override.
     */
    @SuppressWarnings("rawtypes")
    // The actual dependency overrides return HashMap<String, raw AnimationController>; changing its invariant value type breaks the override ABI.
    private static final class OrderedData extends AnimationData {
        private final LinkedHashMap<String, AnimationController> controllers = new LinkedHashMap<>();

        @Override
        public HashMap<String, AnimationController> getAnimationControllers() {
            return controllers;
        }

        @Override
        public AnimationController addAnimationController(AnimationController controller) {
            return controllers.put(controller.getName(), controller);
        }
    }

    private static final class RuntimeModel implements IAnimatableModel<GeckoAnimationDriver> {
        private final AnimationProcessor<GeckoAnimationDriver> processor = new AnimationProcessor<>(this);
        private AnimationFile file;

        @Override
        public void setLivingAnimations(GeckoAnimationDriver entity, Integer id, AnimationEvent event) {
        }

        @Override
        public AnimationProcessor<GeckoAnimationDriver> getAnimationProcessor() {
            return processor;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            return file.getAnimation(name);
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {
        }
    }
}
