package com.circulation.prototype_eng.api.structure;

import org.jetbrains.annotations.Nullable;


/**
 * Complete matching outcome; unavailable facts grant no new structure publication authority.
 */
public record StructureMatchResult(StructurePredicateResult status, @Nullable StructureMatch match) {
    public static StructureMatchResult matched(StructureMatch match) {
        return new StructureMatchResult(StructurePredicateResult.MATCHED, match);
    }

    public static StructureMatchResult mismatch() {
        return new StructureMatchResult(StructurePredicateResult.MISMATCH, null);
    }

    public static StructureMatchResult unavailable() {
        return new StructureMatchResult(StructurePredicateResult.UNAVAILABLE, null);
    }
}
