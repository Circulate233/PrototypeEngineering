package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

import java.util.Objects;

/** A refusal or failure retains the core result's published fact and exact session identity. */
@SuppressWarnings("unused") // Discovered and accessed by ZenScript.
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.UpdateResult")
public record ZenRecipeUpdateResult(RecipeDefinitionUpdates.Result value) {
    public ZenRecipeUpdateResult {
        Objects.requireNonNull(value);
    }

    @ZenGetter("status")
    public String status() {
        return value.status().name();
    }

    @ZenGetter("published")
    public boolean published() {
        return value.published();
    }

    @ZenGetter("session")
    public String session() {
        return value.sessionId().toString();
    }

    @ZenGetter("generation")
    public long generation() {
        return value.publication() == null ? -1 : value.publication().current().generation();
    }

    @ZenGetter("failure")
    public String failure() {
        return Objects.toString(value.failure(), "");
    }
}
