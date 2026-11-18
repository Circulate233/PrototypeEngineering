package com.circulation.prototype_eng.research.content;

import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.content.ResearchPage;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read and edited on the server thread.
 */
public final class ServerResearchCatalog {
    private final ResearchContentLoader loader;
    private final ResearchRegistry registry;
    private final List<ResourceLocation> registered;
    private final Set<ResourceLocation> registeredIds;
    private final List<VisibilityRule> dependencyOrder;
    private Object2ObjectOpenHashMap<ResourceLocation, ResearchContentLoader.BaseData> contents;
    private Object2ObjectLinkedOpenHashMap<String, ResearchPage> configuredPages;
    private List<ResearchOverviewLayout.Page> pages;
    private long revision;

    public ServerResearchCatalog(Path configRoot, ResearchRegistry registry) {
        this.loader = new ResearchContentLoader(configRoot);
        this.registry = registry;
        var definitions = registry.sortedById();
        var ids = new ObjectArrayList<ResourceLocation>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) ids.add(definitions.get(i).id());
        registered = List.copyOf(ids);
        registeredIds = Set.copyOf(ids);
        dependencyOrder = dependencyOrder(definitions);
        reload();
    }

    public long revision() {
        return revision;
    }

    public Set<ResourceLocation> registeredIds() {
        return registeredIds;
    }

    public void reload() {
        var next = new Object2ObjectOpenHashMap<ResourceLocation, ResearchContentLoader.BaseData>(registered.size());
        for (int i = 0; i < registered.size(); i++) {
            var id = registered.get(i);
            next.put(id, loader.loadBase(id, registry.require(id).visibility()));
        }
        var nextPages = new Object2ObjectLinkedOpenHashMap<String, ResearchPage>();
        for (var pageId : loader.pageIds()) nextPages.put(pageId, loader.loadPage(pageId));
        var nextResolved = resolvePages(nextPages);
        contents = next;
        configuredPages = nextPages;
        pages = nextResolved;
        revision++;
    }

    public Set<ResourceLocation> visible(Object2IntMap<ResourceLocation> levels) {
        var visible = new ObjectOpenHashSet<ResourceLocation>();
        for (int i = 0; i < dependencyOrder.size(); i++) {
            var rule = dependencyOrder.get(i);
            var id = rule.id();
            boolean unlocked = switch (contents.get(id).visibility()) {
                case ALWAYS -> true;
                case AFTER_PREREQUISITES -> completedPrerequisites(rule.required(), levels);
                case AFTER_COMPLETION -> levels.getInt(id) > 0;
            };
            if (unlocked && visible.containsAll(rule.required().keySet())) visible.add(id);
        }
        return visible;
    }

    public ResearchOverviewLayout layout(Set<ResourceLocation> visible, boolean editor) {
        var filtered = new ObjectArrayList<ResearchOverviewLayout.Page>();
        var nodes = new Object2ObjectOpenHashMap<ResourceLocation, ResearchOverviewLayout.Node>();
        for (int i = 0; i < pages.size(); i++) {
            var configured = pages.get(i);
            var page = configured.page();
            var members = new ObjectArrayList<ResourceLocation>();
            var positions = new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
            for (var id : Objects.requireNonNull(page.researches())) {
                if (!editor && !visible.contains(id)) continue;
                members.add(id);
                var point = page.positions().get(id.toString());
                if (point != null) positions.put(id.toString(), point);
                nodes.put(id, contents.get(id).node());
            }
            if (!members.isEmpty() || editor && configuredPages.containsKey(configured.id())) {
                var value = new ResearchPage(page.order(), page.manual(), page.title(), List.copyOf(members), positions);
                filtered.add(new ResearchOverviewLayout.Page(configured.id(), value));
            }
        }
        return new ResearchOverviewLayout(List.copyOf(filtered), Map.copyOf(nodes));
    }

    public String detailJson(ResourceLocation id) {
        registry.require(id);
        return contents.get(id).json();
    }

    public String editableJson(ResourceLocation id) {
        return ResearchContentLoader.formatJson(detailJson(id));
    }

    public void saveData(ResourceLocation id, String text) {
        var definition = registry.require(id);
        var replacement = loader.saveBase(id, text, definition.visibility());
        contents.put(id, replacement);
        revision++;
    }

    public void createPage(String id, @Nullable String title) {
        if (findPage(id) != null) throw new IllegalArgumentException("Research page already exists: " + id);
        int priority = 0;
        for (int i = 0; i < pages.size(); i++) {
            var current = pages.get(i).page().order();
            priority = Math.max(priority, current == Integer.MAX_VALUE ? current : current + 1);
        }
        var page = new ResearchPage(priority, false, title, List.of(), new Object2ObjectOpenHashMap<>());
        var updated = new Object2ObjectLinkedOpenHashMap<>(configuredPages);
        updated.put(id, page);
        updatePages(updated, List.of(id));
    }

    public void addResearch(String pageId, ResourceLocation id, int x, int y, boolean manual,
                            Map<String, ResearchPage.Position> automaticPositions) {
        registry.require(id);
        var target = requirePage(pageId);
        var configured = configuredPages.get(pageId);
        var configuredMembers = configured == null ? null : configured.researches();
        var sourceMembers = configuredMembers == null ? Objects.requireNonNull(target.researches()) : configuredMembers;
        var members = new ObjectArrayList<>(sourceMembers);
        if (!members.contains(id)) members.add(id);
        var positions = manual ? new Object2ObjectOpenHashMap<>(target.positions()) : new Object2ObjectOpenHashMap<String, ResearchPage.Position>();
        if (manual && !target.manual()) {
            var existing = Objects.requireNonNull(target.researches());
            if (automaticPositions.size() != existing.size())
                throw new IllegalArgumentException("Automatic layout must include every research on page " + pageId);
            for (var member : existing) {
                var key = member.toString();
                var position = automaticPositions.get(key);
                if (position == null)
                    throw new IllegalArgumentException("Missing automatic layout position for " + member);
                positions.put(key, position);
            }
        } else if (!automaticPositions.isEmpty()) {
            throw new IllegalArgumentException("Automatic layout positions are only accepted when changing to manual layout");
        }
        if (manual) positions.put(id.toString(), new ResearchPage.Position(x, y));

        var updated = new Object2ObjectLinkedOpenHashMap<>(configuredPages);
        var changed = new ObjectArrayList<String>();
        updated.put(pageId, new ResearchPage(target.order(), manual, target.title(), members, positions));
        changed.add(pageId);
        for (var entry : configuredPages.object2ObjectEntrySet()) {
            var otherId = entry.getKey();
            var other = entry.getValue();
            if (otherId.equals(pageId) || other.researches() == null || !other.researches().contains(id)) continue;
            var remaining = new ObjectArrayList<>(other.researches());
            remaining.remove(id);
            var otherPositions = new Object2ObjectOpenHashMap<>(other.positions());
            otherPositions.remove(id.toString());
            updated.put(otherId, new ResearchPage(other.order(), other.manual(), other.title(), remaining, otherPositions));
            changed.add(otherId);
        }
        updatePages(updated, changed);
    }

    public void savePages(Map<String, ResearchPage> layouts) {
        var expected = new ObjectOpenHashSet<String>();
        for (var page : pages)
            if (!Objects.requireNonNull(page.page().researches()).isEmpty() || configuredPages.containsKey(page.id()))
                expected.add(page.id());
        if (!expected.equals(layouts.keySet()))
            throw new IllegalArgumentException("Export must include every research page");
        var updated = new Object2ObjectLinkedOpenHashMap<>(configuredPages);
        for (var entry : layouts.entrySet())
            updated.put(entry.getKey(), pageWithLayout(entry.getKey(), entry.getValue().manual(), entry.getValue().positions()));
        var changed = new ObjectArrayList<>(expected);
        changed.sort(String::compareTo);
        updatePages(updated, changed);
    }

    private ResearchPage pageWithLayout(String pageId, boolean manual, Map<String, ResearchPage.Position> positions) {
        var previous = requirePage(pageId);
        var configured = configuredPages.get(pageId);
        var members = configured == null ? null : configured.researches();
        var updatedPositions = new Object2ObjectOpenHashMap<>(previous.positions());
        if (!manual) updatedPositions.clear();
        else {
            var allowed = new ObjectOpenHashSet<>(Objects.requireNonNull(previous.researches()));
            for (var entry : positions.entrySet()) {
                if (!allowed.contains(new ResourceLocation(entry.getKey())))
                    throw new IllegalArgumentException("Research is not on page " + pageId + ": " + entry.getKey());
                updatedPositions.put(entry.getKey(), entry.getValue());
            }
        }
        return new ResearchPage(previous.order(), manual, previous.title(), members, updatedPositions);
    }

    private void updatePages(Object2ObjectLinkedOpenHashMap<String, ResearchPage> updated, List<String> changed) {
        var resolved = resolvePages(updated);
        loader.exportPages(updated, configuredPages, changed);
        configuredPages = updated;
        pages = resolved;
        revision++;
    }

    private @Nullable ResearchPage findPage(String pageId) {
        for (int i = 0; i < pages.size(); i++) {
            var entry = pages.get(i);
            if (entry.id().equals(pageId)) return entry.page();
        }
        return null;
    }

    private ResearchPage requirePage(String pageId) {
        var page = findPage(pageId);
        if (page == null) throw new IllegalArgumentException("Unknown research page " + pageId);
        return page;
    }

    private List<ResearchOverviewLayout.Page> resolvePages(Map<String, ResearchPage> configured) {
        var ids = new ObjectLinkedOpenHashSet<>(configured.keySet());
        var automatic = new Object2ObjectOpenHashMap<String, ObjectArrayList<ResourceLocation>>();
        var assigned = assignedResearches(configured);
        for (int i = 0; i < registered.size(); i++) {
            var id = registered.get(i);
            String namespace = id.getNamespace();
            ids.add(namespace);
            var page = configured.get(namespace);
            if (!assigned.contains(id) && (page == null || page.researches() == null)) {
                automatic.computeIfAbsent(namespace, _ -> new ObjectArrayList<>()).add(id);
            }
        }
        var result = new ObjectArrayList<ResearchOverviewLayout.Page>();
        for (var id : ids) {
            var config = configured.get(id);
            List<ResourceLocation> members;
            if (config != null && config.researches() != null) {
                var explicit = new ObjectArrayList<ResourceLocation>();
                for (var member : config.researches()) if (registeredIds.contains(member)) explicit.add(member);
                members = explicit;
            } else {
                var namespaced = automatic.get(id);
                members = namespaced == null ? List.of() : namespaced;
            }
            var page = config == null ? new ResearchPage(0, false, null, members, new Object2ObjectOpenHashMap<>())
                : new ResearchPage(config.order(), config.manual(), config.title(), members, config.positions());
            result.add(new ResearchOverviewLayout.Page(id, page));
        }
        result.sort(Comparator.comparingInt((ResearchOverviewLayout.Page row) -> row.page().order()).thenComparing(ResearchOverviewLayout.Page::id));
        return List.copyOf(result);
    }

    private Set<ResourceLocation> assignedResearches(Map<String, ResearchPage> configured) {
        var assigned = new Object2ObjectOpenHashMap<ResourceLocation, String>();
        for (var entry : configured.entrySet()) {
            var explicit = entry.getValue().researches();
            if (explicit == null) continue;
            for (var id : explicit) {
                if (registeredIds.contains(id)) {
                    var previous = assigned.putIfAbsent(id, entry.getKey());
                    if (previous != null)
                        throw new IllegalArgumentException("Research " + id + " occurs on pages " + previous + " and " + entry.getKey());
                }
            }
        }
        return assigned.keySet();
    }

    private static boolean completedPrerequisites(Object2IntMap<ResourceLocation> prerequisites, Object2IntMap<ResourceLocation> levels) {
        for (var required : prerequisites.object2IntEntrySet()) {
            if (levels.getInt(required.getKey()) < required.getIntValue()) return false;
        }
        return true;
    }

    private static List<VisibilityRule> dependencyOrder(List<ResearchDefinition> definitions) {
        var following = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<VisibilityRule>>();
        var pending = new Object2IntOpenHashMap<ResourceLocation>();
        var ordered = new ObjectArrayList<VisibilityRule>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i);
            var required = new Object2IntOpenHashMap<ResourceLocation>();
            for (var stage : definition.stages()) {
                for (var prerequisite : stage.prerequisites().object2IntEntrySet()) {
                    var id = prerequisite.getKey();
                    required.put(id, Math.max(required.getInt(id), prerequisite.getIntValue()));
                }
            }
            var rule = new VisibilityRule(definition.id(), required);
            pending.put(rule.id(), required.size());
            if (required.isEmpty()) ordered.add(rule);
            for (var id : required.keySet()) following.computeIfAbsent(id, _ -> new ObjectArrayList<>()).add(rule);
        }
        for (int i = 0; i < ordered.size(); i++) {
            var dependants = following.get(ordered.get(i).id());
            if (dependants == null) continue;
            for (int j = 0; j < dependants.size(); j++) {
                var dependant = dependants.get(j);
                if (pending.addTo(dependant.id(), -1) == 1) ordered.add(dependant);
            }
        }
        if (ordered.size() != definitions.size())
            throw new IllegalStateException("Research visibility prerequisite graph contains a cycle");
        return List.copyOf(ordered);
    }

    private record VisibilityRule(ResourceLocation id, Object2IntMap<ResourceLocation> required) {
    }
}
