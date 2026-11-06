package com.circulation.prototype_eng.api.recipe.matching;

import ae2.api.stacks.AEKey;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/**
 * Immutable named matcher binding, retained directly by recipes instead of a hot-swappable global
 * registry. The predicate itself must satisfy ResourceMatcher's pure, stable behavior contract.
 * Persistence resolves this ID against the owning recipe's binding, never by serializing a lambda.
 */
public record NamedResourceMatcher(ResourceLocation id, ResourceMatcher matcher) implements ResourceMatcher {
    public NamedResourceMatcher {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(matcher, "matcher");
    }

    @Override
    public boolean matches(AEKey candidate, AEKey pattern) {
        return matcher.matches(Objects.requireNonNull(candidate, "candidate"), Objects.requireNonNull(pattern, "pattern"));
    }
}
