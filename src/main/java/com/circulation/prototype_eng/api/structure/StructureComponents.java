package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.AffinityKeyProvider;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete immutable port bindings for one accepted structure refresh, independent of tick components.
 */
public final class StructureComponents implements AffinityKeyProvider {
    public static final StructureComponents EMPTY = new StructureComponents(List.of());
    public static final ComponentType<MachineInstance, StructureComponents> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "structure_components"), _ -> EMPTY, List.of());
    private final List<Binding<?>> bindings;
    private final Reference2ObjectMap<ComponentType<MachineInstance, ?>, ObjectList<Binding<?>>> byType;

    public StructureComponents(Collection<Binding<?>> bindings) {
        var built = Objects.requireNonNull(beginCreate(bindings).run());
        this.bindings = built.bindings;
        byType = built.byType;
    }

    private StructureComponents(List<Binding<?>> bindings,
                                Reference2ObjectMap<ComponentType<MachineInstance, ?>, ObjectList<Binding<?>>> byType) {
        this.bindings = Collections.unmodifiableList(bindings);
        this.byType = Reference2ObjectMaps.unmodifiable(byType);
    }

    /** Validation, indexing and immutable ownership transfer visit each binding and each type. */
    public static StructureOperation<StructureComponents> beginCreate(Collection<Binding<?>> bindings) {
        var iterator = bindings.iterator();
        return new StructureOperation<>() {
            private final ObjectArrayList<Binding<?>> copy = new ObjectArrayList<>();
            private final Reference2ObjectOpenHashMap<ComponentType<MachineInstance, ?>, ObjectList<Binding<?>>> indexed = new Reference2ObjectOpenHashMap<>();
            private final ObjectLinkedOpenHashSet<Port> identities = new ObjectLinkedOpenHashSet<>();
            private final ReferenceOpenHashSet<Object> components = new ReferenceOpenHashSet<>();
            private final Object2ObjectOpenHashMap<ResourceLocation, ComponentType<MachineInstance, ?>> types = new Object2ObjectOpenHashMap<>();
            @Nullable
            private Iterator<Reference2ObjectMap.Entry<ComponentType<MachineInstance, ?>, ObjectList<Binding<?>>>> freezing;

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                while (iterator.hasNext()) {
                    if (!work.take()) return false;
                    var binding = Objects.requireNonNull(iterator.next());
                    var entry = binding.entry();
                    if (!components.add(entry.component()))
                        throw new IllegalArgumentException("A structure port instance was registered twice");
                    var previous = types.putIfAbsent(entry.type().id(), entry.type());
                    if (previous != null && previous != entry.type())
                        throw new IllegalArgumentException("Different structure component types share " + entry.type().id());
                    if (!identities.add(new Port(binding.context().providerPos(), entry.type(), entry.portId())))
                        throw new IllegalArgumentException("Duplicate structure port at " + binding.context().providerPos() + ": " + entry.portId());
                    copy.add(binding);
                    indexed.computeIfAbsent(entry.type(), _ -> new ObjectArrayList<>()).add(binding);
                }
                if (freezing == null) freezing = indexed.reference2ObjectEntrySet().iterator();
                while (freezing.hasNext()) {
                    if (!work.take()) return false;
                    var entry = freezing.next();
                    entry.setValue(ObjectLists.unmodifiable(entry.getValue()));
                }
                if (!work.take()) return false;
                finish(new StructureComponents(copy, indexed));
                return true;
            }
        };
    }

    public List<Binding<?>> bindings() {
        return bindings;
    }

    /**
     * Query by canonical descriptor, preserving stable position and provider entry order.
     */
    @SuppressWarnings("unchecked") // Each Entry<C> ties its value to its canonical descriptor identity.
    public <C> List<Binding<C>> get(ComponentType<MachineInstance, C> type) {
        Objects.requireNonNull(type);
        return (List<Binding<C>>) (List<?>) byType.getOrDefault(type, ObjectLists.emptyList());
    }

    @Nullable
    public <C> C find(ComponentType<MachineInstance, C> type, BlockPos providerPos, String portId) {
        Objects.requireNonNull(providerPos);
        Objects.requireNonNull(portId);
        for (var binding : get(type)) {
            if (binding.context().providerPos().equals(providerPos) && binding.entry().portId().equals(portId)) {
                return binding.entry().component();
            }
        }
        return null;
    }

    @Override
    public Set<?> affinityKeys() {
        var keys = new ObjectLinkedOpenHashSet<>();
        for (var binding : bindings) {
            if (binding.entry().component() instanceof AffinityKeyProvider provider) {
                for (var key : Objects.requireNonNull(provider.affinityKeys())) {
                    keys.add(Objects.requireNonNull(key));
                }
            }
        }
        return ObjectSets.unmodifiable(keys);
    }

    public record Binding<C>(StructureComponentProvider.Context context, StructureComponentProvider.Entry<C> entry) {
        public Binding {
            Objects.requireNonNull(context);
            Objects.requireNonNull(entry);
        }
    }

    private record Port(BlockPos providerPos, ComponentType<MachineInstance, ?> type, String portId) {
    }
}
