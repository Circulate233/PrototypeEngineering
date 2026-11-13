package com.circulation.prototype_eng.integration.crafttweaker.jei;

import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/**
 * Names an actual client Java interface instance registered by an addon, without loading it here.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.jei.Page")
@SuppressWarnings("unused") // Script/addon handle entry points.
public record ZenJeiPage(ResourceLocation id) {
    public ZenJeiPage {
        Objects.requireNonNull(id);
    }

    @ZenMethod
    public static ZenJeiPage named(String id) {
        return new ZenJeiPage(new ResourceLocation(id));
    }
}
