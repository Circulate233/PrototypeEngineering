package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Typed component assembly and ordered phase execution migrated from PM's MachineComponentMap.
 * One owner executes/mutates it at a time. A tick captures its entire dispatch list once, so edits
 * made by callbacks take effect on the next tick. The plain tick aborts on any callback failure;
 * the body overload preserves post cleanup after entering its business body.
 */
public final class ComponentMap<E> {
    private final TopologicalComponentMap<ComponentType<E, ?>, Binding<E, ?>> components = new TopologicalComponentMap<>();
    private final Object2ObjectOpenHashMap<ResourceLocation, ComponentType<E, ?>> types = new Object2ObjectOpenHashMap<>();
    private long dispatchRevision = -1;
    private List<TickEntry<E, ?>> dispatch = ObjectLists.emptyList();

    public <C> C create(ComponentType<E, C> type, E owner) {
        C component = type.create(owner);
        put(type, component);
        return component;
    }

    public <C> void put(ComponentType<E, C> type, C component) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(component);
        var previous = types.get(type.id());
        if (previous != null && previous != type) {
            throw new IllegalArgumentException("Different component types share id " + type.id());
        }
        types.put(type.id(), type);
        components.add(type, new Binding<>(type, component), type.dependencies());
    }

    @Nullable
    @SuppressWarnings("unchecked") // A value enters only through put(type<C>, C); type uses object identity.
    public <C> C get(ComponentType<E, C> type) {
        var binding = components.get(type);
        return binding == null ? null : (C) binding.component();
    }

    public void remove(ComponentType<E, ?> type) {
        if (!components.contains(type)) {
            return;
        }
        components.remove(type);
        types.remove(type.id());
    }

    /** Downstream mods can reset the components owned by their machine or process. */
    @SuppressWarnings("unused")
    public void clear() {
        components.clear();
        types.clear();
    }

    /** Downstream mods can add ordering constraints while assembling components. */
    @SuppressWarnings("unused")
    public void addDependency(ComponentType<E, ?> dependent, ComponentType<E, ?> dependency) {
        components.addDependency(dependent, dependency);
    }

    /** Downstream mods can remove ordering constraints when their assembly changes. */
    @SuppressWarnings("unused")
    public void removeDependency(ComponentType<E, ?> dependent, ComponentType<E, ?> dependency) {
        components.removeDependency(dependent, dependency);
    }

    /**
     * Captures all phases before invoking any callback.
     */
    public void tick(E owner) {
        var entries = orderedTickEntries();
        for (var entry : entries) {
            entry.pre(owner);
        }
        for (var entry : entries) {
            entry.tick(owner);
        }
        for (var entry : entries) {
            entry.post(owner);
        }
    }

    /**
     * Captures one dispatch for pre, tick, body and post. Pre/tick faults abort before body and post,
     * preserving PM's process callback boundary and the plain tick's fail-fast behavior. Once body
     * starts, every captured post callback runs in order, even after a body or earlier post fault.
     * A body fault remains primary; post faults are suppressed rather than replacing it.
     */
    public void tick(E owner, Runnable body) {
        Objects.requireNonNull(body);
        var entries = orderedTickEntries();
        for (var entry : entries) {
            entry.pre(owner);
        }
        for (var entry : entries) {
            entry.tick(owner);
        }
        Throwable primary = null;
        try {
            body.run();
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            postAll(owner, entries, primary);
        }
    }

    private static <E> void postAll(E owner, List<TickEntry<E, ?>> entries, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (var entry : entries) {
            try {
                entry.post(owner);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private List<TickEntry<E, ?>> orderedTickEntries() {
        if (dispatchRevision == components.revision()) {
            return dispatch;
        }
        var graph = new TopologicalComponentMap<ComponentType<E, ?>, Binding<E, ?>>();
        var bySystem = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<ComponentType<E, ?>>>();
        var systemDefinitions = new Object2ObjectOpenHashMap<ResourceLocation, ComponentSystem<E, ?>>();
        for (var node : components.orderedComponents()) {
            graph.add(node.key(), node.component(), node.dependencies());
            for (var system : node.key().systems()) {
                var id = Objects.requireNonNull(system.id(), "system id");
                var previous = systemDefinitions.putIfAbsent(id, system);
                if (previous != null && previous != system) {
                    throw new IllegalArgumentException("Different systems share id " + id);
                }
                var owners = bySystem.computeIfAbsent(id, ignored -> new ObjectArrayList<>());
                if (owners.contains(node.key())) {
                    throw new IllegalArgumentException("System bound twice: " + id);
                }
                owners.add(node.key());
            }
        }
        for (var node : components.orderedComponents()) {
            for (var system : node.key().systems()) {
                for (var after : system.runAfter()) {
                    var owners = bySystem.get(after);
                    if (owners != null) {
                        for (var other : owners) {
                            if (other != node.key()) {
                                graph.addDependency(node.key(), other);
                            }
                        }
                    }
                }
                for (var before : system.runBefore()) {
                    var owners = bySystem.get(before);
                    if (owners != null) {
                        for (var other : owners) {
                            if (other != node.key()) {
                                graph.addDependency(other, node.key());
                            }
                        }
                    }
                }
            }
        }
        var entries = new ObjectArrayList<TickEntry<E, ?>>();
        for (var node : graph.orderedComponents()) {
            node.component().appendEntries(entries);
        }
        dispatch = ObjectLists.unmodifiable(entries);
        dispatchRevision = components.revision();
        return dispatch;
    }

    private record Binding<E, C>(ComponentType<E, C> type, C component) {
        void appendEntries(ObjectArrayList<TickEntry<E, ?>> entries) {
            // PM preserves the declared order of multiple systems attached to one component.
            for (var system : type.systems()) {
                entries.add(new TickEntry<>(system, component));
            }
        }
    }

    private record TickEntry<E, C>(ComponentSystem<E, C> system, C component) {
        void pre(E owner) {
            system.onPreTick(owner, component);
        }

        void tick(E owner) {
            system.onTick(owner, component);
        }

        void post(E owner) {
            system.onPostTick(owner, component);
        }
    }
}
