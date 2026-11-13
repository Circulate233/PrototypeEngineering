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
import com.circulation.prototype_eng.api.persistence.StrictNbt;
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
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        attributes = new MachineAttributesNbt(builder.attributes);
        validators = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.validators));
        var overlays = new ObjectArrayList<>(builder.overlays);
        overlays.add(new RecipeOverlayNbt.Binding<>(RecipeOverlayTypes.RESOURCE_IO, BuiltinOverlayCodecs.resourceIo(builder.resources)));
        overlays.add(new RecipeOverlayNbt.Binding<>(RecipeOverlayTypes.ENERGY_RATES, BuiltinOverlayCodecs.ENERGY_RATES));
        boolean resourcesStable = true;
        boolean structureStable = true;
        for (var binding : builder.overlays) {
            var type = binding.type().requirementType();
            resourcesStable &= type != RecipeRequirements.RESOURCES;
            structureStable &= type != RecipeRequirements.CHECKPOINT && type != RecipeRequirements.SELECTIVE;
        }
        stableResourceGroups = resourcesStable;
        stableStructure = structureStable;
        if (!builder.overlays.isEmpty() && validators.isEmpty()) {
            throw new IllegalArgumentException("Custom overlays require a validator for their historical process invariants");
        }
        var components = new Object2ObjectLinkedOpenHashMap<>(builder.components);
        add(components, new Binding<>(RecipeOverlays.TYPE, BuiltinProcessCodecs.overlays(overlays)));
        add(components, new Binding<>(ResourceInputLocks.TYPE, BuiltinProcessCodecs.locks(builder.resources)));
        add(components, new Binding<>(SelectiveState.TYPE, BuiltinProcessCodecs.selections()));
        add(components, new Binding<>(RequirementAttributeState.TYPE, BuiltinProcessCodecs.requirementAttributes()));
        bindings = Object2ObjectMaps.unmodifiable(components);
    }

    public static Builder builder(MachineRecipe recipe, String revision) {
        return new Builder(recipe, revision);
    }

    private static void requireAssembly(RecipeProcess process, Map<ComponentType<RecipeProcess, ?>, SavedComponent> entries,
                                        Map<ComponentType<RecipeProcess, ?>, Set<ComponentType<RecipeProcess, ?>>> pending) {
        var actual = process.components().snapshotBindings();
        if (actual.size() != entries.size() || !process.components().snapshotPendingDependencies().equals(pending)) {
            throw new IllegalStateException("Component codec or factory changed process assembly");
        }
        int i = 0;
        for (var entry : entries.values()) {
            var binding = actual.get(i++);
            if (binding.type() != entry.binding().type() || !binding.dependencies().equals(entry.dependencies())) {
                throw new IllegalStateException("Component codec or factory changed process assembly");
            }
        }
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
        StrictNbt.requireKeys(tag, "Lifecycle", "Progress", "SuccessfulTicks", "Started", "Result");
        var decoded = ProcessResultNbt.INSTANCE.decode(StrictNbt.requireCompound(tag, "Result"));
        return new RecipeProcess.State(RecipeProcess.Lifecycle.valueOf(StrictNbt.requireString(tag, "Lifecycle")),
            StrictNbt.requireDouble(tag, "Progress"), StrictNbt.requireLong(tag, "SuccessfulTicks"), decoded,
            StrictNbt.requireBoolean(tag, "Started"));
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
        StrictNbt.requireKeys(tag, "Most", "Least", "Duration", "Parallelism", "MaterialOffset", "Energy");
        var offsets = new Object2ObjectLinkedOpenHashMap<RequirementPath, CrossRecipeBatch.EnergyOffsets>(6);
        var entries = StrictNbt.requireList(tag, "Energy", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Path", "Input", "Output");
            if (offsets.putIfAbsent(RequirementPathNbt.read(entry), new CrossRecipeBatch.EnergyOffsets(
                StrictNbt.requireInt(entry, "Input"), StrictNbt.requireInt(entry, "Output"))) != null)
                throw new IllegalArgumentException("Duplicate batch energy path");
        }
        return new CrossRecipeBatch.Timing(new UUID(StrictNbt.requireLong(tag, "Most"), StrictNbt.requireLong(tag, "Least")),
            StrictNbt.requireInt(tag, "Duration"), StrictNbt.requireInt(tag, "Parallelism"), StrictNbt.requireInt(tag, "MaterialOffset"), offsets);
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
        var binding = bindings.get(Objects.requireNonNull(type).id());
        return binding != null && binding.type() == type;
    }

    public NBTTagCompound encode(RecipeProcess process) {
        process.requirePersistenceBoundary();
        if (process.recipe() != recipe) {
            throw new IllegalArgumentException("Process belongs to a different canonical recipe");
        }
        validate(process);
        var index = new RecipeDefinitionIndex(process);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 2);
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
        for (var saved : process.components().snapshotBindings()) {
            var entry = new NBTTagCompound();
            entry.setString("Type", saved.type().id().toString());
            entry.setTag("Dependencies", writeTypes(saved.dependencies()));
            entry.setTag("Data", require(saved.type()).encode(process, saved).copy());
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
        process.requirePersistenceBoundary();
        return tag;
    }

    public RecipeProcess decode(MachineInstance owner, NBTTagCompound saved) {
        return decode(owner, saved, null);
    }

    /**
     * Restores the complete material requirement graph against newly authorized physical sources.
     */
    public RecipeProcess decodeMaterialCycle(MachineInstance owner, MachineHatches hatches, NBTTagCompound saved) {
        return decode(owner, saved, Objects.requireNonNull(hatches));
    }

    private RecipeProcess decode(MachineInstance owner, NBTTagCompound saved, @Nullable MachineHatches hatches) {
        // Isolate even a codec that retains or edits its input from the caller and subsequent loads.
        var tag = Objects.requireNonNull(saved).copy();
        if (hatches == null) {
            StrictNbt.requireKeys(tag, "Version", "Recipe", "Revision", "Duration", "Graph", "Seed", "Tuning", "State",
                "Attributes", "Components", "PendingDependencies", "CrossBatch", "InputGroup", "Identity");
        } else {
            StrictNbt.requireKeys(tag, "Version", "MaterialCycle", "Recipe", "Revision", "Duration", "Graph", "Seed", "Tuning", "State",
                "Attributes", "Components", "PendingDependencies", "CrossBatch", "InputGroup", "Identity");
            if (!StrictNbt.requireBoolean(tag, "MaterialCycle")) {
                throw new IllegalArgumentException("Expected a material cycle save");
            }
        }
        if (StrictNbt.requireInt(tag, "Version") != 2 || !StrictNbt.requireId(tag, "Recipe").equals(recipe.id())
            || !StrictNbt.requireString(tag, "Revision").equals(revision) || StrictNbt.requireInt(tag, "Duration") != recipe.durationTicks()) {
            throw new IllegalArgumentException("Unsupported process save or incompatible recipe revision");
        }
        var tuning = StrictNbt.requireCompound(tag, "Tuning");
        StrictNbt.requireKeys(tuning, "ExactSmallBinomial", "Threshold");
        long seed = StrictNbt.requireLong(tag, "Seed");
        var probability = new ProbabilityTuning(StrictNbt.requireBoolean(tuning, "ExactSmallBinomial"), StrictNbt.requireInt(tuning, "Threshold"));
        var state = readState(StrictNbt.requireCompound(tag, "State"));
        var timing = tag.hasKey("CrossBatch") ? readTiming(StrictNbt.requireCompound(tag, "CrossBatch")) : null;
        if (timing != null && (!(owner instanceof CrossRecipeController) || hatches != null))
            throw new IllegalArgumentException("Cross-recipe batch belongs to a cross-recipe controller");
        var identity = tag.hasKey("Identity") ? UUID.fromString(StrictNbt.requireString(tag, "Identity")) : UUID.randomUUID();
        var process = hatches == null ? RecipeProcess.restored(owner, recipe, seed, probability, state, timing, identity)
            : RecipeProcess.restoredMaterialCycle(owner, recipe, seed, probability, state, hatches, identity);
        var graph = StrictNbt.requireList(tag, "Graph", Constants.NBT.TAG_LIST);
        var expected = new ObjectArrayList<>(new RecipeDefinitionIndex(process).requirements().keySet());
        if (graph.tagCount() != expected.size()) {
            throw new IllegalArgumentException("Recipe requirement graph changed");
        }
        for (int i = 0; i < graph.tagCount(); i++) {
            if (!RequirementPathNbt.decode((NBTTagList) graph.get(i)).equals(expected.get(i))) {
                throw new IllegalArgumentException("Recipe requirement graph changed");
            }
        }
        process.attributes().restoreLocal(attributes.decode(StrictNbt.requireCompound(tag, "Attributes")));
        if (timing != null) {
            var definitions = new RecipeDefinitionIndex(process).requirements();
            var energyPaths = new ObjectLinkedOpenHashSet<RequirementPath>(6);
            for (var definition : definitions.entrySet())
                if (definition.getValue().type() == RecipeRequirements.ENERGY) energyPaths.add(definition.getKey());
            if (!energyPaths.equals(timing.energyOffsets().keySet()))
                throw new IllegalArgumentException("Cross-recipe energy paths changed");
        }

        if (tag.hasKey("InputGroup")) {
            var group = StrictNbt.requireCompound(tag, "InputGroup");
            StrictNbt.requireKeys(group, "Color", "Partition");
            process.restoreInputGroup(new ResourceInputGroup(AEColor.valueOf(StrictNbt.requireString(group, "Color")),
                StrictNbt.requireString(group, "Partition")));
        }
        var entries = readComponents(StrictNbt.requireList(tag, "Components", Constants.NBT.TAG_COMPOUND));
        var pending = readPending(StrictNbt.requireList(tag, "PendingDependencies", Constants.NBT.TAG_COMPOUND), entries);
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
        requireAssembly(process, entries, pending);
        for (var node : ordered) {
            node.component().binding().restore(process, node.component().data());
        }
        requireAssembly(process, entries, pending);
        validate(process);
        process.requirePersistenceBoundary();
        return process;
    }

    private void validate(RecipeProcess process) {
        BuiltinProcessValidation.validate(process, new RecipeDefinitionIndex(process), stableResourceGroups, stableStructure);
        for (var saved : process.components().snapshotBindings()) {
            require(saved.type()).validate(process);
        }
        for (var validator : validators) {
            validator.accept(process);
        }
    }

    private Map<ComponentType<RecipeProcess, ?>, SavedComponent> readComponents(NBTTagList list) {
        var entries = new Object2ObjectLinkedOpenHashMap<ComponentType<RecipeProcess, ?>, SavedComponent>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            StrictNbt.requireKeys(entry, "Type", "Dependencies", "Data");
            var binding = require(StrictNbt.requireId(entry, "Type"));
            var component = new SavedComponent(binding, readTypes(entry), StrictNbt.requireCompound(entry, "Data").copy());
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
            StrictNbt.requireKeys(entry, "Type", "Dependencies");
            var type = require(StrictNbt.requireId(entry, "Type")).type();
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
        var list = StrictNbt.requireList(tag, "Dependencies", Constants.NBT.TAG_STRING);
        var result = new ObjectLinkedOpenHashSet<ComponentType<RecipeProcess, ?>>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            if (!result.add(require(StrictNbt.requireId(list.getStringTagAt(i))).type())) {
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
        var binding = require(type.id());
        if (binding.type() != type) {
            throw new IllegalArgumentException("Different component descriptors share " + type.id());
        }
        return binding;
    }

    private record SavedComponent(Binding<?> binding, Set<ComponentType<RecipeProcess, ?>> dependencies,
                                  NBTTagCompound data) {
    }

    private record Binding<C>(ComponentType<RecipeProcess, C> type, ProcessComponentCodec<C> codec) {
        private Binding {
            Objects.requireNonNull(type);
            Objects.requireNonNull(codec);
        }

        void create(RecipeProcess process) {
            process.components().create(type, process);
        }

        @SuppressWarnings("unchecked")
            // Verified descriptor identity ties the saved instance to C.
        NBTTagCompound encode(RecipeProcess process, ComponentMap.SavedBinding<RecipeProcess, ?> saved) {
            if (saved.type() != type) {
                throw new IllegalArgumentException("Component codec type mismatch");
            }
            return Objects.requireNonNull(codec.encode(process, (C) saved.component()));
        }

        void restore(RecipeProcess process, NBTTagCompound data) {
            codec.restore(process, Objects.requireNonNull(process.components().get(type)), data.copy());
        }

        void validate(RecipeProcess process) {
            codec.validate(process, Objects.requireNonNull(process.components().get(type)));
        }
    }

    /**
     * Bind only explicitly supported extensions; unknown state is an error, never silently dropped.
     */
    public static final class Builder {
        private final MachineRecipe recipe;
        private final String revision;
        private final Map<ResourceLocation, Binding<?>> components = new Object2ObjectLinkedOpenHashMap<>();
        private final List<RecipeOverlayNbt.Binding<?, ?>> overlays = new ObjectArrayList<>();
        private final List<Consumer<RecipeProcess>> validators = new ObjectArrayList<>();
        private ResourceKeyNbt resources = new ResourceKeyNbt();
        private AttributeModifierNbt attributes = new AttributeModifierNbt();

        private Builder(MachineRecipe recipe, String revision) {
            this.recipe = Objects.requireNonNull(recipe);
            this.revision = Objects.requireNonNull(revision);
            if (revision.isBlank()) {
                throw new IllegalArgumentException("Recipe revision must not be blank");
            }
        }

        public Builder resources(ResourceKeyNbt codec) {
            resources = Objects.requireNonNull(codec);
            return this;
        }

        public Builder attributes(AttributeModifierNbt codec) {
            attributes = Objects.requireNonNull(codec);
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
            validators.add(Objects.requireNonNull(validator));
            return this;
        }

        public RecipeProcessNbt build() {
            return new RecipeProcessNbt(this);
        }
    }
}
