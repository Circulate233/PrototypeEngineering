package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public record ResearchOverviewLayout(List<Page> pages, Map<ResourceLocation, Node> nodes) {
    public static final ResearchOverviewLayout EMPTY = new ResearchOverviewLayout(List.of(), Map.of());

    public record Page(String id, ResearchPage page) {
    }

    public record Node(String title, @Nullable JsonObject icon) {
    }
}
