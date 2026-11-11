package com.circulation.prototype_eng.integration.crafttweaker.render;

import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Locale;
import java.util.Objects;

/** Parameter conversion only; each registration freezes the public Java resource declaration. */
@ZenRegister
@ZenClass("mods.prototype_eng.render.GeckoBinding")
@SuppressWarnings("unused") // Public methods are resolved by the real ZenScript compiler.
public final class ZenGeckoBinding {
    private ResourceLocation geo;
    private ResourceLocation texture;
    private ResourceLocation animation;
    private String defaultAnimation;
    private final ObjectArrayList<String> layers = new ObjectArrayList<>();
    private String animationStateKey;
    private String animationLayersStateKey;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double yOffset;
    private MachineRenderPass pass = MachineRenderPass.DEFAULT;
    private boolean global;
    private GeckoStructureBinding.SliceMode sliceMode = GeckoStructureBinding.SliceMode.PER_SLICE;

    @ZenMethod public ZenGeckoBinding geo(String value) { geo = new ResourceLocation(value); return this; }
    @ZenMethod public ZenGeckoBinding texture(String value) { texture = new ResourceLocation(value); return this; }
    @ZenMethod public ZenGeckoBinding animation(String value) { animation = new ResourceLocation(value); return this; }
    @ZenMethod public ZenGeckoBinding defaultAnimation(String value) { defaultAnimation = Objects.requireNonNull(value).trim(); return this; }
    @ZenMethod public ZenGeckoBinding animationLayer(String value) { if (!value.isBlank()) layers.add(value.trim()); return this; }
    @ZenMethod public ZenGeckoBinding animationStateKey(String value) { animationStateKey = value; return this; }
    @ZenMethod public ZenGeckoBinding animationLayersStateKey(String value) { animationLayersStateKey = value; return this; }
    @ZenMethod public ZenGeckoBinding yOffset(double value) { yOffset = value; return this; }
    @ZenMethod public ZenGeckoBinding forceGlobalRenderer(boolean value) { global = value; return this; }

    @ZenMethod
    public ZenGeckoBinding modelOffset(double x, double y, double z) {
        offsetX = x;
        offsetY = y;
        offsetZ = z;
        return this;
    }

    @ZenMethod
    public ZenGeckoBinding pass(String value) {
        pass = switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "default" -> MachineRenderPass.DEFAULT;
            case "transparent" -> MachineRenderPass.TRANSPARENT;
            case "bloom" -> MachineRenderPass.BLOOM;
            case "bloom_transparent", "bloomtransparent", "bloom-transparent" -> MachineRenderPass.BLOOM_TRANSPARENT;
            default -> throw new IllegalArgumentException("Unknown machine render pass: " + value);
        };
        return this;
    }

    @ZenMethod
    public ZenGeckoBinding sliceMode(String value) {
        sliceMode = switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "per_slice", "perslice", "each" -> GeckoStructureBinding.SliceMode.PER_SLICE;
            case "structure_only" -> GeckoStructureBinding.SliceMode.STRUCTURE_ONLY;
            default -> throw new IllegalArgumentException("Unknown machine render slice mode: " + value);
        };
        return this;
    }

    GeckoModelBinding model() {
        if (geo == null || texture == null) throw new IllegalArgumentException("GeckoBinding requires geo and texture");
        return new GeckoModelBinding(geo, texture, animation, defaultAnimation, layers, offsetX, offsetY, offsetZ,
            yOffset, pass, global, animationStateKey, animationLayersStateKey);
    }

    GeckoStructureBinding structure() {
        return new GeckoStructureBinding(model(), sliceMode);
    }
}
