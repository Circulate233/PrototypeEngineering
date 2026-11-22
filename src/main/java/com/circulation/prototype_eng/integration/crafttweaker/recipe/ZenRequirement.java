package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

/**
 * Each factory produces the existing immutable, typed Java requirement.
 */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Requirement")
public final class ZenRequirement {
    private final RecipeRequirement<?> value;
    private final @Nullable RecipeRequirement<ResourceRequirement> resource;
    private final @Nullable RecipeRequirement<EnergyRequirement> energy;

    /**
     * Addons retain their immutable Java payload/system; no type-name lookup occurs here.
     */
    public ZenRequirement(RecipeRequirement<?> value) {
        this(value, builtinResource(value), builtinEnergy(value));
    }

    private ZenRequirement(RecipeRequirement<?> value, @Nullable RecipeRequirement<ResourceRequirement> resource,
                           @Nullable RecipeRequirement<EnergyRequirement> energy) {
        this.value = value;
        this.resource = resource;
        this.energy = energy;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable RecipeRequirement<ResourceRequirement> builtinResource(RecipeRequirement<?> value) {
        return value.type() == RecipeRequirements.RESOURCES ? (RecipeRequirement<ResourceRequirement>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable RecipeRequirement<EnergyRequirement> builtinEnergy(RecipeRequirement<?> value) {
        return value.type() == RecipeRequirements.ENERGY ? (RecipeRequirement<EnergyRequirement>) value : null;
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
    public static ZenRequirement presence(String id, ZenRequirement inputs) {
        var requirement = RecipeRequirements.RESOURCE_PRESENCE.requirement(ZenRecipeSupport.id(id),
            new ResourcePresenceRequirement(inputs.resource().payload()));
        return new ZenRequirement(requirement);
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
        var group = new DynamicInputGroup(matcher.value(), pattern.key(),
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
            new CheckpointRequirement(time, child.value(), scaleWithProcess));
        return new ZenRequirement(value, null, null);
    }

    @ZenMethod
    public static ZenRequirement attributeAddition(String id, String attribute, String modifierId, double amount,
                                                   boolean applyAtStart, boolean removeAtEnd) {
        return attribute(id, attribute, modifierId, amount, "ADDITION", applyAtStart, removeAtEnd);
    }

    /**
     * Operation is the Java API name: ADDITION, MULTIPLY_BASE or MULTIPLY_TOTAL.
     */
    @ZenMethod
    public static ZenRequirement attribute(String id, String attribute, String modifierId, double amount, String operation,
                                           boolean applyAtStart, boolean removeAtEnd) {
        var value = RecipeRequirements.ATTRIBUTES.requirement(ZenRecipeSupport.id(id),
            new AttributeRequirement(ZenRecipeSupport.id(attribute),
                ZenRecipeSupport.numericModifier(modifierId, amount, operation), applyAtStart, removeAtEnd));
        return new ZenRequirement(value, null, null);
    }

    /**
     * Combines resource declarations; names and matchers are captured before the recipe is constructed.
     */
    @ZenMethod
    public static ZenRequirement combineResources(String id, ZenRequirement[] requirements) {
        var inputs = new ObjectArrayList<GenericStack>(6);
        var ticksIn = new ObjectArrayList<GenericStack>(6);
        var ticksOut = new ObjectArrayList<GenericStack>(6);
        var outputs = new ObjectArrayList<GenericStack>(6);
        var fuzzy = new ObjectArrayList<FuzzyInputGroup>();
        var dynamic = new ObjectArrayList<DynamicInputGroup>();
        for (var requirement : requirements) {
            var base = requirement.resource().payload();
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

    /**
     * Each positive-weight entry may be picked once per output loop; repeated keys remain distinct entries.
     */
    @ZenMethod
    public ZenRequirement randomOutputs(ZenResource[] candidates, int[] weights, int pickCount) {
        if (candidates.length != weights.length)
            throw new IllegalArgumentException("Random output weights must match candidates");
        var entries = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>(candidates.length);
        for (int i = 0; i < candidates.length; i++)
            entries.add(new WeightedSampling.Weighted<>(candidates[i].stack(), weights[i]));
        var base = resource().payload();
        return resources(id(), new ResourceRequirement(base.inputs(), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
            base.voidExcessOutputs(), base.chance(), new RandomOutputPool(entries, pickCount),
            base.fuzzyInputs(), base.dynamicInputs(), base.inputAllocation()));
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
        return withInputAllocation(InputAllocation.SINGLE_SOURCE);
    }

    @ZenMethod
    public ZenRequirement customInputAllocation(ZenInputAllocation allocation) {
        return withInputAllocation(allocation.value());
    }

    private ZenRequirement withInputAllocation(InputAllocation allocation) {
        if (resource != null) return resources(id(), resource.payload().withInputAllocation(allocation));
        if (energy != null) {
            var next = RecipeRequirements.ENERGY.requirement(value.id(), energy.payload().withInputAllocation(allocation));
            return new ZenRequirement(next, null, next);
        }
        throw new IllegalArgumentException("Input allocation requires a resource or energy requirement");
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

    @ZenGetter("isResource")
    public boolean isResource() {
        return resource != null;
    }

    @ZenGetter("isEnergy")
    public boolean isEnergy() {
        return energy != null;
    }

    @ZenGetter("inputCount")
    public int inputCount() {
        var payload = resource().payload();
        return payload.inputs().size() + payload.fuzzyInputs().size() + payload.dynamicInputs().size();
    }

    @ZenMethod
    public ZenResource[] inputCandidates(int index) {
        var payload = resource().payload();
        Objects.checkIndex(index, inputCount());
        if (index < payload.inputs().size()) {
            var input = payload.inputs().get(index);
            return new ZenResource[]{new ZenResource(input.what(), input.amount())};
        }
        index -= payload.inputs().size();
        if (index < payload.fuzzyInputs().size()) {
            var group = payload.fuzzyInputs().get(index);
            var result = new ZenResource[group.candidates().size()];
            for (int i = 0; i < result.length; i++) result[i] = new ZenResource(group.candidates().get(i), group.amount());
            return result;
        }
        var group = payload.dynamicInputs().get(index - payload.fuzzyInputs().size());
        var result = new ZenResource[group.displayedCandidates().size()];
        for (int i = 0; i < result.length; i++) result[i] = new ZenResource(group.displayedCandidates().get(i), group.amount());
        return result;
    }

    @ZenGetter("outputCount")
    public int outputCount() {
        var payload = resource().payload();
        return payload.outputs().size() + (payload.randomOutputs() == null ? 0 : payload.randomOutputs().candidates().size());
    }

    @ZenMethod
    public ZenResource output(int index) {
        var payload = resource().payload();
        Objects.checkIndex(index, outputCount());
        var output = index < payload.outputs().size() ? payload.outputs().get(index)
            : Objects.requireNonNull(payload.randomOutputs()).candidates().get(index - payload.outputs().size()).value();
        return new ZenResource(output.what(), output.amount());
    }

    @ZenGetter("inputsPerTick")
    public ZenResource[] inputsPerTick() {
        return resources(resource().payload().inputsPerTick());
    }

    @ZenGetter("outputsPerTick")
    public ZenResource[] outputsPerTick() {
        return resources(resource().payload().outputsPerTick());
    }

    private static ZenResource[] resources(List<GenericStack> stacks) {
        var result = new ZenResource[stacks.size()];
        for (int i = 0; i < result.length; i++) {
            var stack = stacks.get(i);
            result[i] = new ZenResource(stack.what(), stack.amount());
        }
        return result;
    }

    @ZenGetter("energyInput")
    public long energyInput() {
        return energy().payload().input();
    }

    @ZenGetter("energyInputPerTick")
    public long energyInputPerTick() {
        return energy().payload().inputPerTick();
    }

    @ZenGetter("energyOutputPerTick")
    public long energyOutputPerTick() {
        return energy().payload().outputPerTick();
    }

    @ZenGetter("energyOutput")
    public long energyOutput() {
        return energy().payload().output();
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
