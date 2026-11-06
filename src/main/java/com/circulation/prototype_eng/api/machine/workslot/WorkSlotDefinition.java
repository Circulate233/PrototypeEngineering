package com.circulation.prototype_eng.api.machine.workslot;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.Set;

/** Static work slot metadata supplied by a machine definition. */
public record WorkSlotDefinition(String name, Set<ResourceLocation> allowedRecipeGroups, boolean isCore,
                                 WorkSlotRestartPolicy restartPolicy) {
    public WorkSlotDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Work slot name is required");
        }
        Objects.requireNonNull(allowedRecipeGroups, "allowedRecipeGroups");
        Objects.requireNonNull(restartPolicy, "restartPolicy");
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (var group : allowedRecipeGroups) {
            groups.add(Objects.requireNonNull(group, "recipe group"));
        }
        allowedRecipeGroups = ObjectSets.unmodifiable(groups);
    }

    public WorkSlotDefinition(String name) {
        this(name, ObjectSets.emptySet());
    }

    public WorkSlotDefinition(String name, Set<ResourceLocation> allowedRecipeGroups) {
        this(name, allowedRecipeGroups, true, WorkSlotRestartPolicy.LOCKED_RETRY);
    }
}
