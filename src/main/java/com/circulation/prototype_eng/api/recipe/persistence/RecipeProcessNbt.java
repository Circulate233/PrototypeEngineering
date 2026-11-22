package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.TopologicalComponentMap;
import com.circulation.prototype_eng.api.machine.CrossRecipeBatch;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.persistence.BuiltinOverlayCodecs;
import com.circulation.prototype_eng.api.persistence.MachineAttributesNbt;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ProcessResultNbt;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Frozen persistence bindings for one canonical recipe revision. The host must change revision when
 * payloads, systems, codecs or external functions change incompatibly; matching IDs cannot prove that
 * arbitrary Java behavior is unchanged. Save/load requires exclusive process ownership. Decoding
 * returns a fresh, fully validated process, never publishes it or restores world/lease/epoch authority.
 */
public final class RecipeProcessNbt {
    private final MachineRecipe recipe;
    private final String revision;
    private final MachineAttributesNbt attributes;
    private final Map<ResourceLocation, Binding<?>> bindings;
    private final List<Consumer<RecipeProcess>> validators;
    private final boolean stableResourceGroups;
    private final boolean stableStructure;

    private RecipeProcessNbt(Builder builder) {
        recipe = builder.recipe;
        revision = builder.revision;
        attributes = builder.attributes == AttributeModifierNbt.INSTANCE ? MachineAttributesNbt.INSTANCE : new MachineAttributesNbt(builder.attributes);
        validators = builder.validators;
        var overlays = builder.overlays;
        boolean resourcesStable = true;
        boolean structureStable = true;
        for (int index1 = 0; index1 < builder.overlays.size(); index1++) {
            var binding = builder.overlays.get(index1);
            var type = binding.type().requirementType();
            resourcesStable &= type != RecipeRequirements.RESOURCES;
            structureStable &= type != RecipeRequirements.CHECKPOINT && type != RecipeRequirements.SELECTIVE;
        }
        stableResourceGroups = resourcesStable;
        stableStructure = structureStable;
        if (!builder.overlays.isEmpty() && validators.isEmpty()) {
            throw new IllegalArgumentException("Custom overlays require a validator for their historical process invariants");
        }
        overlays.add(new RecipeOverlayNbt.Binding<>(RecipeOverlayTypes.RESOURCE_IO, BuiltinOverlayCodecs.resourceIo(builder.resources)));
        overlays.add(new RecipeOverlayNbt.Binding<>(RecipeOverlayTypes.ENERGY_RATES, BuiltinOverlayCodecs.ENERGY_RATES));
        var components = builder.components;
        add(components, new Binding<>(RecipeOverlays.TYPE, BuiltinProcessCodecs.overlays(overlays)));
        add(components, new Binding<>(ResourceInputLocks.TYPE, BuiltinProcessCodecs.locks(builder.resources)));
        add(components, new Binding<>(SelectiveState.TYPE, BuiltinProcessCodecs.selections()));
        add(components, new Binding<>(RequirementAttributeState.TYPE, BuiltinProcessCodecs.requirementAttributes()));
        bindings = components;
    }

    private RecipeProcessNbt(RecipeProcessNbt source, MachineRecipe recipe) {
        if (!source.recipe.id().equals(recipe.id()))
            throw new IllegalArgumentException("Replacement codec recipe has a different ID: " + recipe.id());
        this.recipe = recipe;
        revision = source.revision;
        attributes = source.attributes;
        bindings = source.bindings;
        validators = source.validators;
        stableResourceGroups = source.stableResourceGroups;
        stableStructure = source.stableStructure;
    }

    /**
     * Rebinds frozen codecs to an updated definition of the same recipe. Custom validators must
     * accept the new definition; their captured callbacks are retained unchanged.
     */
    public RecipeProcessNbt withRecipe(MachineRecipe recipe) {
        return recipe == this.recipe ? this : new RecipeProcessNbt(this, recipe);
    }

    public static Builder builder(MachineRecipe recipe, String revision) {
        return new Builder(recipe, revision);
    }

