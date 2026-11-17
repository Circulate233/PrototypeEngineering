package com.circulation.prototype_eng.client.research;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The client's last research canvas view, separate from authored research content and page layouts.
 */
@SideOnly(Side.CLIENT)
public final class ResearchOverviewViewState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ResearchOverviewViewState() {
    }

    public static @Nullable State load(Path configRoot) {
        var path = path(configRoot);
        if (!Files.exists(path)) return null;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var data = GSON.fromJson(reader, JsonObject.class);
            var state = new State(data.get("page").getAsString(), data.get("panX").getAsDouble(),
                data.get("panY").getAsDouble(), data.get("zoom").getAsDouble());
            if (state.page().isBlank() || !Double.isFinite(state.panX()) || !Double.isFinite(state.panY())
                || !Double.isFinite(state.zoom()) || state.zoom() < 0.5 || state.zoom() > 2)
                throw new IllegalArgumentException("Invalid research overview camera values");
            return state;
        } catch (IOException | RuntimeException failure) {
            PrototypeEngineering.LOGGER.warn("Cannot load research overview view state at {}", path, failure);
            return null;
        }
    }

    public static void save(Path configRoot, State state) {
        var path = path(configRoot);
        try {
            var data = new JsonObject();
            data.addProperty("page", state.page());
            data.addProperty("panX", state.panX());
            data.addProperty("panY", state.panY());
            data.addProperty("zoom", state.zoom());
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failure) {
            PrototypeEngineering.LOGGER.warn("Cannot save research overview view state at {}", path, failure);
        }
    }

    private static Path path(Path configRoot) {
        return configRoot.resolve("prototype_eng/client/research_overview_view.json");
    }

    public record State(String page, double panX, double panY, double zoom) {
    }
}
