package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Published collections are read-only by contract and must not be modified by consumers.
 * Component identity, factory and borrowed declarations. Inputs must remain unchanged while in use.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class ComponentType<E, C> {
    private final ResourceLocation id;
    private final BiFunction<E, ComponentType<E, C>, C> factory;
    private final List<ComponentSystem<E, C>> systems;
    private final Set<ComponentType<E, ?>> dependencies;

    public ComponentType(ResourceLocation id, Function<E, C> factory, List<ComponentSystem<E, C>> systems) {
        this(id, factory, systems, ObjectSets.emptySet());
    }

    public ComponentType(ResourceLocation id, Function<E, C> factory, List<ComponentSystem<E, C>> systems,
                         Set<ComponentType<E, ?>> dependencies) {
        this(id, (owner, _) -> factory.apply(owner), systems, dependencies);
    }

    /**
     * Factories that retain their declared binding receive this type directly during creation.
     */
    public ComponentType(ResourceLocation id, BiFunction<E, ComponentType<E, C>, C> factory,
                         List<ComponentSystem<E, C>> systems) {
        this(id, factory, systems, ObjectSets.emptySet());
    }

    public ComponentType(ResourceLocation id, BiFunction<E, ComponentType<E, C>, C> factory,
                         List<ComponentSystem<E, C>> systems, Set<ComponentType<E, ?>> dependencies) {
        this.id = id;
        this.factory = factory;
        this.systems = systems;
        this.dependencies = dependencies;
    }

    public ResourceLocation id() {
        return id;
    }

    public C create(E owner) {
        return factory.apply(owner, this);
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
