package com.circulation.prototype_eng.api.research.reward;

import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

/**
 * One choice; all children execute in their declared order without a second selection.
 * Optional title and description are display metadata, outside saved claims and execution identity.
 * Client GUIs call the configured enum's getLocal() directly and use id.toString() when it is absent.
 */
public record RewardGroup(ResourceLocation id, @Nullable LocalizationEnum title,
                          @Nullable LocalizationEnum description, ObjectList<RewardChild> children) {
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

    public RewardGroup(ResourceLocation id, ObjectList<RewardChild> children) {
        this(id, null, null, children);
    }

    public RewardGroup(ResourceLocation id, Collection<? extends RewardChild> children) {
        this(id, null, null, new ObjectArrayList<>(children));
    }

    public RewardGroup(ResourceLocation id, @Nullable LocalizationEnum title,
                       @Nullable LocalizationEnum description, Collection<? extends RewardChild> children) {
        this(id, title, description, new ObjectArrayList<>(children));
    }
}
