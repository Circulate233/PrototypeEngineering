package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.EventContext")
public final class ZenRecipeEventContext {
    private final RecipeProcess process;
    private final RecipeStage stage;

    public ZenRecipeEventContext(RecipeProcess process, RecipeStage stage) {
        this.process = process;
        this.stage = stage;
    }

    @ZenGetter("recipeId")
    public String recipeId() {
        return process.recipe().id().toString();
    }

    @ZenGetter("stage")
    public String stage() {
        return stage.name();
    }

    @ZenGetter("parallelism")
    public int parallelism() {
        return RecipeParallelism.effective(process);
    }

    @ZenGetter("data")
    public ZenComponentData data() {
        var component = process.components().get(DataComponents.PROCESS);
        if (component == null) throw new IllegalStateException("Process data component was not declared");
        return new ZenComponentData(component);
    }
}
