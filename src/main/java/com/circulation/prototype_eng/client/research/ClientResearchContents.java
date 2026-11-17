package com.circulation.prototype_eng.client.research;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.client.research.ResearchIcon;
import com.circulation.prototype_eng.api.client.research.ResearchIcons;
import com.circulation.prototype_eng.api.research.content.ResearchContent;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import com.circulation.prototype_eng.research.content.ResearchContentLoader;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

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

    public static List<String> pageIds() {
        ready();
        var ids = loader.pageIds();
        for (int i = 0; i < ids.size(); i++) page(ids.get(i));
        for (var id : PAGES.keySet()) if (!ids.contains(id)) ids.add(id);
        return ids;
    }

    public static String pageName(String id) {
        var page = page(id);
        if (page.title() != null) return page.title();
        LocalizationEnum label = () -> "gui.prototype_eng.research_page." + id;
        return label.getLocal();
    }

    public static List<ResourceLocation> pageResearches(String pageId, List<ResourceLocation> allRegistered) {
        var page = page(pageId);
        var registered = new ObjectOpenHashSet<>(allRegistered);
        var members = new ObjectArrayList<ResourceLocation>();
        if (page.researches() != null) {
            for (int i = 0; i < page.researches().size(); i++) {
                var id = page.researches().get(i);
                if (registered.contains(id)) members.add(id);
            }
        } else {
            var assigned = new ObjectOpenHashSet<ResourceLocation>();
            for (var candidate : PAGES.values())
                if (candidate.researches() != null) assigned.addAll(candidate.researches());
            for (int i = 0; i < allRegistered.size(); i++) {
                var id = allRegistered.get(i);
                if (id.getNamespace().equals(pageId) && !assigned.contains(id)) members.add(id);
            }
        }
        return members;
    }

    public static void createPage(String id, String title) {
        ready();
        var ids = pageIds();
        if (ids.contains(id)) throw new IllegalArgumentException("Research page already exists: " + id);
        int order = 0;
        for (int i = 0; i < ids.size(); i++) order = Math.max(order, page(ids.get(i)).order() + 1);
        var page = new ResearchPage(order, false, title, new ObjectArrayList<>(), new Object2ObjectOpenHashMap<>());
        exportPage(id, page);
        revision++;
    }

    /**
     * Moves a registered research to the selected page; automatic pages determine their own coordinates.
     */
    public static void addResearch(String pageId, ResourceLocation id, int x, int y, List<ResourceLocation> allRegistered) {
        ready();
        if (!allRegistered.contains(id)) throw new IllegalArgumentException("Unregistered research: " + id);
        pageIds();
        var target = page(pageId);
        var members = target.researches() == null ? pageResearches(pageId, allRegistered) : new ObjectArrayList<>(target.researches());
        if (!members.contains(id)) members.add(id);
        if (target.manual()) target.move(id.toString(), x, y);
        exportPage(pageId, new ResearchPage(target.order(), target.manual(), target.title(), members, target.positions()));
        var others = new ObjectArrayList<>(PAGES.keySet());
        for (int i = 0; i < others.size(); i++) {
            var otherId = others.get(i);
            if (otherId.equals(pageId)) continue;
            var other = page(otherId);
            if (other.researches() == null || !other.researches().contains(id)) continue;
            var remaining = new ObjectArrayList<>(other.researches());
            remaining.remove(id);
            other.positions().remove(id.toString());
            exportPage(otherId, new ResearchPage(other.order(), other.manual(), other.title(), remaining, other.positions()));
        }
        revision++;
    }

    public static String readContentJson(ResourceLocation id, @Nullable String language) {
        ready();
        return loader.readContentJson(id, language);
    }

    public static void saveContentJson(ResourceLocation id, @Nullable String language, String text) {
        ready();
        loader.saveContentJson(id, language, text);
        CONTENT.remove(id);
        ICONS.remove(id);
        revision++;
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