    private static NBTTagCompound writeState(RecipeProcess.State state) {
        var tag = new NBTTagCompound();
        tag.setString("Lifecycle", state.lifecycle().name());
        tag.setDouble("Progress", state.progress());
        tag.setLong("SuccessfulTicks", state.successfulTicks());
        tag.setBoolean("Started", state.started());
        tag.setTag("Result", ProcessResultNbt.INSTANCE.encode(state.result()));
        return tag;
    }

    private static RecipeProcess.State readState(NBTTagCompound tag) {
        var decoded = ProcessResultNbt.INSTANCE.decode(tag.getCompoundTag("Result"));
        return new RecipeProcess.State(RecipeProcess.Lifecycle.valueOf(tag.getString("Lifecycle")),
            tag.getDouble("Progress"), tag.getLong("SuccessfulTicks"), decoded,
            tag.getBoolean("Started"));
    }

    private static void add(Map<ResourceLocation, Binding<?>> bindings, Binding<?> binding) {
        if (bindings.putIfAbsent(binding.type().id(), binding) != null) {
            throw new IllegalArgumentException("Duplicate process component codec: " + binding.type().id());
        }
    }

    private static NBTTagCompound writeTiming(CrossRecipeBatch.Timing timing) {
        var tag = new NBTTagCompound();
        tag.setLong("Most", timing.batch().getMostSignificantBits());
        tag.setLong("Least", timing.batch().getLeastSignificantBits());
        tag.setInteger("Duration", timing.duration());
        tag.setInteger("Parallelism", timing.parallelism());
        tag.setInteger("MaterialOffset", timing.materialOffset());
        var energy = new NBTTagList();
        for (var entry : timing.energyOffsets().entrySet()) {
            var item = new NBTTagCompound();
            item.setTag("Path", RequirementPathNbt.encode(entry.getKey()));
            item.setInteger("Input", entry.getValue().input());
            item.setInteger("Output", entry.getValue().output());
            energy.appendTag(item);
        }
        tag.setTag("Energy", energy);
        return tag;
    }

    private static CrossRecipeBatch.Timing readTiming(NBTTagCompound tag) {
        var offsets = new Object2ObjectLinkedOpenHashMap<RequirementPath, CrossRecipeBatch.EnergyOffsets>(6);
        var entries = tag.getTagList("Energy", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            if (offsets.putIfAbsent(RequirementPathNbt.read(entry), new CrossRecipeBatch.EnergyOffsets(
                entry.getInteger("Input"), entry.getInteger("Output"))) != null)
                throw new IllegalArgumentException("Duplicate batch energy path");
        }
        return new CrossRecipeBatch.Timing(new UUID(tag.getLong("Most"), tag.getLong("Least")),
            tag.getInteger("Duration"), tag.getInteger("Parallelism"), tag.getInteger("MaterialOffset"), offsets);
    }

    /**
     * Canonical recipe frozen into this codec, for explicit host persistence bindings.
     */
    public MachineRecipe recipe() {
        return recipe;
    }

    /**
     * Explicit behavior revision written into every process save.
     */
    public String revision() {
        return revision;
    }

    /**
     * A startup process decorator may only attach components explicitly supported by this codec.
     */
    public boolean supportsComponent(ComponentType<RecipeProcess, ?> type) {
        var binding = bindings.get(type.id());
        return binding != null;
    }

