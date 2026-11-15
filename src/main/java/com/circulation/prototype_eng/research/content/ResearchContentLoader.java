package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Strict PN direct-display JSON schema at the new library's client configuration path.
 */
public final class ResearchContentLoader {
    private static final System.Logger LOGGER = System.getLogger("prototype_eng/research_content");
    private static final Gson GSON = new Gson();
    private final Path configRoot;

    public ResearchContentLoader(Path configRoot) {
        this.configRoot = configRoot.toAbsolutePath().normalize();
    }

    private static ObjectArrayList<String> description(JsonObject root) {
        var value = required(root, "description");
        if (!value.isJsonArray()) throw new IllegalArgumentException("Research description must be a string array");
        var entries = value.getAsJsonArray();
        if (entries.isEmpty()) throw new IllegalArgumentException("Research content requires description lines");
        var lines = new ObjectArrayList<String>(entries.size());
        for (int index = 0; index < entries.size(); index++)
            lines.add(requiredText(string(entries.get(index), "description entry")));
        return lines;
    }

    private static ResearchContent.Icon icon(JsonObject root) {
        var value = required(root, "icon");
        if (!value.isJsonObject()) throw new IllegalArgumentException("Research icon must be an object");
        var icon = value.getAsJsonObject();
        var kind = switch (string(icon, "kind").trim()) {
            case "item" -> ResearchContent.Kind.ITEM;
            case "icon" -> ResearchContent.Kind.ICON;
            default -> throw new IllegalArgumentException("Unknown research content icon kind");
        };
        var id = checkedId(new ResourceLocation(string(icon, "id").trim()));
        int meta = kind == ResearchContent.Kind.ITEM && icon.has("meta") ? meta(required(icon, "meta")) : 0;
        return new ResearchContent.Icon(kind, id, meta);
    }

    private static int meta(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Item icon metadata must be an integer");
        int meta = value.getAsBigDecimal().intValueExact();
        if (meta < 0) throw new IllegalArgumentException("Item icon metadata must be nonnegative");
        return meta;
    }

    private static String string(JsonObject object, String key) {
        return string(required(object, key), key);
    }

    private static String string(JsonElement value, String label) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString())
            throw new IllegalArgumentException("Research content " + label + " must be a string");
        return primitive.getAsString();
    }

    private static JsonElement required(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || value.isJsonNull())
            throw new IllegalArgumentException("Missing research content field: " + key);
        return value;
    }

    private static String requiredText(String text) {
        var trimmed = text.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Research content text must not be blank");
        return trimmed;
    }

    private static ResourceLocation checkedId(ResourceLocation id) {
        var namespace = id.getNamespace();
        var path = id.getPath();
        if (!namespace.matches("[a-z0-9_.-]+") || namespace.equals(".") || namespace.equals("..") || !path.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid research content resource ID: " + id);
        }
        for (var segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("Invalid research content path segment: " + id);
        }
        return id;
    }

    public ResearchContentLoadResult load(ResourceLocation researchId, String language) {
        var id = checkedId(researchId);
        var locale = language.trim();
        if (!locale.matches("[a-z0-9_]+"))
            throw new IllegalArgumentException("Invalid research content language: " + language);
        var requested = contentPath(locale, id);
        if (Files.exists(requested)) return ResearchContentLoadResult.loaded(id, loadFile(requested));
        var fallback = contentPath("en_us", id);
        if (!"en_us".equals(locale) && Files.exists(fallback))
            return ResearchContentLoadResult.loaded(id, loadFile(fallback));
        LOGGER.log(System.Logger.Level.WARNING, "Research content is missing for " + id + " in " + locale + " and en_us");
        return ResearchContentLoadResult.missing(id);
    }

    private Path contentPath(String language, ResourceLocation id) {
        var languageRoot = configRoot.resolve("prototype_eng").resolve("research_content").resolve(language).normalize();
        return languageRoot.resolve(id.getNamespace()).resolve(id.getPath() + ".json");
    }

    private ResearchContent loadFile(Path path) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject())
                throw new IllegalArgumentException("Research content root must be an object");
            var root = element.getAsJsonObject();
            return new ResearchContent(requiredText(string(root, "title")), root.has("summary") ? string(root, "summary") : "", description(root), icon(root));
        } catch (IOException | RuntimeException failure) {
            var message = "Research content load failed at " + path;
            LOGGER.log(System.Logger.Level.ERROR, message, failure);
            throw new IllegalStateException(message, failure);
        }
    }
}
