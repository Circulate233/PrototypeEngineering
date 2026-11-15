package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifiers;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.SelectiveModifier")
public record ZenSelectiveModifier(SelectiveModifierDescriptor value) {

    @ZenMethod
    public static ZenSelectiveModifier create(String id, ZenSelectiveCallback callback) {
        return new ZenSelectiveModifier(new SelectiveModifierDescriptor(ZenRecipeSupport.id(id),
            context -> callback.apply(new ZenSelectiveContext(context))));
    }

    @ZenMethod
    public static ZenSelectiveModifier speedAddition(String id, String modifierId, double amount) {
        return new ZenSelectiveModifier(SelectiveModifiers.processSpeedAddition(ZenRecipeSupport.id(id), modifierId, amount));
    }

    @ZenMethod
    public static ZenSelectiveModifier speedMultiplier(String id, String modifierId, double factor) {
        return new ZenSelectiveModifier(SelectiveModifiers.processSpeedMultiplier(ZenRecipeSupport.id(id), modifierId, factor));
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

}
