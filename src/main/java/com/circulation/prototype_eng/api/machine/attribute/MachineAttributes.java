package com.circulation.prototype_eng.api.machine.attribute;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

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
 */
public final class MachineAttributes {
    private static final AttributeModifier.Operation[] OPERATION_ORDER = AttributeModifier.Operation.values();

    private final @Nullable MachineAttributes parent;
    private final double defaultBase;
    private Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalState> local = new Object2ObjectLinkedOpenHashMap<>();

    /** Root attributes use zero as the base when only modifiers exist. */
    public MachineAttributes() {
        parent = null;
        defaultBase = 0;
    }

    /** Process overlays normally use defaultBase=1 when no parent attribute exists. */
    public MachineAttributes(MachineAttributes parent, double defaultBase) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.defaultBase = finite(defaultBase, "default base");
    }

    public double value(ResourceLocation id) {
        return value(id, defaultBase);
    }

    /** Fallback applies only when neither this layer nor any parent defines the attribute. */
    public double value(ResourceLocation id, double fallback) {
        Objects.requireNonNull(id, "id");
        finite(fallback, "fallback");
        if (!contains(id)) {
            return fallback;
        }
        double base = base(id);
        if (!hasModifiers(id)) {
            return base;
        }
        double current = base;
        var modifiers = new Object2ObjectLinkedOpenHashMap<String, AttributeModifier>();
        collectModifiers(id, modifiers);
        for (var operation : OPERATION_ORDER) {
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

    public void setBase(ResourceLocation id, double base) {
        Objects.requireNonNull(id, "id");
        finite(base, "base");
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        state.baseOverride = base;
        state.inheritModifiers = true;
    }

    /**
     * Freezes an already resolved parent result as this layer's base. Ancestor modifiers are not
     * applied again; every local modifier remains active. The supplied value must not already
     * include this layer's local modifiers, whose arbitrary functions cannot be inverted.
     */
    public void setResolvedBase(ResourceLocation id, double base) {
        Objects.requireNonNull(id, "id");
        finite(base, "resolved base");
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        state.baseOverride = base;
        state.inheritModifiers = false;
    }

    public boolean clearBase(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        var state = local.get(id);
        if (state == null || state.baseOverride == null) {
            return false;
        }
        state.baseOverride = null;
        state.inheritModifiers = true;
        prune(id, state);
        return true;
    }

    /** Refuses a duplicate in this layer; a matching parent ID may be shadowed. */
    public boolean addModifier(ResourceLocation id, AttributeModifier modifier) {
        Objects.requireNonNull(id, "id");
        validateModifier(modifier);
        var state = local.computeIfAbsent(id, _ -> new LocalState());
        return state.modifiers.putIfAbsent(modifier.id(), modifier) == null;
    }

    /** Removes only a local modifier, revealing any inherited modifier with the same ID. */
    public boolean removeModifier(ResourceLocation id, String modifierId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifierId, "modifierId");
        var state = local.get(id);
        if (state == null || state.modifiers.remove(modifierId) == null) {
            return false;
        }
        prune(id, state);
        return true;
    }

    /** Exact local identity for lifetime ownership checks; never reads or claims a parent modifier. */
    @Nullable
    public AttributeModifier localModifier(ResourceLocation id, String modifierId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modifierId, "modifierId");
        var state = local.get(id);
        return state == null ? null : state.modifiers.get(modifierId);
    }

    /**
     * Copies both map levels into unmodifiable maps, retaining immutable modifier references. Saves
     * only local bases/modifiers and their inheritance mode, never the parent or layer default.
     * Preserve the layer's constructor default separately when persisting its owning machine/process.
     */
    public Map<ResourceLocation, LocalAttribute> localSnapshot() {
        var snapshot = new Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalAttribute>();
        local.forEach((id, state) -> snapshot.put(id, new LocalAttribute(state.baseOverride, state.modifiers, state.inheritModifiers)));
        return Collections.unmodifiableMap(snapshot);
    }

    /** Validates and copies the entire snapshot before replacement; rejection leaves this layer intact. */
    public void restoreLocal(Map<ResourceLocation, LocalAttribute> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var replacement = new Object2ObjectLinkedOpenHashMap<ResourceLocation, LocalState>();
        for (var entry : snapshot.entrySet()) {
            var id = Objects.requireNonNull(entry.getKey(), "attribute ID");
            var saved = Objects.requireNonNull(entry.getValue(), "local attribute");
            var checked = new LocalAttribute(saved.baseOverride(), saved.modifiers(), saved.inheritModifiers());
            var state = new LocalState();
            state.baseOverride = checked.baseOverride();
            state.inheritModifiers = checked.inheritModifiers();
            state.modifiers.putAll(checked.modifiers());
            if (replacement.putIfAbsent(id, state) != null) {
                throw new IllegalArgumentException("Duplicate attribute ID: " + id);
            }
        }
        local = replacement;
    }

    /** A local save entry. The map is copied; modifier objects must satisfy their immutable contract. */
    public record LocalAttribute(@Nullable Double baseOverride, Map<String, AttributeModifier> modifiers, boolean inheritModifiers) {
        public LocalAttribute {
            if (baseOverride != null) {
                finite(baseOverride, "base override");
            }
            if (!inheritModifiers && baseOverride == null) {
                throw new IllegalArgumentException("Blocking inherited modifiers requires a resolved base");
            }
            Objects.requireNonNull(modifiers, "modifiers");
            var copy = new Object2ObjectLinkedOpenHashMap<String, AttributeModifier>();
            for (var entry : modifiers.entrySet()) {
                var modifier = entry.getValue();
                validateModifier(modifier);
                if (!modifier.id().equals(entry.getKey())) {
                    throw new IllegalArgumentException("Modifier map key must match its ID");
                }
                if (copy.putIfAbsent(modifier.id(), modifier) != null) {
                    throw new IllegalArgumentException("Duplicate modifier ID: " + modifier.id());
                }
            }
            if (baseOverride == null && copy.isEmpty()) {
                throw new IllegalArgumentException("Empty local attributes must be absent");
            }
            modifiers = Collections.unmodifiableMap(copy);
        }

        public LocalAttribute(@Nullable Double baseOverride, Map<String, AttributeModifier> modifiers) {
            this(baseOverride, modifiers, true);
        }
    }

    private boolean contains(ResourceLocation id) {
        return local.containsKey(id) || parent != null && parent.contains(id);
    }

    private double base(ResourceLocation id) {
        var state = local.get(id);
        if (state != null && state.baseOverride != null) {
            return state.baseOverride;
        }
        return parent != null && parent.contains(id) ? parent.base(id) : defaultBase;
    }

    private boolean hasModifiers(ResourceLocation id) {
        var state = local.get(id);
        return state != null && !state.modifiers.isEmpty()
            || (state == null || state.inheritModifiers) && parent != null && parent.hasModifiers(id);
    }

    private void collectModifiers(ResourceLocation id, Object2ObjectLinkedOpenHashMap<String, AttributeModifier> result) {
        var state = local.get(id);
        if (parent != null && (state == null || state.inheritModifiers)) {
            parent.collectModifiers(id, result);
        }
        if (state != null) {
            result.putAll(state.modifiers);
        }
    }

    private void prune(ResourceLocation id, LocalState state) {
        if (state.baseOverride == null && state.modifiers.isEmpty()) {
            local.remove(id);
        }
    }

    private static void validateModifier(AttributeModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        if (Objects.requireNonNull(modifier.id(), "modifier ID").isBlank()) {
            throw new IllegalArgumentException("Modifier ID must be nonblank");
        }
        Objects.requireNonNull(modifier.operation(), "modifier operation");
    }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value;
    }

    private static final class LocalState {
        private @Nullable Double baseOverride;
        private boolean inheritModifiers = true;
        private final Object2ObjectLinkedOpenHashMap<String, AttributeModifier> modifiers = new Object2ObjectLinkedOpenHashMap<>();
    }
}
