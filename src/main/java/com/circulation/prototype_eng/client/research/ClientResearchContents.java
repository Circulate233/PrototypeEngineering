package com.circulation.prototype_eng.client.research;

import com.circulation.prototype_eng.api.client.research.ResearchIcon;
import com.circulation.prototype_eng.api.client.research.ResearchIcons;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.circulation.prototype_eng.research.content.ResearchContentLoader;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Shared client display cache. Access from the client owner; configuration and language changes retire cached values.
 */
@SideOnly(Side.CLIENT)
public final class ClientResearchContents {
    private static final System.Logger LOGGER = System.getLogger("prototype_eng/research_content");
    private static final Object2ObjectOpenHashMap<ResourceLocation, ResearchContent> CONTENT = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<ResourceLocation, ResearchIcon> ICONS = new Object2ObjectOpenHashMap<>();
    private static final Object2ObjectOpenHashMap<String, ResearchPage> PAGES = new Object2ObjectOpenHashMap<>();
    private static ResearchContentLoader loader;
    private static String language;
    private static long revision;

    private ClientResearchContents() {
    }

    public static void configure(Path configRoot) {
        loader = new ResearchContentLoader(configRoot);
        clear();
    }

    public static void clear() {
        CONTENT.clear();
        ICONS.clear();
        PAGES.clear();
        language = null;
        revision++;
    }

    public static long revision() {
        ready();
        return revision;
    }

    private static void ready() {
        var minecraft = Minecraft.getMinecraft();
        if (loader == null) loader = new ResearchContentLoader(minecraft.gameDir.toPath().resolve("config"));
        var current = minecraft.getLanguageManager().getCurrentLanguage().getLanguageCode();
        if (!current.equals(language)) {
            clear();
            language = current;
        }
    }

    public static ResearchContent get(ResourceLocation id) {
        ready();
        var content = CONTENT.get(id);
        if (content == null) {
            content = loader.load(id, language).content();
            if (content == null)
                content = new ResearchContent(id.toString(), "", ObjectList.of(), null, Object2ObjectMaps.emptyMap());
            CONTENT.put(id, content);
        }
        return content;
    }

    public static String name(ResourceLocation id) {
        return get(id).title();
    }

    public static @Nullable ResearchIcon icon(ResourceLocation id) {
        var content = get(id);
        if (content.icon() == null) return null;
        if (!ICONS.containsKey(id)) {
            try {
                ICONS.put(id, ResearchIcons.parse(content.icon()));
            } catch (RuntimeException failure) {
                LOGGER.log(System.Logger.Level.ERROR, "Research icon load failed at " + loader.sourcePath(language, id), failure);
                ICONS.put(id, null);
            }
        }
        return ICONS.get(id);
    }

    public static ResearchPage page(String namespace) {
        ready();
        return PAGES.computeIfAbsent(namespace, loader::loadPage);
    }

    /**
     * Retains accepted editor moves in client memory; exporting remains an explicit separate action.
     */
    public static void retainPage(String namespace, ResearchPage page) {
        ready();
        PAGES.put(namespace, page);
    }

    public static void exportPage(String namespace, ResearchPage page) {
        ready();
        loader.exportPage(namespace, page);
        PAGES.put(namespace, page);
    }
}
