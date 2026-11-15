package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import crafttweaker.annotations.ZenRegister;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.concurrent.CompletableFuture;

/**
 * Nonblocking observation. Script callback failures remain exceptional in the returned task.
 */
@SuppressWarnings("unused") // Discovered and accessed by ZenScript.
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.UpdateTask")
public record ZenRecipeUpdateTask(CompletableFuture<RecipeDefinitionUpdates.Result> future) {

    @ZenGetter("done")
    public boolean done() {
        return future.isDone();
    }

    @ZenGetter("failed")
    public boolean failed() {
        return future.isCompletedExceptionally();
    }

    @ZenGetter("result")
    public @Nullable ZenRecipeUpdateResult result() {
        var result = future.getNow(null);
        return result == null ? null : new ZenRecipeUpdateResult(result);
    }

    @ZenMethod
    public ZenRecipeUpdateTask onComplete(ZenRecipeUpdateCallback callback) {
        return new ZenRecipeUpdateTask(future.thenApply(result -> {
            callback.completed(new ZenRecipeUpdateResult(result));
            return result;
        }));
    }
}
