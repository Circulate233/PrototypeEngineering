package com.circulation.prototype_eng.api.client.render;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resource declarations only; each later render owner creates its own mutable Gecko instance.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public record GeckoModelBinding(ResourceLocation geo, ResourceLocation texture, @Nullable ResourceLocation animation,
                                @Nullable String defaultAnimationName, List<String> layers,
                                double offsetX, double offsetY, double offsetZ, double yOffset,
                                MachineRenderPass pass, boolean forceGlobalRenderer,
                                @Nullable String animationStateKey, @Nullable String animationLayersStateKey) {
    public GeckoModelBinding {
        layers = new MachineAnimationState.Snapshot(0, layers).layers();
        if (defaultAnimationName != null) new MachineAnimationState.Snapshot(0, List.of(defaultAnimationName));
        animationStateKey = key(animationStateKey);
        animationLayersStateKey = key(animationLayersStateKey);
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(offsetZ) || !Double.isFinite(yOffset))
            throw new IllegalArgumentException("Model offsets must be finite");
    }

    public GeckoModelBinding(ResourceLocation geo, ResourceLocation texture) {
        this(geo, texture, null, null, List.of(), 0, 0, 0, 0, MachineRenderPass.DEFAULT, false);
    }

    public GeckoModelBinding(ResourceLocation geo, ResourceLocation texture, @Nullable ResourceLocation animation,
                             @Nullable String defaultAnimationName, List<String> layers,
                             double offsetX, double offsetY, double offsetZ, double yOffset,
                             MachineRenderPass pass, boolean forceGlobalRenderer) {
        this(geo, texture, animation, defaultAnimationName, layers, offsetX, offsetY, offsetZ, yOffset, pass, forceGlobalRenderer, null, null);
    }

    private static @Nullable String key(@Nullable String key) {
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * Layer ordering is retained; a later layer overrides conflicting bone transforms.
     */
    public List<String> effectiveLayers(@Nullable MachineAnimationState.Snapshot state) {
        return effectiveLayers(state, null);
    }

    /**
     * Typed state, synchronized data layer key, data single-name key, then declaration defaults.
     */
    public List<String> effectiveLayers(@Nullable MachineAnimationState.Snapshot state, @Nullable ComponentData.Snapshot data) {
        if (animation == null) return List.of();
        if (state != null && !state.layers().isEmpty()) return state.layers();
        if (data != null && (animationLayersStateKey != null || animationStateKey != null)) {
            var values = data.data();
            if (animationLayersStateKey != null && values.getTag(animationLayersStateKey) instanceof NBTTagString names) {
                var parsed = new ObjectArrayList<String>();
                for (var name : names.getString().split(",")) {
                    var trimmed = name.trim();
                    if (!trimmed.isEmpty()) parsed.add(trimmed);
                }
                if (!parsed.isEmpty()) return new MachineAnimationState.Snapshot(0, parsed).layers();
            }
            if (animationStateKey != null && values.getTag(animationStateKey) instanceof NBTTagString name && !name.getString().isBlank())
                return new MachineAnimationState.Snapshot(0, List.of(name.getString().trim())).layers();
        }
        if (!layers.isEmpty()) return layers;
        return defaultAnimationName == null ? List.of() : List.of(defaultAnimationName);
    }
}
