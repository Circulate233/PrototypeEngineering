package com.circulation.prototype_eng.api.research.content;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.ResourceLocation;


/**
 * Missing base content is explicit; an absent language overlay uses the base file normally.
 */
public record ResearchContentLoadResult(ResourceLocation researchId, Status status, @Nullable ResearchContent content) {
    public static ResearchContentLoadResult loaded(ResourceLocation id, ResearchContent content) {
        return new ResearchContentLoadResult(id, Status.LOADED, content);
    }

    public static ResearchContentLoadResult missing(ResourceLocation id) {
        return new ResearchContentLoadResult(id, Status.MISSING, null);
    }

    public enum Status {LOADED, MISSING}
}
