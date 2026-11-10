package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Typed identity and factory, with no mirrored interface/Impl or reflective class discovery.
 */
public final class ComponentType<E, C> {
    private final ResourceLocation id;
    private final Function<E, C> factory;
    private final List<ComponentSystem<E, C>> systems;
    private final Set<ComponentType<E, ?>> dependencies;

    public ComponentType(ResourceLocation id, Function<E, C> factory, Collection<? extends ComponentSystem<E, C>> systems) {
        this(id, factory, systems, ObjectSets.emptySet());
    }

    public ComponentType(ResourceLocation id, Function<E, C> factory, Collection<? extends ComponentSystem<E, C>> systems,
                         Collection<? extends ComponentType<E, ?>> dependencies) {
        this.id = Objects.requireNonNull(id);
        this.factory = Objects.requireNonNull(factory);
        var systemCopy = new ObjectArrayList<ComponentSystem<E, C>>();
        for (var system : systems) {
            systemCopy.add(Objects.requireNonNull(system));
        }
        this.systems = ObjectLists.unmodifiable(systemCopy);
        var dependencyCopy = new ObjectLinkedOpenHashSet<ComponentType<E, ?>>();
        for (var dependency : dependencies) {
            dependencyCopy.add(Objects.requireNonNull(dependency));
        }
        this.dependencies = ObjectSets.unmodifiable(dependencyCopy);
    }

    public ResourceLocation id() {
        return id;
    }

    public C create(E owner) {
        return Objects.requireNonNull(factory.apply(owner), "component factory result");
    }

    public List<ComponentSystem<E, C>> systems() {
        return systems;
    }

    public Set<ComponentType<E, ?>> dependencies() {
        return dependencies;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
