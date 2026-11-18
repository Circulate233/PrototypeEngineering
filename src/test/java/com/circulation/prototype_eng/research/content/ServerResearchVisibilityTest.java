package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchVisibility;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerResearchVisibilityTest {
    @TempDir
    Path configRoot;

    @Test
    void anUnrevealedPrerequisiteHidesItsDependantsAndTheirPagesUntilTheTeamMakesProgress() throws IOException {
        var foundation = id("foundation");
        var revealedOnCompletion = id("prototype");
        var stageGated = id("extension");
        var dependant = id("application");
        var registry = new ResearchRegistry();
        registry.register(research(dependant, 1).stage(stage(1).prerequisite(stageGated, 1).build()).build());
        registry.register(research(stageGated, 2).visibility(ResearchVisibility.AFTER_PREREQUISITES)
            .stage(stage(1).prerequisite(foundation, 1).build())
            .stage(stage(2).prerequisite(foundation, 2).prerequisite(revealedOnCompletion, 1).build()).build());
        registry.register(research(revealedOnCompletion, 1).stage(stage(1).build()).build());
        registry.register(research(foundation, 2).stage(stage(1, 2).build()).build());
        registry.freeze();
        var data = Files.createDirectories(configRoot.resolve("prototype_eng/research_content/data"));
        writeResearch(data, revealedOnCompletion, "{\"title\":\"Prototype\",\"visibility\":\"after_completion\"}");
        writeResearch(data, foundation, "{\"title\":\"Foundation\"}");
        writeResearch(data, stageGated, "{\"title\":\"Extension\"}");
        writeResearch(data, dependant, "{\"title\":\"Application\"}");
        writePage(data, "intro", 5, foundation);
        writePage(data, "future", -10, dependant, stageGated);
        writePage(data, "discovery", 0, revealedOnCompletion);
        var catalog = new ServerResearchCatalog(configRoot, registry);
        var completed = new Object2IntOpenHashMap<ResourceLocation>();

        assertProjection(catalog, completed, Set.of(foundation), List.of("intro"));
        completed.put(foundation, 2);
        assertProjection(catalog, completed, Set.of(foundation), List.of("intro"));
        completed.put(revealedOnCompletion, 1);
        assertProjection(catalog, completed, Set.of(foundation, revealedOnCompletion, stageGated, dependant),
            List.of("future", "discovery", "intro"));
        completed.put(foundation, 1);
        assertProjection(catalog, completed, Set.of(foundation, revealedOnCompletion), List.of("discovery", "intro"));
        assertEquals(Set.of(foundation, revealedOnCompletion, stageGated, dependant), catalog.layout(Set.of(), true).nodes().keySet());
    }

    private static void assertProjection(ServerResearchCatalog catalog, Object2IntOpenHashMap<ResourceLocation> completed,
                                         Set<ResourceLocation> expected, List<String> pages) {
        var visible = catalog.visible(completed);
        assertEquals(expected, visible);
        var layout = catalog.layout(visible, false);
        assertEquals(expected, layout.nodes().keySet());
        assertEquals(pages, layout.pages().stream().map(ResearchOverviewLayout.Page::id).toList());
    }

    private static ResearchDefinition.Builder research(ResourceLocation id, int maxLevel) {
        return ResearchDefinition.builder(id).maxLevel(maxLevel).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
    }

    private static ResearchLevelStage.Builder stage(int level) {
        return stage(level, level);
    }

    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).durationTicks(20).researchTier(0)
            .resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("visibility_test", path);
    }

    private static void writeResearch(Path data, ResourceLocation id, String json) throws IOException {
        Files.writeString(Files.createDirectories(data.resolve(id.getNamespace())).resolve(id.getPath() + ".json"), json);
    }

    private static void writePage(Path data, String page, int priority, ResourceLocation... members) throws IOException {
        var entries = new JsonArray();
        for (var member : members) entries.add(member.toString());
        var json = new JsonObject();
        json.addProperty("priority", priority);
        json.add("researches", entries);
        Files.writeString(data.resolve(page + ".json"), json.toString());
    }
}
