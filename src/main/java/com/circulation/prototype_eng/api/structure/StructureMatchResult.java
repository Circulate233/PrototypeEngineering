package com.circulation.prototype_eng.api.structure;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Complete matching outcome; unavailable facts grant no new structure publication authority. */
public record StructureMatchResult(StructurePredicateResult status, @Nullable StructureMatch match) {
    public StructureMatchResult {
        Objects.requireNonNull(status);
        if (status == StructurePredicateResult.MATCHED) Objects.requireNonNull(match, "Matched structure occurrence");
        else if (match != null) throw new IllegalArgumentException("Non-matched outcome contains a structure occurrence");
    }

    public static StructureMatchResult matched(StructureMatch match) {
        return new StructureMatchResult(StructurePredicateResult.MATCHED, Objects.requireNonNull(match));
    }

    public static StructureMatchResult mismatch() {
        return new StructureMatchResult(StructurePredicateResult.MISMATCH, null);
    }

    public static StructureMatchResult unavailable() {
        return new StructureMatchResult(StructurePredicateResult.UNAVAILABLE, null);
    }
}
