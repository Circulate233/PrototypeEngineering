package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Process-owned overlays of immutable requirement definitions. A named layer replaces only its own
 * prior data; layers apply in first-insertion order. Effective definitions are recomputed on writes,
 * never on the execution hot path. Removing one owner's layer recomputes remaining layers instead
 * of restoring a whole payload snapshot and erasing somebody else's changes.
 *
 * <p>All access requires exclusive process ownership. Layer IDs belong to their writer; overlapping
 * changes to the same layer must be undone in reverse order. Each acquired Change must eventually
 * be rolled back or completed. Data must remain immutable. Full process persistence is separate.
 */
public final class RecipeOverlays {
    public static final ComponentType<RecipeProcess, RecipeOverlays> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "recipe_overlays"), _ -> new RecipeOverlays(), ObjectLists.emptyList());

    private Object2ObjectOpenHashMap<RequirementPath, Binding<?>> bindings = new Object2ObjectOpenHashMap<>();
    private boolean editing;
    private int acquiredChanges;

    public static RecipeOverlays getOrCreate(RecipeProcess process) {
        var existing = process.components().get(TYPE);
        return existing == null ? process.components().create(TYPE, process) : existing;
    }

    public <C> RecipeRequirement<C> resolve(RecipeRequirement<C> base) {
        return resolve(base, base.path(null));
    }

    public <C> RecipeRequirement<C> resolve(RecipeRequirement<C> base, RequirementPath path) {
        validatePath(base, path);
        var binding = binding(base, path);
        return binding == null ? base : binding.effective;
    }

    /** A stable local layer save, retaining immutable type/data and the canonical target definition. */
    public record LayerSnapshot<C, D>(RequirementPath path, RecipeRequirement<C> target, String layerId,
                                      long order, RecipeOverlayType<C, D> type, D data) {
        public LayerSnapshot {
            Objects.requireNonNull(path);
            Objects.requireNonNull(target);
            Objects.requireNonNull(layerId);
            Objects.requireNonNull(type);
            Objects.requireNonNull(data);
        }
    }

    /** Lifetime removal claims are not stage undo; only acquired Change handles prevent a snapshot. */
    public List<LayerSnapshot<?, ?>> snapshot() {
        requireStable();
        var result = new ObjectArrayList<LayerSnapshot<?, ?>>();
        for (var entry : bindings.entrySet()) {
            appendSnapshots(result, entry.getKey(), entry.getValue());
        }
        result.sort(Comparator.comparing((LayerSnapshot<?, ?> layer) -> layer.path().toString())
            .thenComparingLong(LayerSnapshot::order));
        return ObjectLists.unmodifiable(result);
    }

    /** All identities, layers and computations are checked before replacing this component's map. */
    public void restore(List<LayerSnapshot<?, ?>> snapshot) {
        requireStable();
        beginEdit();
        try {
            var replacement = new Object2ObjectOpenHashMap<RequirementPath, Binding<?>>();
            var orders = new Object2ObjectOpenHashMap<RequirementPath, LongOpenHashSet>();
            for (var layer : snapshot) {
                restoreLayer(replacement, orders, Objects.requireNonNull(layer));
            }
            for (var binding : replacement.values()) { binding.recompute(); }
            bindings = replacement;
        } finally {
            editing = false;
        }
    }

    public boolean hasLayer(RecipeRequirement<?> target, RequirementPath path, String layerId) {
        validatePath(target, path);
        validateLayerId(layerId);
        var binding = binding(target, path);
        return binding != null && binding.layers.containsKey(layerId);
    }

    /** Acquires cleanup ownership of an already installed layer, without reinstalling or invoking it. */
    public RemovalClaim claim(RecipeRequirement<?> target, RequirementPath path, String layerId) {
        validatePath(target, path);
        validateLayerId(layerId);
        if (editing) { throw new IllegalStateException("Cannot claim a layer during an overlay edit"); }
        var binding = binding(target, path);
        var layer = binding == null ? null : binding.layers.get(layerId);
        if (layer == null) { throw new IllegalStateException("Missing owned overlay layer: " + layerId); }
        return new RemovalClaim(this, target.definition(), path, layerId, layer);
    }

    private void requireStable() {
        if (editing || acquiredChanges != 0) { throw new IllegalStateException("Overlay stage changes are still pending"); }
    }

    private static <C> void appendSnapshots(List<LayerSnapshot<?, ?>> output, RequirementPath path, Binding<C> binding) {
        for (var layer : binding.layers.values()) { appendSnapshot(output, path, binding.base, layer); }
    }

    private static <C, D> void appendSnapshot(List<LayerSnapshot<?, ?>> output, RequirementPath path,
                                             RecipeRequirement<C> target, Layer<C, D> layer) {
        output.add(new LayerSnapshot<>(path, target, layer.id(), layer.order(), layer.type(), layer.data()));
    }

    private static <C, D> void restoreLayer(Object2ObjectOpenHashMap<RequirementPath, Binding<?>> replacement,
                                            Object2ObjectOpenHashMap<RequirementPath, LongOpenHashSet> orders,
                                            LayerSnapshot<C, D> saved) {
        validatePath(saved.target(), saved.path());
        validateLayerId(saved.layerId());
        if (saved.target() != saved.target().definition()) { throw new IllegalArgumentException("Overlay restore target must be canonical"); }
        if (saved.target().type() != saved.type().requirementType()) { throw new IllegalArgumentException("Overlay restore type mismatch"); }
        if (saved.order() < 0) { throw new IllegalArgumentException("Negative overlay layer order"); }
        if (!orders.computeIfAbsent(saved.path(), _ -> new LongOpenHashSet()).add(saved.order())) {
            throw new IllegalArgumentException("Duplicate overlay layer order");
        }
        var binding = binding(replacement, saved.target(), saved.path());
        if (binding == null) {
            binding = new Binding<>(saved.target());
            replacement.put(saved.path(), binding);
        }
        if (binding.layers.putIfAbsent(saved.layerId(), new Layer<>(saved.layerId(), saved.order(), saved.type(), saved.data())) != null) {
            throw new IllegalArgumentException("Duplicate overlay layer id");
        }
        binding.nextOrder = Math.max(binding.nextOrder, Math.incrementExact(saved.order()));
    }

    public <C, D> Change put(RecipeRequirement<C> target, String layerId, RecipeOverlayType<C, D> type, D data) {
        return put(target, target.path(null), layerId, type, data);
    }

    /** A successful put is immediately visible to later acquisitions; already acquired transactions stay frozen. */
    public <C, D> Change put(RecipeRequirement<C> target, RequirementPath path, String layerId,
                             RecipeOverlayType<C, D> type, D data) {
        validatePath(target, path);
        validateLayerId(layerId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(data);
        if (target.type() != type.requirementType()) {
            throw new IllegalArgumentException("Overlay applier targets a different requirement type");
        }
        beginEdit();
        try {
            var existing = binding(target, path);
            var binding = existing == null ? new Binding<>(target.definition()) : existing;
            var previous = binding.layers.get(layerId);
            long order = previous == null ? binding.nextOrder : previous.order();
            long nextOrder = previous == null ? Math.incrementExact(order) : binding.nextOrder;
            var replacement = new Layer<>(layerId, order, type, data);
            var next = new Object2ObjectOpenHashMap<>(binding.layers);
            next.put(layerId, replacement);
            var effective = binding.compute(next);
            var change = change(binding, previous, replacement);
            binding.install(next, effective);
            binding.nextOrder = nextOrder;
            if (existing == null) { bindings.put(path, binding); }
            return change;
        } finally {
            editing = false;
        }
    }

    public Change remove(RecipeRequirement<?> target, String layerId) {
        return remove(target, target.path(null), layerId);
    }

    /** Removes one layer. Absence returns an already completed no-op handle. */
    public Change remove(RecipeRequirement<?> target, RequirementPath path, String layerId) {
        validatePath(target, path);
        validateLayerId(layerId);
        beginEdit();
        try {
            return remove(binding(target, path), layerId);
        } finally {
            editing = false;
        }
    }

    private <C> Change remove(@Nullable Binding<C> binding, String layerId) {
        var previous = binding == null ? null : binding.layers.get(layerId);
        if (previous == null) { return new Change(null, null); }
        var next = new Object2ObjectOpenHashMap<>(binding.layers);
        next.remove(layerId);
        var effective = binding.compute(next);
        var change = change(binding, previous, null);
        binding.install(next, effective);
        return change;
    }

    /** Clears permanent overlays only at a lifecycle boundary with no acquired undo handles. */
    public void clear() {
        beginEdit();
        try {
            if (acquiredChanges != 0) { throw new IllegalStateException("Cannot clear overlays with acquired changes"); }
            bindings.clear();
        } finally {
            editing = false;
        }
    }

    private <C> Change change(Binding<C> binding, @Nullable Layer<C, ?> previous, @Nullable Layer<C, ?> installed) {
        acquiredChanges = Math.incrementExact(acquiredChanges);
        return new Change(this, () -> {
            beginEdit();
            try {
                String id = Objects.requireNonNull(installed == null ? previous : installed).id();
                if (binding.layers.get(id) != installed) {
                    throw new IllegalStateException("Overlay layer changed before rollback: " + id);
                }
                var next = new Object2ObjectOpenHashMap<>(binding.layers);
                if (previous == null) { next.remove(id); }
                else { next.put(id, previous); }
                binding.install(next, binding.compute(next));
            } finally {
                editing = false;
            }
        });
    }

    private void beginEdit() {
        if (editing) { throw new IllegalStateException("Overlay appliers must not mutate overlays recursively"); }
        editing = true;
    }

    @Nullable
    private <C> Binding<C> binding(RecipeRequirement<C> base, RequirementPath path) {
        return binding(bindings, base, path);
    }

    @Nullable
    @SuppressWarnings("unchecked") // Typed writes/restoration validate exact canonical identity at this map boundary.
    private static <C> Binding<C> binding(Object2ObjectOpenHashMap<RequirementPath, Binding<?>> source,
                                          RecipeRequirement<C> base, RequirementPath path) {
        var existing = source.get(path);
        if (existing != null && existing.base != base.definition()) {
            throw new IllegalArgumentException("Different immutable requirement definitions share overlay path " + path);
        }
        return (Binding<C>) existing;
    }

    private static void validatePath(RecipeRequirement<?> target, RequirementPath path) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(path);
        if (!target.id().equals(path.requirementId()) || !target.type().id().equals(path.typeId())) {
            throw new IllegalArgumentException("Overlay path does not identify its target requirement");
        }
    }

    private static void validateLayerId(String layerId) {
        if (Objects.requireNonNull(layerId).isBlank()) { throw new IllegalArgumentException("Blank overlay layer id"); }
    }

    private static final class Binding<C> {
        private final RecipeRequirement<C> base;
        private Object2ObjectOpenHashMap<String, Layer<C, ?>> layers = new Object2ObjectOpenHashMap<>();
        private RecipeRequirement<C> effective;
        private long nextOrder;

        private Binding(RecipeRequirement<C> base) {
            this.base = base;
            effective = base;
        }

        private RecipeRequirement<C> compute(Object2ObjectOpenHashMap<String, Layer<C, ?>> proposed) {
            if (proposed.isEmpty()) { return base; }
            var ordered = new ObjectArrayList<>(proposed.values());
            ordered.sort(Comparator.comparingLong(Layer::order));
            C payload = base.payload();
            for (var layer : ordered) { payload = layer.apply(payload); }
            return base.withPayload(payload);
        }

        private void install(Object2ObjectOpenHashMap<String, Layer<C, ?>> next, RecipeRequirement<C> result) {
            layers = next;
            effective = result;
        }

        private void recompute() { effective = compute(layers); }
    }

    private record Layer<C, D>(String id, long order, RecipeOverlayType<C, D> type, D data) {
        private C apply(C base) { return Objects.requireNonNull(type.applier().apply(base, data), "effective requirement payload"); }
    }

    private <C> void removeClaim(RecipeRequirement<C> target, RequirementPath path, String layerId, Layer<?, ?> expected) {
        beginEdit();
        try {
            var binding = binding(target, path);
            if (binding == null || binding.layers.get(layerId) != expected) {
                throw new IllegalStateException("Owned overlay layer changed before cleanup: " + layerId);
            }
            var next = new Object2ObjectOpenHashMap<>(binding.layers);
            next.remove(layerId);
            binding.install(next, binding.compute(next));
        } finally {
            editing = false;
        }
    }

    /** Structural lifetime cleanup handle; no stage undo closure and no pending Change count. */
    public static final class RemovalClaim {
        private final RecipeOverlays owner;
        private final RecipeRequirement<?> target;
        private final RequirementPath path;
        private final String layerId;
        private final Layer<?, ?> expected;
        private boolean removed;

        private RemovalClaim(RecipeOverlays owner, RecipeRequirement<?> target, RequirementPath path, String layerId, Layer<?, ?> expected) {
            this.owner = owner;
            this.target = target;
            this.path = path;
            this.layerId = layerId;
            this.expected = expected;
        }

        public RecipeRequirement<?> target() { return target; }
        public RequirementPath path() { return path; }
        public String layerId() { return layerId; }

        /** Checks version identity, so an old handle cannot claim or remove a replacement layer. */
        public void validate() {
            var binding = owner.binding(target, path);
            if (removed || binding == null || binding.layers.get(layerId) != expected) {
                throw new IllegalStateException("Owned overlay layer is no longer installed: " + layerId);
            }
        }

        public void remove() {
            if (removed) { return; }
            owner.removeClaim(target, path, layerId, expected);
            removed = true;
        }
    }

    /** A local layer edit. Completion relinquishes undo rights; rollback never restores other layers. */
    public static final class Change {
        @Nullable private RecipeOverlays owner;
        @Nullable private Runnable undo;

        private Change(@Nullable RecipeOverlays owner, @Nullable Runnable undo) {
            this.owner = owner;
            this.undo = undo;
        }

        public void rollback() {
            if (undo == null) { return; }
            undo.run();
            complete();
        }

        public void complete() {
            if (owner != null) { owner.acquiredChanges--; }
            owner = null;
            undo = null;
        }
    }
}
