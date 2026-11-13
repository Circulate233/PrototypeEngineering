package com.circulation.prototype_eng.api.structure;

/**
 * The visited branch distinguishes an unavailable world fact from a loaded ordinary mismatch.
 */
public enum StructurePredicateResult {
    MATCHED,
    MISMATCH,
    UNAVAILABLE
}
