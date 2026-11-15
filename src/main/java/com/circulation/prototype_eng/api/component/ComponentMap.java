package com.circulation.prototype_eng.api.component;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Published collections are read-only by contract and must not be modified by consumers.
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
    private long graphValidatedRevision = -1;
    private long assemblyCapturedRevision = -1;
    private List<SavedBinding<E, ?>> frozenAssembly = ObjectLists.emptyList();
    private long orderedBindingsRevision = -1;
    private List<SavedBinding<E, ?>> frozenOrderedBindings = ObjectLists.emptyList();

    /**
     * Checks declaration identities and the same dependency/system graph used by live dispatch,
     * without invoking a component factory. Absent dependencies remain ordering constraints;
     * declaring a dependency never implicitly creates a component.
     */
    public static <E> void validateTypes(Collection<? extends ComponentType<E, ?>> declarations) {
        var dependencies = new Object2ObjectLinkedOpenHashMap<ComponentType<E, ?>, Set<ComponentType<E, ?>>>(declarations.size());
        for (var type : declarations) {
            if (dependencies.putIfAbsent(type, type.dependencies()) != null) {
                throw new IllegalArgumentException("Component declared twice: " + type.id());
            }
        }
        validateGraph(dependencies);
    }

    /**
     * Validates saved effective edges before restoration invokes any component factory.
     */
    public static <E> void validateGraph(Map<ComponentType<E, ?>, ? extends Set<ComponentType<E, ?>>> dependencies) {
        var identities = new Object2ObjectOpenHashMap<ResourceLocation, ComponentType<E, ?>>();
        var graph = new TopologicalComponentMap<ComponentType<E, ?>, ComponentType<E, ?>>();
        for (var entry : dependencies.entrySet()) {
            var type = entry.getKey();
            var edges = entry.getValue();
            if (graph.contains(type)) {
                throw new IllegalArgumentException("Component declared twice: " + type.id());
            }
            requireTypeIdentity(identities, type);
            for (var dependency : edges) {
                requireTypeIdentity(identities, dependency);
            }
            graph.add(type, type, edges);
        }
        orderWithSystems(graph);
    }

    private static <E> void requireTypeIdentity(Map<ResourceLocation, ComponentType<E, ?>> identities, ComponentType<E, ?> type) {
        var previous = identities.putIfAbsent(type.id(), type);
        if (previous != null && previous != type) {
            throw new IllegalArgumentException("Different component types share id " + type.id());
        }
    }

    private static <E> void postAll(E owner, List<TickEntry<E, ?>> entries, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (int index14 = 0; index14 < entries.size(); index14++) {
            var entry = entries.get(index14);
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

    private static <E, C> List<TopologicalComponentMap.Node<ComponentType<E, ?>, C>> orderWithSystems(
        TopologicalComponentMap<ComponentType<E, ?>, C> components) {
        var graph = new TopologicalComponentMap<ComponentType<E, ?>, C>();
        var bySystem = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<ComponentType<E, ?>>>();
        var systemDefinitions = new Object2ObjectOpenHashMap<ResourceLocation, ComponentSystem<E, ?>>();
        var elements17 = components.orderedComponents();
        for (int index17 = 0; index17 < elements17.size(); index17++) {
            var node = elements17.get(index17);
            graph.add(node.key(), node.component(), node.dependencies());
            var elements16 = node.key().systems();
            for (int index16 = 0; index16 < elements16.size(); index16++) {
                var system = elements16.get(index16);
                var id = system.id();
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
        var elements19 = components.orderedComponents();
        for (int index19 = 0; index19 < elements19.size(); index19++) {
            var node = elements19.get(index19);
            var elements18 = node.key().systems();
            for (int index18 = 0; index18 < elements18.size(); index18++) {
                var system = elements18.get(index18);
                for (var after : system.runAfter()) {
                    var owners = bySystem.get(after);
                    if (owners != null) {
                        for (int index1 = 0; index1 < owners.size(); index1++) {
                            var other = owners.get(index1);
                            if (other != node.key()) {
                                graph.addDependency(node.key(), other);
                            }
                        }
                    }
                }
                for (var before : system.runBefore()) {
                    var owners = bySystem.get(before);
                    if (owners != null) {
                        for (int index2 = 0; index2 < owners.size(); index2++) {
                            var other = owners.get(index2);
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

    public <C> C create(ComponentType<E, C> type, E owner) {
        C component = type.create(owner);
        put(type, component);
        return component;
    }

    public <C> void put(ComponentType<E, C> type, C component) {
        var previous = types.get(type.id());
        if (previous != null && previous != type) {
            throw new IllegalArgumentException("Different component types share id " + type.id());
        }
        types.put(type.id(), type);
        components.add(type, new Binding<>(type, component), type.dependencies());
    }

    /**
     * Capture a complete insertion's declared and pending edges before an atomic publication.
     */
    @Nullable
    @SuppressWarnings("ReturnOfInnerClass") // The candidate belongs to this exact component map and graph revision.
    public <C> PreparedPut preparePut(ComponentType<E, C> type, C component, RecipeWorkBudget work) {
        var previous = types.get(type.id());
        if (previous != null && previous != type)
            throw new IllegalArgumentException("Different component types share id " + type.id());
        var candidate = components.prepareAdd(type, new Binding<>(type, component), type.dependencies(), work);
        return candidate == null ? null : new PreparedPut(type, candidate);
    }

    /**
     * The caller has admitted every map write and holds its owner until the whole group installs.
     */
    public void installPuts(List<PreparedPut> prepared) {
        var candidates = new ObjectArrayList<TopologicalComponentMap.PreparedNode<ComponentType<E, ?>, Binding<E, ?>>>();
        var unique = new ObjectLinkedOpenHashSet<ComponentType<E, ?>>(prepared.size());
        var identities = new Object2ObjectOpenHashMap<ResourceLocation, ComponentType<E, ?>>(prepared.size());
        for (int index15 = 0; index15 < prepared.size(); index15++) {
            var put = prepared.get(index15);
            if (put.owner != this || !unique.add(put.type))
                throw new IllegalArgumentException("Foreign or duplicate prepared component insertion");
            requireTypeIdentity(identities, put.type);
            var previous = types.get(put.type.id());
            if (previous != null && previous != put.type)
                throw new IllegalArgumentException("Different component types share id " + put.type.id());
            candidates.add(put.candidate);
        }
        components.installPrepared(candidates);
        for (int index3 = 0; index3 < prepared.size(); index3++) {
            var put = prepared.get(index3);
            types.put(put.type.id(), put.type);
        }
    }

    /**
     * Used when restoring an immutable component; preserves the existing node and effective graph.
     */
    public <C> void replaceValue(ComponentType<E, C> type, C component) {
        if (types.get(type.id()) != type) {
            throw new IllegalArgumentException("Cannot replace absent or foreign component type: " + type.id());
        }
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

    /**
     * Captures assembly order and effective dependency edges at an exclusive save boundary.
     */
    public List<SavedBinding<E, ?>> snapshotBindings() {
        orderedTickEntries();
        return assemblyBindings();
    }

    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public BindingCapture beginAssemblyCapture() {
        return new BindingCapture(false);
    }

    /**
     * Same complete component/system graph validation as snapshotBindings, with actual visits charged.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public BindingCapture beginSnapshotBindings() {
        return new BindingCapture(true);
    }

    /**
     * Unsorted assembly for exceptional lifecycle cleanup when a broken dependency graph cannot be
     * ordered. This deliberately does not validate the graph and must not replace save validation.
     */
    public List<SavedBinding<E, ?>> assemblyBindings() {
        long revision = components.revision();
        if (assemblyCapturedRevision == revision) return frozenAssembly;
        var result = new ObjectArrayList<SavedBinding<E, ?>>();
        var nodes = components.assemblyIterator();
        while (nodes.hasNext()) {
            var node = nodes.next();
            result.add(node.component().saved(node.dependencies()));
        }
        frozenAssembly = result;
        assemblyCapturedRevision = revision;
        return frozenAssembly;
    }

    /**
     * Captures component dependency order, independently of assembly order, for lifecycle cleanup.
     */
    public List<SavedBinding<E, ?>> orderedBindings() {
        if (orderedBindingsRevision == components.revision()) return frozenOrderedBindings;
        return orderedBindings(new RecipeWorkBudget(Long.MAX_VALUE));
    }

    /**
     * Original component dependency order; only uncached work consumes the budget.
     */
    @Nullable
    public List<SavedBinding<E, ?>> orderedBindings(RecipeWorkBudget work) {
        long revision = components.revision();
        if (orderedBindingsRevision == revision) return frozenOrderedBindings;
        var result = new ObjectArrayList<SavedBinding<E, ?>>();
        var ordered = components.orderedComponents(work);
        if (ordered == null) return null;
        for (int index13 = 0; index13 < ordered.size(); index13++) {
            var node = ordered.get(index13);
            if (!work.take()) return null;
            result.add(node.component().saved(node.dependencies()));
        }
        frozenOrderedBindings = result;
        orderedBindingsRevision = revision;
        return frozenOrderedBindings;
    }

    /**
     * Constraints for dependents not yet installed also survive a save/reload.
     */
    public Map<ComponentType<E, ?>, Set<ComponentType<E, ?>>> snapshotPendingDependencies() {
        return components.pendingDependencies();
    }

    /**
     * Downstream mods can reset the components owned by their machine or process.
     */
    @SuppressWarnings("unused")
    public void clear() {
        components.clear();
        types.clear();
    }

    /**
     * Downstream mods can add ordering constraints while assembling components.
     */
    @SuppressWarnings("unused")
    public void addDependency(ComponentType<E, ?> dependent, ComponentType<E, ?> dependency) {
        components.addDependency(dependent, dependency);
    }

    /**
     * Downstream mods can remove ordering constraints when their assembly changes.
     */
    @SuppressWarnings("unused")
    public void removeDependency(ComponentType<E, ?> dependent, ComponentType<E, ?> dependency) {
        components.removeDependency(dependent, dependency);
    }

    /**
     * Captures all phases before invoking any callback.
     */
    public void tick(E owner) {
        var entries = orderedTickEntries();
        for (int index4 = 0; index4 < entries.size(); index4++) {
            var entry = entries.get(index4);
            entry.pre(owner);
        }
        for (int index6 = 0; index6 < entries.size(); index6++) {
            var entry = entries.get(index6);
            entry.tick(owner);
        }
        for (int index7 = 0; index7 < entries.size(); index7++) {
            var entry = entries.get(index7);
            entry.post(owner);
        }
    }

    /**
     * Captures the plain tick's exact dispatch without running callbacks. A scheduler may advance
     * one normal callback at a time, retaining this cursor while that callback's deferred work is
     * settled. It must not advance or finish until that work is confirmed. This cursor preserves
     * the plain tick's fail-fast post policy; process-body cleanup uses {@link #beginTick} instead.
     * The caller supplies execution ownership for the entity throughout every suspended interval.
     */
    public TickDispatch<E> captureDispatch(E owner) {
        return new TickDispatch<>(owner, orderedTickEntries());
    }

    /**
     * Captures one dispatch for pre, tick, body and post. Pre/tick faults abort before body and post,
     * preserving PM's process callback boundary and the plain tick's fail-fast behavior. Once body
     * starts, every captured post callback runs in order, even after a body or earlier post fault.
     * A body fault remains primary; post faults are suppressed rather than replacing it.
     */
    public void tick(E owner, Runnable body) {
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
        for (int index5 = 0; index5 < entries.size(); index5++) {
            var entry = entries.get(index5);
            entry.pre(owner);
        }
        for (int index8 = 0; index8 < entries.size(); index8++) {
            var entry = entries.get(index8);
            entry.tick(owner);
        }
        return session;
    }

    private List<TickEntry<E, ?>> orderedTickEntries() {
        if (dispatchRevision == components.revision()) {
            return dispatch;
        }
        var entries = new ObjectArrayList<TickEntry<E, ?>>();
        var elements = orderWithSystems(components);
        for (int index11 = 0; index11 < elements.size(); index11++) {
            var node = elements.get(index11);
            node.component().appendEntries(entries);
        }
        dispatch = entries;
        dispatchRevision = components.revision();
        return dispatch;
    }

    /**
     * Completed dependency sets are borrowed read-only from the captured component graph.
     */
    public record SavedBinding<E, C>(ComponentType<E, C> type, C component, Set<ComponentType<E, ?>> dependencies) {
    }

    /**
     * One captured, owner-thread plain dispatch, including intervals between callbacks.
     */
    public static final class TickDispatch<E> {
        private final Thread creator = Thread.currentThread();
        private final E owner;
        private final List<TickEntry<E, ?>> entries;
        private Phase phase = Phase.PREPARED;
        private int cursor;
        private boolean executing;
        @Nullable
        private WorkerDispatch delegated;

        private TickDispatch(E owner, List<TickEntry<E, ?>> entries) {
            this.owner = owner;
            this.entries = entries;
        }

        /**
         * Transfers a suspended dispatch chunk; reclaim only after its worker has actually exited.
         */
        @SuppressWarnings("ReturnOfInnerClass") // The one-use claim retains this exact captured dispatch.
        public WorkerDispatch delegateWorker(Runnable action) {
            requireBoundary();
            if (Thread.currentThread() != creator || delegated != null)
                throw new IllegalStateException("Only the undispatched creator may delegate component work");
            delegated = new WorkerDispatch(action);
            return delegated;
        }

        /**
         * Runs all captured pre callbacks once. A failure aborts every later phase.
         */
        public void begin() {
            requireBoundary();
            if (phase != Phase.PREPARED) {
                throw new IllegalStateException("Component dispatch has already begun");
            }
            executing = true;
            try {
                for (int index9 = 0; index9 < entries.size(); index9++) {
                    var entry = entries.get(index9);
                    entry.pre(owner);
                }
                phase = Phase.NORMAL;
            } catch (RuntimeException | Error failure) {
                phase = Phase.ABORTED;
                throw failure;
            } finally {
                executing = false;
            }
        }

        /**
         * Invokes exactly the next normal callback, returning false only when none remain. The
         * callback is consumed before entry; neither failure nor a later resume can replay it.
         */
        public boolean advance() {
            requireBoundary();
            if (phase != Phase.NORMAL) {
                throw new IllegalStateException("Component dispatch is not in its normal phase");
            }
            if (cursor == entries.size()) {
                return false;
            }
            executing = true;
            try {
                entries.get(cursor++).tick(owner);
                return true;
            } catch (RuntimeException | Error failure) {
                phase = Phase.ABORTED;
                throw failure;
            } finally {
                executing = false;
            }
        }

        /**
         * Runs post only after all normal callbacks and their caller-owned deferred work. Like
         * plain tick, the first post failure stops later post callbacks and cannot be replayed.
         */
        public void finish() {
            requireBoundary();
            if (phase != Phase.NORMAL || cursor != entries.size()) {
                throw new IllegalStateException("Component dispatch still has unfinished normal callbacks");
            }
            phase = Phase.FINISHED;
            executing = true;
            try {
                for (int index10 = 0; index10 < entries.size(); index10++) {
                    var entry = entries.get(index10);
                    entry.post(owner);
                }
            } finally {
                executing = false;
            }
        }

        /**
         * Stops an unstarted or suspended dispatch without post, for example when deferred normal
         * work failed. The caller must still retain and settle that work before releasing ownership.
         */
        public void abort() {
            requireBoundary();
            if (phase == Phase.FINISHED) {
                throw new IllegalStateException("Component dispatch already finished");
            }
            phase = Phase.ABORTED;
        }

        private void requireBoundary() {
            if (executing || (delegated == null ? Thread.currentThread() != creator : Thread.currentThread() != delegated.worker)) {
                throw new IllegalStateException("Component dispatch requires its owner outside callbacks");
            }
        }

        private enum Phase {PREPARED, NORMAL, ABORTED, FINISHED}

        public final class WorkerDispatch implements Runnable {
            private final Runnable action;
            @Nullable
            private Thread worker;
            private boolean started;
            private boolean complete;

            private WorkerDispatch(Runnable action) {
                this.action = action;
            }

            @Override
            public void run() {
                synchronized (this) {
                    if (started || complete || delegated != this)
                        throw new IllegalStateException("Dispatch worker claim was consumed");
                    started = true;
                    worker = Thread.currentThread();
                }
                try {
                    action.run();
                } finally {
                    synchronized (this) {
                        worker = null;
                        complete = true;
                    }
                }
            }

            public void reclaim() {
                if (Thread.currentThread() != creator)
                    throw new IllegalStateException("Only the dispatch creator may reclaim it");
                synchronized (this) {
                    if (delegated != this || started && !complete)
                        throw new IllegalStateException("Dispatch worker is still active or foreign");
                    complete = true;
                    delegated = null;
                }
            }
        }
    }

    /**
     * One owner-thread post boundary for exactly the entries captured before pre/tick execution.
     */
    public static final class TickSession<E> {
        private final Thread creator = Thread.currentThread();
        private final E owner;
        private final List<TickEntry<E, ?>> entries;
        private boolean finished;

        private TickSession(E owner, List<TickEntry<E, ?>> entries) {
            this.owner = owner;
            this.entries = entries;
        }

        /**
         * Attempts every captured post once, aggregating faults after later posts have run.
         */
        public void finish() {
            finish(null);
        }

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

    private record Binding<E, C>(ComponentType<E, C> type, C component) {
        SavedBinding<E, C> saved(Set<ComponentType<E, ?>> dependencies) {
            return new SavedBinding<>(type, component, dependencies);
        }

        void appendEntries(ObjectArrayList<TickEntry<E, ?>> entries) {
            // PM preserves the declared order of multiple systems attached to one component.
            var elements12 = type.systems();
            for (int index12 = 0; index12 < elements12.size(); index12++) {
                var system = elements12.get(index12);
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

    public final class PreparedPut {
        private final ComponentMap<E> owner = ComponentMap.this;
        private final ComponentType<E, ?> type;
        private final TopologicalComponentMap.PreparedNode<ComponentType<E, ?>, Binding<E, ?>> candidate;

        private PreparedPut(ComponentType<E, ?> type, TopologicalComponentMap.PreparedNode<ComponentType<E, ?>, Binding<E, ?>> candidate) {
            this.type = type;
            this.candidate = candidate;
        }
    }

    public final class BindingCapture {
        private final long revision = components.revision();
        private final boolean validate;
        private Iterator<TopologicalComponentMap.Node<ComponentType<E, ?>, Binding<E, ?>>> assembly;
        private ObjectArrayList<SavedBinding<E, ?>> captured;
        private Object2ObjectOpenHashMap<ComponentType<E, ?>, ObjectLinkedOpenHashSet<ComponentType<E, ?>>> edges;
        private Object2ObjectOpenHashMap<ResourceLocation, ComponentSystem<E, ?>> systems;
        private Object2ObjectOpenHashMap<ResourceLocation, ObjectLinkedOpenHashSet<ComponentType<E, ?>>> owners;
        private Object2IntOpenHashMap<ComponentType<E, ?>> indegrees;
        private Object2ObjectOpenHashMap<ComponentType<E, ?>, ObjectArrayList<ComponentType<E, ?>>> dependents;
        private ObjectArrayList<ComponentType<E, ?>> queue;
        private TopologicalComponentMap.Node<ComponentType<E, ?>, Binding<E, ?>> node;
        private Iterator<ComponentType<E, ?>> originalEdges;
        private ObjectLinkedOpenHashSet<ComponentType<E, ?>> mutableEdges;
        private int registrationCursor;
        private ComponentType<E, ?> registrationType;
        private int registrationSystem;
        private ObjectLinkedOpenHashSet<ResourceLocation> registrationIds;
        private int edgeCursor;
        private ComponentType<E, ?> edgeType;
        private int edgeSystemCursor;
        private ComponentSystem<E, ?> edgeSystem;
        private Iterator<ResourceLocation> ordering;
        private Iterator<ComponentType<E, ?>> targetOwners;
        private boolean before;
        private int degreeCursor;
        private ComponentType<E, ?> degreeType;
        private Iterator<ComponentType<E, ?>> dependencies;
        private int rootCursor;
        private int queueCursor;
        private Iterator<ComponentType<E, ?>> outgoing;
        private int ordered;
        private int phase;
        private boolean stale;
        private List<SavedBinding<E, ?>> cachedResult;

        private BindingCapture(boolean validate) {
            this.validate = validate && dispatchRevision != revision && graphValidatedRevision != revision;
            if (!this.validate && assemblyCapturedRevision == revision) {
                cachedResult = frozenAssembly;
                phase = 6;
            } else {
                assembly = components.assemblyIterator();
                captured = new ObjectArrayList<>();
                if (this.validate) {
                    edges = new Object2ObjectOpenHashMap<>();
                    systems = new Object2ObjectOpenHashMap<>();
                    owners = new Object2ObjectOpenHashMap<>();
                    indegrees = new Object2IntOpenHashMap<>();
                    dependents = new Object2ObjectOpenHashMap<>();
                    queue = new ObjectArrayList<>();
                }
            }
        }

        public boolean advance(RecipeWorkBudget budget) {
            if (!current()) return false;
            if (phase == 6) return true;
            if (phase == 0) {
                while (node != null || assembly.hasNext()) {
                    if (node == null) {
                        if (!budget.take()) return false;
                        node = assembly.next();
                        captured.add(node.component().saved(node.dependencies()));
                        if (!validate) {
                            node = null;
                            continue;
                        }
                        mutableEdges = new ObjectLinkedOpenHashSet<>();
                        edges.put(node.key(), mutableEdges);
                        originalEdges = node.dependencies().iterator();
                    }
                    while (originalEdges.hasNext()) {
                        if (!budget.take()) return false;
                        mutableEdges.add(originalEdges.next());
                    }
                    node = null;
                }
                if (!validate) {
                    frozenAssembly = captured;
                    assemblyCapturedRevision = revision;
                    phase = 6;
                    return true;
                }
                phase = 1;
            }
            if (phase == 1) {
                while (registrationType != null || registrationCursor < captured.size()) {
                    if (registrationType == null) {
                        if (!budget.take()) return false;
                        registrationType = captured.get(registrationCursor++).type();
                        registrationSystem = 0;
                        registrationIds = new ObjectLinkedOpenHashSet<>();
                    }
                    while (registrationSystem < registrationType.systems().size()) {
                        if (!budget.take()) return false;
                        var system = registrationType.systems().get(registrationSystem++);
                        var id = system.id();
                        var previous = systems.putIfAbsent(id, system);
                        if (previous != null && previous != system)
                            throw new IllegalArgumentException("Different systems share id " + id);
                        if (!registrationIds.add(id)) throw new IllegalArgumentException("System bound twice: " + id);
                        owners.computeIfAbsent(id, _ -> new ObjectLinkedOpenHashSet<>()).add(registrationType);
                    }
                    registrationType = null;
                }
                phase = 2;
            }
            if (phase == 2) {
                while (edgeType != null || edgeCursor < captured.size()) {
                    if (edgeType == null) {
                        if (!budget.take()) return false;
                        edgeType = captured.get(edgeCursor++).type();
                        edgeSystemCursor = 0;
                    }
                    while (edgeSystem != null || edgeSystemCursor < edgeType.systems().size()) {
                        if (edgeSystem == null) {
                            if (!budget.take()) return false;
                            edgeSystem = edgeType.systems().get(edgeSystemCursor++);
                            ordering = edgeSystem.runAfter().iterator();
                            before = false;
                        }
                        while (true) {
                            if (targetOwners != null) {
                                while (targetOwners.hasNext()) {
                                    if (!budget.take()) return false;
                                    var other = targetOwners.next();
                                    if (other != edgeType) {
                                        if (before) edges.get(other).add(edgeType);
                                        else edges.get(edgeType).add(other);
                                    }
                                }
                                targetOwners = null;
                            }
                            if (ordering.hasNext()) {
                                if (!budget.take()) return false;
                                var targets = owners.get(ordering.next());
                                if (targets != null) targetOwners = targets.iterator();
                                continue;
                            }
                            if (!before) {
                                if (!budget.take()) return false;
                                before = true;
                                ordering = edgeSystem.runBefore().iterator();
                                continue;
                            }
                            edgeSystem = null;
                            break;
                        }
                    }
                    edgeType = null;
                }
                phase = 3;
            }
            if (phase == 3) {
                while (degreeType != null || degreeCursor < captured.size()) {
                    if (degreeType == null) {
                        if (!budget.take()) return false;
                        degreeType = captured.get(degreeCursor++).type();
                        dependencies = edges.get(degreeType).iterator();
                    }
                    while (dependencies.hasNext()) {
                        if (!budget.take()) return false;
                        var dependency = dependencies.next();
                        if (edges.containsKey(dependency)) {
                            indegrees.addTo(degreeType, 1);
                            dependents.computeIfAbsent(dependency, _ -> new ObjectArrayList<>()).add(degreeType);
                        }
                    }
                    degreeType = null;
                }
                phase = 4;
            }
            if (phase == 4) {
                while (rootCursor < captured.size()) {
                    if (!budget.take()) return false;
                    var type = captured.get(rootCursor++).type();
                    if (indegrees.getInt(type) == 0) queue.add(type);
                }
                phase = 5;
            }
            while (outgoing != null || queueCursor < queue.size()) {
                if (outgoing == null) {
                    if (!budget.take()) return false;
                    var type = queue.get(queueCursor++);
                    ordered++;
                    var next = dependents.get(type);
                    if (next == null) continue;
                    outgoing = next.iterator();
                }
                while (outgoing.hasNext()) {
                    if (!budget.take()) return false;
                    var type = outgoing.next();
                    if (indegrees.addTo(type, -1) == 1) queue.add(type);
                }
                outgoing = null;
            }
            if (ordered != captured.size()) throw new IllegalStateException("Circular component dependency");
            graphValidatedRevision = revision;
            frozenAssembly = captured;
            assemblyCapturedRevision = revision;
            phase = 6;
            return true;
        }

        public boolean current() {
            stale |= components.revision() != revision;
            return !stale;
        }

        public List<SavedBinding<E, ?>> result() {
            if (phase != 6 || !current())
                throw new IllegalStateException("Component binding capture is incomplete or stale");
            return cachedResult == null ? captured : cachedResult;
        }
    }
}
