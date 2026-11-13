package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Published collections are read-only by contract and must not be modified by consumers.
 * Typed identity and factory, with no mirrored interface/Impl or reflective class discovery.
 */
public final class ComponentType<E, C> {
    private final ResourceLocation id;
    private final BiFunction<E, ComponentType<E, C>, C> factory;
    private final List<ComponentSystem<E, C>> systems;
    private final Set<ComponentType<E, ?>> dependencies;

    public ComponentType(ResourceLocation id, Function<E, C> factory, Collection<? extends ComponentSystem<E, C>> systems) {
        this(id, factory, systems, ObjectSets.emptySet());
    }

    public ComponentType(ResourceLocation id, Function<E, C> factory, Collection<? extends ComponentSystem<E, C>> systems,
                         Collection<? extends ComponentType<E, ?>> dependencies) {
        this(id, (owner, _) -> factory.apply(owner), systems, dependencies);
        Objects.requireNonNull(factory);
    }

    /**
     * Factories that retain their declared binding receive this type directly during creation.
     */
    public ComponentType(ResourceLocation id, BiFunction<E, ComponentType<E, C>, C> factory,
                         Collection<? extends ComponentSystem<E, C>> systems) {
        this(id, factory, systems, ObjectSets.emptySet());
    }

    public ComponentType(ResourceLocation id, BiFunction<E, ComponentType<E, C>, C> factory,
                         Collection<? extends ComponentSystem<E, C>> systems, Collection<? extends ComponentType<E, ?>> dependencies) {
        this.id = Objects.requireNonNull(id);
        this.factory = Objects.requireNonNull(factory);
        var systemCopy = new ObjectArrayList<ComponentSystem<E, C>>(systems.size());
        for (var system : systems) {
            systemCopy.add(Objects.requireNonNull(system));
        }
        this.systems = systemCopy;
        var dependencyCopy = new ObjectLinkedOpenHashSet<ComponentType<E, ?>>(dependencies.size());
        for (var dependency : dependencies) {
            dependencyCopy.add(Objects.requireNonNull(dependency));
        }
        this.dependencies = dependencyCopy;
    }

    public ResourceLocation id() {
        return id;
    }

    public C create(E owner) {
        return Objects.requireNonNull(factory.apply(owner, this), "component factory result");
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
