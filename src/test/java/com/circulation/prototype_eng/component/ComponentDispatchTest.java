package com.circulation.prototype_eng.component;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.component.TopologicalComponentMap;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.event.MachineEvent;
import com.circulation.prototype_eng.api.machine.event.MachineTickEvent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDispatchTest {
    private static ResourceLocation id(String name) { return new ResourceLocation("migration_test", name); }

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

    private static List<String> values(TopologicalComponentMap<String, String> graph) {
        return graph.orderedComponents().stream().map(TopologicalComponentMap.Node::component).toList();
    }

    @Test
    void externalSystemsKeepPhaseOrderAndReplacementTakesEffectOnTheNextTick() {
        var log = new ObjectArrayList<String>();
        var machine = new MachineInstance();
        var scan = new RecordingSystem("scan", log);
        var process = new RecordingSystem("process", log);
        var audit = new RecordingSystem("audit", log) {
            @Override public Set<ResourceLocation> runAfter() { return ObjectSets.singleton(ComponentDispatchTest.id("process")); }
        };
        var processorType = new ComponentType<MachineInstance, String>(id("processor"), owner -> "old",
                ObjectArrayList.of(scan, process));
        var auditType = new ComponentType<MachineInstance, String>(id("audit_component"), owner -> "audit",
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
        scan.preAction = () -> {};
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
            @Override public void onTick(MachineInstance owner, String component) { throw failure; }
        };
        var later = new RecordingSystem("later", log);
        var type = new ComponentType<MachineInstance, String>(id("processor"), owner -> "state",
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
            @Override public Set<ResourceLocation> runAfter() { return ObjectSets.singleton(ComponentDispatchTest.id("b")); }
        };
        var b = new RecordingSystem("b", log);
        var typeA = new ComponentType<MachineInstance, String>(id("a_component"), owner -> "a", ObjectArrayList.of(a));
        var typeB = new ComponentType<MachineInstance, String>(id("b_component"), owner -> "b", ObjectArrayList.of(b));
        machine.components().create(typeA, machine);
        machine.components().create(typeB, machine);
        machine.onSchedule();
        assertEquals("tick:b:b", log.get(2));
        machine.components().remove(typeB);
        var replacement = new ComponentType<MachineInstance, String>(id("b_component"), owner -> "b2", ObjectLists.emptyList());
        machine.components().create(replacement, machine);
        log.clear();
        machine.onSchedule();
        assertEquals(List.of("pre:a:a", "tick:a:a", "post:a:a"), log);
    }

    private static class RecordingSystem implements MachineSystem<String> {
        private final String name;
        private final List<String> log;
        Runnable preAction = () -> {};

        RecordingSystem(String name, List<String> log) { this.name = name; this.log = log; }
        @Override public ResourceLocation id() { return ComponentDispatchTest.id(name); }

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
            if (event instanceof MachineTickEvent.Pre) { preAction.run(); }
        }
    }
}
