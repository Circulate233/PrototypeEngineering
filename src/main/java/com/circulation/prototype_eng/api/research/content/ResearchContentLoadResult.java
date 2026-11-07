package com.circulation.prototype_eng.api.research.content;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.ResourceLocation;

/** Missing language content is explicit; a malformed existing file is reported by the loader. */
public record ResearchContentLoadResult(ResourceLocation researchId, Status status, Optional<ResearchContent> content) {
    public ResearchContentLoadResult {
        Objects.requireNonNull(researchId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(content);
        if ((status == Status.LOADED) != content.isPresent()) throw new IllegalArgumentException("Research content result status differs from content");
    }

    public static ResearchContentLoadResult loaded(ResourceLocation id, ResearchContent content) {
        return new ResearchContentLoadResult(id, Status.LOADED, Optional.of(content));
    }

    public static ResearchContentLoadResult missing(ResourceLocation id) {
        return new ResearchContentLoadResult(id, Status.MISSING, Optional.empty());
    }

    public enum Status { LOADED, MISSING }
}
