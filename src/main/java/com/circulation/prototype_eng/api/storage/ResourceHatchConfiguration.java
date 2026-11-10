package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** Independent per-side layouts and capacities in each key type's native units. */
public record ResourceHatchConfiguration(@Nullable Side input, @Nullable Side output) implements HatchConfiguration {
    @SuppressWarnings("ResultOfMethodCallIgnored") // Checked addition validates the combined native int index range.
    public ResourceHatchConfiguration {
        if (input == null && output == null) throw new IllegalArgumentException("A hatch needs a side");
        // The native combined GenericInternalInventory and Forge handler use one int slot index.
        if (input != null && output != null) Math.addExact(input.slots(), output.slots());
    }

    public static ResourceHatchConfiguration symmetric(HatchMode mode, int slots, Map<AEKeyType, Long> capacities) {
        var side = new Side(slots, capacities);
        return new ResourceHatchConfiguration(mode.hasInput() ? side : null, mode.hasOutput() ? side : null);
    }

    @Override
    public HatchMode mode() {
        return input == null ? HatchMode.OUTPUT : output == null ? HatchMode.INPUT : HatchMode.MIXED;
    }

    public record CapacityBasis(long itemEquivalent, Map<AEKeyType, Long> ratios) {
        public CapacityBasis {
            if (itemEquivalent <= 0) throw new IllegalArgumentException("Item-equivalent capacity must be positive");
            ratios = Map.copyOf(ratios);
            if (ratios.isEmpty() || ratios.values().stream().anyMatch(value -> value <= 0))
                throw new IllegalArgumentException("Capacity ratios must be positive");
        }
    }

    public record Side(int slots, Map<AEKeyType, Long> capacities, @Nullable CapacityBasis basis) {
        /** Native-capacity profiles may omit a declared conversion basis. */
        public Side(int slots, Map<AEKeyType, Long> capacities) {
            this(slots, capacities, null);
        }

        public Side {
            if (slots <= 0 || capacities.isEmpty())
                throw new IllegalArgumentException("A side needs slots and resource types");
            capacities.forEach((type, capacity) -> {
                Objects.requireNonNull(type);
                if (capacity == null || capacity <= 0) throw new IllegalArgumentException("Capacity must be positive");
            });
            capacities = Map.copyOf(capacities);
            if (basis != null) {
                if (!basis.ratios().keySet().equals(capacities.keySet()))
                    throw new IllegalArgumentException("Capacity basis type set differs");
                for (var entry : capacities.entrySet()) {
                    if (Math.multiplyExact(basis.itemEquivalent(), basis.ratios().get(entry.getKey())) != entry.getValue())
                        throw new IllegalArgumentException("Capacity basis differs from native capacity");
                }
            }
        }

        /** One item-equivalent unit defaults to one display unit of each supported AE key type. */
        public static Side fromItemEquivalent(int slots, long itemEquivalent, Iterable<AEKeyType> types) {
            return fromItemEquivalent(slots, itemEquivalent, types, Map.of());
        }

        /** Explicit positive ratios use native amount units per item-equivalent, before checked multiplication. */
        public static Side fromItemEquivalent(int slots, long itemEquivalent, Iterable<AEKeyType> types,
                                              Map<AEKeyType, Long> ratioOverrides) {
            if (itemEquivalent <= 0) throw new IllegalArgumentException("Item-equivalent capacity must be positive");
            Objects.requireNonNull(types);
            var ratios = Map.copyOf(ratioOverrides);
            ratios.forEach((type, ratio) -> {
                if (ratio <= 0)
                    throw new IllegalArgumentException("Resource capacity ratio must be positive: " + type.getId());
            });
            var capacities = new Reference2LongArrayMap<AEKeyType>();
            var effectiveRatios = new Reference2LongArrayMap<AEKeyType>();
            for (var type : types) {
                Objects.requireNonNull(type);
                Long override = ratios.get(type);
                long ratio = override == null ? type.getAmountPerUnit() : override;
                if (ratio <= 0)
                    throw new IllegalArgumentException("Resource unit amount must be positive: " + type.getId());
                if (capacities.containsKey(type))
                    throw new IllegalArgumentException("Duplicate resource type: " + type.getId());
                capacities.put(type, Math.multiplyExact(itemEquivalent, ratio));
                effectiveRatios.put(type, ratio);
            }
            for (var type : ratios.keySet()) {
                if (!capacities.containsKey(type))
                    throw new IllegalArgumentException("Ratio override targets an unsupported resource type: " + type.getId());
            }
            return new Side(slots, capacities, new CapacityBasis(itemEquivalent, effectiveRatios));
        }
    }
}
