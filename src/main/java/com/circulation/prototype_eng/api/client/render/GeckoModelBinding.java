package com.circulation.prototype_eng.api.client.render;

import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Resource declarations only; each later render owner creates its own mutable Gecko instance. */
public record GeckoModelBinding(ResourceLocation geo, ResourceLocation texture, @Nullable ResourceLocation animation,
                                @Nullable String defaultAnimationName, List<String> layers,
                                double offsetX, double offsetY, double offsetZ, double yOffset,
                                MachineRenderPass pass, boolean forceGlobalRenderer) {
    public GeckoModelBinding {
        Objects.requireNonNull(geo);
        Objects.requireNonNull(texture);
        Objects.requireNonNull(pass);
        layers = new MachineAnimationState.Snapshot(0, layers).layers();
        if (defaultAnimationName != null) new MachineAnimationState.Snapshot(0, List.of(defaultAnimationName));
        if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY) || !Double.isFinite(offsetZ) || !Double.isFinite(yOffset))
            throw new IllegalArgumentException("Model offsets must be finite");
    }

    public GeckoModelBinding(ResourceLocation geo, ResourceLocation texture) {
        this(geo, texture, null, null, List.of(), 0, 0, 0, 0, MachineRenderPass.DEFAULT, false);
    }

    /** Layer ordering is retained; a later layer overrides conflicting bone transforms. */
    public List<String> effectiveLayers(@Nullable MachineAnimationState.Snapshot state) {
        if (animation == null) return List.of();
        if (state != null && !state.layers().isEmpty()) return state.layers();
        if (!layers.isEmpty()) return layers;
        return defaultAnimationName == null ? List.of() : List.of(defaultAnimationName);
    }
}
