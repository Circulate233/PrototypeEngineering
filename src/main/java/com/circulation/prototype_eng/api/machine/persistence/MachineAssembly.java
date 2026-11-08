package com.circulation.prototype_eng.api.machine.persistence;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.TopologicalComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.persistence.MachineAttributesNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
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
import java.util.function.Consumer;

import com.circulation.prototype_eng.api.structure.StructureComponents;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import java.util.function.Function;

/**
 * Attached-host assembly and explicit persistence for one canonical machine declaration revision.
 * Creation/restoration completes before lifecycle load; successful load returns an unregistered
 * machine for the controller to register with its scheduler. No formed/configuration/epoch state
 * is restored. Revision changes cover incompatible factories, codecs, layouts and external behavior.
 */
public final class MachineAssembly {
    private static final MachineComponentCodec<MachineScheduling> SCHEDULING = new MachineComponentCodec<>() {
        @Override public NBTTagCompound encode(MachineInstance owner, MachineScheduling component) {
            var tag = new NBTTagCompound();
            tag.setString("Mode", component.mode().name());
            return tag;
        }
        @Override public MachineScheduling restore(MachineInstance owner, MachineScheduling component, NBTTagCompound saved) {
            StrictNbt.requireKeys(saved, "Mode");
            return new MachineScheduling(ExecutionMode.valueOf(StrictNbt.requireString(saved, "Mode")));
        }
    };

    private final Function<MachineType, ? extends MachineInstance> instanceFactory;

    private final MachineType type;
    private final String revision;
    private final MachineAttributesNbt attributes;
    private final Map<ResourceLocation, Binding<?>> bindings;
    private final List<Consumer<MachineInstance>> validators;

