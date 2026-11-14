package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Runtime matcher alternatives. Displayed candidates are presentation data, never a runtime filter;
 * actual keys come from available snapshots. Amount describes one execution, before chance/parallelism.
 * An optional candidateOrder must remain a pure stable total order throughout execution/restoration;
 * null selects the canonical serialized-key order.
 */
public record DynamicInputGroup(NamedResourceMatcher matcher, AEKey pattern, long amount,
                                List<AEKey> displayedCandidates, int maxCandidates,
                                @Nullable Comparator<AEKey> candidateOrder) {
    public DynamicInputGroup {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(displayedCandidates, "displayedCandidates");
        if (amount <= 0 || maxCandidates < 1) {
            throw new IllegalArgumentException("Dynamic input amount and candidate limit must be positive");
        }
        var copy = new ObjectArrayList<AEKey>(displayedCandidates.size());
        for (int index = 0; index < displayedCandidates.size(); index++) {
            var candidate = displayedCandidates.get(index);
            copy.add(Objects.requireNonNull(candidate, "displayed candidate"));
        }
        displayedCandidates = ObjectLists.unmodifiable(copy);
    }

    public DynamicInputGroup(NamedResourceMatcher matcher, AEKey pattern, long amount) {
        this(matcher, pattern, amount, ObjectLists.emptyList(), 256, null);
    }

    public DynamicInputGroup(NamedResourceMatcher matcher, AEKey pattern, long amount,
                             List<AEKey> displayedCandidates, int maxCandidates) {
        this(matcher, pattern, amount, displayedCandidates, maxCandidates, null);
    }
}
