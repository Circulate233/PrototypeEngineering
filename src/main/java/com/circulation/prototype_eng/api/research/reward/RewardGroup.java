package com.circulation.prototype_eng.api.research.reward;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.Collection;
import java.util.Objects;
import net.minecraft.util.ResourceLocation;

/** One choice; all children execute in their declared order without a second selection. */
public record RewardGroup(ResourceLocation id, ObjectList<RewardChild> children) {
    public RewardGroup {
        Objects.requireNonNull(id, "id");
        var copy = new ObjectArrayList<>(Objects.requireNonNull(children, "children"));
        if (copy.isEmpty()) throw new IllegalArgumentException("Reward group must have children");
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var child : copy) {
            if (!ids.add(Objects.requireNonNull(child, "child").id())) {
                throw new IllegalArgumentException("Duplicate reward child: " + child.id());
            }
        }
        children = ObjectLists.unmodifiable(copy);
    }

    public RewardGroup(ResourceLocation id, Collection<? extends RewardChild> children) {
        this(id, new ObjectArrayList<>(children));
    }
}