    private MachineAssembly(Builder builder) {
        instanceFactory = builder.instanceFactory;
        type = builder.type;
        revision = builder.revision;
        attributes = new MachineAttributesNbt(builder.attributes);
        validators = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.validators));
        var copy = new Object2ObjectLinkedOpenHashMap<>(builder.bindings);
        add(copy, new Binding<>(MachineScheduling.TYPE, SCHEDULING));
        add(copy, new Binding<>(MachineOwnership.TYPE, MachineOwnership.CODEC));
        bindings = Object2ObjectMaps.unmodifiable(copy);
        for (var declared : type.componentTypes()) {
            if (isStructureDerived(declared)) {
                throw new IllegalArgumentException("Structure-derived hatches must be established by structure publication, not declared as components");
            }
            require(declared);
            for (var dependency : declared.dependencies()) {
                requireReference(dependency);
            }
        }
    }

    public static Builder builder(MachineType type, String revision) { return new Builder(type, revision); }

    /** The exact declaration this assembly creates and restores. */
    public MachineType type() { return type; }

    /** Read this assembly's explicit ownership codec without factories, loading or world effects. */
    @Nullable
    public PersistentMachineIdentity savedIdentity(NBTTagCompound saved) {
        var tag = Objects.requireNonNull(saved).copy();
        StrictNbt.requireKeys(tag, "Version", "Type", "Revision", "Attributes", "Components", "PendingDependencies", "FormationGeneration");
        if (StrictNbt.requireInt(tag, "Version") != 2 || !StrictNbt.requireId(tag, "Type").equals(type.id())
            || !StrictNbt.requireString(tag, "Revision").equals(revision)) {
            throw new IllegalArgumentException("Saved identity requires this exact machine assembly");
        }
        PersistentMachineIdentity identity = null;
        for (var component : readComponents(StrictNbt.requireList(tag, "Components", Constants.NBT.TAG_COMPOUND)).values()) {
            if (component.binding().codec() == MachineOwnership.CODEC) {
                if (identity != null) {
                    throw new IllegalArgumentException("Saved machine has multiple ownership components");
                }
                identity = PersistentMachineIdentity.decode(component.data());
            }
        }
        return identity;
    }

    private MachineInstance createInstance() {
        var machine = Objects.requireNonNull(instanceFactory.apply(type), "Machine instance factory returned null");
        if (machine.type() != type || machine.lifecycle() != null || !machine.components().assemblyBindings().isEmpty()
            || !machine.components().snapshotPendingDependencies().isEmpty()) {
            throw new IllegalArgumentException("Machine instance factory must return a fresh empty instance of this canonical type");
        }
        return machine;
    }

    public MachineInstance create(MachineLifecycleHost host) { return create(host, MachineLifecycleRegistry.GLOBAL); }

    public MachineInstance create(MachineLifecycleHost host, MachineLifecycleRegistry registry) {
        var machine = createInstance();
        var lifecycle = new MachineLifecycle(machine, host, registry);
        lifecycle.assemble(() -> {
            for (var declared : type.componentTypes()) {
                createBinding(machine, require(declared));
            }
            if (machine.components().get(MachineScheduling.TYPE) == null) { createBinding(machine, require(MachineScheduling.TYPE)); }
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
        lifecycle.assemble(() -> restoreState(machine, Objects.requireNonNull(saved).copy()));
        lifecycle.load(MachineLifecycleEvent.LoadBoundary.RESTORED);
        return machine;
    }

    public NBTTagCompound encode(MachineInstance machine) {
        Objects.requireNonNull(machine);
        if (machine.type() != type) { throw new IllegalArgumentException("Machine belongs to a different canonical declaration"); }
        var lifecycle = Objects.requireNonNull(machine.lifecycle(), "Machine has no attached lifecycle");
        return lifecycle.withPersistence(() -> encodeState(machine)).copy();
    }

    private NBTTagCompound encodeState(MachineInstance machine) {
        requireOwner(machine);
        var graph = Graph.capture(machine);
        var local = machine.attributes().localSnapshot();
        validate(machine);
        requireUnchanged(machine, graph, local);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 2);
        tag.setString("Type", type.id().toString());
        tag.setString("Revision", revision);
        tag.setLong("FormationGeneration", Objects.requireNonNull(machine.lifecycle()).formationGeneration());
        tag.setTag("Attributes", attributes.encode(local).copy());
        requireUnchanged(machine, graph, local);
        var components = new NBTTagList();
        for (var saved : graph.components()) {
            if (isStructureDerived(saved.type())) {
                continue;
            }
            var binding = require(saved.type());
            var entry = new NBTTagCompound();
            entry.setString("Type", saved.type().id().toString());
            entry.setTag("Dependencies", writeTypes(saved.dependencies()));
            requireUnchanged(machine, graph, local);
            entry.setTag("Data", binding.encode(machine, saved).copy());
            requireUnchanged(machine, graph, local);
            components.appendTag(entry);
        }
        tag.setTag("Components", components);
        var pending = new NBTTagList();
        graph.pending().forEach((dependent, dependencies) -> {
            require(dependent);
            var entry = new NBTTagCompound();
            entry.setString("Type", dependent.id().toString());
            entry.setTag("Dependencies", writeTypes(dependencies));
            pending.appendTag(entry);
        });
        tag.setTag("PendingDependencies", pending);
        requireUnchanged(machine, graph, local);
        machine.requirePersistenceBoundary();
        return tag;
    }

    private void restoreState(MachineInstance machine, NBTTagCompound tag) {
        requireOwner(machine);
        StrictNbt.requireKeys(tag, "Version", "Type", "Revision", "Attributes", "Components", "PendingDependencies", "FormationGeneration");
        if (StrictNbt.requireInt(tag, "Version") != 2 || !StrictNbt.requireId(tag, "Type").equals(type.id())
            || !StrictNbt.requireString(tag, "Revision").equals(revision)) {
            throw new IllegalArgumentException("Unsupported machine save or incompatible declaration revision");
        }
        long generation = StrictNbt.requireLong(tag, "FormationGeneration");
        if (generation < 0) {
            throw new IllegalArgumentException("Formation generation must be nonnegative");
        }
        Objects.requireNonNull(machine.lifecycle()).restoreFormationGeneration(generation);
        var entries = readComponents(StrictNbt.requireList(tag, "Components", Constants.NBT.TAG_COMPOUND));
        var pending = readPending(StrictNbt.requireList(tag, "PendingDependencies", Constants.NBT.TAG_COMPOUND), entries);
        var dependencies = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>>();
        var order = new TopologicalComponentMap<ComponentType<MachineInstance, ?>, SavedComponent>();
        entries.forEach((descriptor, entry) -> {
            dependencies.put(descriptor, entry.dependencies());
            order.add(descriptor, entry, entry.dependencies());
        });
        ComponentMap.validateGraph(dependencies);
        var ordered = order.orderedComponents();
        var before = Graph.capture(machine);
        var beforeAttributes = machine.attributes().localSnapshot();
        var restoredAttributes = attributes.decode(StrictNbt.requireCompound(tag, "Attributes").copy());
        requireUnchanged(machine, before, beforeAttributes);
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
        for (var node : ordered) {
            restoreBinding(machine, node.component().binding(), node.component().data());
        }
        validate(machine);
    }

    private static <C> void createBinding(MachineInstance machine, Binding<C> binding) {
        var before = Graph.capture(machine);
        var component = binding.type().create(machine);
        before.requireSame(machine);
        machine.components().put(binding.type(), component);
    }

    private static <C> void restoreBinding(MachineInstance machine, Binding<C> binding, NBTTagCompound tag) {
        var before = Graph.capture(machine);
        var local = machine.attributes().localSnapshot();
        var component = Objects.requireNonNull(machine.components().get(binding.type()));
        var restored = binding.restore(machine, component, tag.copy());
        requireUnchanged(machine, before, local);
        if (restored != component) { machine.components().replaceValue(binding.type(), restored); }
    }

    private static boolean isStructureDerived(ComponentType<MachineInstance, ?> descriptor) {
        if (descriptor.id().equals(StructureComponents.TYPE.id())) {
            if (descriptor != StructureComponents.TYPE) {
                throw new IllegalArgumentException("Different component descriptor uses the structure-port ID");
            }
            return true;
        }
        if (!descriptor.id().equals(MachineHatches.TYPE.id())) {
            return false;
        }
        if (descriptor != MachineHatches.TYPE) {
            throw new IllegalArgumentException("Different component descriptor uses the structure-hatches ID");
        }
        return true;
    }

    private void supplementDefaults(MachineInstance machine) {
        var current = machine.attributes().localSnapshot();
        type.defaultAttributeBases().object2DoubleEntrySet().forEach(entry -> {
            if (!current.containsKey(entry.getKey())) { machine.attributes().setBase(entry.getKey(), entry.getDoubleValue()); }
        });
    }

    private void validate(MachineInstance machine) {
        requireOwner(machine);
        var graph = Graph.capture(machine);
        var local = machine.attributes().localSnapshot();
        machine.components().snapshotBindings();
        requireUnchanged(machine, graph, local);
        for (var saved : graph.components()) {
            if (isStructureDerived(saved.type())) {
                continue;
            }
            require(saved.type()).validate(machine, saved);
            requireUnchanged(machine, graph, local);
        }
        graph.pending().forEach((dependent, dependencies) -> {
            require(dependent);
            for (var dependency : dependencies) {
                requireReference(dependency);
            }
        });
        for (var saved : graph.components()) {
            for (var dependency : saved.dependencies()) {
                requireReference(dependency);
            }
        }
        for (var validator : validators) {
            requireUnchanged(machine, graph, local);
            validator.accept(machine);
            requireUnchanged(machine, graph, local);
        }
        machine.requirePersistenceBoundary();
    }

    private void requireOwner(MachineInstance machine) {
        if (machine.type() != type) { throw new IllegalArgumentException("Machine belongs to a different canonical declaration"); }
        machine.requirePersistenceBoundary();
    }

    private Map<ComponentType<MachineInstance, ?>, SavedComponent> readComponents(NBTTagList list) {
        var entries = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, SavedComponent>();
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Type", "Dependencies", "Data");
            var binding = require(StrictNbt.requireId(entry, "Type"));
            var data = StrictNbt.requireCompound(entry, "Data").copy();
            if (binding.codec() == null) { StrictNbt.requireKeys(data); }
            var component = new SavedComponent(binding, readTypes(entry), data);
            if (entries.putIfAbsent(binding.type(), component) != null) { throw new IllegalArgumentException("Duplicate saved machine component"); }
        }
        return entries;
    }

    private Map<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>> readPending(
            NBTTagList list, Map<ComponentType<MachineInstance, ?>, SavedComponent> components) {
        var pending = new Object2ObjectLinkedOpenHashMap<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>>();
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Type", "Dependencies");
            var descriptor = require(StrictNbt.requireId(entry, "Type")).type();
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
        var result = new ObjectLinkedOpenHashSet<ComponentType<MachineInstance, ?>>();
        var saved = StrictNbt.requireList(entry, "Dependencies", Constants.NBT.TAG_STRING);
        for (int index = 0; index < saved.tagCount(); index++) {
            var id = StrictNbt.requireId(saved.getStringTagAt(index));
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
        if (binding == null) { throw new IllegalArgumentException("Unknown machine component persistence binding: " + id); }
        return binding;
    }

    private Binding<?> require(ComponentType<MachineInstance, ?> descriptor) {
        var binding = require(descriptor.id());
        if (binding.type() != descriptor) { throw new IllegalArgumentException("Different component descriptors share " + descriptor.id()); }
        return binding;
    }

    private void requireReference(ComponentType<MachineInstance, ?> descriptor) {
        if (!isStructureDerived(descriptor)) {
            require(descriptor);
        }
    }

    private static void requireUnchanged(MachineInstance machine, Graph graph, Map<ResourceLocation, MachineAttributes.LocalAttribute> attributes) {
        graph.requireSame(machine);
        requireSameAttributes(attributes, machine.attributes().localSnapshot());
        graph.requireSame(machine);
    }

    private static void requireSameAttributes(Map<ResourceLocation, MachineAttributes.LocalAttribute> expected,
                                              Map<ResourceLocation, MachineAttributes.LocalAttribute> actual) {
        if (expected.size() != actual.size()) { throw new IllegalStateException("Machine callback changed owner attributes"); }
        var original = expected.entrySet().iterator();
        var current = actual.entrySet().iterator();
        while (original.hasNext()) {
            var before = original.next();
            var after = current.next();
            var left = before.getValue();
            var right = after.getValue();
            if (!before.getKey().equals(after.getKey()) || !Objects.equals(left.baseOverride(), right.baseOverride())
                || left.inheritModifiers() != right.inheritModifiers() || left.modifiers().size() != right.modifiers().size()) {
                throw new IllegalStateException("Machine callback changed owner attributes");
            }
            var oldModifiers = left.modifiers().entrySet().iterator();
            var newModifiers = right.modifiers().entrySet().iterator();
            while (oldModifiers.hasNext()) {
                var old = oldModifiers.next();
                var replacement = newModifiers.next();
                if (!old.getKey().equals(replacement.getKey()) || old.getValue() != replacement.getValue()) {
                    throw new IllegalStateException("Machine callback replaced an owner attribute modifier");
                }
            }
        }
    }

    private record Graph(List<ComponentMap.SavedBinding<MachineInstance, ?>> components,
                         Map<ComponentType<MachineInstance, ?>, Set<ComponentType<MachineInstance, ?>>> pending) {
        private static Graph capture(MachineInstance machine) {
            return new Graph(machine.components().assemblyBindings(), machine.components().snapshotPendingDependencies());
        }

        private void requireSame(MachineInstance machine) {
            var current = capture(machine);
            if (components.size() != current.components.size() || pending.size() != current.pending.size()) { changed(); }
            for (int index = 0; index < components.size(); index++) {
                var before = components.get(index);
                var after = current.components.get(index);
                if (before.type() != after.type() || before.component() != after.component()
                    || !sameTypes(before.dependencies(), after.dependencies())) { changed(); }
            }
            for (var entry : pending.entrySet()) {
                var after = current.pending.get(entry.getKey());
                if (after == null || !sameTypes(entry.getValue(), after)) { changed(); }
            }
        }

        private static boolean sameTypes(Set<ComponentType<MachineInstance, ?>> left, Set<ComponentType<MachineInstance, ?>> right) {
            if (left.size() != right.size()) { return false; }
            var first = left.iterator();
            var second = right.iterator();
            while (first.hasNext()) { if (first.next() != second.next()) { return false; } }
            return true;
        }

        private static void changed() { throw new IllegalStateException("Machine callback changed component assembly or dependencies"); }
    }

    private record SavedComponent(Binding<?> binding, Set<ComponentType<MachineInstance, ?>> dependencies, NBTTagCompound data) { }

    private record Binding<C>(ComponentType<MachineInstance, C> type, @Nullable MachineComponentCodec<C> codec) {
        @SuppressWarnings("unchecked") // Descriptor identity ties this saved component to C.
        private C component(ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            if (saved.type() != type) { throw new IllegalArgumentException("Machine component codec type mismatch"); }
            return (C) saved.component();
        }
        private NBTTagCompound encode(MachineInstance owner, ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            return codec == null ? new NBTTagCompound() : Objects.requireNonNull(codec.encode(owner, component(saved)), "Machine component save");
        }
        private C restore(MachineInstance owner, C component, NBTTagCompound saved) {
            if (codec == null) { StrictNbt.requireKeys(saved); return component; }
            return Objects.requireNonNull(codec.restore(owner, component, saved), "Restored machine component");
        }
        private void validate(MachineInstance owner, ComponentMap.SavedBinding<MachineInstance, ?> saved) {
            if (codec != null) { codec.validate(owner, component(saved)); }
        }
    }

    private static void add(Map<ResourceLocation, Binding<?>> bindings, Binding<?> binding) {
        if (bindings.putIfAbsent(binding.type().id(), binding) != null) {
            throw new IllegalArgumentException("Duplicate machine component persistence binding: " + binding.type().id());
        }
    }

    /** Bind every supported component explicitly; a recreated binding promises it has no persistent state. */
    public static final class Builder {
        private final MachineType type;
        private final String revision;
        private AttributeModifierNbt attributes = new AttributeModifierNbt();
        private final Map<ResourceLocation, Binding<?>> bindings = new Object2ObjectLinkedOpenHashMap<>();
        private final List<Consumer<MachineInstance>> validators = new ObjectArrayList<>();
        private Function<MachineType, ? extends MachineInstance> instanceFactory = MachineInstance::new;

        private Builder(MachineType type, String revision) {
            this.type = Objects.requireNonNull(type);
            this.revision = Objects.requireNonNull(revision);
            if (revision.isBlank()) { throw new IllegalArgumentException("Machine declaration revision must not be blank"); }
        }
        public <C> Builder component(ComponentType<MachineInstance, C> descriptor, MachineComponentCodec<C> codec) {
            requireExternal(descriptor);
            add(bindings, new Binding<>(descriptor, Objects.requireNonNull(codec)));
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
        private <C> void addRecreated(ComponentType<MachineInstance, C> descriptor) { add(bindings, new Binding<>(descriptor, null)); }

        /** Creates the same custom runtime subclass for fresh assembly and restoration, before host attachment. */
        public Builder instanceFactory(Function<MachineType, ? extends MachineInstance> factory) {
            instanceFactory = Objects.requireNonNull(factory);
            return this;
        }
        public Builder attributes(AttributeModifierNbt codec) { attributes = Objects.requireNonNull(codec); return this; }
        public Builder workSlots(MachineWorkSlotsNbt codec) {
            Objects.requireNonNull(codec);
            return component(MachineWorkSlots.TYPE, new MachineComponentCodec<>() {
                @Override public NBTTagCompound encode(MachineInstance owner, MachineWorkSlots component) {
                    if (component.owner() != owner) { throw new IllegalArgumentException("Foreign machine work slots"); }
                    return codec.encode(component);
                }
                @Override public MachineWorkSlots restore(MachineInstance owner, MachineWorkSlots component, NBTTagCompound saved) {
                    if (component.owner() != owner) { throw new IllegalArgumentException("Foreign machine work slots"); }
                    codec.restore(component, saved);
                    return component;
                }
            });
        }
        public Builder validator(Consumer<MachineInstance> validator) { validators.add(Objects.requireNonNull(validator)); return this; }
        public MachineAssembly build() { return new MachineAssembly(this); }

        private static void requireExternal(ComponentType<MachineInstance, ?> descriptor) {
            Objects.requireNonNull(descriptor);
            if (isStructureDerived(descriptor) || descriptor.id().equals(MachineScheduling.TYPE.id())) {
                throw new IllegalArgumentException("Reserved machine component persistence binding: " + descriptor.id());
            }
        }
    }
}
