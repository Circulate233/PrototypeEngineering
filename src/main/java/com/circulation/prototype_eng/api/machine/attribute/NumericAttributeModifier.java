package com.circulation.prototype_eng.api.machine.attribute;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * PM-compatible numeric operations. The optional adder is opaque provenance, never used by apply;
 * snapshots retain its reference with this immutable modifier rather than copying external objects.
 */
public record NumericAttributeModifier(String id, double amount, Operation operation,
                                       @Nullable Object adder) implements AttributeModifier {
    public NumericAttributeModifier {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operation, "operation");
        if (id.isBlank() || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("Modifier ID must be nonblank and amount finite");
        }
    }

    public NumericAttributeModifier(String id, double amount, Operation operation) {
        this(id, amount, operation, null);
    }

    @Override
    public double apply(double base, double current) {
        return switch (operation) {
            case ADDITION -> current + amount;
            case MULTIPLY_BASE -> current + base * amount;
            case MULTIPLY_TOTAL -> current * (1 + amount);
        };
    }
}