    public NBTTagCompound encode(RecipeProcess process) {
        process.requirePersistenceBoundary();
        if (process.recipe() != recipe) {
            throw new IllegalArgumentException("Process belongs to a different canonical recipe");
        }
        var index = process.definitionIndex();
        var tag = new NBTTagCompound();
        tag.setString("Identity", process.identity().toString());
        if (process.isMaterialCycle()) {
            tag.setBoolean("MaterialCycle", true);
        }
        if (process.selectedInputGroup() != null) {
            var group = new NBTTagCompound();
            group.setString("Color", process.selectedInputGroup().color().name());
            group.setString("Partition", process.selectedInputGroup().partition());
            tag.setTag("InputGroup", group);
        }
        tag.setString("Recipe", recipe.id().toString());
        tag.setString("Revision", revision);
        tag.setInteger("Duration", recipe.durationTicks());
        var graph = new NBTTagList();
        index.requirements().keySet().forEach(path -> graph.appendTag(RequirementPathNbt.encode(path)));
        tag.setTag("Graph", graph);
        tag.setLong("Seed", process.seed());
        var tuning = new NBTTagCompound();
        tuning.setBoolean("ExactSmallBinomial", process.probabilityTuning().exactSmallBinomial());
        tuning.setInteger("Threshold", process.probabilityTuning().exactSmallBinomialThreshold());
        tag.setTag("Tuning", tuning);
        tag.setTag("State", writeState(process.state()));
        if (process.crossRecipeTiming() != null) tag.setTag("CrossBatch", writeTiming(process.crossRecipeTiming()));
        tag.setTag("Attributes", attributes.encode(process.attributes().localSnapshot()));
        var components = new NBTTagList();
        var elements5 = process.components().snapshotBindings();
        for (int index5 = 0; index5 < elements5.size(); index5++) {
            var saved = elements5.get(index5);
            var entry = new NBTTagCompound();
            entry.setString("Type", saved.type().id().toString());
            entry.setTag("Dependencies", writeTypes(saved.dependencies()));
            entry.setTag("Data", require(saved.type()).encode(process, saved));
            components.appendTag(entry);
        }
        tag.setTag("Components", components);
        var pending = new NBTTagList();
        process.components().snapshotPendingDependencies().forEach((type, dependencies) -> {
            require(type);
            var entry = new NBTTagCompound();
            entry.setString("Type", type.id().toString());
            entry.setTag("Dependencies", writeTypes(dependencies));
            pending.appendTag(entry);
        });
        tag.setTag("PendingDependencies", pending);
        return tag;
    }

    public RecipeProcess decode(MachineInstance owner, NBTTagCompound saved) {
        return decode(owner, saved, null);
    }

    /**
     * Restores the complete material requirement graph against newly authorized physical sources.
     */
    public RecipeProcess decodeMaterialCycle(MachineInstance owner, MachineHatches hatches, NBTTagCompound saved) {
        return decode(owner, saved, hatches);
    }

