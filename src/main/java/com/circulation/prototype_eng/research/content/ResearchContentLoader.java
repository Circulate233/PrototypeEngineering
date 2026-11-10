package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
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
import java.util.Objects;
import java.util.Set;

/**
 * Strict PN direct-display JSON schema at the new library's client configuration path.
 */
public final class ResearchContentLoader {
    private static final System.Logger LOGGER = System.getLogger("prototype_eng/research_content");
    private static final Gson GSON = new Gson();
    private static final Set<String> ROOT_FIELDS = Set.of("title", "summary", "description", "icon");
    private static final Set<String> ICON_FIELDS = Set.of("kind", "id", "meta");
    private final Path configRoot;

    public ResearchContentLoader(Path configRoot) {
        this.configRoot = Objects.requireNonNull(configRoot).toAbsolutePath().normalize();
    }

    private static ObjectArrayList<String> description(JsonObject root) {
        var value = required(root, "description");
        if (!value.isJsonArray()) throw new IllegalArgumentException("Research description must be a string array");
        var lines = new ObjectArrayList<String>();
        for (var entry : value.getAsJsonArray()) lines.add(string(entry, "description entry"));
        return lines;
    }

    private static ResearchContent.Icon icon(JsonObject root) {
        var value = required(root, "icon");
        if (!value.isJsonObject()) throw new IllegalArgumentException("Research icon must be an object");
        var icon = value.getAsJsonObject();
        requireFields(icon, ICON_FIELDS);
        var kind = switch (string(icon, "kind").trim()) {
            case "item" -> ResearchContent.Kind.ITEM;
            case "icon" -> ResearchContent.Kind.ICON;
            default -> throw new IllegalArgumentException("Unknown research content icon kind");
        };
        if (kind == ResearchContent.Kind.ICON && icon.has("meta"))
            throw new IllegalArgumentException("Icon-map entries must not define metadata");
        var id = checkedId(StrictNbt.requireId(string(icon, "id").trim()));
        int meta = icon.has("meta") ? meta(required(icon, "meta")) : 0;
        return new ResearchContent.Icon(kind, id, meta);
    }

    private static int meta(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Item icon metadata must be an integer");
        return value.getAsBigDecimal().intValueExact();
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

    private static void requireFields(JsonObject object, Set<String> allowed) {
        for (var field : object.entrySet())
            if (!allowed.contains(field.getKey()))
                throw new IllegalArgumentException("Unknown research content field: " + field.getKey());
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
        var id = checkedId(Objects.requireNonNull(researchId));
        var locale = Objects.requireNonNull(language).trim();
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
        var path = languageRoot.resolve(id.getNamespace()).resolve(id.getPath() + ".json").normalize();
        if (!path.startsWith(languageRoot))
            throw new IllegalArgumentException("Research content path escaped its language root");
        return path;
    }

    private ResearchContent loadFile(Path path) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject())
                throw new IllegalArgumentException("Research content root must be an object");
            var root = element.getAsJsonObject();
            requireFields(root, ROOT_FIELDS);
            return new ResearchContent(string(root, "title"), root.has("summary") ? string(root, "summary") : "", description(root), icon(root));
        } catch (IOException | RuntimeException failure) {
            var message = "Research content load failed at " + path;
            LOGGER.log(System.Logger.Level.ERROR, message, failure);
            throw new IllegalStateException(message, failure);
        }
    }
}
