package com.circulation.prototype_eng.api.research.content;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/**
 * Immutable direct-display client text; executable research and reward definitions stay separate.
 * Loaded collections and icon JSON are borrowed read-only. Text is direct display content, not translation keys.
 */
public record ResearchContent(String title, String summary, ObjectList<String> description,
                              @Nullable JsonObject icon, Object2ObjectMap<ResourceLocation, Reward> rewards) {
    public Reward reward(ResourceLocation groupId) {
        var reward = rewards.get(groupId);
        return reward == null ? new Reward(groupId.toString(), "") : reward;
    }

    public record Reward(String title, String description) {
    }
}