    private RecipeProcess decode(MachineInstance owner, NBTTagCompound tag, @Nullable MachineHatches hatches) {
        if (hatches != null && !tag.getBoolean("MaterialCycle"))
            throw new IllegalArgumentException("Expected a material cycle save");
        if (!new ResourceLocation(tag.getString("Recipe")).equals(recipe.id())
            || !tag.getString("Revision").equals(revision) || tag.getInteger("Duration") != recipe.durationTicks()) {
            throw new IllegalArgumentException("Unsupported process save or incompatible recipe revision");
        }
        var tuning = tag.getCompoundTag("Tuning");
        long seed = tag.getLong("Seed");
        var probability = new ProbabilityTuning(tuning.getBoolean("ExactSmallBinomial"), tuning.getInteger("Threshold"));
        var state = readState(tag.getCompoundTag("State"));
        var timing = tag.hasKey("CrossBatch") ? readTiming(tag.getCompoundTag("CrossBatch")) : null;
        if (timing != null && (!(owner instanceof CrossRecipeController) || hatches != null))
            throw new IllegalArgumentException("Cross-recipe batch belongs to a cross-recipe controller");
        var identity = tag.hasKey("Identity") ? UUID.fromString(tag.getString("Identity")) : UUID.randomUUID();
        var process = hatches == null ? RecipeProcess.restored(owner, recipe, seed, probability, state, timing, identity)
            : RecipeProcess.restoredMaterialCycle(owner, recipe, seed, probability, state, hatches, identity);
        var graph = tag.getTagList("Graph", Constants.NBT.TAG_LIST);
        var index = process.definitionIndex();
        var expected = index.requirements().keySet();
        if (graph.tagCount() != expected.size()) {
            throw new IllegalArgumentException("Recipe requirement graph changed");
        }
        var paths = expected.iterator();
        for (int i = 0; i < graph.tagCount(); i++) {
            if (!RequirementPathNbt.decode((NBTTagList) graph.get(i)).equals(paths.next())) {
                throw new IllegalArgumentException("Recipe requirement graph changed");
            }
        }
        process.attributes().restoreLocal(attributes.decode(tag.getCompoundTag("Attributes")));
        if (timing != null) {
            var definitions = index.requirements();
            var energyPaths = new ObjectLinkedOpenHashSet<RequirementPath>(6);
            for (var definition : definitions.entrySet())
                if (definition.getValue().type() == RecipeRequirements.ENERGY) energyPaths.add(definition.getKey());
            if (!energyPaths.equals(timing.energyOffsets().keySet()))
                throw new IllegalArgumentException("Cross-recipe energy paths changed");
        }

        if (tag.hasKey("InputGroup")) {
            var group = tag.getCompoundTag("InputGroup");
            process.restoreInputGroup(new ResourceInputGroup(AEColor.valueOf(group.getString("Color")),
                group.getString("Partition")));
        }
        var entries = readComponents(tag.getTagList("Components", Constants.NBT.TAG_COMPOUND));
        var pending = readPending(tag.getTagList("PendingDependencies", Constants.NBT.TAG_COMPOUND), entries);
        var restoreOrder = new TopologicalComponentMap<ComponentType<RecipeProcess, ?>, SavedComponent>();
        entries.forEach((type, entry) -> restoreOrder.add(type, entry, entry.dependencies()));
        // Selector ownership binds layers already restored. This constraint is only for loading,
        // not a change to the saved component tick graph.
        if (entries.containsKey(SelectiveState.TYPE) && entries.containsKey(RecipeOverlays.TYPE)) {
            restoreOrder.addDependency(SelectiveState.TYPE, RecipeOverlays.TYPE);
        }
        var ordered = restoreOrder.orderedComponents();
        for (var entry : entries.values()) {
            entry.binding().create(process);
        }
        for (var entry : entries.values()) {
            var type = entry.binding().type();
            for (var dependency : type.dependencies()) {
                process.components().removeDependency(type, dependency);
            }
            for (var dependency : entry.dependencies()) {
                process.components().addDependency(type, dependency);
            }
        }
        pending.forEach((type, dependencies) -> dependencies.forEach(dependency -> process.components().addDependency(type, dependency)));
        for (int index3 = 0; index3 < ordered.size(); index3++) {
            var node = ordered.get(index3);
            node.component().binding().restore(process, node.component().data());
        }
        validate(process);
        return process;
    }

    private void validate(RecipeProcess process) {
        BuiltinProcessValidation.validate(process, process.definitionIndex(), stableResourceGroups, stableStructure);
        var elements = process.components().snapshotBindings();
        for (int index4 = 0; index4 < elements.size(); index4++) {
            var saved = elements.get(index4);
            require(saved.type()).validate(process);
        }
        for (int index2 = 0; index2 < validators.size(); index2++) {
            var validator = validators.get(index2);
            validator.accept(process);
        }
    }

