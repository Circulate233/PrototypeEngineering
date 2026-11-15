package com.circulation.prototype_eng.api.machine.persistence;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.TopologicalComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.network.ComputerInputWork;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.persistence.MachineAttributesNbt;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.structure.StructureComponents;
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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Attached-host assembly and explicit persistence for one canonical machine declaration revision.
 * Creation/restoration completes before lifecycle load; successful load returns an unregistered
 * machine for the controller to register with its scheduler. The persisted formation count prevents
 * port identity reuse; formed/configuration authority is rebuilt. Revision changes cover incompatible
 * factories, codecs, layouts and external behavior.
 */
public final class MachineAssembly {
    private static final MachineComponentCodec<MachineScheduling> SCHEDULING = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, MachineScheduling component) {
            var tag = new NBTTagCompound();
            tag.setString("Mode", component.mode().name());
            if (component.policy() != null) tag.setString("Policy", component.policy().name());
            return tag;
        }

        @Override
        public MachineScheduling restore(MachineInstance owner, MachineScheduling component, NBTTagCompound saved) {
            return new MachineScheduling(ExecutionMode.valueOf(saved.getString("Mode")),
                saved.hasKey("Policy") ? ControllerExecutionPolicy.valueOf(saved.getString("Policy")) : null);
        }
    };

    private final Function<MachineType, ? extends MachineInstance> instanceFactory;
    private final MachineType type;
    private final String revision;
    private final MachineAttributesNbt attributes;
    private final Map<ResourceLocation, Binding<?>> bindings;
    private final List<Consumer<MachineInstance>> validators;
    private final List<ComputerRecipe> computerRecipes;

    private MachineAssembly(Builder builder) {
        instanceFactory = builder.instanceFactory;
        type = builder.type;
        revision = builder.revision;
        attributes = builder.attributes == AttributeModifierNbt.INSTANCE ? MachineAttributesNbt.INSTANCE : new MachineAttributesNbt(builder.attributes);
        validators = builder.validators;
        computerRecipes = builder.computerRecipes;
        var copy = builder.bindings;
        add(copy, new Binding<>(MachineScheduling.TYPE, SCHEDULING));
        add(copy, new Binding<>(MachineOwnership.TYPE, MachineOwnership.CODEC));
        add(copy, new Binding<>(MachineNetworkDevice.DEFAULT, null));
        if (type.componentTypes().contains(ComputerInputWork.TYPE))
            add(copy, new Binding<>(ComputerInputWork.TYPE, ComputerInputWork.CODEC));
        bindings = copy;
        var elements8 = type.componentTypes();
        for (int index8 = 0; index8 < elements8.size(); index8++) {
            var declared = elements8.get(index8);
            if (isStructureDerived(declared)) {
                throw new IllegalArgumentException("Structure-derived hatches must be established by structure publication, not declared as components");
            }
            require(declared);
            for (var dependency : declared.dependencies()) {
                requireReference(dependency);
            }
        }
    }

    public static Builder builder(MachineType type, String revision) {
        return new Builder(type, revision);
    }

    private static <C> void createBinding(MachineInstance machine, Binding<C> binding) {
        var component = binding.type().create(machine);
        machine.components().put(binding.type(), component);
    }

    private static <C> void restoreBinding(MachineInstance machine, Binding<C> binding, NBTTagCompound tag) {
        var component = machine.components().get(binding.type());
        var restored = binding.restore(machine, component, tag);
        if (restored != component) {
            machine.components().replaceValue(binding.type(), restored);
        }
    }

    private static boolean isStructureDerived(ComponentType<MachineInstance, ?> descriptor) {
        return descriptor == StructureComponents.TYPE || descriptor == MachineHatches.TYPE;
    }

    private static void add(Map<ResourceLocation, Binding<?>> bindings, Binding<?> binding) {
        if (bindings.putIfAbsent(binding.type().id(), binding) != null) {
            throw new IllegalArgumentException("Duplicate machine component persistence binding: " + binding.type().id());
        }
    }

    /**
     * The exact declaration this assembly creates and restores.
     */
    public MachineType type() {
        return type;
    }

    /**
     * Completed read-only computer recipe declarations. JEI reads this list without creating a machine instance.
     */
    public List<ComputerRecipe> computerRecipes() {
        return computerRecipes;
    }

    /**
     * Read this assembly's explicit ownership codec without factories, loading or world effects.
     */
    @Nullable
    public PersistentMachineIdentity savedIdentity(NBTTagCompound tag) {
        if (tag.getInteger("Version") != 2 || !new ResourceLocation(tag.getString("Type")).equals(type.id())
            || !tag.getString("Revision").equals(revision)) {
            throw new IllegalArgumentException("Saved identity requires this exact machine assembly");
        }
        PersistentMachineIdentity identity = null;
        var components = tag.getTagList("Components", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < components.tagCount(); index++) {
            var component = components.getCompoundTagAt(index);
            var binding = require(new ResourceLocation(component.getString("Type")));
            if (binding.codec() == MachineOwnership.CODEC) {
                if (identity != null)
                    throw new IllegalArgumentException("Saved machine has multiple ownership components");
                identity = PersistentMachineIdentity.decode(component.getCompoundTag("Data"));
            }
        }
        return identity;
    }

    private MachineInstance createInstance() {
        return instanceFactory.apply(type);
    }

    public MachineInstance create(MachineLifecycleHost host) {
        return create(host, MachineLifecycleRegistry.GLOBAL);
    }

    public MachineInstance create(MachineLifecycleHost host, MachineLifecycleRegistry registry) {
        var machine = createInstance();
        var lifecycle = new MachineLifecycle(machine, host, registry);
        lifecycle.assemble(() -> {
            var elements = type.componentTypes();
            for (int index2 = 0; index2 < elements.size(); index2++) {
                var declared = elements.get(index2);
                createBinding(machine, require(declared));
            }
            if (machine.components().get(MachineScheduling.TYPE) == null) {
                createBinding(machine, require(MachineScheduling.TYPE));
            }
            // Keep the original owner even while a physical controller is configured to work offline.
            if (host.controllerBlockId() != null && host.placementPlayer() != null && !hasOwnership(machine)) {
                createBinding(machine, require(MachineOwnership.TYPE));
            }
            supplementDefaults(machine);
            validate(machine);
        });
        lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        return machine;
    }

    public MachineInstance restore(MachineLifecycleHost host, NBTTagCompound saved) {
        return restore(host, saved, MachineLifecycleRegistry.GLOBAL);
    }

    public MachineInstance restore(MachineLifecycleHost host, NBTTagCompound saved, MachineLifecycleRegistry registry) {
        var machine = createInstance();
        var lifecycle = new MachineLifecycle(machine, host, registry);
        lifecycle.assemble(() -> restoreState(machine, saved));
        lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED);
        return machine;
    }

    @SuppressWarnings("DataFlowIssue")
    public NBTTagCompound encode(MachineInstance machine) {
        var lifecycle = machine.lifecycle();
        return lifecycle.withPersistence(() -> encodeState(machine));
    }

    @SuppressWarnings("DataFlowIssue")
    private NBTTagCompound encodeState(MachineInstance machine) {
        requireOwner(machine);
        var bindings = machine.components().assemblyBindings();
        var pendingDependencies = machine.components().snapshotPendingDependencies();
        var local = machine.attributes().localSnapshot();
        validate(machine, bindings, pendingDependencies);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 2);
        tag.setString("Type", type.id().toString());
        tag.setString("Revision", revision);
        tag.setLong("FormationGeneration", machine.lifecycle().formationGeneration());
        tag.setTag("Attributes", attributes.encode(local));
        var components = new NBTTagList();
        for (int index9 = 0; index9 < bindings.size(); index9++) {
            var saved = bindings.get(index9);
            if (isStructureDerived(saved.type())) {
                continue;
            }
            var binding = require(saved.type());
            var entry = new NBTTagCompound();
            entry.setString("Type", saved.type().id().toString());
            entry.setTag("Dependencies", writeTypes(saved.dependencies()));
            entry.setTag("Data", binding.encode(machine, saved));
            components.appendTag(entry);
        }
        tag.setTag("Components", components);
        var pending = new NBTTagList();
        pendingDependencies.forEach((dependent, dependencies) -> {
            require(dependent);
            var entry = new NBTTagCompound();
            entry.setString("Type", dependent.id().toString());
            entry.setTag("Dependencies", writeTypes(dependencies));
            pending.appendTag(entry);
        });
        tag.setTag("PendingDependencies", pending);
        machine.requirePersistenceBoundary();
        return tag;
    }

    @SuppressWarnings("DataFlowIssue")
    private void restoreState(MachineInstance machine, NBTTagCompound tag) {
        requireOwner(machine);
        if (tag.getInteger("Version") != 2 || !new ResourceLocation(tag.getString("Type")).equals(type.id())
            || !tag.getString("Revision").equals(revision)) {
            throw new IllegalArgumentException("Unsupported machine save or incompatible declaration revision");
        }
        long generation = tag.getLong("FormationGeneration");
        if (generation < 0) {
            throw new IllegalArgumentException("Formation generation must be nonnegative");
        }
        machine.lifecycle().restoreFormationGeneration(generation);
        var entries = readComponents(tag.getTagList("Components", Constants.NBT.TAG_COMPOUND));
        var pending = readPending(tag.getTagList("PendingDependencies", Constants.NBT.TAG_COMPOUND), entries);
        var dependencies = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>>();
        var order = new TopologicalComponentMap<ComponentType<MachineInstance, ?>, SavedComponent>();
        entries.forEach((descriptor, entry) -> {
            dependencies.put(descriptor, entry.dependencies());
            order.add(descriptor, entry, entry.dependencies());
        });
        ComponentMap.validateGraph(dependencies);
        var ordered = order.orderedComponents();
        var restoredAttributes = attributes.decode(tag.getCompoundTag("Attributes"));
        for (var entry : entries.values()) {
            createBinding(machine, entry.binding());
        }
        for (var entry : entries.values()) {
            var descriptor = entry.binding().type();
            for (var dependency : descriptor.dependencies()) {
                machine.components().removeDependency(descriptor, dependency);
            }
            for (var dependency : entry.dependencies()) {
                machine.components().addDependency(descriptor, dependency);
            }
        }
        pending.forEach((descriptor, edges) -> edges.forEach(dependency -> machine.components().addDependency(descriptor, dependency)));
        machine.attributes().restoreLocal(restoredAttributes);
        supplementDefaults(machine);
        for (int index3 = 0; index3 < ordered.size(); index3++) {
            var node = ordered.get(index3);
            restoreBinding(machine, node.component().binding(), node.component().data());
        }
        validate(machine);
    }

    private static boolean hasOwnership(MachineInstance machine) {
        var elements4 = machine.components().assemblyBindings();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var binding = elements4.get(index4);
            if (binding.component() instanceof MachineOwnership) return true;
        }
        return false;
    }

    private void supplementDefaults(MachineInstance machine) {
        if (machine.requiresNetwork() && machine.networkDevice() == null) {
            if (machine.components().get(MachineOwnership.TYPE) == null)
                createBinding(machine, require(MachineOwnership.TYPE));
            createBinding(machine, require(MachineNetworkDevice.DEFAULT));
        }
        var current = machine.attributes().localSnapshot();
        type.defaultAttributeBases().object2DoubleEntrySet().forEach(entry -> {
            if (!current.containsKey(entry.getKey())) {
                machine.attributes().setBase(entry.getKey(), entry.getDoubleValue());
            }
        });
    }

    private void validate(MachineInstance machine) {
        validate(machine, machine.components().assemblyBindings(), machine.components().snapshotPendingDependencies());
    }

    private void validate(MachineInstance machine, List<ComponentMap.SavedBinding<MachineInstance, ?>> bindings,
                          Map<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>> pendingDependencies) {
        requireOwner(machine);
        for (int index7 = 0; index7 < bindings.size(); index7++) {
            var saved = bindings.get(index7);
            for (var dependency : saved.dependencies()) requireReference(dependency);
            if (isStructureDerived(saved.type())) {
                continue;
            }
            require(saved.type()).validate(machine, saved);
        }
        pendingDependencies.forEach((dependent, dependencies) -> {
            require(dependent);
            for (var dependency : dependencies) {
                requireReference(dependency);
            }
        });
        for (int index1 = 0; index1 < validators.size(); index1++) {
            var validator = validators.get(index1);
            validator.accept(machine);
        }
        machine.requirePersistenceBoundary();
    }

    private void requireOwner(MachineInstance machine) {
        machine.requirePersistenceBoundary();
    }

    private Map<ComponentType<MachineInstance, ?>, SavedComponent> readComponents(NBTTagList list) {
        var entries = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, SavedComponent>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            var binding = require(new ResourceLocation(entry.getString("Type")));
            var data = entry.getCompoundTag("Data");
            var component = new SavedComponent(binding, readTypes(entry), data);
            if (entries.putIfAbsent(binding.type(), component) != null) {
                throw new IllegalArgumentException("Duplicate saved machine component");
            }
        }
        return entries;
    }

    private Map<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>> readPending(
        NBTTagList list, Map<ComponentType<MachineInstance, ?>, SavedComponent> components) {
        var pending = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            var descriptor = require(new ResourceLocation(entry.getString("Type"))).type();
            var dependencies = readTypes(entry);
            if (components.containsKey(descriptor) || dependencies.isEmpty() || pending.putIfAbsent(descriptor, dependencies) != null) {
                throw new IllegalArgumentException("Invalid pending machine component dependencies");
            }
        }
        return pending;
    }

    private NBTTagList writeTypes(Set<ComponentType<MachineInstance, ?>> descriptors) {
        var result = new NBTTagList();
        for (var descriptor : descriptors) {
            requireReference(descriptor);
            result.appendTag(new NBTTagString(descriptor.id().toString()));
        }
        return result;
    }

    private Set<ComponentType<MachineInstance, ?>> readTypes(NBTTagCompound entry) {
        var saved = entry.getTagList("Dependencies", Constants.NBT.TAG_STRING);
        var result = new ObjectLinkedOpenHashSet<ComponentType<MachineInstance, ?>>(saved.tagCount());
        for (int index = 0; index < saved.tagCount(); index++) {
            var id = new ResourceLocation(saved.getStringTagAt(index));
            var descriptor = id.equals(MachineHatches.TYPE.id()) ? MachineHatches.TYPE
                : id.equals(StructureComponents.TYPE.id()) ? StructureComponents.TYPE : require(id).type();
            if (!result.add(descriptor)) {
                throw new IllegalArgumentException("Duplicate machine component dependency");
            }
        }
        return result;
    }

    private Binding<?> require(ResourceLocation id) {
        var binding = bindings.get(id);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown machine component persistence binding: " + id);
        }
        return binding;
    }

    private Binding<?> require(ComponentType<MachineInstance, ?> descriptor) {
        return require(descriptor.id());
    }

    private void requireReference(ComponentType<MachineInstance, ?> descriptor) {
        if (!isStructureDerived(descriptor)) {
            require(descriptor);
        }
    }

    private record SavedComponent(Binding<?> binding, Set<ComponentType<MachineInstance, ?>> dependencies,
                                  NBTTagCompound data) {
    }

    private record Binding<C>(ComponentType<MachineInstance, C> type, @Nullable MachineComponentCodec<C> codec) {
        @SuppressWarnings("unchecked") // Descriptor identity ties this saved component to C.
        private C component(ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            return (C) saved.component();
        }

        private NBTTagCompound encode(MachineInstance owner, ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            return codec == null ? new NBTTagCompound() : codec.encode(owner, component(saved));
        }

        private C restore(MachineInstance owner, C component, NBTTagCompound saved) {
            if (codec == null) {
                return component;
            }
            return codec.restore(owner, component, saved);
        }

        private void validate(MachineInstance owner, ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            if (codec != null) {
                codec.validate(owner, component(saved));
            }
        }
    }

    /**
     * Bind every supported component explicitly; a recreated binding promises it has no persistent state.
     */
    public static final class Builder {
        private final MachineType type;
        private final String revision;
        private final Map<ResourceLocation, Binding<?>> bindings = new Object2ObjectLinkedOpenHashMap<>();
        private final List<Consumer<MachineInstance>> validators = new ObjectArrayList<>();
        private Function<MachineType, ? extends MachineInstance> instanceFactory = MachineInstance::new;
        private AttributeModifierNbt attributes = AttributeModifierNbt.INSTANCE;
        private List<ComputerRecipe> computerRecipes = List.of();

        private Builder(MachineType type, String revision) {
            this.type = type;
            this.revision = revision;
            if (revision.isBlank()) {
                throw new IllegalArgumentException("Machine declaration revision must not be blank");
            }
        }

        private static void requireExternal(ComponentType<MachineInstance, ?> descriptor) {
            if (isStructureDerived(descriptor) || descriptor.id().equals(MachineScheduling.TYPE.id())) {
                throw new IllegalArgumentException("Reserved machine component persistence binding: " + descriptor.id());
            }
        }

        public <C> Builder component(ComponentType<MachineInstance, C> descriptor, MachineComponentCodec<C> codec) {
            requireExternal(descriptor);
            add(bindings, new Binding<>(descriptor, codec));
            return this;
        }

        @SafeVarargs
        public final Builder recreated(ComponentType<MachineInstance, ?>... descriptors) {
            for (var descriptor : descriptors) {
                requireExternal(descriptor);
                if (descriptor.id().equals(MachineWorkSlots.TYPE.id())) {
                    throw new IllegalArgumentException("Work slots require a persistence codec");
                }
                addRecreated(descriptor);
            }
            return this;
        }

        private <C> void addRecreated(ComponentType<MachineInstance, C> descriptor) {
            add(bindings, new Binding<>(descriptor, null));
        }

        /**
         * Creates the same custom runtime subclass for fresh assembly and restoration, before host attachment.
         */
        public Builder instanceFactory(Function<MachineType, ? extends MachineInstance> factory) {
            instanceFactory = factory;
            return this;
        }

        /**
         * Pass the controller's complete recipe declarations and retain the list unchanged for the assembly lifetime.
         * JEI uses this list to display each conversion unit and its local resources.
         */
        public Builder computerRecipes(List<ComputerRecipe> recipes) {
            computerRecipes = recipes;
            return this;
        }

        public Builder attributes(AttributeModifierNbt codec) {
            attributes = codec;
            return this;
        }

        public Builder workSlots(MachineWorkSlotsNbt codec) {
            return component(MachineWorkSlots.TYPE, new MachineComponentCodec<>() {
                @Override
                public void validate(MachineInstance owner, MachineWorkSlots component) {
                    if (component.owner() != owner) {
                        throw new IllegalArgumentException("Foreign machine work slots");
                    }
                    var elements6 = owner.recipeScanners();
                    for (int index6 = 0; index6 < elements6.size(); index6++) {
                        var scanner = elements6.get(index6);
                        if (scanner.host() == component) scanner.validateProcessCodecs(codec.processCodecs());
                    }
                }

                @Override
                public NBTTagCompound encode(MachineInstance owner, MachineWorkSlots component) {
                    if (component.owner() != owner) {
                        throw new IllegalArgumentException("Foreign machine work slots");
                    }
                    return codec.encode(component);
                }

                @Override
                public MachineWorkSlots restore(MachineInstance owner, MachineWorkSlots component, NBTTagCompound saved) {
                    if (component.owner() != owner) {
                        throw new IllegalArgumentException("Foreign machine work slots");
                    }
                    codec.restore(component, saved);
                    return component;
                }
            });
        }

        public Builder validator(Consumer<MachineInstance> validator) {
            validators.add(validator);
            return this;
        }

        /**
         * Build once. Transfers declarations to the assembly; do not modify this builder afterward.
         */
        public MachineAssembly build() {
            return new MachineAssembly(this);
        }
    }
}
