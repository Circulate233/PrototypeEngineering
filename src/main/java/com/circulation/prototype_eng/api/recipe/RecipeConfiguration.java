package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventHandler;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Pool-wide additions. Existing input definitions remain intact so provider discovery stays valid.
 */
public final class RecipeConfiguration {
    private final List<RecipeRequirement<?>> requirements;
    private final List<GenericStack> auxiliaryInputs;
    private final boolean auxiliaryInputsSet;
    private final int durationTicks;
    private final @Nullable List<ResourceLocation> recipeGroups;
    private final int maxConcurrentThreads;
    private final @Nullable RecipeParallelismSelection parallelismSelection;

    private RecipeConfiguration(Builder builder) {
        requirements = new ObjectArrayList<>(builder.requirements);
        var ids = new ObjectOpenHashSet<ResourceLocation>(requirements.size());
        for (var requirement : requirements) {
            if (!ids.add(requirement.id()))
                throw new IllegalArgumentException("Duplicate common requirement: " + requirement.id());
        }
        auxiliaryInputs = new ObjectArrayList<>(builder.auxiliaryInputs);
        auxiliaryInputsSet = builder.auxiliaryInputsSet;
        durationTicks = builder.durationTicks;
        recipeGroups = builder.recipeGroups == null ? null : new ObjectArrayList<>(builder.recipeGroups);
        maxConcurrentThreads = builder.maxConcurrentThreads;
        parallelismSelection = builder.parallelismSelection;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RecipeConfiguration withAuxiliaryInputs(List<GenericStack> inputs) {
        return toBuilder().auxiliaryInputs(inputs).build();
    }

    RecipeConfiguration merge(RecipeConfiguration additional) {
        var merged = toBuilder();
        merged.requirements.addAll(additional.requirements);
        if (additional.auxiliaryInputsSet) merged.auxiliaryInputs(additional.auxiliaryInputs);
        if (additional.durationTicks >= 0) merged.durationTicks = additional.durationTicks;
        if (additional.recipeGroups != null) merged.recipeGroups = additional.recipeGroups;
        if (additional.maxConcurrentThreads > 0) merged.maxConcurrentThreads = additional.maxConcurrentThreads;
        if (additional.parallelismSelection != null) merged.parallelismSelection = additional.parallelismSelection;
        return merged.build();
    }

    private Builder toBuilder() {
        var builder = new Builder();
        builder.requirements.addAll(requirements);
        builder.auxiliaryInputs = auxiliaryInputs;
        builder.auxiliaryInputsSet = auxiliaryInputsSet;
        builder.durationTicks = durationTicks;
        builder.recipeGroups = recipeGroups;
        builder.maxConcurrentThreads = maxConcurrentThreads;
        builder.parallelismSelection = parallelismSelection;
        return builder;
    }

    List<RecipeRequirement<?>> materialize(ResourceLocation poolId) {
        if (auxiliaryInputs.isEmpty()) return requirements;
        var result = new ObjectArrayList<RecipeRequirement<?>>(requirements.size() + 1);
        result.addAll(requirements);
        var auxiliaryId = new ResourceLocation(poolId.getNamespace(), poolId.getPath() + "/auxiliary_inputs");
        for (var requirement : requirements) {
            if (requirement.id().equals(auxiliaryId))
                throw new IllegalArgumentException("Common requirement conflicts with auxiliary inputs: " + auxiliaryId);
        }
        result.add(RecipeRequirements.RESOURCES.requirement(
            auxiliaryId,
            new ResourceRequirement(auxiliaryInputs, List.of())));
        return result;
    }

    MachineRecipe apply(MachineRecipe recipe, List<RecipeRequirement<?>> additions) {
        if (matches(recipe, additions)) return recipe;
        var builder = RecipeBuilder.from(recipe);
        for (var addition : additions) {
            if (!recipe.orderedRequirements().contains(addition)) builder.addRequirement(addition);
        }
        if (durationTicks >= 0) builder.durationTicks(durationTicks);
        if (recipeGroups != null) builder.recipeGroups(recipeGroups);
        if (maxConcurrentThreads > 0) builder.maxConcurrentThreads(maxConcurrentThreads);
        if (parallelismSelection != null) builder.parallelismSelection(parallelismSelection);
        return builder.build();
    }

    boolean matches(MachineRecipe recipe, List<RecipeRequirement<?>> additions) {
        if (durationTicks >= 0 && recipe.durationTicks() != durationTicks
            || recipeGroups != null && (recipe.recipeGroups().size() != recipeGroups.size() || !recipe.recipeGroups().containsAll(recipeGroups))
            || maxConcurrentThreads > 0 && recipe.maxConcurrentThreads() != maxConcurrentThreads
            || parallelismSelection != null && recipe.parallelismSelection() != parallelismSelection) return false;
        for (var addition : additions) {
            if (!recipe.orderedRequirements().contains(addition)) return false;
        }
        return true;
    }

    boolean preservesInputDiscovery() {
        if (parallelismSelection != null && parallelismSelection != RecipeParallelismSelection.NONE) return false;
        for (var requirement : requirements) {
            if (requirement.type() != RecipeRequirements.RESOURCES && requirement.type() != RecipeRequirements.ENERGY)
                return false;
        }
        return true;
    }

    public static final class Builder {
        private final ObjectArrayList<RecipeRequirement<?>> requirements = new ObjectArrayList<>();
        private List<GenericStack> auxiliaryInputs = List.of();
        private boolean auxiliaryInputsSet;
        private int durationTicks = -1;
        private @Nullable List<ResourceLocation> recipeGroups;
        private int maxConcurrentThreads;
        private @Nullable RecipeParallelismSelection parallelismSelection;

        private Builder() {
        }

        public Builder requirements(Collection<? extends RecipeRequirement<?>> values) {
            requirements.clear();
            for (var value : values) requirements.add(Objects.requireNonNull(value, "Common recipe requirement"));
            return this;
        }

        public Builder addRequirement(RecipeRequirement<?> value) {
            requirements.add(Objects.requireNonNull(value, "Common recipe requirement"));
            return this;
        }

        public Builder auxiliaryInputs(List<GenericStack> inputs) {
            ResourceRequirement.validateStacks(inputs);
            auxiliaryInputs = inputs;
            auxiliaryInputsSet = true;
            return this;
        }

        public Builder durationTicks(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative recipe duration");
            durationTicks = value;
            return this;
        }

        public Builder recipeGroups(Collection<ResourceLocation> values) {
            var groups = new ObjectArrayList<ResourceLocation>(values.size());
            for (var value : values) {
                Objects.requireNonNull(value, "Common recipe group");
                if (!groups.contains(value)) groups.add(value);
            }
            recipeGroups = groups;
            return this;
        }

        public Builder maxConcurrentThreads(int value) {
            if (value < 1) throw new IllegalArgumentException("Recipe thread limit must be positive");
            maxConcurrentThreads = value;
            return this;
        }

        public Builder parallelismSelection(RecipeParallelismSelection value) {
            parallelismSelection = Objects.requireNonNull(value, "Common parallelism selection");
            return this;
        }

        public Builder event(ResourceLocation id, RecipeStage stage, RecipeEventHandler handler) {
            return addRequirement(RecipeRequirements.EVENTS.requirement(id, new RecipeEventRequirement(stage, handler)));
        }

        public RecipeConfiguration build() {
            return new RecipeConfiguration(this);
        }
    }
}
