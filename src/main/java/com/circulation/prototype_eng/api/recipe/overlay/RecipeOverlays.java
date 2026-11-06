package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
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

    private final Object2ObjectOpenHashMap<RequirementPath, Binding<?>> bindings = new Object2ObjectOpenHashMap<>();
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
    @SuppressWarnings("unchecked") // Only put(target<C>, type<C,D>, D) creates a binding; effective views retain canonical identity.
    private <C> Binding<C> binding(RecipeRequirement<C> base, RequirementPath path) {
        var existing = bindings.get(path);
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
    }

    private record Layer<C, D>(String id, long order, RecipeOverlayType<C, D> type, D data) {
        private C apply(C base) { return Objects.requireNonNull(type.applier().apply(base, data), "effective requirement payload"); }
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
