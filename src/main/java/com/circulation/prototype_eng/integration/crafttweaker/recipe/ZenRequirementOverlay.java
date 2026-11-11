package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayContext;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Addon bridge for an existing typed Java overlay. Target/path/type/data are captured once; data
 * must obey the Java overlay's immutable payload contract and its exact codec/validator must be
 * included in the recipe. No name resolver or script-owned overlay implementation is introduced.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.RequirementOverlay")
@SuppressWarnings("unused")
public final class ZenRequirementOverlay {
    private final BiConsumer<RecipeOverlayContext, String> recipe;
    private final BiConsumer<SelectiveContext, String> selective;

    public <C, D> ZenRequirementOverlay(RecipeRequirement<C> target, RecipeOverlayType<C, D> type, D data) {
        this(target, target.path(null), type, data);
    }

    public <C, D> ZenRequirementOverlay(RecipeRequirement<C> target, RequirementPath path, RecipeOverlayType<C, D> type, D data) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(path);
        Objects.requireNonNull(type);
        Objects.requireNonNull(data);
        recipe = (context, layerId) -> context.put(target, path, layerId, type, data);
        selective = (context, layerId) -> context.putRequirementOverlay(target, path, layerId, type, data);
    }

    void put(RecipeOverlayContext context, String layerId) {
        recipe.accept(context, layerId);
    }

    void put(SelectiveContext context, String layerId) {
        selective.accept(context, layerId);
    }
}