    private Map<ComponentType<RecipeProcess, ?>, SavedComponent> readComponents(NBTTagList list) {
        var entries = new Object2ObjectLinkedOpenHashMap<ComponentType<RecipeProcess, ?>, SavedComponent>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            var binding = require(new ResourceLocation(entry.getString("Type")));
            var component = new SavedComponent(binding, readTypes(entry), entry.getCompoundTag("Data"));
            if (entries.putIfAbsent(binding.type(), component) != null) {
                throw new IllegalArgumentException("Duplicate saved component");
            }
        }
        return entries;
    }

    private Map<ComponentType<RecipeProcess, ?>, Set<ComponentType<RecipeProcess, ?>>> readPending(NBTTagList list,
                                                                                                   Map<ComponentType<RecipeProcess, ?>, SavedComponent> entries) {
        var pending = new Object2ObjectLinkedOpenHashMap<ComponentType<RecipeProcess, ?>, Set<ComponentType<RecipeProcess, ?>>>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            var type = require(new ResourceLocation(entry.getString("Type"))).type();
            var dependencies = readTypes(entry);
            if (entries.containsKey(type) || dependencies.isEmpty() || pending.putIfAbsent(type, dependencies) != null) {
                throw new IllegalArgumentException("Invalid pending component dependencies");
            }
        }
        return pending;
    }

    private NBTTagList writeTypes(Set<ComponentType<RecipeProcess, ?>> types) {
        var result = new NBTTagList();
        for (var type : types) {
            require(type);
            result.appendTag(new NBTTagString(type.id().toString()));
        }
        return result;
    }

    private Set<ComponentType<RecipeProcess, ?>> readTypes(NBTTagCompound tag) {
        var list = tag.getTagList("Dependencies", Constants.NBT.TAG_STRING);
        var result = new ObjectLinkedOpenHashSet<ComponentType<RecipeProcess, ?>>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            if (!result.add(require(new ResourceLocation(list.getStringTagAt(i))).type())) {
                throw new IllegalArgumentException("Duplicate component dependency");
            }
        }
        return result;
    }

    private Binding<?> require(ResourceLocation id) {
        var binding = bindings.get(id);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown process component codec: " + id);
        }
        return binding;
    }

    private Binding<?> require(ComponentType<RecipeProcess, ?> type) {
        return require(type.id());
    }

    private record SavedComponent(Binding<?> binding, Set<ComponentType<RecipeProcess, ?>> dependencies,
                                  NBTTagCompound data) {
    }

    private record Binding<C>(ComponentType<RecipeProcess, C> type, ProcessComponentCodec<C> codec) {
        void create(RecipeProcess process) {
            process.components().create(type, process);
        }

        @SuppressWarnings("unchecked")
        NBTTagCompound encode(RecipeProcess process, ComponentMap.SavedBinding<RecipeProcess, ?> saved) {
            return codec.encode(process, (C) saved.component());
        }

        void restore(RecipeProcess process, NBTTagCompound data) {
            codec.restore(process, process.components().get(type), data);
        }

        void validate(RecipeProcess process) {
            codec.validate(process, process.components().get(type));
        }
    }

    /**
     * Build transfers the completed collections. Do not reuse or modify this builder afterward.
     * Bind every component and overlay type that may be saved.
     */
    public static final class Builder {
        private final MachineRecipe recipe;
        private final String revision;
        private final Map<ResourceLocation, Binding<?>> components = new Object2ObjectLinkedOpenHashMap<>();
        private final List<RecipeOverlayNbt.Binding<?, ?>> overlays = new ObjectArrayList<>();
        private final List<Consumer<RecipeProcess>> validators = new ObjectArrayList<>();
        private ResourceKeyNbt resources = ResourceKeyNbt.INSTANCE;
        private AttributeModifierNbt attributes = AttributeModifierNbt.INSTANCE;

        private Builder(MachineRecipe recipe, String revision) {
            this.recipe = recipe;
            this.revision = revision;
            if (revision.isBlank()) {
                throw new IllegalArgumentException("Recipe revision must not be blank");
            }
        }

        public Builder resources(ResourceKeyNbt codec) {
            resources = codec;
            return this;
        }

        public Builder attributes(AttributeModifierNbt codec) {
            attributes = codec;
            return this;
        }

        public <C> Builder component(ComponentType<RecipeProcess, C> type, ProcessComponentCodec<C> codec) {
            add(components, new Binding<>(type, codec));
            return this;
        }

        public <C, D> Builder overlay(RecipeOverlayType<C, D> type, NbtCodec<D> codec) {
            overlays.add(new RecipeOverlayNbt.Binding<>(type, codec));
            return this;
        }

        /**
         * Pure checks for external wrappers, overlay history or cross-component invariants, after restoration.
         */
        public Builder validator(Consumer<RecipeProcess> validator) {
            validators.add(validator);
            return this;
        }

        public RecipeProcessNbt build() {
            return new RecipeProcessNbt(this);
        }
    }
}
