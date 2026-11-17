package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchContentLoadResult;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads client display JSON from data, then overlays fields supplied by the current language.
 * No Minecraft item registry or client renderer is required by the file loader.
 */
public final class ResearchContentLoader {
    private static final System.Logger LOGGER = System.getLogger("prototype_eng/research_content");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    private final ObjectOpenHashSet<ResourceLocation> missing = new ObjectOpenHashSet<>();

    public ResearchContentLoader(Path configRoot) {
        root = configRoot.toAbsolutePath().normalize().resolve("prototype_eng/research_content");
    }

    public ResearchContentLoadResult load(ResourceLocation id, String language) {
        var base = contentPath("data", id);
        if (!Files.exists(base)) {
            if (missing.add(id)) LOGGER.log(System.Logger.Level.WARNING, "Research content is missing at " + base);
            return ResearchContentLoadResult.missing(id);
        }
        var data = read(base);
        var localized = contentPath(language, id);
        if (Files.exists(localized)) overlay(data, read(localized));
        try {
            var description = new ObjectArrayList<String>();
            if (data.has("description")) {
                var lines = data.getAsJsonArray("description");
                for (int index = 0; index < lines.size(); index++) description.add(lines.get(index).getAsString());
            }
            var rewards = new Object2ObjectOpenHashMap<ResourceLocation, ResearchContent.Reward>();
            if (data.has("rewards")) {
                for (var entry : data.getAsJsonObject("rewards").entrySet()) {
                    var rewardId = new ResourceLocation(entry.getKey());
                    var reward = entry.getValue().getAsJsonObject();
                    rewards.put(rewardId, new ResearchContent.Reward(text(reward, "title", rewardId.toString()), text(reward, "description", "")));
                }
            }
            return ResearchContentLoadResult.loaded(id, new ResearchContent(text(data, "title", id.toString()), text(data, "summary", ""),
                description, data.has("icon") ? data.getAsJsonObject("icon") : null, rewards));
        } catch (RuntimeException failure) {
            throw failed(Files.exists(localized) ? localized : base, failure);
        }
    }

    public Path contentPath(String language, ResourceLocation id) {
        var directory = root.resolve(language).resolve(id.getNamespace());
        var path = directory.resolve(id.getPath() + ".json").normalize();
        if (!path.startsWith(directory) || !language.matches("[a-z0-9_]+")
            || !id.getNamespace().matches("[a-z0-9_.-]+") || id.getNamespace().equals(".") || id.getNamespace().equals(".."))
            throw new IllegalArgumentException("Research content path is outside its configured directory: " + id);
        return path;
    }

    public Path sourcePath(String language, ResourceLocation id) {
        var localized = contentPath(language, id);
        return Files.exists(localized) ? localized : contentPath("data", id);
    }

    public ResearchPage loadPage(String namespace) {
        var file = pagePath(namespace);
        var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        if (!Files.exists(file)) return new ResearchPage(0, false, positions);
        var data = read(file);
        try {
            boolean manual = "manual".equals(text(data, "layout", "auto"));
            if (manual && data.has("positions")) {
                for (var entry : data.getAsJsonObject("positions").entrySet()) {
                    var position = entry.getValue().getAsJsonArray();
                    positions.put(entry.getKey(), new ResearchPage.Position(position.get(0).getAsInt(), position.get(1).getAsInt()));
                }
            }
            return new ResearchPage(data.has("order") ? data.get("order").getAsInt() : 0, manual, positions);
        } catch (RuntimeException failure) {
            throw failed(file, failure);
        }
    }

    public void exportPage(String namespace, ResearchPage page) {
        var file = pagePath(namespace);
        var data = new JsonObject();
        data.addProperty("order", page.order());
        data.addProperty("layout", page.manual() ? "manual" : "auto");
        var positions = new JsonObject();
        for (var entry : page.positions().object2ObjectEntrySet()) {
            var point = new JsonArray();
            point.add(entry.getValue().x());
            point.add(entry.getValue().y());
            positions.add(entry.getKey(), point);
        }
        data.add("positions", positions);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw failed(file, failure);
        }
    }

    private Path pagePath(String namespace) {
        if (!namespace.matches("[a-z0-9_.-]+") || namespace.equals(".") || namespace.equals(".."))
            throw new IllegalArgumentException("Invalid research page namespace: " + namespace);
        return root.resolve("data").resolve(namespace + ".json");
    }

    private static String text(JsonObject data, String name, String fallback) {
        return data.has(name) ? data.get(name).getAsString() : fallback;
    }

    private static void overlay(JsonObject target, JsonObject source) {
        for (var entry : source.entrySet()) {
            var existing = target.get(entry.getKey());
            if (existing instanceof JsonObject object && entry.getValue() instanceof JsonObject replacement)
                overlay(object, replacement);
            else target.add(entry.getKey(), entry.getValue());
        }
    }

    private static JsonObject read(Path path) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonElement.class).getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            throw failed(path, failure);
        }
    }

    private static IllegalStateException failed(Path path, Throwable failure) {
        var message = "Research content operation failed at " + path;
        LOGGER.log(System.Logger.Level.ERROR, message, failure);
        return new IllegalStateException(message, failure);
    }
}
