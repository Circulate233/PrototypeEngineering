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
        assertEquals(new ResearchPage.Position(17, 29), loaded.positions().get("network/basic"));
        positions.put("network/basic", new ResearchPage.Position(-13, 72));
        loader.exportPage("addon", page);
        assertEquals(new ResearchPage.Position(-13, 72), loader.loadPage("addon").positions().get("network/basic"));
        assertNotEquals(exported, Files.readString(file));
    }

    @Test
    void pageMovesRejectExpandedOverlapWithoutChangingThePreviousPosition() {
        var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        positions.put("a", new ResearchPage.Position(0, 0));
        positions.put("b", new ResearchPage.Position(92, 0));
        var page = new ResearchPage(0, true, positions);
        assertFalse(page.move("a", 30, 0));
        assertEquals(new ResearchPage.Position(0, 0), positions.get("a"));
        assertTrue(page.move("a", 17, 43));
        assertEquals(new ResearchPage.Position(17, 43), positions.get("a"));
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
