package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.machine.ParallelControllerRegistry;
import com.circulation.prototype_eng.api.machine.ParallelControllerType;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.ParallelController")
@SuppressWarnings("unused") // CraftTweaker resolves these public declaration entry points.
public final class ZenParallelController {
    private final ResourceLocation id;
    private final String translationKey;
    private final int maximum;
    @Nullable
    private ResourceLocation model;
    private boolean registered;

    private ZenParallelController(String id, String translationKey, int maximum) {
        this.id = new ResourceLocation(id);
        this.translationKey = Objects.requireNonNull(translationKey);
        if (maximum < 1) throw new IllegalArgumentException("Parallel maximum must be positive");
        this.maximum = maximum;
    }

    @ZenMethod
    public static ZenParallelController create(String id, String translationKey, int maximum) {
        return new ZenParallelController(id, translationKey, maximum);
    }

    @ZenMethod
    public ZenParallelController model(String modelId) {
        open();
        model = new ResourceLocation(modelId);
        return this;
    }

    @ZenMethod
    public void register() {
        open();
        ParallelControllerRegistry.INSTANCE.register(new ParallelControllerType(id, () -> translationKey, maximum, model));
        registered = true;
    }

    private void open() {
        if (registered) throw new IllegalStateException("Parallel controller was already registered");
    }
}
