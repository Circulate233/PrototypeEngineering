package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real configuration IO and language overlays, without a Minecraft client or registry bootstrap.
 */
class ResearchContentLoaderTest {
    private static final ResourceLocation RESEARCH = new ResourceLocation("addon", "network/basic");
    private static final ResourceLocation GROUP = new ResourceLocation("addon", "reward");
    @TempDir
    Path configRoot;

    @Test
    void languageOverlaysOnlyPresentFieldsIncludingRewardTextAndCustomIconFields() throws IOException {
        write("data", """
            {"title":"Base","summary":"Summary","description":["Base line"],
             "icon":{"kind":"custom","id":"addon:map","color":3},
             "rewards":{"addon:reward":{"title":"Base reward","description":"Kept description"}}}
            """);
        write("zh_cn", """
            {"title":"基础研究","description":["第一行","第二行"],
             "icon":{"color":5},"rewards":{"addon:reward":{"title":"奖励"}}}
            """);
        var result = loader().load(RESEARCH, "zh_cn");
        assertEquals(ResearchContentLoadResult.Status.LOADED, result.status());
        var content = result.content();
        assertNotNull(content);
        assertEquals("基础研究", content.title());
        assertEquals("Summary", content.summary());
        assertEquals(ObjectList.of("第一行", "第二行"), content.description());
        var icon = content.icon();
        assertNotNull(icon);
        assertEquals("custom", icon.get("kind").getAsString());
        assertEquals(5, icon.get("color").getAsInt());
        assertEquals("奖励", content.reward(GROUP).title());
        assertEquals("Kept description", content.reward(GROUP).description());
        assertEquals("addon:other", content.reward(new ResourceLocation("addon", "other")).title());
    }

    @Test
    void absentLanguageUsesDataAndDoesNotReadEnglishAsFallback() throws IOException {
        write("data", "{\"title\":\"Base\"}");
        write("en_us", "{\"title\":\"English override\"}");
        var content = loader().load(RESEARCH, "zh_cn").content();
        assertNotNull(content);
        assertEquals("Base", content.title());
        assertTrue(content.description().isEmpty());
        assertNull(content.icon());
        var english = loader().load(RESEARCH, "en_us").content();
        assertNotNull(english);
        assertEquals("English override", english.title());
    }

    @Test
    void missingBaseIsExplicitEvenWithLanguageFileAndCreatesNoFiles() throws IOException {
        assertEquals(ResearchContentLoadResult.Status.MISSING, loader().load(RESEARCH, "zh_cn").status());
        assertFalse(Files.exists(configRoot.resolve("prototype_eng")));
        write("zh_cn", "{\"title\":\"Only locale\"}");
        assertNull(loader().load(RESEARCH, "zh_cn").content());
    }

    @Test
    void malformedExistingLanguageIsReportedAtItsFile() throws IOException {
        write("data", "{\"title\":\"Base\"}");
        write("zh_cn", "{\"title\":");
        var failure = assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        assertTrue(failure.getMessage().contains(file("zh_cn").toString()));
        assertNotNull(failure.getCause());
    }

    @Test
    void pageExportOverwritesThePageAndRoundTripsFreeCoordinates() throws IOException {
        var loader = loader();
        var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        positions.put("network/basic", new ResearchPage.Position(17, 29));
        var page = new ResearchPage(4, true, positions);
        loader.exportPage("addon", page);
        var file = configRoot.resolve("prototype_eng/research_content/data/addon.json");
        var exported = Files.readString(file);
        assertTrue(exported.contains("network/basic"));
        var loaded = loader.loadPage("addon");
        assertEquals(4, loaded.order());
        assertTrue(loaded.manual());
        assertEquals(new ResearchPage.Position(17, 29), loaded.positions().get(RESEARCH.toString()));
        positions.put("network/basic", new ResearchPage.Position(-13, 72));
        loader.exportPage("addon", page);
        assertEquals(new ResearchPage.Position(-13, 72), loader.loadPage("addon").positions().get(RESEARCH.toString()));
        assertNotEquals(exported, Files.readString(file));
    }

