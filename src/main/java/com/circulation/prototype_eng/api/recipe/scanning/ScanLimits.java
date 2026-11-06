package com.circulation.prototype_eng.api.recipe.scanning;

/** Per-call candidate/probe limits, not a wall-clock or nested requirement-operation budget. */
public record ScanLimits(int candidates, int probes) {
    public ScanLimits {
        if (candidates < 1 || probes < 1) { throw new IllegalArgumentException("Scan limits must be positive"); }
    }
}
