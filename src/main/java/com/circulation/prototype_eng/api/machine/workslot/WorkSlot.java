package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * Runtime state owned and updated by the machine's work slot host.
 */
public final class WorkSlot {
    private final WorkSlotDefinition definition;
    @Nullable
    private RecipeProcess process;
    @Nullable
    private ResourceLocation lastCompletedRecipe;
    private ProcessResult lastResult = ProcessResult.SUCCESS;

    public WorkSlot(WorkSlotDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    public WorkSlotDefinition definition() {
        return definition;
    }

    public String name() {
        return definition.name();
    }

    public Set<ResourceLocation> allowedRecipeGroups() {
        return definition.allowedRecipeGroups();
    }

    @Nullable
    public RecipeProcess process() {
        return process;
    }

    /** Transient successful completion hint; restoration and cancellation grant no retry authority. */
    public @Nullable ResourceLocation lastCompletedRecipe() {
        return lastCompletedRecipe;
    }

    void lastCompletedRecipe(@Nullable ResourceLocation recipe) {
        lastCompletedRecipe = recipe;
    }

    public ProcessResult result() {
        return process == null ? lastResult : process.state().result();
    }

    public WorkSlotStatus status() {
        var result = result();
        if (result instanceof ProcessResult.Failure) {
            return WorkSlotStatus.FAILED;
        }
        if (process == null) {
            return WorkSlotStatus.IDLE;
        }
        return result instanceof ProcessResult.Blocked ? WorkSlotStatus.BLOCKED : WorkSlotStatus.RUNNING;
    }

    void setState(@Nullable RecipeProcess process, ProcessResult lastResult) {
        Objects.requireNonNull(lastResult, "lastResult");
        this.process = process;
        this.lastResult = lastResult;
    }
}
