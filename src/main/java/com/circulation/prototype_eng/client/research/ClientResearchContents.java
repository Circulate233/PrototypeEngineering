package com.circulation.prototype_eng.client.research;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.client.research.ResearchIcon;
import com.circulation.prototype_eng.api.client.research.ResearchIcons;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.research.content.ResearchContentLoader;
import com.circulation.prototype_eng.research.content.ResearchOverviewLayout;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

@SideOnly(Side.CLIENT)
public final class ClientResearchContents {
    private static final System.Logger LOGGER = System.getLogger("prototype_eng/research_content");
    private static final Object2ObjectOpenHashMap<ResourceLocation, NodeDisplay> NODES = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<ResourceLocation, DetailDisplay> DETAILS = new Object2ObjectOpenHashMap<>();
    private static ResearchContentLoader loader;
    private static @Nullable ResearchOverviewLayout knownLayout;
    private static @Nullable String language;
    private static long revision;

    private ClientResearchContents() {
    }

    public static void configure(Path configRoot) {
        loader = new ResearchContentLoader(configRoot);
        clear();
    }

    public static void clear() {
        NODES.clear();
        DETAILS.clear();
        language = null;
        knownLayout = null;
        revision++;
    }

    public static long revision() {
        ready();
        return revision;
    }

    private static void ready() {
        var minecraft = Minecraft.getMinecraft();
        if (loader == null) loader = new ResearchContentLoader(minecraft.gameDir.toPath().resolve("config"));
        var currentLanguage = minecraft.getLanguageManager().getCurrentLanguage().getLanguageCode();
        var layout = ClientResearchCatalog.layout();
        if (!currentLanguage.equals(language) || layout != knownLayout) {
            NODES.clear();
            DETAILS.clear();
            language = currentLanguage;
            knownLayout = layout;
            revision++;
        }
    }

    public static String name(ResourceLocation id) {
        ready();
        return name(id, knownLayout == null ? null : knownLayout.nodes().get(id));
    }

    public static String name(ResourceLocation id, @Nullable ResearchOverviewLayout.Node node) {
        return display(id, node).title;
    }

    public static @Nullable ResearchIcon icon(ResourceLocation id) {
        ready();
        return icon(id, knownLayout == null ? null : knownLayout.nodes().get(id));
    }

    public static @Nullable ResearchIcon icon(ResourceLocation id, @Nullable ResearchOverviewLayout.Node node) {
        var display = display(id, node);
        if (display.icon == null && !display.iconParsed && node != null && node.icon() != null) {
            display.iconParsed = true;
            try {
                display.icon = ResearchIcons.parse(node.icon());
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.ERROR, "Cannot parse server research icon for " + id, failure);
            }
        }
        return display.icon;
    }

    private static NodeDisplay display(ResourceLocation id, @Nullable ResearchOverviewLayout.Node node) {
        ready();
        var previous = NODES.get(id);
        if (previous != null && Objects.equals(previous.node, node)) return previous;
        String title = node == null ? id.toString() : node.title();
        try {
            title = loader.localizedTitle(id, language, title);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Cannot apply research title localization for " + id, failure);
        }
        var display = new NodeDisplay(node, title);
        NODES.put(id, display);
        return display;
    }

    public static ResearchContent detail(ResourceLocation id, String serverJson) {
        ready();
        var previous = DETAILS.get(id);
        if (previous != null && previous.json.equals(serverJson)) return previous.content;
        ResearchContent content;
        try {
            content = loader.loadLocalized(id, language, serverJson);
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Cannot apply research content localization for " + id, failure);
            var node = knownLayout == null ? null : knownLayout.nodes().get(id);
            content = new ResearchContent(name(id, node), "", ObjectList.of(), node == null ? null : node.icon(), Object2ObjectMaps.emptyMap());
        }
        DETAILS.put(id, new DetailDisplay(serverJson, content));
        return content;
    }

    public static String pageName(String id, @Nullable String title) {
        LocalizationEnum label = () -> "gui.prototype_eng.research_page." + id;
        return title == null ? label.getLocal() : title;
    }

    public static String readLocalized(ResourceLocation id, String language) {
        ready();
        return loader.readContentJson(id, language);
    }

    public static void saveLocalized(ResourceLocation id, String language, String text, String serverJson) {
        ready();
        loader.saveLocalized(id, language, text, serverJson);
        NODES.remove(id);
        DETAILS.remove(id);
        revision++;
    }

    private static final class NodeDisplay {
        private final @Nullable ResearchOverviewLayout.Node node;
        private final String title;
        private @Nullable ResearchIcon icon;
        private boolean iconParsed;

        private NodeDisplay(@Nullable ResearchOverviewLayout.Node node, String title) {
            this.node = node;
            this.title = title;
        }
    }

    private record DetailDisplay(String json, ResearchContent content) {
    }
}
