package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayModifierDescriptor;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.OverlayModifier")
public record ZenOverlayModifier(RecipeOverlayModifierDescriptor value) {
    public ZenOverlayModifier {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenOverlayModifier create(String id, ZenOverlayCallback callback) {
        Objects.requireNonNull(callback);
        return new ZenOverlayModifier(new RecipeOverlayModifierDescriptor(ZenRecipeSupport.id(id),
            context -> callback.apply(new ZenOverlayContext(context))));
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

}