    @Test
    void emptyPagesAndCrossNamespaceMembershipSurviveExportAndDiscovery() {
        var loader = loader();
        var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        positions.put(RESEARCH.toString(), new ResearchPage.Position(-21, 43));
        var members = ObjectList.of(RESEARCH, new ResourceLocation("other", "linked"));
        loader.exportPage("custom_page", new ResearchPage(7, true, "Custom page", members, positions));
        loader.exportPage("empty_page", new ResearchPage(8, false, "Empty page", ObjectList.of(), new Object2ObjectOpenHashMap<>()));
        assertEquals(ObjectList.of("custom_page", "empty_page"), loader.pageIds());
        var loaded = loader.loadPage("custom_page");
        assertEquals("Custom page", loaded.title());
        assertEquals(members, loaded.researches());
        assertEquals(new ResearchPage.Position(-21, 43), loaded.positions().get(RESEARCH.toString()));
        var empty = loader.loadPage("empty_page").researches();
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    void contentEditorWritesOnlySelectedLayerAndRejectsInvalidReplacement() {
        var loader = loader();
        loader.saveContentJson(RESEARCH, null, "{\"title\":\"Base\",\"description\":[\"Preserved\"]}");
        loader.saveContentJson(RESEARCH, "zh_cn", "{\"title\":\"本地编辑\"}");
        var chinese = loader.load(RESEARCH, "zh_cn").content();
        var english = loader.load(RESEARCH, "en_us").content();
        assertNotNull(chinese);
        assertNotNull(english);
        assertEquals("本地编辑", chinese.title());
        assertEquals(ObjectList.of("Preserved"), chinese.description());
        assertEquals("Base", english.title());
        var saved = loader.readContentJson(RESEARCH, "zh_cn");
        assertThrows(IllegalStateException.class, () -> loader.saveContentJson(RESEARCH, "zh_cn", "{\"description\":42}"));
        assertEquals(saved, loader.readContentJson(RESEARCH, "zh_cn"));
        assertEquals("{}", loader.readContentJson(RESEARCH, "fr_fr"));
        assertFalse(Files.exists(file("fr_fr")));
    }

    @Test
    void languageEditorRequiresDataBeforeCreatingItsSelectedLayer() {
        var loader = loader();
        var failure = assertThrows(IllegalStateException.class,
            () -> loader.saveContentJson(RESEARCH, "zh_cn", "{\"title\":\"本地编辑\"}"));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Save the data content"));
        assertFalse(Files.exists(file("data")));
        assertFalse(Files.exists(file("zh_cn")));
        loader.saveContentJson(RESEARCH, null, "{\"title\":\"Base\"}");
        loader.saveContentJson(RESEARCH, "zh_cn", "{\"title\":\"本地编辑\"}");
        assertTrue(Files.exists(file("data")));
        assertTrue(Files.exists(file("zh_cn")));
        var content = loader.load(RESEARCH, "zh_cn").content();
        assertNotNull(content);
        assertEquals("本地编辑", content.title());
    }

    @Test
    void configuredFileOperationsCannotEscapeTheirDirectories() throws IOException {
        write("data", "{\"title\":\"Base\"}");
        var loader = loader();
        assertThrows(IllegalArgumentException.class, () -> loader.load(new ResourceLocation("addon", "../outside"), "zh_cn"));
        assertThrows(IllegalArgumentException.class, () -> loader.load(RESEARCH, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> loader.exportPage("../outside", loader.loadPage("addon")));
        assertFalse(Files.exists(configRoot.resolve("outside.json")));
    }

    private ResearchContentLoader loader() {
        return new ResearchContentLoader(configRoot);
    }

    private Path file(String language) {
        return configRoot.resolve("prototype_eng/research_content").resolve(language).resolve("addon/network/basic.json");
    }

    private void write(String language, String json) throws IOException {
        var file = file(language);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}
