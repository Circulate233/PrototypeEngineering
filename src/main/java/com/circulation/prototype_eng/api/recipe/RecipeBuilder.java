package com.circulation.prototype_eng.api.recipe;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventHandler;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeEventRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Mutable creation state. Built recipes retain independent collections and an unchanged identity.
 */
@SuppressWarnings("UnusedReturnValue") // Java addons may chain edits; scripts can use their setter effects.
public final class RecipeBuilder {
    private final ResourceLocation id;
    private int durationTicks;
    private final ObjectArrayList<RecipeRequirement<?>> requirements = new ObjectArrayList<>();
    private final ObjectLinkedOpenHashSet<ResourceLocation> groups = new ObjectLinkedOpenHashSet<>();
    private int maxConcurrentThreads = Integer.MAX_VALUE;
    private RecipeParallelismSelection parallelismSelection = RecipeParallelismSelection.NONE;

    public RecipeBuilder(ResourceLocation id, int durationTicks) {
        this.id = Objects.requireNonNull(id, "Recipe identity");
        durationTicks(durationTicks);
    }

    public static RecipeBuilder from(MachineRecipe recipe) {
        return new RecipeBuilder(recipe.id(), recipe.durationTicks()).requirements(recipe.orderedRequirements())
                                                                     .recipeGroups(recipe.recipeGroups()).maxConcurrentThreads(recipe.maxConcurrentThreads())
                                                                     .parallelismSelection(recipe.parallelismSelection());
    }

