package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchVisibility;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerResearchCatalogTest {
    @TempDir
    Path config;

    @Test
    void filtersEveryHiddenDependencyIncludingAcrossPagesAndEvaluatesEachTeamSeparately() {
        var basic = id("start", "basic");
        var next = id("gated", "next");
        var secret = id("secret", "study");
        var depend = id("tail", "depend");
        var tail = id("tail", "end");
        var registry = registry(
            definition(basic, ResearchVisibility.ALWAYS, stage(3)),
            definition(next, ResearchVisibility.AFTER_PREREQUISITES, stage(1).prerequisite(basic, 2)),
            definition(secret, ResearchVisibility.AFTER_COMPLETION, stage(3).prerequisite(next, 1)),
            definition(depend, ResearchVisibility.ALWAYS, stage(1).prerequisite(secret, 1)),
            definition(tail, ResearchVisibility.ALWAYS, stage(1).prerequisite(depend, 1)));
        var catalog = new ServerResearchCatalog(config, registry);
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        levels.put(basic, 1);
        assertEquals(Set.of(basic), catalog.visible(levels));
        levels.put(basic, 2);
        assertEquals(Set.of(basic, next), catalog.visible(levels));
        assertEquals(List.of("gated", "start"), pageIds(catalog.layout(catalog.visible(levels), false)));

        levels.put(next, 1);
        levels.put(secret, 1);
        assertEquals(Set.of(basic, next, secret, depend, tail), catalog.visible(levels));
        assertEquals(List.of("gated", "secret", "start", "tail"), pageIds(catalog.layout(catalog.visible(levels), false)));
        assertEquals(Set.of(basic), catalog.visible(new Object2IntOpenHashMap<>()));
    }

    @Test
    void honorsSignedPrioritiesAndKeepsEmptyPagesOnlyInTheEditor() {
        var first = id("default_a", "first");
        var second = id("default_b", "second");
        var secret = id("default_b", "secret");
        var loader = new ResearchContentLoader(config);
        var points = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        points.put(second.toString(), new ResearchPage.Position(-17, 49));
        points.put(secret.toString(), new ResearchPage.Position(125, 230));
        loader.exportPage("z_top", new ResearchPage(-8, true, "First", List.of(second, secret), points));
        loader.exportPage("a_middle", new ResearchPage(4, false, "Next", List.of(first), new Object2ObjectOpenHashMap<>()));
        loader.exportPage("empty", new ResearchPage(-10, false, "Empty", List.of(), new Object2ObjectOpenHashMap<>()));
        var catalog = new ServerResearchCatalog(config, registry(
            definition(first, ResearchVisibility.ALWAYS, stage(1)),
            definition(second, ResearchVisibility.ALWAYS, stage(1)),
            definition(secret, ResearchVisibility.AFTER_COMPLETION, stage(1))));

        var visible = catalog.visible(new Object2IntOpenHashMap<>());
        var layout = catalog.layout(visible, false);
        assertEquals(List.of("z_top", "a_middle"), pageIds(layout));
        var top = layout.pages().getFirst().page();
        assertEquals(List.of(second), top.researches());
        assertEquals(Map.of(second.toString(), new ResearchPage.Position(-17, 49)), top.positions());
        assertFalse(layout.nodes().containsKey(secret));
        var editor = catalog.layout(visible, true);
        assertEquals(List.of("empty", "z_top", "a_middle"), pageIds(editor));
        assertTrue(editor.nodes().containsKey(secret));
    }

    @Test
    void invalidPrioritiesDoNotReplaceTheCurrentCatalog() throws IOException {
        var study = id("research", "study");
        var file = config.resolve("prototype_eng/research_content/data/research.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"priority\": -5, \"title\": \"Current\"}");
        var catalog = new ServerResearchCatalog(config, registry(definition(study, ResearchVisibility.ALWAYS, stage(1))));
        var layout = catalog.layout(Set.of(study), false);
        long revision = catalog.revision();

        for (var invalid : List.of("0.25", "2147483648")) {
            Files.writeString(file, "{\"priority\": " + invalid + ", \"title\": \"Invalid\"}");
            assertThrows(IllegalStateException.class, catalog::reload);
            assertEquals(revision, catalog.revision());
            assertEquals(layout, catalog.layout(Set.of(study), false));
        }
    }

    @Test
    void editedAssignmentsAndDetailsReloadWithoutRemovingUnregisteredPageEntries() {
        var study = id("addon", "study");
        var unavailable = id("addon", "not_loaded");
        var loader = new ResearchContentLoader(config);
        loader.exportPage("source", new ResearchPage(0, false, null, List.of(study, unavailable), new Object2ObjectOpenHashMap<>()));
        var registry = registry(definition(study, ResearchVisibility.ALWAYS, stage(1)));
        var catalog = new ServerResearchCatalog(config, registry);
        catalog.createPage("research", "Research");
        catalog.addResearch("research", study, -33, 44, true, Map.of());
        var text = "{\"title\":\"Edited\",\"description\":[\"Server text\"],\"visibility\":\"after_completion\"}";
        catalog.saveData(study, text);
        var invalid = text.replace("after_completion", "unknown");
        assertThrows(IllegalStateException.class, () -> catalog.saveData(study, invalid));
        catalog.reload();
        var reloaded = new ServerResearchCatalog(config, registry);

        assertEquals(Set.of(), reloaded.visible(new Object2IntOpenHashMap<>()));
        assertEquals(List.of(unavailable), loader.loadPage("source").researches());
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        levels.put(study, 1);
        var layout = reloaded.layout(reloaded.visible(levels), false);
        assertEquals(List.of("research"), pageIds(layout));
        assertEquals(Map.of(study.toString(), new ResearchPage.Position(-33, 44)), layout.pages().getFirst().page().positions());
        assertEquals("Edited", layout.nodes().get(study).title());
        assertEquals(List.of("Server text"), loader.loadLocalized(study, "en_us", reloaded.detailJson(study)).description());
    }

    @Test
    void addingToAnUnexportedManualPageKeepsTheOriginalAutomaticLayout() {
        var original = id("research", "original");
        var incoming = id("other", "incoming");
        var registry = registry(definition(original, ResearchVisibility.ALWAYS, stage(1)),
            definition(incoming, ResearchVisibility.ALWAYS, stage(1)));
        var catalog = new ServerResearchCatalog(config, registry);
        var originalPosition = new ResearchPage.Position(92, 40);
        var addedPosition = new ResearchPage.Position(92, 40);
        catalog.addResearch("research", incoming, addedPosition.x(), addedPosition.y(), true,
            Map.of(original.toString(), originalPosition));
        catalog.reload();

        var layout = catalog.layout(Set.of(original, incoming), false);
        var page = layout.pages().getFirst().page();
        assertTrue(page.manual());
        assertEquals(List.of(original, incoming), page.researches());
        assertEquals(Map.of(original.toString(), originalPosition, incoming.toString(), addedPosition), page.positions());
    }

    private static List<String> pageIds(ResearchOverviewLayout layout) {
        return layout.pages().stream().map(ResearchOverviewLayout.Page::id).toList();
    }

    private static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    private static ResearchLevelStage.Builder stage(int max) {
        return ResearchLevelStage.builder(1, max).durationTicks(10).researchTier(0)
            .resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResearchDefinition definition(ResourceLocation id, ResearchVisibility visibility, ResearchLevelStage.Builder stage) {
        var value = stage.build();
        return ResearchDefinition.builder(id).maxLevel(value.lastLevel()).visibility(visibility).stage(value).build();
    }

    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var registry = new ResearchRegistry();
        for (var definition : definitions) registry.register(definition);
        registry.freeze();
        return registry;
    }

}
