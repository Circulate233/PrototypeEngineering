package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.network.ComputerInputWork;
import com.circulation.prototype_eng.api.network.ComputerRecipeEvent;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.function.Consumer;

/**
 * Computer recipe with ordinary duration and input stages; events define all resource effects.
 */
public final class ComputerInputRecipe {
    public static final RecipeRequirementType<RecipeRequirement<?>> END_INPUT = new RecipeRequirementType<>(
        new ResourceLocation("prototype_eng", "computer_end_input"), new RecipeRequirementSystem<>() {
        @Override
        public List<RecipeRequirement<?>> children(RecipeRequirement<?> input) {
            return List.of(input);
        }

        @Override
        public RequirementTransaction end(RecipeProcess process, RecipeRequirement<RecipeRequirement<?>> requirement) {
            return requirement.payload().acquire(process, RecipeStage.START);
        }

        @Override
        public void cancel(RecipeProcess process, RecipeRequirement<RecipeRequirement<?>> requirement) {
            requirement.payload().cancel(process);
        }
    });
    private static final RecipeRequirementType<Consumer<ComputerRecipeEvent>> EVENTS = new RecipeRequirementType<>(
        new ResourceLocation("prototype_eng", "computer_recipe_event"), new RecipeRequirementSystem<>() {
        @Override
        public boolean requiresOwnerThread() {
            return true;
        }

        @Override
        public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Consumer<ComputerRecipeEvent>> requirement) {
            return event(process, requirement, RecipeStage.START);
        }

        @Override
        public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Consumer<ComputerRecipeEvent>> requirement) {
            return event(process, requirement, RecipeStage.TICK);
        }

        @Override
        public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Consumer<ComputerRecipeEvent>> requirement) {
            return event(process, requirement, RecipeStage.END);
        }
    });
    private final MachineRecipe recipe;
    private final MachineRecipe inputDefinition;
    private final RecipeProcessNbt codec;

    public ComputerInputRecipe(ResourceLocation id, String revision, int duration, List<RecipeRequirement<?>> inputs,
                               Consumer<ComputerRecipeEvent> callback) {
        this(id, revision, duration, inputs, callback, _ -> {
        });
    }

    public ComputerInputRecipe(ResourceLocation id, String revision, int duration, List<RecipeRequirement<?>> inputs,
                               Consumer<ComputerRecipeEvent> callback, Consumer<RecipeProcessNbt.Builder> codecs) {
        var requirements = new ObjectArrayList<RecipeRequirement<?>>(inputs.size() + 1);
        inputDefinition = new MachineRecipe(id, duration, inputs);
        for (int index1 = 0; index1 < inputs.size(); index1++) {
            var input = inputs.get(index1);
            validateInput(input);
            requirements.add(input);
        }
        requirements.add(EVENTS.requirement(new ResourceLocation(id.getNamespace(), id.getPath() + "/event"), callback));
        recipe = new MachineRecipe(id, duration, requirements);
        var builder = RecipeProcessNbt.builder(recipe, revision);
        codecs.accept(builder);
        codec = builder.build();
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    public MachineRecipe inputDefinition() {
        return inputDefinition;
    }

    public RecipeProcessNbt codec() {
        return codec;
    }

    /**
     * Moves START inputs to END for ordinary material and FE requirements. Child requirements must not declare TICK or END behavior.
     */
    public static RecipeRequirement<RecipeRequirement<?>> endInput(RecipeRequirement<?> input) {
        validateInput(input);
        if (input.type() == RecipeRequirements.RESOURCES && input.payload() instanceof ResourceRequirement resources && !resources.inputsPerTick().isEmpty()
            || input.type() == RecipeRequirements.ENERGY && input.payload() instanceof EnergyRequirement energy && energy.inputPerTick() != 0)
            throw new IllegalArgumentException("End inputs must declare a one-time input");
        return END_INPUT.requirement(input.id(), input);
    }

    private static <T> void validateInput(RecipeRequirement<T> input) {
        if (input.type() == RecipeRequirements.RESOURCES && input.payload() instanceof ResourceRequirement resources && (!resources.outputs().isEmpty()
            || !resources.outputsPerTick().isEmpty() || resources.randomOutputs() != null))
            throw new IllegalArgumentException("Computer input recipes cannot declare material outputs");
        if (input.type() == RecipeRequirements.ENERGY && input.payload() instanceof EnergyRequirement energy && (energy.output() != 0 || energy.outputPerTick() != 0))
            throw new IllegalArgumentException("Computer input recipes cannot declare energy outputs");

        var elements = input.type().system().children(input.payload());
        for (int index = 0; index < elements.size(); index++) {
            var child = elements.get(index);
            validateInput(child);
        }

    }

    @SuppressWarnings("DataFlowIssue")
    private static RequirementTransaction event(RecipeProcess process, RecipeRequirement<Consumer<ComputerRecipeEvent>> requirement, RecipeStage stage) {
        var computer = (ComputerController) process.owner();
        var work = computer.components().get(ComputerInputWork.TYPE);
        var before = work.effects();
        var changed = before.copy();
        requirement.payload().accept(new ComputerRecipeEvent(computer, process, stage,
            computer.networkDevice().networkData(), changed));
        return new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
                work.effects(changed);
            }

            @Override
            public void rollback() {
                work.effects(before);
            }
        };
    }
}