    public ResourceLocation id() {
        return id;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public RecipeBuilder durationTicks(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative recipe duration");
        durationTicks = value;
        return this;
    }

    public List<RecipeRequirement<?>> requirements() {
        return requirements;
    }

    public RecipeBuilder requirements(Collection<? extends RecipeRequirement<?>> values) {
        if (values == requirements) return this;
        var prepared = checkedRequirements(values);
        requirements.clear();
        requirements.addAll(prepared);
        return this;
    }

    public RecipeBuilder addRequirement(RecipeRequirement<?> value) {
        requirements.add(Objects.requireNonNull(value, "Recipe requirement"));
        return this;
    }

    public RecipeBuilder addRequirements(Collection<? extends RecipeRequirement<?>> values) {
        requirements.addAll(checkedRequirements(values));
        return this;
    }

    private static List<RecipeRequirement<?>> checkedRequirements(Collection<? extends RecipeRequirement<?>> values) {
        var prepared = new ObjectArrayList<RecipeRequirement<?>>(values.size());
        for (var value : values) prepared.add(Objects.requireNonNull(value, "Recipe requirement"));
        return prepared;
    }

    public RecipeRequirement<?> requirement(ResourceLocation requirementId) {
        return requirements.get(requiredIndex(requirementId));
    }

    public ResourceRequirement resources(ResourceLocation requirementId) {
        return resource(requirement(requirementId));
    }

    public EnergyRequirement energy(ResourceLocation requirementId) {
        return energy(requirement(requirementId));
    }

    public RecipeBuilder replaceRequirement(RecipeRequirement<?> value) {
        Objects.requireNonNull(value, "Recipe requirement");
        int index = requiredIndex(value.id());
        requirements.set(index, declaration(value));
        return this;
    }

    private static <C> RecipeRequirement<C> declaration(RecipeRequirement<C> value) {
        return value.definition() == value ? value : value.type().requirement(value.id(), value.payload());
    }

    public RecipeBuilder removeRequirement(ResourceLocation requirementId) {
        requirements.remove(requiredIndex(requirementId));
        return this;
    }

    public RecipeBuilder addInput(ResourceLocation requirementId, GenericStack input) {
        Objects.requireNonNull(input, "Recipe input");
        return editResources(requirementId, true, base -> copyResources(base, appended(base.inputs(), input), base.inputsPerTick(),
            base.outputsPerTick(), base.outputs(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    public RecipeBuilder addOutput(ResourceLocation requirementId, GenericStack output) {
        Objects.requireNonNull(output, "Recipe output");
        return editResources(requirementId, true, base -> copyResources(base, base.inputs(), base.inputsPerTick(),
            base.outputsPerTick(), appended(base.outputs(), output), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    public RecipeBuilder addInputPerTick(ResourceLocation requirementId, GenericStack input) {
        Objects.requireNonNull(input, "Recipe input");
        return editResources(requirementId, true, base -> copyResources(base, base.inputs(), appended(base.inputsPerTick(), input),
            base.outputsPerTick(), base.outputs(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    public RecipeBuilder addOutputPerTick(ResourceLocation requirementId, GenericStack output) {
        Objects.requireNonNull(output, "Recipe output");
        return editResources(requirementId, true, base -> copyResources(base, base.inputs(), base.inputsPerTick(),
            appended(base.outputsPerTick(), output), base.outputs(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    /** START slots are ordered as fixed inputs, fuzzy groups, then dynamic groups. */
    public RecipeBuilder removeInput(ResourceLocation requirementId, int index) {
        return editResources(requirementId, false, base -> {
            if (index < base.inputs().size())
                return copyResources(base, without(base.inputs(), index), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
                    base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs());
            int fuzzyIndex = index - base.inputs().size();
            if (fuzzyIndex < base.fuzzyInputs().size())
                return copyResources(base, base.inputs(), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
                    base.randomOutputs(), without(base.fuzzyInputs(), fuzzyIndex), base.dynamicInputs());
            return copyResources(base, base.inputs(), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
                base.randomOutputs(), base.fuzzyInputs(), without(base.dynamicInputs(), fuzzyIndex - base.fuzzyInputs().size()));
        });
    }

    /** END slots are ordered as fixed outputs followed by weighted output candidates. */
    public RecipeBuilder removeOutput(ResourceLocation requirementId, int index) {
        return editResources(requirementId, false, base -> {
            if (index < base.outputs().size())
                return copyResources(base, base.inputs(), base.inputsPerTick(), base.outputsPerTick(), without(base.outputs(), index),
                    base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs());
            var random = base.randomOutputs();
            if (random == null) throw new IndexOutOfBoundsException("Recipe output slot: " + index);
            var replacement = new RandomOutputPool(without(random.candidates(), index - base.outputs().size()), random.pickCount());
            return copyResources(base, base.inputs(), base.inputsPerTick(), base.outputsPerTick(), base.outputs(),
                replacement, base.fuzzyInputs(), base.dynamicInputs());
        });
    }

    public RecipeBuilder removeInputPerTick(ResourceLocation requirementId, int index) {
        return editResources(requirementId, false, base -> copyResources(base, base.inputs(), without(base.inputsPerTick(), index),
            base.outputsPerTick(), base.outputs(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    public RecipeBuilder removeOutputPerTick(ResourceLocation requirementId, int index) {
        return editResources(requirementId, false, base -> copyResources(base, base.inputs(), base.inputsPerTick(),
            without(base.outputsPerTick(), index), base.outputs(), base.randomOutputs(), base.fuzzyInputs(), base.dynamicInputs()));
    }

    public RecipeBuilder clearInputs(ResourceLocation requirementId) {
        return editResources(requirementId, false, RecipeBuilder::withoutInputs);
    }

    public RecipeBuilder clearOutputs(ResourceLocation requirementId) {
        return editResources(requirementId, false, RecipeBuilder::withoutOutputs);
    }

    /** Clears top-level resource inputs without changing nested lifecycles or energy requirements. */
    public RecipeBuilder clearInputs() {
        return editAllResources(RecipeBuilder::withoutInputs);
    }

    /** Clears top-level resource outputs without changing nested lifecycles or energy requirements. */
    public RecipeBuilder clearOutputs() {
        return editAllResources(RecipeBuilder::withoutOutputs);
    }

    public RecipeBuilder energy(ResourceLocation requirementId, long input, long inputPerTick, long outputPerTick, long output) {
        int index = findIndex(requirementId);
        var base = index < 0 ? null : energy(requirements.get(index));
        var updated = base == null ? new EnergyRequirement(input, inputPerTick, outputPerTick, output, false)
            : new EnergyRequirement(input, inputPerTick, outputPerTick, output, base.voidExcessOutputs(), base.inputAllocation());
        var replacement = RecipeRequirements.ENERGY.requirement(requirementId, updated);
        if (index < 0) requirements.add(replacement);
        else requirements.set(index, replacement);
        return this;
    }

    private int findIndex(ResourceLocation requirementId) {
        Objects.requireNonNull(requirementId, "Recipe requirement identity");
        for (int i = 0; i < requirements.size(); i++)
            if (requirements.get(i).id().equals(requirementId)) return i;
        return -1;
    }

    private int requiredIndex(ResourceLocation requirementId) {
        int index = findIndex(requirementId);
        if (index < 0) throw new IllegalArgumentException("Unknown recipe requirement: " + requirementId);
        return index;
    }

    private static ResourceRequirement resource(RecipeRequirement<?> requirement) {
        if (requirement.type() != RecipeRequirements.RESOURCES)
            throw new IllegalArgumentException("Recipe requirement is not a resource requirement: " + requirement.id());
        return (ResourceRequirement) requirement.payload();
    }

    private static EnergyRequirement energy(RecipeRequirement<?> requirement) {
        if (requirement.type() != RecipeRequirements.ENERGY)
            throw new IllegalArgumentException("Recipe requirement is not an energy requirement: " + requirement.id());
        return (EnergyRequirement) requirement.payload();
    }

    private RecipeBuilder editResources(ResourceLocation requirementId, boolean create, UnaryOperator<ResourceRequirement> editor) {
        int index = create ? findIndex(requirementId) : requiredIndex(requirementId);
        var base = index < 0 ? new ResourceRequirement(List.of(), List.of()) : resource(requirements.get(index));
        var replacement = RecipeRequirements.RESOURCES.requirement(requirementId, editor.apply(base));
        if (index < 0) requirements.add(replacement);
        else requirements.set(index, replacement);
        return this;
    }

    private RecipeBuilder editAllResources(UnaryOperator<ResourceRequirement> editor) {
        var updated = new ObjectArrayList<>(requirements);
        for (int i = 0; i < updated.size(); i++) {
            var requirement = updated.get(i);
            if (requirement.type() == RecipeRequirements.RESOURCES)
                updated.set(i, RecipeRequirements.RESOURCES.requirement(requirement.id(), editor.apply(resource(requirement))));
        }
        requirements.clear();
        requirements.addAll(updated);
        return this;
    }

    private static ResourceRequirement withoutInputs(ResourceRequirement base) {
        return copyResources(base, List.of(), List.of(), base.outputsPerTick(), base.outputs(), base.randomOutputs(), List.of(), List.of());
    }

    private static ResourceRequirement withoutOutputs(ResourceRequirement base) {
        return copyResources(base, base.inputs(), base.inputsPerTick(), List.of(), List.of(), null, base.fuzzyInputs(), base.dynamicInputs());
    }

    private static ResourceRequirement copyResources(ResourceRequirement base, List<GenericStack> inputs, List<GenericStack> inputsPerTick,
                                                     List<GenericStack> outputsPerTick, List<GenericStack> outputs,
                                                     @Nullable RandomOutputPool randomOutputs, List<FuzzyInputGroup> fuzzyInputs,
                                                     List<DynamicInputGroup> dynamicInputs) {
        return new ResourceRequirement(inputs, inputsPerTick, outputsPerTick, outputs, base.voidExcessOutputs(), base.chance(),
            randomOutputs, fuzzyInputs, dynamicInputs, base.inputAllocation());
    }

    private static <T> List<T> appended(List<T> values, T value) {
        var updated = new ObjectArrayList<>(values);
        updated.add(value);
        return updated;
    }

    private static <T> List<T> without(List<T> values, int index) {
        Objects.checkIndex(index, values.size());
        var updated = new ObjectArrayList<>(values);
        updated.remove(index);
        return updated;
    }

    public Set<ResourceLocation> recipeGroups() {
        return groups;
    }

    public RecipeBuilder recipeGroups(Collection<ResourceLocation> values) {
        if (values == groups) return this;
        groups.clear();
        for (var value : values) groups.add(Objects.requireNonNull(value, "Recipe group"));
        return this;
    }

    public int maxConcurrentThreads() {
        return maxConcurrentThreads;
    }

    public RecipeBuilder maxConcurrentThreads(int value) {
        if (value < 1) throw new IllegalArgumentException("Recipe thread limit must be positive");
        maxConcurrentThreads = value;
        return this;
    }

    @SuppressWarnings("unused")
    public RecipeParallelismSelection parallelismSelection() {
        return parallelismSelection;
    }

    public RecipeBuilder parallelismSelection(RecipeParallelismSelection value) {
        parallelismSelection = Objects.requireNonNull(value, "Recipe parallelism selection");
        return this;
    }

    public RecipeBuilder event(ResourceLocation eventId, RecipeStage stage, RecipeEventHandler handler) {
        return addRequirement(RecipeRequirements.EVENTS.requirement(eventId, new RecipeEventRequirement(stage, handler)));
    }

    public MachineRecipe build() {
        return new MachineRecipe(id, durationTicks, new ObjectArrayList<>(requirements),
            new ObjectArrayList<>(groups), maxConcurrentThreads, parallelismSelection);
    }
}
