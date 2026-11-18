package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Objects;

/**
 * One choice; all children execute in their declared order without a second selection.
 * The presentation is sent to clients independently of the executable children.
 */
public record RewardGroup(ResourceLocation id, ObjectList<RewardChild> children, RewardPresentation presentation) {
    public RewardGroup {
        Objects.requireNonNull(id);
        children = ObjectLists.unmodifiable(new ObjectArrayList<>(children));
        Objects.requireNonNull(presentation);
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

    public RewardGroup(ResourceLocation id, Collection<? extends RewardChild> children, RewardPresentation presentation) {
        this(id, new ObjectArrayList<>(children), presentation);
    }

    public RewardGroup(ResourceLocation id, ObjectList<RewardChild> children) {
        this(id, children, composite(children));
    }

    private static RewardPresentation composite(ObjectList<RewardChild> children) {
        var rendered = new ObjectArrayList<RewardPresentation>(children.size());
        for (var child : children) {
            rendered.add(switch (child) {
                case RewardChild.Item item -> new ItemRewardPresentation(new GenericStack(item.resource(), item.amount()));
                case RewardChild.Event event -> new TextRewardPresentation(event.id().toString());
            });
        }
        return rendered.size() == 1 ? rendered.getFirst() : new CompositeRewardPresentation(rendered);
    }
}
