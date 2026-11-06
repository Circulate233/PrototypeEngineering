package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/** Typed payload binding avoids the unchecked component/system cast in PM's stage executor. */
public final class RecipeRequirement<C> {
    private final ResourceLocation id;
    private final RecipeRequirementType<C> type;
    private final C payload;

    RecipeRequirement(ResourceLocation id, RecipeRequirementType<C> type, C payload) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.payload = Objects.requireNonNull(payload);
    }

    public ResourceLocation id() { return id; }
    public RecipeRequirementType<C> type() { return type; }
    public C payload() { return payload; }

    public RequirementTransaction acquire(RecipeProcess process, RecipeStage stage) {
        return switch (stage) {
            case START -> type.system().start(process, this);
            case TICK -> type.system().tick(process, this);
            case END -> type.system().end(process, this);
        };
    }
}
