package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Objects;

/**
 * Loading-thread registration, followed by one atomic validated publication for runtime readers.
 */
@SuppressWarnings("unused")
public final class ResearchRegistry {
    public static final ResearchRegistry INSTANCE = new ResearchRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ResearchDefinition> registrations = new Object2ObjectLinkedOpenHashMap<>();
    @Nullable
    private volatile Frozen frozen;

    /**
     * Visit stage boundaries rather than every level of a potentially unbounded research.
     */
    private static void validateSymmetricMutex(ResearchDefinition definition, ResearchLevelStage stage,
                                               ResearchDefinition counterpart, ResearchMutexRule rule) {
        if (stage.lastLevel() > counterpart.maxLevel()) {
            throw new IllegalArgumentException("Mutex research lacks matching level coverage: " + counterpart.id());
        }
        int cursor = stage.firstLevel();
        while (true) {
            var otherStage = counterpart.stageForLevel(cursor);
            boolean found = false;
            for (var other : otherStage.mutexRules()) {
                if (other.researchId().equals(definition.id()) && other.minLevel() == rule.minLevel()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Research mutex must be symmetric with the same level: " + definition.id() + " and " + counterpart.id());
            }
            if (otherStage.lastLevel() >= stage.lastLevel()) {
                return;
            }
            cursor = Math.incrementExact(otherStage.lastLevel());
        }
    }

    public ResearchDefinition register(ResearchDefinition definition) {
        if (frozen != null) {
            throw new IllegalStateException("Research registration has ended");
        }
        Objects.requireNonNull(definition);
        if (registrations.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("Duplicate research " + definition.id());
        }
        return definition;
    }

    public boolean isFrozen() {
        return frozen != null;
    }

    public void freeze() {
        if (frozen != null) {
            throw new IllegalStateException("Research registry already frozen");
        }
        for (var definition : registrations.values()) {
            for (var stage : definition.stages()) {
                for (var prerequisite : stage.prerequisites().object2IntEntrySet()) {
                    validateReference(definition, prerequisite.getKey(), prerequisite.getIntValue(), "prerequisite");
                }
                for (var mutex : stage.mutexRules()) {
                    var counterpart = validateReference(definition, mutex.researchId(), mutex.minLevel(), "mutex");
                    validateSymmetricMutex(definition, stage, counterpart, mutex);
                }
            }
        }
        validatePrerequisiteGraph();
        var definitions = new Object2ObjectLinkedOpenHashMap<>(registrations);
        var orderedById = new ObjectArrayList<>(definitions.values());
        orderedById.sort(Comparator.comparing(ResearchDefinition::id));
        frozen = new Frozen(definitions, ObjectLists.unmodifiable(new ObjectArrayList<>(definitions.values())),
            ObjectLists.unmodifiable(orderedById));
        registrations.clear();
    }

    public ResearchDefinition require(ResourceLocation id) {
        var definition = requireFrozen().byId.get(Objects.requireNonNull(id));
        if (definition == null) {
            throw new IllegalArgumentException("Unknown research " + id);
        }
        return definition;
    }

    public ObjectList<ResearchDefinition> all() {
        return requireFrozen().ordered;
    }

    /**
     * Stable GUI order is prepared once at definition freeze, independently of registration order.
     */
    public ObjectList<ResearchDefinition> sortedById() {
        return requireFrozen().orderedById;
    }

    private ResearchDefinition validateReference(ResearchDefinition owner, ResourceLocation id, int level, String kind) {
        if (owner.id().equals(id)) {
            throw new IllegalArgumentException("Research cannot " + kind + " itself: " + id);
        }
        var target = registrations.get(id);
        if (target == null || level > target.maxLevel()) {
            throw new IllegalArgumentException("Missing or out-of-range research " + kind + " " + id + " for " + owner.id());
        }
        return target;
    }

    /**
     * Kahn's traversal avoids recursive stack growth on long research dependency chains.
     */
    private void validatePrerequisiteGraph() {
        var remaining = new Object2IntOpenHashMap<ResourceLocation>();
        var dependants = new Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<ResourceLocation>>();
        var ready = new ObjectArrayList<ResourceLocation>();
        for (var definition : registrations.values()) {
            var prerequisites = new ObjectOpenHashSet<ResourceLocation>();
            for (var stage : definition.stages()) {
                prerequisites.addAll(stage.prerequisites().keySet());
            }
            remaining.put(definition.id(), prerequisites.size());
            if (prerequisites.isEmpty()) {
                ready.add(definition.id());
            }
            for (var prerequisite : prerequisites) {
                dependants.computeIfAbsent(prerequisite, _ -> new ObjectArrayList<>()).add(definition.id());
            }
        }
        int visited = 0;
        while (visited < ready.size()) {
            var id = ready.get(visited++);
            var following = dependants.get(id);
            if (following == null) {
                continue;
            }
            for (var dependant : following) {
                if (remaining.addTo(dependant, -1) == 1) {
                    ready.add(dependant);
                }
            }
        }
        if (visited != registrations.size()) {
            throw new IllegalArgumentException("Research prerequisite graph contains a cycle");
        }
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Research registry is not ready");
        }
        return current;
    }

    private record Frozen(Object2ObjectLinkedOpenHashMap<ResourceLocation, ResearchDefinition> byId,
                          ObjectList<ResearchDefinition> ordered,
                          ObjectList<ResearchDefinition> orderedById) {
    }
}
