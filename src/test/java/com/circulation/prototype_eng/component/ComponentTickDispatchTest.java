package com.circulation.prototype_eng.component;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentTickDispatchTest {
    private static ComponentType<Object, String> install(ComponentMap<Object> map, String name, List<String> events) {
        var type = new ComponentType<>(id(name), _ -> name, List.of(new Recorder(name, events)));
        map.put(type, name);
        return type;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("component_dispatch_test", name);
    }

    @Test
    void suspensionKeepsLaterNormalCallbacksAfterDeferredWorkAndRetainsTheCapturedGraph() {
        var map = new ComponentMap<>();
        var events = new ObjectArrayList<String>();
        var scan = install(map, "scan", events);
        var process = install(map, "process", events);
        var after = install(map, "after", events);
        var dispatch = map.captureDispatch(new Object());
        assertTrue(events.isEmpty());
        dispatch.begin();
        assertThrows(IllegalStateException.class, dispatch::finish);
        assertTrue(dispatch.advance());
        assertTrue(dispatch.advance());
        assertEquals(List.of("pre:scan", "pre:process", "pre:after", "tick:scan", "tick:process"), events);

        // A real deferred processor settles at this interval; later components must see its result.
        events.add("source:complete");
        map.remove(process);
        map.put(after, "replacement");
        map.addDependency(scan, after);
        assertTrue(dispatch.advance());
        assertFalse(dispatch.advance());
        dispatch.finish();
        assertEquals(List.of("pre:scan", "pre:process", "pre:after", "tick:scan", "tick:process",
            "source:complete", "tick:after", "post:scan", "post:process", "post:after"), events);

        events.clear();
        map.tick(new Object());
        assertEquals(List.of("pre:replacement", "pre:scan", "tick:replacement", "tick:scan",
            "post:replacement", "post:scan"), events);
    }

    @Test
    void aFaultInAnyPhaseAbortsRemainingCallbacksAndCannotReplayTheFailingEntry() {
        for (String phase : List.of("pre", "tick", "post")) {
            var map = new ComponentMap<>();
            var events = new ObjectArrayList<String>();
            var failure = new AssertionError("callback failed");
            var system = new Recorder("first", events);
            system.action = current -> {
                if (current.equals(phase)) {
                    throw failure;
                }
            };
            map.put(new ComponentType<>(id("first"), _ -> "first", List.of(system)), "first");
            install(map, "second", events);
            var dispatch = map.captureDispatch(new Object());
            Runnable attempt = switch (phase) {
                case "pre" -> dispatch::begin;
                case "tick" -> {
                    dispatch.begin();
                    yield dispatch::advance;
                }
                default -> {
                    dispatch.begin();
                    assertTrue(dispatch.advance());
                    assertTrue(dispatch.advance());
                    yield dispatch::finish;
                }
            };
            assertSame(failure, assertThrows(AssertionError.class, attempt::run));
            var expected = switch (phase) {
                case "pre" -> List.of("pre:first");
                case "tick" -> List.of("pre:first", "pre:second", "tick:first");
                default -> List.of("pre:first", "pre:second", "tick:first", "tick:second", "post:first");
            };
            assertEquals(expected, events);
            assertThrows(IllegalStateException.class, dispatch::begin);
            assertThrows(IllegalStateException.class, dispatch::advance);
            assertThrows(IllegalStateException.class, dispatch::finish);
            assertEquals(expected, events);
        }
    }

    @Test
    void failedDeferredWorkDiscardsLaterNormalAndPostWithoutLosingItsCallerOwnedCleanup() {
        var map = new ComponentMap<>();
        var events = new ObjectArrayList<String>();
        install(map, "process", events);
        install(map, "after", events);
        var dispatch = map.captureDispatch(new Object());
        dispatch.begin();
        assertTrue(dispatch.advance());
        events.add("source:failed");
        dispatch.abort();
        events.add("source:compensated");
        assertThrows(IllegalStateException.class, dispatch::advance);
        assertThrows(IllegalStateException.class, dispatch::finish);
        assertEquals(List.of("pre:process", "pre:after", "tick:process", "source:failed", "source:compensated"), events);
    }

    @Test
    void foreignThreadsAndRecursiveCallbacksCannotConsumeOrDiscardOwnerDispatch() throws Exception {
        var map = new ComponentMap<>();
        var events = new ObjectArrayList<String>();
        var retained = new ComponentMap.TickDispatch<?>[1];
        var system = new Recorder("only", events);
        system.action = _ -> {
            var dispatch = Objects.requireNonNull(retained[0]);
            assertThrows(IllegalStateException.class, dispatch::begin);
            assertThrows(IllegalStateException.class, dispatch::advance);
            assertThrows(IllegalStateException.class, dispatch::finish);
            assertThrows(IllegalStateException.class, dispatch::abort);
        };
        map.put(new ComponentType<>(id("only"), _ -> "only", List.of(system)), "only");
        var dispatch = map.captureDispatch(new Object());
        retained[0] = dispatch;
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, dispatch::begin);
                assertThrows(IllegalStateException.class, dispatch::advance);
                assertThrows(IllegalStateException.class, dispatch::finish);
                assertThrows(IllegalStateException.class, dispatch::abort);
            }).get(5, TimeUnit.SECONDS);
            dispatch.begin();
            assertTrue(dispatch.advance());
            worker.submit(() -> assertThrows(IllegalStateException.class, dispatch::finish)).get(5, TimeUnit.SECONDS);
            dispatch.finish();
        }
        assertEquals(List.of("pre:only", "tick:only", "post:only"), events);
    }

    private static final class Recorder implements ComponentSystem<Object, String> {
        private final ResourceLocation id;
        private final List<String> events;
        private Consumer<String> action = _ -> {
        };

        private Recorder(String name, List<String> events) {
            id = ComponentTickDispatchTest.id(name);
            this.events = events;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public void onPreTick(Object owner, String component) {
            record("pre", component);
        }

        @Override
        public void onTick(Object owner, String component) {
            record("tick", component);
        }

        @Override
        public void onPostTick(Object owner, String component) {
            record("post", component);
        }

        private void record(String phase, String component) {
            events.add(phase + ":" + component);
            action.accept(phase);
        }
    }
}
