package com.circulation.prototype_eng.api.recipe.process;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Stable nested identity: reused child definitions in different wrappers own separate process state. */
public record RequirementPath(@Nullable RequirementPath parent, ResourceLocation typeId, ResourceLocation requirementId) {
    public RequirementPath {
        Objects.requireNonNull(typeId);
        Objects.requireNonNull(requirementId);
    }

    @Override
    public @NotNull String toString() {
        // Frame each ID: legacy ResourceLocation constructors do not enforce modern ID character rules.
        return (parent == null ? "" : parent.toString()) + frame(typeId) + frame(requirementId);
    }

    private static String frame(ResourceLocation id) {
        String value = id.toString();
        return value.length() + ":" + value;
    }
}
