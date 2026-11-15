package com.circulation.prototype_eng.api.machine.attribute;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;

/**
 * ID-addressed root or overlay attributes. A layer may be mutated only by its machine/process
 * execution owner during exclusive ownership, transferred at a scheduler join. A parent must remain
 * stable while its children run concurrently; each child can then mutate its own layer and read that
 * shared parent. Other reads likewise require a stable layer. This class has no internal synchronization.
 *
 * <p>Reads calculate without writing a cache and resolve the live parent by ID. Local bases override
 * inherited bases; local modifier IDs override inherited modifiers. A resolved local base already
 * includes its parent's calculation and blocks ancestor modifiers while retaining local modifiers.
 * Clearing local state reveals the parent again. All bases, fallbacks and intermediate results must
 * be finite; negative values are allowed so each consumer can apply its own domain rules.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class MachineAttributes {
    private final @Nullable MachineAttributes parent;
    private final double defaultBase;
    private final AttributeEpoch epoch;
    private volatile Object localMarker = new Object();
    private Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalState> local = new Object2ObjectLinkedOpenHashMap<>();

    /**
     * Root attributes use zero as the base when only modifiers exist.
     */
    public MachineAttributes() {
        parent = null;
        defaultBase = 0;
        epoch = new AttributeEpoch();
    }

    /**
     * Process overlays normally use defaultBase=1 when no parent attribute exists.
     */
    public MachineAttributes(MachineAttributes parent, double defaultBase) {
        this.parent = parent;
        this.defaultBase = finite(defaultBase, "default base");
        epoch = parent.epoch;
    }

    private static void validateModifier(AttributeModifier modifier) {
        if (modifier.id().isBlank()) {
            throw new IllegalArgumentException("Modifier ID must be nonblank");
        }
    }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    public double value(ResourceLocation id) {
        return value(id, defaultBase);
    }

    /**
     * Fallback applies only when neither this layer nor any parent defines the attribute.
     */
    public double value(ResourceLocation id, double fallback) {
        finite(fallback, "fallback");
        double base = defaultBase;
        boolean defined = false;
        boolean baseFound = false;
        boolean inheritModifiers = true;
        boolean borrowed = true;
        Object2ObjectLinkedOpenHashMap<String, AttributeModifier> modifiers = null;
        for (var attributes = this; attributes != null; attributes = attributes.parent) {
            var state = attributes.local.get(id);
            if (state == null) continue;
            defined = true;
            if (!baseFound) {
                base = Double.isNaN(state.baseOverride) ? attributes.defaultBase : state.baseOverride;
                baseFound = !Double.isNaN(state.baseOverride);
            }
            if (inheritModifiers) {
                if (!state.modifiers.isEmpty()) {
                    if (modifiers == null) {
                        modifiers = state.modifiers;
                    } else {
                        if (borrowed) {
                            modifiers = new Object2ObjectLinkedOpenHashMap<>(modifiers);
                            borrowed = false;
                        }
                        var keys = state.modifiers.keySet().iterator(state.modifiers.lastKey());
                        while (keys.hasPrevious()) {
                            var key = keys.previous();
                            var replacement = modifiers.get(key);
                            modifiers.putAndMoveToFirst(key, replacement == null ? state.modifiers.get(key) : replacement);
                        }
                    }
                }
                inheritModifiers = state.inheritModifiers;
            }
            if (baseFound && !inheritModifiers) break;
        }
        if (!defined) return fallback;
        if (modifiers == null) return base;
        double current = base;
        for (var operation : AttributeModifier.Operation.VALUES) {
            for (var modifier : modifiers.values()) {
                if (modifier.operation() == operation) {
                    current = modifier.apply(base, current);
                    if (!Double.isFinite(current)) {
                        throw new IllegalStateException("Nonfinite attribute result for " + id + " from " + modifier.id());
                    }
                }
            }
        }
        return current;
    }

    /**
     * Resumes this original value capture; modifier functions remain individual cooperative calls.
     */
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ValueCapture beginValueCapture(ResourceLocation id, double fallback) {
        return new ValueCapture(id, finite(fallback, "fallback"));
    }

    private void changed() {
        localMarker = new Object();
        epoch.marker = new Object();
    }

    /**
     * Captures all related layers for aggregate quantities such as machine-wide parallel usage.
     */
    public FamilyCapture familyCapture() {
        return new FamilyCapture(epoch);
    }

    public void setBase(ResourceLocation id, double base) {
        finite(base, "base");
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        state.baseOverride = base;
        state.inheritModifiers = true;
        changed();
    }

    /**
     * Freezes an already resolved parent result as this layer's base. Ancestor modifiers are not
     * applied again; every local modifier remains active. The supplied value must not already
     * include this layer's local modifiers, whose arbitrary functions cannot be inverted.
     */
    public void setResolvedBase(ResourceLocation id, double base) {
        finite(base, "resolved base");
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        state.baseOverride = base;
        state.inheritModifiers = false;
        changed();
    }

    public boolean clearBase(ResourceLocation id) {
        var state = local.get(id);
        if (state == null || Double.isNaN(state.baseOverride)) {
            return false;
        }
        state.baseOverride = Double.NaN;
        state.inheritModifiers = true;
        prune(id, state);
        changed();
        return true;
    }

    /**
     * Refuses a duplicate in this layer; a matching parent ID may be shadowed.
     */
    public boolean addModifier(ResourceLocation id, AttributeModifier modifier) {
        validateModifier(modifier);
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        boolean added = state.modifiers.putIfAbsent(modifier.id(), modifier) == null;
        if (added) changed();
        return added;
    }

    /**
     * Removes only a local modifier, revealing any inherited modifier with the same ID.
     */
    public boolean removeModifier(ResourceLocation id, String modifierId) {
        var state = local.get(id);
        if (state == null || state.modifiers.remove(modifierId) == null) {
            return false;
        }
        prune(id, state);
        changed();
        return true;
    }

    /**
     * Exact local identity for lifetime ownership checks; never reads or claims a parent modifier.
     */
    @Nullable
    public AttributeModifier localModifier(ResourceLocation id, String modifierId) {
        var state = local.get(id);
        return state == null ? null : state.modifiers.get(modifierId);
    }

    /**
     * Captures both mutable map levels once, retaining immutable modifier references. The returned
     * snapshot maps are read-only by contract. Saves
     * only local bases/modifiers and their inheritance mode, never the parent or layer default.
     * Preserve the layer's constructor default separately when persisting its owning machine/process.
     */
    public Map<ResourceLocation, LocalAttribute> localSnapshot() {
        var snapshot = new Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalAttribute>();
        local.forEach((id, state) -> snapshot.put(id, new LocalAttribute(state.baseOverride, new Object2ObjectLinkedOpenHashMap<>(state.modifiers), state.inheritModifiers)));
        return snapshot;
    }

    /**
     * Restores owned mutable state from the borrowed snapshot.
     */
    public void restoreLocal(Map<ResourceLocation, LocalAttribute> snapshot) {
        var replacement = new Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalState>(snapshot.size());
        for (var entry : snapshot.entrySet()) {
            var id = entry.getKey();
            var saved = entry.getValue();
            var state = new LocalState();
            state.baseOverride = saved.baseOverride();
            state.inheritModifiers = saved.inheritModifiers();
            state.modifiers.putAll(saved.modifiers());
            replacement.put(id, state);
        }
        local = replacement;
        changed();
    }

    /**
     * Tests modifier presence across this effective family without evaluating modifier callbacks.
     */
    public boolean hasModifiers(ResourceLocation id) {
        var state = local.get(id);
        return state != null && !state.modifiers.isEmpty()
            || (state == null || state.inheritModifiers) && parent != null && parent.hasModifiers(id);
    }

    private void prune(ResourceLocation id, LocalState state) {
        if (Double.isNaN(state.baseOverride) && state.modifiers.isEmpty()) {
            local.remove(id);
        }
    }

    private record LayerValue(MachineAttributes attributes, @Nullable LocalState state, Object marker) {
    }

    private static final class AttributeEpoch {
        private volatile Object marker = new Object();
    }

    public static final class FamilyCapture {
        private final AttributeEpoch epoch;
        private final Object marker;

        private FamilyCapture(AttributeEpoch epoch) {
            this.epoch = epoch;
            marker = epoch.marker;
        }

        public boolean current() {
            return marker == epoch.marker;
        }
    }

    /**
     * A local save entry. Its modifier map and modifier objects are borrowed read-only and must
     * remain unchanged for the entry lifetime. NaN means no local base override.
     */
    public record LocalAttribute(double baseOverride, Map<String, AttributeModifier> modifiers,
                                 boolean inheritModifiers) {
        public LocalAttribute(double baseOverride, Map<String, AttributeModifier> modifiers) {
            this(baseOverride, modifiers, true);
        }
    }

    private static final class LocalState {
        private final Object2ObjectLinkedOpenHashMap<String, AttributeModifier> modifiers = new Object2ObjectLinkedOpenHashMap<>();
        private double baseOverride = Double.NaN;
        private boolean inheritModifiers = true;
    }

    public final class ValueCapture {
        private final ResourceLocation id;
        private final double fallback;
        private final Object capturedLocal = localMarker;
        private final ObjectArrayList<LayerValue> layers = new ObjectArrayList<>();
        private final Object2ObjectLinkedOpenHashMap<String, AttributeModifier> modifiers = new Object2ObjectLinkedOpenHashMap<>();
        private MachineAttributes next = MachineAttributes.this;
        private Object nextMarker = capturedLocal;
        private Iterator<Map.Entry<String, AttributeModifier>> entries;
        private Iterator<AttributeModifier> applying;
        private int phase;
        private int included = -1;
        private int layer;
        private int operation;
        private boolean defined;
        private boolean baseFound;
        private double base;
        private double result;

        private ValueCapture(ResourceLocation id, double fallback) {
            this.id = id;
            this.fallback = fallback;
        }

        public boolean advance(RecipeWorkBudget budget) {
            if (!current()) return false;
            if (phase == 4) return true;
            while (phase == 0 && next != null) {
                if (!budget.take()) return false;
                var attributes = next;
                var state = attributes.local.get(id);
                layers.add(new LayerValue(attributes, state, attributes.localMarker));
                if (state != null) {
                    defined = true;
                    if (!baseFound && !Double.isNaN(state.baseOverride)) {
                        base = state.baseOverride;
                        baseFound = true;
                    } else if (!baseFound) base = attributes.defaultBase;
                    if (included == -1 && !state.inheritModifiers) included = layers.size() - 1;
                }
                next = attributes.parent;
                if (next != null) nextMarker = next.localMarker;
            }
            if (phase == 0) {
                if (!defined) {
                    result = fallback;
                    phase = 4;
                    return current();
                }
                layer = included == -1 ? layers.size() - 1 : included;
                phase = 1;
            }
            while (phase == 1 && layer >= 0) {
                if (entries == null) {
                    if (!budget.take()) return false;
                    var state = layers.get(layer).state;
                    if (state == null) {
                        layer--;
                        continue;
                    }
                    entries = state.modifiers.entrySet().iterator();
                }
                while (entries.hasNext()) {
                    if (!budget.take()) return false;
                    var entry = entries.next();
                    modifiers.put(entry.getKey(), entry.getValue());
                }
                entries = null;
                layer--;
            }
            if (phase == 1) {
                result = base;
                phase = 2;
            }
            while (phase == 2 && operation < AttributeModifier.Operation.VALUES.length) {
                if (applying == null) applying = modifiers.values().iterator();
                while (applying.hasNext()) {
                    if (!budget.take()) return false;
                    var modifier = applying.next();
                    if (modifier.operation() == AttributeModifier.Operation.VALUES[operation]) {
                        result = modifier.apply(base, result);
                        if (!Double.isFinite(result))
                            throw new IllegalStateException("Nonfinite attribute result for " + id + " from " + modifier.id());
                    }
                    if (!current()) return false;
                }
                applying = null;
                operation++;
            }
            if (!budget.take()) return false;
            phase = 4;
            return current();
        }

        /**
         * Mutations to this layer or a captured ancestor invalidate the capture. Sibling overlays
         * may independently advance their own recipe work without invalidating this value.
         */
        public boolean current() {
            if (capturedLocal != localMarker) return false;
            if (next != null && nextMarker != next.localMarker) return false;
            for (int index = 0; index < layers.size(); index++) {
                var captured = layers.get(index);
                if (captured.marker != captured.attributes.localMarker) return false;
            }
            return true;
        }

        public double result() {
            if (phase != 4 || !current())
                throw new IllegalStateException("Attribute value capture is incomplete or changed");
            return result;
        }
    }
}
