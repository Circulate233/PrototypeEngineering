package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Typed payload binding avoids the unchecked component/system cast in PM's stage executor. */
public final class RecipeRequirement<C> {
    private final ResourceLocation id;
    private final RecipeRequirementType<C> type;
    private final C payload;
    private final RequirementPath rootPath;
    private final RecipeRequirement<C> definition;

    RecipeRequirement(ResourceLocation id, RecipeRequirementType<C> type, C payload) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.payload = Objects.requireNonNull(payload);
        rootPath = new RequirementPath(null, type.id(), id);
        definition = this;
    }

    private RecipeRequirement(RecipeRequirement<C> definition, C payload) {
        this.definition = definition;
        id = definition.id;
        type = definition.type;
        rootPath = definition.rootPath;
        this.payload = Objects.requireNonNull(payload);
    }

    public ResourceLocation id() { return id; }
    public RecipeRequirementType<C> type() { return type; }
    public C payload() { return payload; }

    /** Canonical immutable definition retained by effective views returned to requirement callbacks. */
    public RecipeRequirement<C> definition() { return definition; }

    /** An immutable effective view retaining identity and the original definition, without editing either. */
    public RecipeRequirement<C> withPayload(C replacement) {
        return payload == replacement ? this : new RecipeRequirement<>(definition, replacement);
    }

    /** Root paths are shared by all processes; nested wrappers add only their own ancestry. */
    public RequirementPath path(@Nullable RequirementPath parent) {
        return parent == null ? rootPath : new RequirementPath(parent, type.id(), id);
    }

    /** Structural declaration lets alternative owners establish isolation before any child effects. */
    public boolean usesContinuations() {
        if (type.system().continuedAcquisition(payload)) { return true; }
        for (var child : type.system().children(payload)) { if (child.usesContinuations()) { return true; } }
        return false;
    }

    public RequirementTransaction acquire(RecipeProcess process, RecipeStage stage) {
        return process.acquireRequirement(this, () -> {
            var overlays = process.components().get(RecipeOverlays.TYPE);
            var effective = overlays == null ? this : overlays.resolve(this, process.requirementPath());
            return switch (stage) {
                case START -> type.system().start(process, effective);
                case TICK -> type.system().tick(process, effective);
                case END -> type.system().end(process, effective);
            };
        });
    }

    /** Terminal cleanup preserves the same effective definition and nested path as normal callbacks. */
    public void cancel(RecipeProcess process) {
        process.cancelRequirement(this, () -> {
            var overlays = process.components().get(RecipeOverlays.TYPE);
            var effective = overlays == null ? this : overlays.resolve(this, process.requirementPath());
            type.system().cancel(process, effective);
        });
    }
}
