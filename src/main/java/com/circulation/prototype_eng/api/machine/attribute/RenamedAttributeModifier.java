package com.circulation.prototype_eng.api.machine.attribute;

import java.util.Objects;

/** Explicit immutable ID wrapper; persistence can encode the delegate without inspecting private classes. */
public record RenamedAttributeModifier(String id, AttributeModifier delegate) implements AttributeModifier {
    public RenamedAttributeModifier {
        if (Objects.requireNonNull(id).isBlank()) { throw new IllegalArgumentException("Blank modifier id"); }
        Objects.requireNonNull(delegate);
        Objects.requireNonNull(delegate.operation());
    }

    @Override public Operation operation() { return delegate.operation(); }
    @Override public double apply(double base, double current) { return delegate.apply(base, current); }
}
