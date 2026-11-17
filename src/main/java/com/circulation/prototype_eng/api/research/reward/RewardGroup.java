package com.circulation.prototype_eng.api.research.reward;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;

/**
 * One choice; all children execute in their declared order without a second selection.
 * The supplied child list must remain immutable for the lifetime of this group.
 * Titles and descriptions are supplied separately by client research content JSON.
 */
public record RewardGroup(ResourceLocation id, ObjectList<RewardChild> children) {
    public RewardGroup {
        if (children.isEmpty()) throw new IllegalArgumentException("Reward group must have children");
        var ids = new ObjectOpenHashSet<ResourceLocation>(children.size());
        for (int index = 0; index < children.size(); index++) {
            var child = children.get(index);
            if (!ids.add(child.id())) {
                throw new IllegalArgumentException("Duplicate reward child: " + child.id());
            }
        }
    }

    public RewardGroup(ResourceLocation id, Collection<? extends RewardChild> children) {
        this(id, new ObjectArrayList<>(children));
    }
}
