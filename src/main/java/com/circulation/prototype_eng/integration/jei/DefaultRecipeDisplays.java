package com.circulation.prototype_eng.integration.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.network.resource.ComputerInputRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role.INPUT;
import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role.OUTPUT;
import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Text.of;

/**
 * MMCE's requirement-driven generation, with pool ownership and the current execution model.
 */
@SideOnly(Side.CLIENT)
public class DefaultRecipeDisplays {
    private DefaultRecipeDisplays() {
    }

    public static JeiRecipeDisplay describe(MachineRecipe recipe, RecipeJeiDisplays extensions) {
        var display = new JeiRecipeDisplay.Builder();
        display.information(of(JeiText.Duration, recipe.durationTicks(), recipe.durationTicks() / 20.0));
        var elements6 = recipe.orderedRequirements();
        for (int index6 = 0; index6 < elements6.size(); index6++) {
            var requirement = elements6.get(index6);
            describe(recipe, requirement, display, extensions);
        }
        return display.build();
    }

    private static void describe(MachineRecipe recipe, RecipeRequirement<?> requirement,
                                 JeiRecipeDisplay.Builder display, RecipeJeiDisplays extensions) {
        describe(recipe, requirement, display, extensions, JeiText.Start);
    }

    private static void describe(MachineRecipe recipe, RecipeRequirement<?> requirement,
                                 JeiRecipeDisplay.Builder display, RecipeJeiDisplays extensions, JeiText inputStage) {
        if (extensions.describeCustom(recipe, requirement, display)) {
            return;
        }
        if (requirement.type() == RecipeRequirements.RESOURCES) {
            resources(requirement, (ResourceRequirement) requirement.payload(), display, inputStage);
        } else if (requirement.type() == RecipeRequirements.RESOURCE_PRESENCE) {
            resources(requirement, ((ResourcePresenceRequirement) requirement.payload()).resources(),
                display.scoped(of(JeiText.NotConsumed)), inputStage, false);
        } else if (requirement.type() == RecipeRequirements.ENERGY) {
            energy((EnergyRequirement) requirement.payload(), display, inputStage);
        } else if (requirement.type() == ComputerInputRecipe.END_INPUT) {
            describe(recipe, (RecipeRequirement<?>) requirement.payload(), display, extensions, JeiText.End);
        } else if (requirement.type() == RecipeRequirements.CHECKPOINT) {
            var checkpoint = (CheckpointRequirement) requirement.payload();
            var nested = display.scoped(of(checkpoint.scaleWithProcess() ? JeiText.CheckpointScaled : JeiText.CheckpointUnscaled,
                requirement.id(), checkpoint.time()));
            describe(recipe, checkpoint.requirement(), nested, extensions);
        } else if (requirement.type() == RecipeRequirements.SELECTIVE) {
            var selective = (SelectiveRequirement) requirement.payload();
            if (selective.candidates().isEmpty()) {
                display.information(of(JeiText.NoCandidates, requirement.id()));
            }
            var inputs = new ObjectArrayList<JeiRecipeDisplay.CandidateGroup>();
            var outputs = new ObjectArrayList<JeiRecipeDisplay.CandidateGroup>();
            for (int i = 0; i < selective.candidates().size(); i++) {
                var nested = new JeiRecipeDisplay.Builder().scoped(of(JeiText.Alternative, requirement.id(), i + 1, selective.candidates().size()));
                if (!selective.modifiers().isEmpty()) {
                    nested = nested.scoped(of(JeiText.Modifiers));
                }
                describe(recipe, selective.candidates().get(i), nested, extensions, inputStage);
                var candidate = nested.build();
                appendAlternative(display, candidate, INPUT, inputs, requirement.id().toString());
                appendAlternative(display, candidate, OUTPUT, outputs, requirement.id().toString());
                var elements = candidate.information();
                for (int index = 0; index < elements.size(); index++) {
                    var line = elements.get(index);
                    display.information(line);
                }
            }
        } else if (requirement.type() == RecipeRequirements.ATTRIBUTES) {
            var attribute = (AttributeRequirement) requirement.payload();
            if (attribute.applyAtStart())
                display.information(of(JeiText.AttributeAdded, attribute.attribute(), attribute.modifier().id()));
            if (attribute.removeAtEnd())
                display.information(of(JeiText.AttributeRemoved, attribute.attribute(), attribute.modifier().id()));
        } else if (requirement.type() != RecipeRequirements.EVENTS) {
            display.information(of(JeiText.CustomRequirement, requirement.type().id(), requirement.id()));
        }
    }

    /**
     * Corresponding input/output slots share JEI candidate lists, including nested fuzzy groups.
     */
    private static void appendAlternative(JeiRecipeDisplay.Builder display, JeiRecipeDisplay candidate,
                                          JeiRecipeDisplay.Role role, List<JeiRecipeDisplay.CandidateGroup> slots, String name) {
        var assigned = new Reference2ObjectOpenHashMap<JeiRecipeDisplay.CandidateGroup, List<JeiRecipeDisplay.CandidateGroup>>();
        int nextSlot = 0;
        var elements13 = candidate.resources();
        for (int index13 = 0; index13 < elements13.size(); index13++) {
            var row = elements13.get(index13);
            if (row.role() != role) continue;
            var source = row.candidates();
            var targets = source == null ? null : assigned.get(source);
            if (targets == null) {
                targets = new ObjectArrayList<>();
                int count = source == null ? 1 : source.alternativeSlots();
                for (int pick = 0; pick < count; pick++) {
                    if (nextSlot == slots.size())
                        slots.add(new JeiRecipeDisplay.CandidateGroup(name + "/" + role + "/" + nextSlot));
                    targets.add(slots.get(nextSlot++));
                }
                if (source != null) assigned.put(source, targets);
            }
            for (int index10 = 0; index10 < targets.size(); index10++) {
                var target = targets.get(index10);
                var output = display.candidates(target);
                if (row.chance() != null)
                    output = output.chance(row.chance().percent(), row.chance().attributeDriven());
                output.resource(row.stack(), role, row.details().toArray(JeiRecipeDisplay.Text[]::new));
            }
        }
    }

