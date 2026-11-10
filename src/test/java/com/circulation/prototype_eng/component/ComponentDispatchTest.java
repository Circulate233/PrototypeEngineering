package com.circulation.prototype_eng.component;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.TopologicalComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.event.MachineEvent;
import com.circulation.prototype_eng.api.machine.event.MachineTickEvent;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentDispatchTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("migration_test", name);
    }

    private static List<String> values(TopologicalComponentMap<String, String> graph) {
        return graph.orderedComponents().stream().map(TopologicalComponentMap.Node::component).toList();
    }

    @Test
    void preparedInsertionsChargePendingEdgesAndRejectAStaleGroupBeforeAnyWrite() {
        var map = new ComponentMap<MachineInstance>();
        var first = new ComponentType<MachineInstance, String>(id("prepared_first"), _ -> "unused", List.of());
        var second = new ComponentType<MachineInstance, String>(id("prepared_second"), _ -> "unused", List.of());
        var dependency = new ComponentType<MachineInstance, String>(id("prepared_dependency"), _ -> "unused", List.of());
        var removed = new ComponentType<MachineInstance, String>(id("removed_dependency"), _ -> "unused", List.of());
        map.addDependency(second, dependency);
        map.addDependency(second, removed);
        map.removeDependency(second, removed);
        var firstPut = Objects.requireNonNull(map.preparePut(first, "first", new RecipeWorkBudget(1)));
        assertNull(map.preparePut(second, "second", new RecipeWorkBudget(1)));
        assertNull(map.get(first));
        assertNull(map.get(second));
        var secondPut = Objects.requireNonNull(map.preparePut(second, "second", new RecipeWorkBudget(2)));
        map.put(dependency, "dependency");
        var previous = map.orderedBindings();
        assertThrows(IllegalStateException.class, () -> map.installPuts(List.of(firstPut, secondPut)));
        assertNull(map.get(first));
        assertNull(map.get(second));
        assertEquals(List.of("dependency"), previous.stream().map(ComponentMap.SavedBinding::component).toList());
        var currentFirst = Objects.requireNonNull(map.preparePut(first, "first", new RecipeWorkBudget(1)));
        var currentSecond = Objects.requireNonNull(map.preparePut(second, "second", new RecipeWorkBudget(2)));
        map.installPuts(List.of(currentFirst, currentSecond));
        assertNotNull(map.get(first));
        assertNotNull(map.get(second));
        assertEquals(List.of("dependency", "first", "second"), map.orderedBindings().stream().map(ComponentMap.SavedBinding::component).toList());
        assertEquals(Set.of(dependency), map.orderedBindings().getLast().dependencies());
        var canonical = new ComponentType<MachineInstance, String>(id("prepared_id"), _ -> "unused", List.of());
        var counterfeit = new ComponentType<MachineInstance, String>(canonical.id(), _ -> "unused", List.of());
        var canonicalPut = Objects.requireNonNull(map.preparePut(canonical, "canonical", new RecipeWorkBudget(1)));
        var counterfeitPut = Objects.requireNonNull(map.preparePut(counterfeit, "counterfeit", new RecipeWorkBudget(1)));
        assertThrows(IllegalArgumentException.class, () -> map.installPuts(List.of(canonicalPut, counterfeitPut)));
        assertNull(map.get(canonical));
        assertNull(map.get(counterfeit));
        map.put(canonical, "legitimate successor");
        assertEquals("legitimate successor", map.get(canonical));
    }

    @Test
    void declarationValidationFindsTheLiveSystemCycleWithoutCreatingComponents() {
        var log = new ObjectArrayList<String>();
        var first = new RecordingSystem("first", log) {
            @Override
            public Set<ResourceLocation> runAfter() {
                return Set.of(ComponentDispatchTest.id("second"));
            }
        };
        var second = new RecordingSystem("second", log) {
            @Override
            public Set<ResourceLocation> runAfter() {
                return Set.of(ComponentDispatchTest.id("first"));
            }
        };
        var a = new ComponentType<>(id("a"), _ -> {
            throw new AssertionError("Declaration validation must not create components");
        }, List.of(first));
        var b = new ComponentType<>(id("b"), _ -> {
            throw new AssertionError("Declaration validation must not create components");
        }, List.of(second));
        assertThrows(IllegalStateException.class, () -> ComponentMap.validateTypes(List.of(a, b)));
        var machine = new MachineInstance();
        machine.components().put(a, "a");
        machine.components().put(b, "b");
        assertThrows(IllegalStateException.class, machine::onSchedule);
        assertEquals(List.of(), log);
    }

    @Test
    void absentDependenciesStaySoftButTheirIdsCannotNameDifferentDescriptors() {
        var dependency = new ComponentType<MachineInstance, String>(id("optional"), _ -> "unused", List.of());
        var dependent = new ComponentType<>(id("dependent"), _ -> "unused", List.of(), Set.of(dependency));
        ComponentMap.validateTypes(List.of(dependent));
        ComponentMap.validateTypes(List.of(dependent, dependency));
        var counterfeit = new ComponentType<MachineInstance, String>(dependency.id(), _ -> "wrong", List.of());
        assertThrows(IllegalArgumentException.class, () -> ComponentMap.validateTypes(List.of(dependent, counterfeit)));
    }

    @Test
    void softDependenciesSurviveReplacementRemovalAndLateInstallation() {
        var graph = new TopologicalComponentMap<String, String>();
        graph.addBefore("finish", "consume", "consume-1");
        graph.addDependency("consume", "check");
        graph.add("finish", "finish");
        graph.add("check", "check");
        graph.addFirst("prepare", "prepare");
        graph.addTail("release", "release");
        assertEquals(List.of("prepare", "check", "consume-1", "finish", "release"), values(graph));

        var snapshot = graph.orderedComponents();
        graph.add("consume", "consume-2", ObjectSets.singleton("check"));
        graph.remove("check");
        graph.add("check", "check", ObjectSets.singleton("prepare"));
        assertEquals(List.of("prepare", "check", "consume-2", "finish", "release"), values(graph));
        assertEquals("consume-1", snapshot.get(2).component());

        graph.addDependency("prepare", "finish");
        assertThrows(IllegalStateException.class, graph::orderedComponents);
        graph.removeDependency("prepare", "finish");
        assertEquals(List.of("prepare", "check", "consume-2", "finish", "release"), values(graph));
    }

    @Test
    void externalSystemsKeepPhaseOrderAndReplacementTakesEffectOnTheNextTick() {
        var log = new ObjectArrayList<String>();
        var machine = new MachineInstance();
        var scan = new RecordingSystem("scan", log);
        var process = new RecordingSystem("process", log);
        var audit = new RecordingSystem("audit", log) {
            @Override
            public Set<ResourceLocation> runAfter() {
                return ObjectSets.singleton(ComponentDispatchTest.id("process"));
            }
        };
        var processorType = new ComponentType<>(id("processor"), _ -> "old",
            ObjectArrayList.of(scan, process));
        var auditType = new ComponentType<>(id("audit_component"), _ -> "audit",
            ObjectArrayList.of(audit));
        // Deliberately install in reverse order: system constraints must not depend on registration order.
        machine.components().create(auditType, machine);
        machine.components().create(processorType, machine);
        scan.preAction = () -> machine.components().put(processorType, "new");
        machine.onSchedule();
        assertEquals(List.of("pre:scan:old", "pre:process:old", "pre:audit:audit",
            "tick:scan:old", "tick:process:old", "tick:audit:audit",
            "post:scan:old", "post:process:old", "post:audit:audit"), log);
        log.clear();
        scan.preAction = () -> {
        };
        machine.onSchedule();
        assertEquals(List.of("pre:scan:new", "pre:process:new", "pre:audit:audit",
            "tick:scan:new", "tick:process:new", "tick:audit:audit",
            "post:scan:new", "post:process:new", "post:audit:audit"), log);
    }

    @Test
    void callbackFailureStopsRemainingSystemsAndPostPhase() {
        var log = new ObjectArrayList<String>();
        var machine = new MachineInstance();
        var failure = new IllegalStateException("broken external system");
        var broken = new RecordingSystem("broken", log) {
            @Override
            public void onTick(MachineInstance owner, String component) {
                throw failure;
            }
        };
        var later = new RecordingSystem("later", log);
        var type = new ComponentType<>(id("processor"), _ -> "state",
            ObjectArrayList.of(broken, later));
        machine.components().create(type, machine);
        assertSame(failure, assertThrows(IllegalStateException.class, machine::onSchedule));
        assertEquals(List.of("pre:broken:state", "pre:later:state"), log);
    }

    @Test
    void replacingAComponentAlsoRemovesItsObsoleteSystemOrdering() {
        var machine = new MachineInstance();
        var log = new ObjectArrayList<String>();
        var a = new RecordingSystem("a", log) {
            @Override
            public Set<ResourceLocation> runAfter() {
                return ObjectSets.singleton(ComponentDispatchTest.id("b"));
            }
        };
        var b = new RecordingSystem("b", log);
        var typeA = new ComponentType<>(id("a_component"), _ -> "a", ObjectArrayList.of(a));
        var typeB = new ComponentType<>(id("b_component"), _ -> "b", ObjectArrayList.of(b));
        machine.components().create(typeA, machine);
        machine.components().create(typeB, machine);
        machine.onSchedule();
        assertEquals("tick:b:b", log.get(2));
        machine.components().remove(typeB);
        var replacement = new ComponentType<MachineInstance, String>(id("b_component"), _ -> "b2", ObjectLists.emptyList());
        machine.components().create(replacement, machine);
        log.clear();
        machine.onSchedule();
        assertEquals(List.of("pre:a:a", "tick:a:a", "post:a:a"), log);
    }

    private static class RecordingSystem implements MachineSystem<String> {
        private final String name;
        private final List<String> log;
        Runnable preAction = () -> {
        };

        RecordingSystem(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public ResourceLocation id() {
            return ComponentDispatchTest.id(name);
        }

        @Override
        public void onEvent(MachineInstance machine, String component, MachineEvent event) {
            assertSame(machine, event.machine());
            String phase = switch (event) {
                case MachineTickEvent.Pre ignored -> "pre";
                case MachineTickEvent.Normal ignored -> "tick";
                case MachineTickEvent.Post ignored -> "post";
                default -> throw new AssertionError(event);
            };
            log.add(phase + ":" + name + ":" + component);
            if (event instanceof MachineTickEvent.Pre) {
                preAction.run();
            }
        }
    }
}
