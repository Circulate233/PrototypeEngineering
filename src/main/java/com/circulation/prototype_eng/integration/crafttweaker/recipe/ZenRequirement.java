package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

/** Each factory produces the existing immutable, typed Java requirement. */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Requirement")
public final class ZenRequirement {
    private final RecipeRequirement<?> value;
    private final @Nullable RecipeRequirement<ResourceRequirement> resource;
    private final @Nullable RecipeRequirement<EnergyRequirement> energy;

    private ZenRequirement(RecipeRequirement<?> value, @Nullable RecipeRequirement<ResourceRequirement> resource,
                           @Nullable RecipeRequirement<EnergyRequirement> energy) {
        this.value = Objects.requireNonNull(value);
        this.resource = resource;
        this.energy = energy;
    }

    private static ZenRequirement resources(String id, ResourceRequirement payload) {
        var value = RecipeRequirements.RESOURCES.requirement(ZenRecipeSupport.id(id), payload);
        return new ZenRequirement(value, value, null);
    }

    @ZenMethod
    public static ZenRequirement resources(String id, ZenResource[] inputs, ZenResource[] outputs) {
        return resources(id, new ResourceRequirement(ZenRecipeSupport.stacks(inputs), ZenRecipeSupport.stacks(outputs)));
    }

    @ZenMethod
    public static ZenRequirement resourceIO(String id, ZenResource[] inputs, ZenResource[] inputsPerTick,
                                            ZenResource[] outputsPerTick, ZenResource[] outputs, boolean voidExcessOutputs) {
        return resources(id, new ResourceRequirement(ZenRecipeSupport.stacks(inputs), ZenRecipeSupport.stacks(inputsPerTick),
            ZenRecipeSupport.stacks(outputsPerTick), ZenRecipeSupport.stacks(outputs), voidExcessOutputs));
    }

    @ZenMethod
    public static ZenRequirement dynamicInput(String id, ZenMatcher matcher, ZenResource pattern, long amount,
                                              ZenResource[] displayedCandidates, int maxCandidates) {
        var group = new DynamicInputGroup(Objects.requireNonNull(matcher).value(), Objects.requireNonNull(pattern).key(),
            amount, ZenRecipeSupport.keys(displayedCandidates), maxCandidates);
        return resources(id, new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(group)));
    }

    @ZenMethod
    public static ZenRequirement fuzzyInput(String id, ZenResource[] candidates, long amount) {
        var group = new FuzzyInputGroup(ZenRecipeSupport.keys(candidates), amount);
        return resources(id, new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(group), List.of()));
    }

    @ZenMethod
    public static ZenRequirement energy(String id, long input, long inputPerTick, long outputPerTick, long output,
                                        boolean voidExcessOutputs) {
        var value = RecipeRequirements.ENERGY.requirement(ZenRecipeSupport.id(id),
            new EnergyRequirement(input, inputPerTick, outputPerTick, output, voidExcessOutputs));
        return new ZenRequirement(value, null, value);
    }

    @ZenMethod
    public static ZenRequirement selective(String id, ZenRequirement[] candidates, ZenSelectiveModifier[] modifiers) {
        var value = RecipeRequirements.SELECTIVE.requirement(ZenRecipeSupport.id(id),
            new SelectiveRequirement(ZenRecipeSupport.requirements(candidates), ZenRecipeSupport.modifiers(modifiers)));
        return new ZenRequirement(value, null, null);
    }

    @ZenMethod
    public static ZenRequirement checkpoint(String id, int time, ZenRequirement child, boolean scaleWithProcess) {
        var value = RecipeRequirements.CHECKPOINT.requirement(ZenRecipeSupport.id(id),
            new CheckpointRequirement(time, Objects.requireNonNull(child).value(), scaleWithProcess));
        return new ZenRequirement(value, null, null);
    }

    @ZenMethod
    public static ZenRequirement attributeAddition(String id, String attribute, String modifierId, double amount,
                                                    boolean applyAtStart, boolean removeAtEnd) {
        var value = RecipeRequirements.ATTRIBUTES.requirement(ZenRecipeSupport.id(id),
            new AttributeRequirement(ZenRecipeSupport.id(attribute),
                new NumericAttributeModifier(modifierId, amount, AttributeModifier.Operation.ADDITION), applyAtStart, removeAtEnd));
        return new ZenRequirement(value, null, null);
    }

    @ZenMethod
    public ZenRequirement chance(double percent, @Nullable String attribute) {
        var base = resource().payload();
        return resources(id(), new ResourceRequirement(base.inputs(), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
            base.voidExcessOutputs(), new ResourceChance(percent, attribute == null ? null : ZenRecipeSupport.id(attribute)),
            base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs(), base.inputAllocation()));
    }

    @ZenMethod
    public ZenRequirement singleInputSource() {
        if (resource != null) return resources(id(), resource.payload().withInputAllocation(InputAllocation.SINGLE_SOURCE));
        if (energy != null) {
            var next = RecipeRequirements.ENERGY.requirement(value.id(), energy.payload().withInputAllocation(InputAllocation.SINGLE_SOURCE));
            return new ZenRequirement(next, null, next);
        }
        throw new IllegalArgumentException("Input allocation requires a resource or energy requirement");
    }

    /** Combines resource declarations; names and matchers are captured before the recipe is constructed. */
    @ZenMethod
    public static ZenRequirement combineResources(String id, ZenRequirement[] requirements) {
        var inputs = new ObjectArrayList<GenericStack>();
        var ticksIn = new ObjectArrayList<GenericStack>();
        var ticksOut = new ObjectArrayList<GenericStack>();
        var outputs = new ObjectArrayList<GenericStack>();
        var fuzzy = new ObjectArrayList<FuzzyInputGroup>();
        var dynamic = new ObjectArrayList<DynamicInputGroup>();
        for (var requirement : requirements) {
            var base = Objects.requireNonNull(requirement).resource().payload();
            if (!base.chance().equals(ResourceChance.ALWAYS) || base.randomOutputs() != null || base.voidExcessOutputs()
                || base.inputAllocation() != InputAllocation.SPLITTABLE) {
                throw new IllegalArgumentException("Combine before applying chance, random outputs, voiding or source allocation");
            }
            inputs.addAll(base.inputs());
            ticksIn.addAll(base.inputsPerTick());
            ticksOut.addAll(base.outputsPerTick());
            outputs.addAll(base.outputs());
            fuzzy.addAll(base.fuzzyInputs());
            dynamic.addAll(base.dynamicInputs());
        }
        return resources(id, new ResourceRequirement(inputs, ticksIn, ticksOut, outputs, false, ResourceChance.ALWAYS, null, fuzzy, dynamic));
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    public RecipeRequirement<?> value() {
        return value;
    }

    public RecipeRequirement<ResourceRequirement> resource() {
        if (resource == null) throw new IllegalArgumentException("Expected a resource requirement");
        return resource;
    }

    public RecipeRequirement<EnergyRequirement> energy() {
        if (energy == null) throw new IllegalArgumentException("Expected an energy requirement");
        return energy;
    }
}
