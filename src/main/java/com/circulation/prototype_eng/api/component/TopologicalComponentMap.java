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
import java.util.Iterator;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import java.util.AbstractSet;
import org.jetbrains.annotations.NotNull;

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

    /** Prepare the original declaration/pending merge without granting the new node authority. */
    @Nullable
    PreparedNode<K, C> prepareAdd(K key, C component, Collection<? extends K> dependencies, RecipeWorkBudget work) {
        var merged = new ObjectLinkedOpenHashSet<K>();
        for (var dependency : dependencies) {
            if (!work.take()) return null;
            merged.add(Objects.requireNonNull(dependency));
        }
        var waiting = pending.get(key);
        if (waiting != null) {
            for (var dependency : waiting) {
                if (!work.take()) return null;
                merged.add(Objects.requireNonNull(dependency));
            }
        }
        if (!work.take()) return null;
        return new PreparedNode<>(new Node<>(key, component, new FrozenDependencies<>(merged)), revision);
    }

    /** All preflight precedes the caller's admitted atomic map writes. */
    void installPrepared(List<PreparedNode<K, C>> prepared) {
        for (var candidate : prepared) {
            if (candidate.revision() != revision) throw new IllegalStateException("Component graph changed after insertion preparation");
        }
        for (var candidate : prepared) {
            var node = candidate.node();
            nodes.remove(node.key()); nodes.put(node.key(), node); pending.remove(node.key()); revision++;
        }
    }

    record PreparedNode<K, C>(Node<K, C> node, long revision) { }

    /** Only a private preparation can transfer its completed owned dependency set without copying. */
    private static final class FrozenDependencies<K> extends AbstractSet<K> {
        private final Set<K> owned;
        private FrozenDependencies(Set<K> owned) { this.owned = owned; }
        @Override public @NotNull Iterator<K> iterator() { return Collections.unmodifiableSet(owned).iterator(); }
        @Override public int size() { return owned.size(); }
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

    /** Package owner cursor: immutable nodes; the enclosing capture checks revision before use. */
    Iterator<Node<K, C>> assemblyIterator() {
        return nodes.values().iterator();
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
        return Objects.requireNonNull(orderedComponents(new RecipeWorkBudget(Long.MAX_VALUE)));
                }

    /** A publication must complete this original Kahn ordering in its current owner window. */
    @Nullable
    public List<Node<K, C>> orderedComponents(RecipeWorkBudget work) {
        if (cachedRevision == revision) {
            if (!work.take()) return null;
            return cached;
        }
        var indegrees = new Object2IntOpenHashMap<K>();
        var dependents = new Object2ObjectOpenHashMap<K, ObjectArrayList<K>>();
        for (var node : nodes.values()) {
            if (!work.take()) return null;
            int degree = 0;
            for (K dependency : node.dependencies()) {
                if (!work.take()) return null;
                if (nodes.containsKey(dependency)) {
                    degree++;
                    dependents.computeIfAbsent(dependency, ignored -> new ObjectArrayList<>()).add(node.key());
                }
            }
            indegrees.put(node.key(), degree);
        }
        var queue = new ObjectArrayList<K>(nodes.size());
        for (K key : nodes.keySet()) {
            if (!work.take()) return null;
            if (indegrees.getInt(key) == 0) {
                queue.add(key);
            }
        }
        var ordered = new ObjectArrayList<Node<K, C>>(nodes.size());
        for (int i = 0; i < queue.size(); i++) {
            if (!work.take()) return null;
            K key = queue.get(i);
            ordered.add(nodes.get(key));
            var next = dependents.get(key);
            if (next == null) {
                continue;
            }
            for (K dependent : next) {
                if (!work.take()) return null;
                if (indegrees.addTo(dependent, -1) == 1) {
                    queue.add(dependent);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            var remaining = new ObjectLinkedOpenHashSet<K>();
            for (var key : nodes.keySet()) {
                if (!work.take()) return null;
                remaining.add(key);
            }
            for (var key : queue) {
                if (!work.take()) return null;
                remaining.remove(key);
            }
            throw new IllegalStateException("Circular component dependency: " + remaining);
        }
        if (!work.take()) return null;
        cached = ObjectLists.unmodifiable(ordered);
        cachedRevision = revision;
        return cached;
    }

    public record Node<K, C>(K key, C component, Set<K> dependencies) {
        public Node {
            Objects.requireNonNull(key);
            Objects.requireNonNull(component);
            if (!(dependencies instanceof FrozenDependencies<?>)) {
                var copy = new ObjectLinkedOpenHashSet<K>();
            for (K dependency : dependencies) {
                copy.add(Objects.requireNonNull(dependency));
            }
            dependencies = ObjectSets.unmodifiable(copy);
        }
        }
    }
}
