package com.circulation.prototype_eng.api.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Typed payload binding avoids the unchecked component/system cast in PM's stage executor.
 */
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

    private static RequirementPreparation ownerFacts(RecipeProcess process, RecipeStage stage, RequirementPreparation prepared) {
        return new RequirementPreparation() {
            @Override
            public boolean ownerWorkMetered() {
                return prepared.ownerWorkMetered();
            }

            @Override
            public boolean captureOnlyPreparation() {
                return prepared.captureOnlyPreparation();
            }

            @Override
            public @Nullable RecipePureComputation computation() {
                return prepared.computation();
            }

            @Override
            public RequirementTransaction acquire() {
                return prepared.acquire();
            }

            @Override
            public void retainPureFacts() {
                prepared.retainPureFacts();
            }

            @Override
            public void withdrawPureFacts(RecipePureComputation expected) {
                process.withdrawPureFacts(stage, expected);
                prepared.withdrawPureFacts(expected);
            }
        };
    }

    public ResourceLocation id() {
        return id;
    }

    public RecipeRequirementType<C> type() {
        return type;
    }

    public C payload() {
        return payload;
    }

    /**
     * Canonical immutable definition retained by effective views returned to requirement callbacks.
     */
    public RecipeRequirement<C> definition() {
        return definition;
    }

    /**
     * An immutable effective view retaining identity and the original definition, without editing either.
     */
    public RecipeRequirement<C> withPayload(C replacement) {
        return payload == replacement ? this : new RecipeRequirement<>(definition, replacement);
    }

    /**
     * Root paths are shared by all processes; nested wrappers add only their own ancestry.
     */
    public RequirementPath path(@Nullable RequirementPath parent) {
        return parent == null ? rootPath : new RequirementPath(parent, type.id(), id);
    }

    /**
     * Structural declaration lets alternative owners establish isolation before any child effects.
     */
    public boolean usesContinuations() {
        if (type.system().continuedAcquisition(payload)) {
            return true;
        }

        var elements = type.system().children(payload);
        for (int index = 0; index < elements.size(); index++) {
            var child = elements.get(index);
            if (child.usesContinuations()) {
                return true;
            }
        }

        return false;
    }

    public RequirementTransaction acquire(RecipeProcess process, RecipeStage stage) {
        return process.acquireRequirement(this, () -> {
            var overlays = process.components().get(RecipeOverlays.TYPE);
            var effective = overlays == null ? this : overlays.resolve(this, process.requirementPath());
            if (process.usesPurePreparation()) {
                var prepared = type.system().prepare(process, effective, stage);
                if (prepared != null) {
                    return new PreparedRequirement(ownerFacts(process, stage, prepared));
                }
            }
            Supplier<RequirementTransaction> callback = () -> switch (stage) {
                case START -> type.system().start(process, effective);
                case TICK -> type.system().tick(process, effective);
                case END -> type.system().end(process, effective);
            };
            return process.usesPurePreparation() ? new PreparedRequirement(() -> process.withSynchronousRequirements(callback)) : callback.get();
        });
    }

    /**
     * Captures one nested child's effective definition at its actual candidate arrival. The binding
     * retains owner scope; its kernel alone may leave the owner. Opaque callbacks remain synchronous,
     * including any nested child calls they perform. This method neither reserves nor acquires effects.
     */
    public RequirementPreparation prepareBinding(RecipeProcess process, RecipeStage stage) {
        var path = path(process.requirementPath());
        return process.withPreparationContext(path, () -> {
            var overlays = process.components().get(RecipeOverlays.TYPE);
            var effective = overlays == null ? this : overlays.resolve(this, path);
            var prepared = type.system().prepare(process, effective, stage);
            Supplier<RequirementTransaction> callback = () -> switch (stage) {
                case START -> type.system().start(process, effective);
                case TICK -> type.system().tick(process, effective);
                case END -> type.system().end(process, effective);
            };
            return new RequirementPreparation() {
                @Override
                public boolean ownerWorkMetered() {
                    return prepared != null && prepared.ownerWorkMetered();
                }

                @Override
                public boolean captureOnlyPreparation() {
                    return prepared != null && prepared.captureOnlyPreparation();
                }

                @Override
                public @Nullable RecipePureComputation computation() {
                    return prepared == null ? null : prepared.computation();
                }

                @Override
                public void retainPureFacts() {
                    if (prepared != null) {
                        process.withPreparationContext(path, () -> {
                            prepared.retainPureFacts();
                            return null;
                        });
                    }
                }

                @Override
                public void withdrawPureFacts(RecipePureComputation expected) {
                    if (prepared != null) {
                        process.withPreparationContext(path, () -> {
                            process.withdrawPureFacts(stage, expected);
                            prepared.withdrawPureFacts(expected);
                            return null;
                        });
                    }
                }

                @Override
                public RequirementTransaction acquire() {
                    return process.acquireRequirement(RecipeRequirement.this,
                        () -> prepared == null ? process.withSynchronousRequirements(callback) : prepared.acquire());
                }
            };
        });
    }

    /**
     * Terminal cleanup preserves the same effective definition and nested path as normal callbacks.
     */
    public void cancel(RecipeProcess process) {
        process.cancelRequirement(this, () -> {
            var overlays = process.components().get(RecipeOverlays.TYPE);
            var effective = overlays == null ? this : overlays.resolve(this, process.requirementPath());
            type.system().cancel(process, effective);
        });
    }
}
