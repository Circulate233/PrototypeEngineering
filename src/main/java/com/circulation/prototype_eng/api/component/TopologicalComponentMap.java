package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.Collections;

/**
 * PM's soft dependency graph, with a cached Kahn ordering. Missing dependencies become active when
 * installed, including after removal/reinstallation. Independent nodes retain deterministic order.
 * Mutations and reads belong to one execution owner; a volatile revision would not make this graph
 * safe for concurrent mutation. Schedulers must transfer ownership at their join boundaries.
 */
public final class TopologicalComponentMap<K, C> {
    private final Object2ObjectLinkedOpenHashMap<K, Node<K, C>> nodes = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectOpenHashMap<K, ObjectLinkedOpenHashSet<K>> pending = new Object2ObjectOpenHashMap<>();
    private List<Node<K, C>> cached = ObjectLists.emptyList();
    private long revision;
    private long cachedRevision;

    public long revision() {
        return revision;
    }

    @Nullable
    public C get(K key) {
        var node = nodes.get(key);
        return node == null ? null : node.component();
    }

    public boolean contains(K key) {
        return nodes.containsKey(key);
    }

    public void add(K key, C component) {
        add(key, component, ObjectSets.emptySet());
    }

    /** Replace an existing value without moving its node or changing any ordering constraints. */
    public void replaceValue(K key, C component) {
        var node = nodes.get(key);
        if (node == null) { throw new IllegalArgumentException("Cannot replace an absent component: " + key); }
        nodes.put(key, new Node<>(key, component, node.dependencies()));
        revision++;
    }

    /**
     * Replaces this node's own dependencies; incoming dependencies remain attached to its key.
     */
    public void add(K key, C component, Collection<? extends K> dependencies) {
        var merged = new ObjectLinkedOpenHashSet<K>(dependencies);
        var waiting = pending.get(key);
        if (waiting != null) {
            merged.addAll(waiting);
        }
        var node = new Node<>(key, component, merged);
        nodes.remove(key);
        nodes.put(key, node);
        pending.remove(key);
        revision++;
    }

    public void addDependency(K dependent, K dependency) {
        Objects.requireNonNull(dependent);
        Objects.requireNonNull(dependency);
        var node = nodes.get(dependent);
        if (node == null) {
            if (pending.computeIfAbsent(dependent, ignored -> new ObjectLinkedOpenHashSet<>()).add(dependency)) {
                revision++;
            }
        } else if (!node.dependencies().contains(dependency)) {
            var dependencies = new ObjectLinkedOpenHashSet<>(node.dependencies());
            dependencies.add(dependency);
            nodes.put(dependent, new Node<>(dependent, node.component(), dependencies));
            revision++;
        }
    }

    public void removeDependency(K dependent, K dependency) {
        var node = nodes.get(dependent);
        if (node == null) {
            var waiting = pending.get(dependent);
            if (waiting != null && waiting.remove(dependency)) {
                if (waiting.isEmpty()) {
                    pending.remove(dependent);
                }
                revision++;
            }
        } else if (node.dependencies().contains(dependency)) {
            var dependencies = new ObjectLinkedOpenHashSet<>(node.dependencies());
            dependencies.remove(dependency);
            nodes.put(dependent, new Node<>(dependent, node.component(), dependencies));
            revision++;
        }
    }

    /** Downstream mods can insert a component after an existing or pending target. */
    @SuppressWarnings("unused")
    public void addAfter(K target, K key, C component) {
        add(key, component, ObjectSets.singleton(target));
    }

    public void addBefore(K target, K key, C component) {
        add(key, component);
        addDependency(target, key);
    }

    public void addFirst(K key, C component) {
        var roots = new ObjectArrayList<K>();
        for (var node : nodes.values()) {
            if (!node.key().equals(key) && node.dependencies().stream().noneMatch(nodes::containsKey)) {
                roots.add(node.key());
            }
        }
        add(key, component);
        for (K root : roots) {
            addDependency(root, key);
        }
    }

    public void addTail(K key, C component) {
        var leaves = new ObjectLinkedOpenHashSet<>(nodes.keySet());
        leaves.remove(key);
        for (var node : nodes.values()) {
            if (!node.key().equals(key)) {
                leaves.removeAll(node.dependencies());
            }
        }
        add(key, component, leaves);
    }

    public void remove(K key) {
        if (nodes.remove(key) != null) {
            revision++;
        }
    }

    public void clear() {
        nodes.clear();
        pending.clear();
        revision++;
    }

    List<Node<K, C>> assemblyComponents() {
        return ObjectLists.unmodifiable(new ObjectArrayList<>(nodes.values()));
    }

    Map<K, Set<K>> pendingDependencies() {
        var result = new Object2ObjectLinkedOpenHashMap<K, Set<K>>();
        pending.forEach((key, dependencies) -> result.put(key, ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(dependencies))));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Immutable snapshot: later edits cannot change a phase that is already executing.
     */
    public List<Node<K, C>> orderedComponents() {
        if (cachedRevision == revision) {
            return cached;
        }
        var indegrees = new Object2IntOpenHashMap<K>();
        var dependents = new Object2ObjectOpenHashMap<K, ObjectArrayList<K>>();
        for (var node : nodes.values()) {
            int degree = 0;
            for (K dependency : node.dependencies()) {
                if (nodes.containsKey(dependency)) {
                    degree++;
                    dependents.computeIfAbsent(dependency, ignored -> new ObjectArrayList<>()).add(node.key());
                }
            }
            indegrees.put(node.key(), degree);
        }
        var queue = new ObjectArrayList<K>(nodes.size());
        for (K key : nodes.keySet()) {
            if (indegrees.getInt(key) == 0) {
                queue.add(key);
            }
        }
        var ordered = new ObjectArrayList<Node<K, C>>(nodes.size());
        for (int i = 0; i < queue.size(); i++) {
            K key = queue.get(i);
            ordered.add(nodes.get(key));
            var next = dependents.get(key);
            if (next == null) {
                continue;
            }
            for (K dependent : next) {
                if (indegrees.addTo(dependent, -1) == 1) {
                    queue.add(dependent);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            var remaining = new ObjectLinkedOpenHashSet<>(nodes.keySet());
            remaining.removeAll(queue);
            throw new IllegalStateException("Circular component dependency: " + remaining);
        }
        cached = ObjectLists.unmodifiable(ordered);
        cachedRevision = revision;
        return cached;
    }

    public record Node<K, C>(K key, C component, Set<K> dependencies) {
        public Node {
            Objects.requireNonNull(key);
            Objects.requireNonNull(component);
            var copy = new ObjectLinkedOpenHashSet<K>();
            for (K dependency : dependencies) {
                copy.add(Objects.requireNonNull(dependency));
            }
            dependencies = ObjectSets.unmodifiable(copy);
        }
    }
}
