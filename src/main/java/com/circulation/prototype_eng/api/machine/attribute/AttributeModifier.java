package com.circulation.prototype_eng.api.machine.attribute;

/**
 * Immutable attribute transformation. Implementations must keep their ID and operation stable and
 * apply as a pure function, without mutating attributes or other state. Snapshots share modifier
 * references because this contract makes them safe to retain across rollback and concurrent reads.
 */
public interface AttributeModifier {
    String id();

    Operation operation();

    /**
     * Original resolved base and the result of the preceding modifier, both finite.
     */
    double apply(double base, double current);

    /**
     * Phases run in this order; registration order is preserved within each phase.
     */
    enum Operation {
        ADDITION,
        MULTIPLY_BASE,
        MULTIPLY_TOTAL
    }
}
