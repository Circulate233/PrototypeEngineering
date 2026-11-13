package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real client configuration files and immutable values, without a Minecraft client or registry.
 */
class ResearchContentLoaderTest {
    private static final ResourceLocation RESEARCH = new ResourceLocation("addon", "network/basic");
    @TempDir
    Path configRoot;

    private static String valid(String title) {
        return "{\"title\":\"" + title + "\",\"description\":[\"D\"],\"icon\":{\"kind\":\"item\",\"id\":\"minecraft:paper\"}}";
    }

    @Test
    void requestedLanguageWinsAtTheExactNamespaceAndNestedPath() throws IOException {
        write("en_us", valid("Fallback"));
        write("zh_cn", """
            {"title":"  基础研究  ","summary":"  保留摘要空格  ","description":["  第一行  ","第二行"],
             "icon":{"kind":"item","id":"unregistered_addon:paper","meta":3}}
            """);
        var result = loader().load(RESEARCH, " zh_cn ");
        assertEquals(ResearchContentLoadResult.Status.LOADED, result.status());
        assertEquals(RESEARCH, result.researchId());
        var content = result.content().orElseThrow();
        assertEquals("基础研究", content.title());
        assertEquals("  保留摘要空格  ", content.summary());
        assertEquals(ObjectArrayList.of("第一行", "第二行"), content.description());
        assertEquals(new ResearchContent.Icon(ResearchContent.Kind.ITEM, new ResourceLocation("unregistered_addon", "paper"), 3), content.icon());
    }

    @Test
    void missingLocaleFallsBackToEnglishAndOptionalFieldsDefault() throws IOException {
        write("en_us", """
            {"title":"Fallback","description":["Text"],"icon":{"kind":"icon","id":"addon:network_basic"}}
            """);
        var content = loader().load(RESEARCH, "zh_cn").content().orElseThrow();
        assertEquals("Fallback", content.title());
        assertEquals("", content.summary());
        assertEquals(new ResearchContent.Icon(ResearchContent.Kind.ICON, new ResourceLocation("addon", "network_basic"), 0), content.icon());
    }

    @Test
    void missingContentIsExplicitAndDoesNotCreateFilesOrPlaceholderIcons() {
        var result = loader().load(RESEARCH, "zh_cn");
        assertEquals(ResearchContentLoadResult.Status.MISSING, result.status());
        assertTrue(result.content().isEmpty());
        assertFalse(Files.exists(configRoot.resolve("prototype_eng")));
        assertThrows(IllegalArgumentException.class, () -> new ResearchContentLoadResult(RESEARCH, ResearchContentLoadResult.Status.LOADED, Optional.empty()));
    }

    @Test
    void malformedRequestedFileCannotSilentlyUseTheEnglishFallback() throws IOException {
        write("en_us", valid("Fallback"));
        write("zh_cn", "{\"title\":");
        var failure = assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        assertTrue(failure.getMessage().contains(file("zh_cn").toString()));
        assertNotNull(failure.getCause());
    }

    @Test
    void malformedFallbackAndExistingDirectoryAreErrorsRatherThanMissing() throws IOException {
        write("en_us", "null");
        assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        Files.createDirectories(file("zh_cn"));
        assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
    }

    @Test
    void unsupportedCategorySectionsAndLegacyTranslationFieldsAreRejected() throws IOException {
        for (var extra : new String[]{"\"category\":\"network\"", "\"sections\":[]", "\"nameTranslationKey\":\"old.key\""}) {
            write("zh_cn", "{\"title\":\"T\",\"description\":[\"D\"],\"icon\":{\"kind\":\"item\",\"id\":\"minecraft:paper\"}," + extra + "}");
            assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        }
        write("zh_cn", "{\"title\":\"T\",\"description\":[\"D\"],\"icon\":{\"kind\":\"item\",\"id\":\"minecraft:paper\",\"unknown\":true}}");
        assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
    }

