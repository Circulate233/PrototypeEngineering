package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Independent per-side layouts and capacities in each key type's native units.
 * Capacity and ratio maps are borrowed read-only; producers and consumers must not mutate them
 * for the configuration lifetime. Derived profiles allocate their own numeric results.
 */
public record ResourceHatchConfiguration(@Nullable Side input, @Nullable Side output) implements HatchConfiguration {
    @SuppressWarnings("ResultOfMethodCallIgnored") // Checked addition validates the combined native int index range.
    public ResourceHatchConfiguration {
        if (input == null && output == null) throw new IllegalArgumentException("A hatch needs a side");
        // The native combined GenericInternalInventory and Forge handler use one int slot index.
        if (input != null && output != null) Math.addExact(input.slots(), output.slots());
    }

    public static ResourceHatchConfiguration symmetric(HatchMode mode, int slots, Reference2LongMap<AEKeyType> capacities) {
        var side = new Side(slots, capacities);
        return new ResourceHatchConfiguration(mode.hasInput() ? side : null, mode.hasOutput() ? side : null);
    }

    @Override
    public HatchMode mode() {
        return input == null ? HatchMode.OUTPUT : output == null ? HatchMode.INPUT : HatchMode.MIXED;
    }

    public record CapacityBasis(long itemEquivalent, Reference2LongMap<AEKeyType> ratios) {
        public CapacityBasis {
            if (itemEquivalent <= 0) throw new IllegalArgumentException("Item-equivalent capacity must be positive");
            if (ratios.isEmpty() || ratios.values().longStream().anyMatch(value -> value <= 0))
                throw new IllegalArgumentException("Capacity ratios must be positive");
        }
    }

    public record Side(int slots, Reference2LongMap<AEKeyType> capacities, @Nullable CapacityBasis basis) {
        /**
         * Native-capacity profiles may omit a declared conversion basis.
         */
        public Side(int slots, Reference2LongMap<AEKeyType> capacities) {
            this(slots, capacities, null);
        }

        public Side {
            if (slots <= 0 || capacities.isEmpty())
                throw new IllegalArgumentException("A side needs slots and resource types");
            for (var entry : capacities.reference2LongEntrySet()) {
                Objects.requireNonNull(entry.getKey());
                if (entry.getLongValue() <= 0) throw new IllegalArgumentException("Capacity must be positive");
            }
            if (basis != null) {
                if (!basis.ratios().keySet().equals(capacities.keySet()))
                    throw new IllegalArgumentException("Capacity basis type set differs");
                for (var entry : capacities.reference2LongEntrySet()) {
                    if (Math.multiplyExact(basis.itemEquivalent(), basis.ratios().getLong(entry.getKey())) != entry.getLongValue())
                        throw new IllegalArgumentException("Capacity basis differs from native capacity");
                }
            }
        }

        /**
         * One item-equivalent unit defaults to one display unit of each supported AE key type.
         */
        public static Side fromItemEquivalent(int slots, long itemEquivalent, Iterable<AEKeyType> types) {
            return fromItemEquivalent(slots, itemEquivalent, types, Reference2LongMaps.emptyMap());
        }

        /**
         * Explicit positive ratios use native amount units per item-equivalent, before checked multiplication.
         */
        public static Side fromItemEquivalent(int slots, long itemEquivalent, Iterable<AEKeyType> types,
                                              Reference2LongMap<AEKeyType> ratioOverrides) {
            if (itemEquivalent <= 0) throw new IllegalArgumentException("Item-equivalent capacity must be positive");
            Objects.requireNonNull(types);
            for (var entry : ratioOverrides.reference2LongEntrySet())
                if (entry.getLongValue() <= 0)
                    throw new IllegalArgumentException("Resource capacity ratio must be positive: " + entry.getKey().getId());
            var capacities = new Reference2LongArrayMap<AEKeyType>(2);
            var effectiveRatios = new Reference2LongArrayMap<AEKeyType>(2);
            for (var type : types) {
                Objects.requireNonNull(type);
                long ratio = ratioOverrides.containsKey(type) ? ratioOverrides.getLong(type) : type.getAmountPerUnit();
                if (ratio <= 0)
                    throw new IllegalArgumentException("Resource unit amount must be positive: " + type.getId());
                if (capacities.containsKey(type))
                    throw new IllegalArgumentException("Duplicate resource type: " + type.getId());
                capacities.put(type, Math.multiplyExact(itemEquivalent, ratio));
                effectiveRatios.put(type, ratio);
            }
            for (var type : ratioOverrides.keySet()) {
                if (!capacities.containsKey(type))
                    throw new IllegalArgumentException("Ratio override targets an unsupported resource type: " + type.getId());
            }
            return new Side(slots, capacities, new CapacityBasis(itemEquivalent, effectiveRatios));
        }
    }

}