    private static void resources(RecipeRequirement<?> requirement, ResourceRequirement resource, JeiRecipeDisplay.Builder display, JeiText inputStage) {
        resources(requirement, resource, display, inputStage, true);
    }

    private static void resources(RecipeRequirement<?> requirement, ResourceRequirement resource, JeiRecipeDisplay.Builder display,
                                  JeiText inputStage, boolean consumed) {
        var declared = display.chance(resource.chance().percent(), resource.chance().attribute() != null)
                              .scoped(of(JeiText.Chance, resource.chance().percent()));
        if (resource.chance().attribute() != null) {
            declared = declared.scoped(of(JeiText.ChanceAttribute, resource.chance().attribute()));
        }
        var elements2 = resource.inputs();
        for (int index2 = 0; index2 < elements2.size(); index2++) {
            var stack = elements2.get(index2);
            declared.resource(stack, INPUT, of(inputStage));
        }
        var elements4 = resource.inputsPerTick();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var stack = elements4.get(index4);
            declared.resource(stack, INPUT, of(JeiText.Tick));
        }
        var outputs = resource.voidExcessOutputs() ? declared.scoped(of(JeiText.VoidExcess)) : declared;
        var elements5 = resource.outputsPerTick();
        for (int index5 = 0; index5 < elements5.size(); index5++) {
            var stack = elements5.get(index5);
            outputs.resource(stack, OUTPUT, of(JeiText.Tick));
        }
        var elements3 = resource.outputs();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var stack = elements3.get(index3);
            outputs.resource(stack, OUTPUT, of(JeiText.End));
        }
        int groupIndex = 0;
        var elements11 = resource.fuzzyInputs();
        for (int index11 = 0; index11 < elements11.size(); index11++) {
            var group = elements11.get(index11);
            var groupId = requirement.id() + "/fuzzy/" + groupIndex++;
            if (group.candidates().isEmpty()) {
                declared.information(of(JeiText.NoCandidates, groupId));
            }
            var alternatives = declared.candidates(new JeiRecipeDisplay.CandidateGroup(groupId));
            var elements8 = group.candidates();
            for (int index8 = 0; index8 < elements8.size(); index8++) {
                var key = elements8.get(index8);
                alternatives.resource(new GenericStack(key, group.amount()), INPUT, of(inputStage), of(JeiText.Fuzzy, groupId));
            }
        }
        groupIndex = 0;
        var elements12 = resource.dynamicInputs();
        for (int index12 = 0; index12 < elements12.size(); index12++) {
            var group = elements12.get(index12);
            var groupId = requirement.id() + "/dynamic/" + groupIndex++;
            var dynamic = declared.candidates(new JeiRecipeDisplay.CandidateGroup(groupId))
                                  .scoped(of(JeiText.Dynamic, groupId, group.matcher().id()));
            dynamic.information(of(consumed ? JeiText.Consume : JeiText.PresentAmount, group.amount()));
            // Only explicitly supplied examples are indexed; pattern and callbacks are not inferred outputs.
            var elements7 = group.displayedCandidates();
            for (int index7 = 0; index7 < elements7.size(); index7++) {
                var key = elements7.get(index7);
                dynamic.resource(new GenericStack(key, group.amount()), INPUT, of(inputStage));
            }
        }
        var random = resource.randomOutputs();
        if (random != null && random.hasOutputs()) {
            var alternatives = outputs.candidates(new JeiRecipeDisplay.CandidateGroup(requirement.id() + "/weighted", random.pickCount()));
            var elements9 = random.candidates();
            for (int index9 = 0; index9 < elements9.size(); index9++) {
                var candidate = elements9.get(index9);
                alternatives.resource(candidate.value(), OUTPUT, of(JeiText.End),
                    of(JeiText.Weighted, requirement.id(), candidate.weight(), random.pickCount(), random.candidates().size()));
            }
        }
    }

    private static void energy(EnergyRequirement energy, JeiRecipeDisplay.Builder display, JeiText inputStage) {
        energy(inputStage == JeiText.End ? display.scoped(of(JeiText.End)) : display, energy.input(), true, inputStage);
        energy(display, energy.inputPerTick(), true, JeiText.Tick);
        var outputs = energy.voidExcessOutputs() ? display.scoped(of(JeiText.VoidExcess)) : display;
        energy(outputs, energy.outputPerTick(), false, JeiText.Tick);
        energy(outputs, energy.output(), false, JeiText.End);
    }

    private static void energy(JeiRecipeDisplay.Builder display, long amount, boolean input, JeiText stage) {
        if (amount > 0) {
            var text = stage == JeiText.Tick ? (input ? JeiText.EnergyInputPerTick : JeiText.EnergyOutputPerTick)
                : (input ? JeiText.EnergyInput : JeiText.EnergyOutput);
            display.energy(amount, input ? INPUT : OUTPUT, stage == JeiText.Tick, of(text, amount));
        }
    }
}
