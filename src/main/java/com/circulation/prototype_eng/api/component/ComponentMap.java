package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;

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

    /**
     * Checks declaration identities and the same dependency/system graph used by live dispatch,
     * without invoking a component factory. Absent dependencies remain ordering constraints;
     * declaring a dependency never implicitly creates a component.
     */
    public static <E> void validateTypes(Collection<? extends ComponentType<E, ?>> declarations) {
        var dependencies = new Object2ObjectLinkedOpenHashMap<ComponentType<E, ?>, Set<ComponentType<E, ?>>>();
        for (var type : declarations) {
            Objects.requireNonNull(type);
            if (dependencies.putIfAbsent(type, type.dependencies()) != null) { throw new IllegalArgumentException("Component declared twice: " + type.id()); }
        }
        validateGraph(dependencies);
    }

    /** Validates saved effective edges before restoration invokes any component factory. */
    public static <E> void validateGraph(Map<ComponentType<E, ?>, ? extends Set<ComponentType<E, ?>>> dependencies) {
        var identities = new Object2ObjectOpenHashMap<ResourceLocation, ComponentType<E, ?>>();
        var graph = new TopologicalComponentMap<ComponentType<E, ?>, ComponentType<E, ?>>();
        for (var entry : dependencies.entrySet()) {
            var type = Objects.requireNonNull(entry.getKey());
            var edges = Objects.requireNonNull(entry.getValue());
            if (graph.contains(type)) { throw new IllegalArgumentException("Component declared twice: " + type.id()); }
            requireTypeIdentity(identities, type);
            for (var dependency : edges) { requireTypeIdentity(identities, Objects.requireNonNull(dependency)); }
            graph.add(type, type, edges);
        }
        orderWithSystems(graph);
    }

    private static <E> void requireTypeIdentity(Map<ResourceLocation, ComponentType<E, ?>> identities, ComponentType<E, ?> type) {
        var previous = identities.putIfAbsent(type.id(), type);
        if (previous != null && previous != type) { throw new IllegalArgumentException("Different component types share id " + type.id()); }
    }

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

    /** Used when restoring an immutable component; preserves the existing node and effective graph. */
    public <C> void replaceValue(ComponentType<E, C> type, C component) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(component);
        if (types.get(type.id()) != type) { throw new IllegalArgumentException("Cannot replace absent or foreign component type: " + type.id()); }
        components.replaceValue(type, new Binding<>(type, component));
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

    /** Captures assembly order and effective dependency edges at an exclusive save boundary. */
    public List<SavedBinding<E, ?>> snapshotBindings() {
        orderedTickEntries();
        return assemblyBindings();
    }

    /**
     * Unsorted assembly for exceptional lifecycle cleanup when a broken dependency graph cannot be
     * ordered. This deliberately does not validate the graph and must not replace save validation.
     */
    public List<SavedBinding<E, ?>> assemblyBindings() {
        var result = new ObjectArrayList<SavedBinding<E, ?>>();
        for (var node : components.assemblyComponents()) {
            result.add(node.component().saved(node.dependencies()));
        }
        return ObjectLists.unmodifiable(result);
    }

    /** Captures component dependency order, independently of assembly order, for lifecycle cleanup. */
    public List<SavedBinding<E, ?>> orderedBindings() {
        var result = new ObjectArrayList<SavedBinding<E, ?>>();
        for (var node : components.orderedComponents()) {
            result.add(node.component().saved(node.dependencies()));
        }
        return ObjectLists.unmodifiable(result);
    }

    /** Constraints for dependents not yet installed also survive a save/reload. */
    public Map<ComponentType<E, ?>, Set<ComponentType<E, ?>>> snapshotPendingDependencies() {
        return components.pendingDependencies();
    }

    public record SavedBinding<E, C>(ComponentType<E, C> type, C component, Set<ComponentType<E, ?>> dependencies) {
        public SavedBinding {
            Objects.requireNonNull(type);
            Objects.requireNonNull(component);
            dependencies = ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(dependencies));
        }
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
        var session = beginTick(owner);
        Throwable primary = null;
        try {
            body.run();
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            session.finish(primary);
        }
    }

    /**
     * Starts one captured dispatch and runs all pre callbacks, then all tick callbacks. A callback
     * failure produces no session and never enters post. The caller retains the successful session
     * across its business stages and finishes only after their complete or compensated boundary.
     */
    public TickSession<E> beginTick(E owner) {
        var entries = orderedTickEntries();
        var session = new TickSession<>(owner, entries);
        for (var entry : entries) {
            entry.pre(owner);
        }
        for (var entry : entries) {
            entry.tick(owner);
        }
        return session;
    }

    /** One owner-thread post boundary for exactly the entries captured before pre/tick execution. */
    public static final class TickSession<E> {
        private final Thread creator = Thread.currentThread();
        private final E owner;
        private final List<TickEntry<E, ?>> entries;
        private boolean finished;

        private TickSession(E owner, List<TickEntry<E, ?>> entries) {
            this.owner = owner;
            this.entries = entries;
        }

        /** Attempts every captured post once, aggregating faults after later posts have run. */
        public void finish() { finish(null); }

        /**
         * Attach every post fault to an existing primary without throwing it; its caller owns that
         * fault. Without a primary, throw the aggregated post fault. Reject replay and callback
         * reentry even when the original post dispatch failed.
         */
        public void finish(@Nullable Throwable primary) {
            if (Thread.currentThread() != creator) {
                throw new IllegalStateException("Component tick session must finish on its creating thread");
            }
            if (finished) {
                throw new IllegalStateException("Component tick session already entered its post boundary");
            }
            finished = true;
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
        var entries = new ObjectArrayList<TickEntry<E, ?>>();
        for (var node : orderWithSystems(components)) { node.component().appendEntries(entries); }
        dispatch = ObjectLists.unmodifiable(entries);
        dispatchRevision = components.revision();
        return dispatch;
    }

    private static <E, C> List<TopologicalComponentMap.Node<ComponentType<E, ?>, C>> orderWithSystems(
            TopologicalComponentMap<ComponentType<E, ?>, C> components) {
        var graph = new TopologicalComponentMap<ComponentType<E, ?>, C>();
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
        return graph.orderedComponents();
    }

    private record Binding<E, C>(ComponentType<E, C> type, C component) {
        SavedBinding<E, C> saved(Set<ComponentType<E, ?>> dependencies) {
            return new SavedBinding<>(type, component, dependencies);
        }

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
