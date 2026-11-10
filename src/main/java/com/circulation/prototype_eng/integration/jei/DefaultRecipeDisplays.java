package com.circulation.prototype_eng.integration.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
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
        for (var requirement : recipe.orderedRequirements()) {
            describe(recipe, requirement, display, extensions);
        }
        return display.build();
    }

    private static void describe(MachineRecipe recipe, RecipeRequirement<?> requirement,
                                 JeiRecipeDisplay.Builder display, RecipeJeiDisplays extensions) {
        if (extensions.describeCustom(recipe, requirement, display)) {
            return;
        }
        // Exact built-in type identity is essential: an addon can use the same payload differently.
        if (requirement.type() == RecipeRequirements.RESOURCES) {
            resources(requirement, (ResourceRequirement) requirement.payload(), display);
        } else if (requirement.type() == RecipeRequirements.ENERGY) {
            energy((EnergyRequirement) requirement.payload(), display);
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
                describe(recipe, selective.candidates().get(i), nested, extensions);
                var candidate = nested.build();
                appendAlternative(display, candidate, INPUT, inputs, requirement.id().toString());
                appendAlternative(display, candidate, OUTPUT, outputs, requirement.id().toString());
                for (var line : candidate.information()) display.information(line);
            }
        } else if (requirement.type() == RecipeRequirements.ATTRIBUTES) {
            var attribute = (AttributeRequirement) requirement.payload();
            if (attribute.applyAtStart())
                display.information(of(JeiText.AttributeAdded, attribute.attribute(), attribute.modifier().id()));
            if (attribute.removeAtEnd())
                display.information(of(JeiText.AttributeRemoved, attribute.attribute(), attribute.modifier().id()));
        } else {
            display.information(of(JeiText.CustomRequirement, requirement.type().id(), requirement.id()));
        }
    }

    /** Corresponding input/output slots share JEI candidate lists, including nested fuzzy groups. */
    private static void appendAlternative(JeiRecipeDisplay.Builder display, JeiRecipeDisplay candidate,
                                          JeiRecipeDisplay.Role role, List<JeiRecipeDisplay.CandidateGroup> slots, String name) {
        var assigned = new Reference2ObjectOpenHashMap<JeiRecipeDisplay.CandidateGroup, List<JeiRecipeDisplay.CandidateGroup>>();
        int nextSlot = 0;
        for (var row : candidate.resources()) {
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
            for (var target : targets) {
                var output = display.candidates(target);
                if (row.chance() != null)
                    output = output.chance(row.chance().percent(), row.chance().attributeDriven());
                output.resource(row.stack(), role, row.details().toArray(JeiRecipeDisplay.Text[]::new));
            }
        }
    }

    private static void resources(RecipeRequirement<?> requirement, ResourceRequirement resource, JeiRecipeDisplay.Builder display) {
        var declared = display.chance(resource.chance().percent(), resource.chance().attribute() != null)
                              .scoped(of(JeiText.Chance, resource.chance().percent()));
        if (resource.chance().attribute() != null) {
            declared = declared.scoped(of(JeiText.ChanceAttribute, resource.chance().attribute()));
        }
        for (var stack : resource.inputs()) {
            declared.resource(stack, INPUT, of(JeiText.Start));
        }
        for (var stack : resource.inputsPerTick()) {
            declared.resource(stack, INPUT, of(JeiText.Tick));
        }
        var outputs = resource.voidExcessOutputs() ? declared.scoped(of(JeiText.VoidExcess)) : declared;
        for (var stack : resource.outputsPerTick()) {
            outputs.resource(stack, OUTPUT, of(JeiText.Tick));
        }
        for (var stack : resource.outputs()) {
            outputs.resource(stack, OUTPUT, of(JeiText.End));
        }
        int groupIndex = 0;
        for (var group : resource.fuzzyInputs()) {
            var groupId = requirement.id() + "/fuzzy/" + groupIndex++;
            if (group.candidates().isEmpty()) {
                declared.information(of(JeiText.NoCandidates, groupId));
            }
            var alternatives = declared.candidates(new JeiRecipeDisplay.CandidateGroup(groupId));
            for (var key : group.candidates()) {
                alternatives.resource(new GenericStack(key, group.amount()), INPUT, of(JeiText.Start), of(JeiText.Fuzzy, groupId));
            }
        }
        groupIndex = 0;
        for (var group : resource.dynamicInputs()) {
            var groupId = requirement.id() + "/dynamic/" + groupIndex++;
            var dynamic = declared.candidates(new JeiRecipeDisplay.CandidateGroup(groupId))
                                  .scoped(of(JeiText.Dynamic, groupId, group.matcher().id()));
            dynamic.information(of(JeiText.Consume, group.amount()));
            // Only explicitly supplied examples are indexed; pattern and callbacks are not inferred outputs.
            for (var key : group.displayedCandidates()) {
                dynamic.resource(new GenericStack(key, group.amount()), INPUT, of(JeiText.Start));
            }
        }
        var random = resource.randomOutputs();
        if (random != null && random.hasOutputs()) {
            var alternatives = outputs.candidates(new JeiRecipeDisplay.CandidateGroup(requirement.id() + "/weighted", random.pickCount()));
            for (var candidate : random.candidates()) {
                alternatives.resource(candidate.value(), OUTPUT, of(JeiText.End),
                    of(JeiText.Weighted, requirement.id(), candidate.weight(), random.pickCount(), random.candidates().size()));
            }
        }
    }

    private static void energy(EnergyRequirement energy, JeiRecipeDisplay.Builder display) {
        energy(display, energy.input(), true, JeiText.Start);
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