    @Test
    void textFieldsMustHaveTheirDeclaredTypesAndNonemptyRequiredText() throws IOException {
        for (var fields : new String[]{
            "\"title\":\" \",\"description\":[\"D\"]", "\"title\":42,\"description\":[\"D\"]",
            "\"description\":[\"D\"]", "\"title\":\"T\",\"summary\":null,\"description\":[\"D\"]",
            "\"title\":\"T\",\"summary\":false,\"description\":[\"D\"]", "\"title\":\"T\",\"description\":[]",
            "\"title\":\"T\",\"description\":[\" \"]", "\"title\":\"T\",\"description\":[null]",
            "\"title\":\"T\",\"description\":[3]", "\"title\":\"T\",\"description\":\"D\""
        }) {
            write("zh_cn", "{" + fields + ",\"icon\":{\"kind\":\"item\",\"id\":\"minecraft:paper\"}}");
            assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        }
    }

    @Test
    void itemMetadataMustBeAnExactNonnegativeIntAndMapIconsCannotDeclareIt() throws IOException {
        for (var meta : new String[]{"-1", "1.5", "2147483648", "true", "null", "\"1\""}) {
            writeIcon("{\"kind\":\"item\",\"id\":\"minecraft:paper\",\"meta\":" + meta + "}");
            assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        }
        writeIcon("{\"kind\":\"icon\",\"id\":\"addon:map_entry\",\"meta\":0}");
        assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        writeIcon("{\"kind\":\"item\",\"id\":\"minecraft:paper\"}");
        assertEquals(0, loader().load(RESEARCH, "zh_cn").content().orElseThrow().icon().meta());
    }

    @Test
    void iconKindAndCanonicalResourceIdAreStrict() throws IOException {
        for (var icon : new String[]{
            "{\"kind\":\"unknown\",\"id\":\"minecraft:paper\"}", "{\"kind\":\"item\",\"id\":\"paper\"}",
            "{\"kind\":\"item\",\"id\":\"Minecraft:paper\"}", "{\"kind\":\"item\",\"id\":\"addon:../outside\"}",
            "{\"kind\":\"item\",\"id\":null}", "null", "[]"
        }) {
            writeIcon(icon);
            assertThrows(IllegalStateException.class, () -> loader().load(RESEARCH, "zh_cn"));
        }
    }

    @Test
    void localeAndResearchPathCannotEscapeOrCrossLanguageDirectories() {
        for (var language : new String[]{"", "..", "../en_us", "en/us", "en\\us", "EN_US", "en-us", "file:c"}) {
            assertThrows(IllegalArgumentException.class, () -> loader().load(RESEARCH, language));
        }
        for (var path : new String[]{"../outside", "a/../outside", "a/./b", "a//b", "/absolute", "back\\slash", "with space"}) {
            assertThrows(IllegalArgumentException.class, () -> loader().load(new ResourceLocation("addon", path), "zh_cn"));
        }
        assertThrows(IllegalArgumentException.class, () -> loader().load(new ResourceLocation("..", "outside"), "zh_cn"));
        assertFalse(Files.exists(configRoot.resolve("prototype_eng")));
    }

    @Test
    void relativeConfigurationSegmentsNormalizeToTheSameSafeRoot() throws IOException {
        write("zh_cn", valid("T"));
        var normalized = new ResearchContentLoader(configRoot.resolve("unused").resolve(".."));
        assertEquals("T", normalized.load(RESEARCH, "zh_cn").content().orElseThrow().title());
    }

    private ResearchContentLoader loader() {
        return new ResearchContentLoader(configRoot);
    }

    private Path file(String language) {
        return configRoot.resolve("prototype_eng").resolve("research_content").resolve(language).resolve("addon").resolve("network/basic.json");
    }

    private void write(String language, String json) throws IOException {
        var file = file(language);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private void writeIcon(String icon) throws IOException {
        write("zh_cn", "{\"title\":\"T\",\"description\":[\"D\"],\"icon\":" + icon + "}");
    }
}
