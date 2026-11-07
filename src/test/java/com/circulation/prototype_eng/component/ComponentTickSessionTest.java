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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentTickSessionTest {
    private static final Runnable NO_OP = () -> {};

    @Test
    void retainedSessionCapturesAllPhasesBeforeCallbacksEditValuesAndDependencyGraph() {
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var map = new ComponentMap<>();
        var first = new RecordingSystem("first", events);
        var second = new RecordingSystem("second", events);
        var firstType = install(map, owner, first);
        var secondType = install(map, owner, second);
        var third = new RecordingSystem("third", events);
        var thirdType = new ComponentType<>(id("third"), _ -> "third", List.of(third));
        first.preAction = () -> map.put(secondType, "replacement");
        first.tickAction = () -> {
            map.create(thirdType, owner);
            map.addDependency(firstType, thirdType);
        };

        var session = map.beginTick(owner);
        assertEquals(List.of("pre:first:first", "pre:second:second", "tick:first:first", "tick:second:second"), events);
        events.add("stage:start");
        map.put(firstType, "later");
        events.add("stage:tick");
        session.finish();
        assertEquals(List.of("pre:first:first", "pre:second:second", "tick:first:first", "tick:second:second",
            "stage:start", "stage:tick", "post:first:first", "post:second:second"), events);

        events.clear();
        first.preAction = NO_OP;
        first.tickAction = NO_OP;
        var next = map.beginTick(owner);
        assertTrue(events.indexOf("pre:third:third") < events.indexOf("pre:first:later"));
        assertTrue(events.contains("tick:second:replacement"));
        assertEquals(6, events.size());
        next.finish();
        assertEquals(9, events.size());
        assertTrue(events.contains("post:first:later"));
        assertTrue(events.contains("post:second:replacement"));
        assertTrue(events.contains("post:third:third"));
    }

    @Test
    void preOrTickFailureNeverCreatesAPostBoundaryAndALaterDispatchCanRetry() {
        for (boolean failingPre : new boolean[]{true, false}) {
            var owner = new Object();
            var events = new ObjectArrayList<String>();
            var map = new ComponentMap<>();
            var first = new RecordingSystem("first", events);
            var second = new RecordingSystem("second", events);
            install(map, owner, first);
            install(map, owner, second);
            var fault = new IllegalStateException("early callback failed");
            Runnable fail = () -> { throw fault; };
            if (failingPre) { first.preAction = fail; }
            else { first.tickAction = fail; }

            assertSame(fault, assertThrows(IllegalStateException.class, () -> map.beginTick(owner)));
            assertEquals(failingPre ? List.of("pre:first:first")
                : List.of("pre:first:first", "pre:second:second", "tick:first:first"), events);
            first.preAction = NO_OP;
            first.tickAction = NO_OP;
            events.clear();
            var session = map.beginTick(owner);
            session.finish();
            assertEquals(List.of("pre:first:first", "pre:second:second", "tick:first:first", "tick:second:second",
                "post:first:first", "post:second:second"), events);
        }
    }

    @Test
    void existingPrimaryRetainsEveryPostFaultAndFinishingCannotReplayCleanup() {
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var map = new ComponentMap<>();
        var primary = new AssertionError("owner business failed");
        var firstFault = new IllegalStateException("first post failed");
        var secondFault = new AssertionError("second post failed");
        var first = new RecordingSystem("first", events);
        var second = new RecordingSystem("second", events);
        var third = new RecordingSystem("third", events);
        first.postAction = () -> { throw firstFault; };
        second.postAction = () -> { throw secondFault; };
        third.postAction = () -> { throw primary; };
        install(map, owner, first);
        install(map, owner, second);
        install(map, owner, third);
        var session = map.beginTick(owner);
        events.clear();
        events.add("business:failed");
        session.finish(primary);
        assertArrayEquals(new Throwable[]{firstFault, secondFault}, primary.getSuppressed());
        assertEquals(List.of("business:failed", "post:first:first", "post:second:second", "post:third:third"), events);
        assertThrows(IllegalStateException.class, session::finish);
        assertEquals(4, events.size());
    }

    @Test
    void absentPrimaryAggregatesPostErrorsAfterLaterCallbacksAndRejectsRetry() {
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var map = new ComponentMap<>();
        var firstFault = new IllegalStateException("first post failed");
        var secondFault = new AssertionError("second post failed");
        var first = new RecordingSystem("first", events);
        var second = new RecordingSystem("second", events);
        first.postAction = () -> { throw firstFault; };
        second.postAction = () -> { throw secondFault; };
        install(map, owner, first);
        install(map, owner, second);
        install(map, owner, new RecordingSystem("third", events));
        var session = map.beginTick(owner);
        events.clear();
        assertSame(firstFault, assertThrows(IllegalStateException.class, session::finish));
        assertArrayEquals(new Throwable[]{secondFault}, firstFault.getSuppressed());
        assertEquals(List.of("post:first:first", "post:second:second", "post:third:third"), events);
        assertThrows(IllegalStateException.class, () -> session.finish(firstFault));
        assertEquals(3, events.size());
    }

    @Test
    void postCallbackReentryIsRejectedWithoutSkippingLaterCapturedPosts() {
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var map = new ComponentMap<>();
        var first = new RecordingSystem("first", events);
        ComponentMap.TickSession<?>[] retained = {null};
        first.postAction = () -> Objects.requireNonNull(retained[0]).finish();
        install(map, owner, first);
        install(map, owner, new RecordingSystem("second", events));
        retained[0] = map.beginTick(owner);
        events.clear();
        assertThrows(IllegalStateException.class, () -> retained[0].finish());
        assertEquals(List.of("post:first:first", "post:second:second"), events);
        assertThrows(IllegalStateException.class, () -> retained[0].finish());
        assertEquals(2, events.size());
    }

    @Test
    void crossThreadFinishDoesNotConsumeTheOwnerPostBoundary() throws Exception {
        var owner = new Object();
        var creator = Thread.currentThread();
        var events = new ObjectArrayList<String>();
        var map = new ComponentMap<>();
        var first = new RecordingSystem("first", events);
        first.postAction = () -> assertSame(creator, Thread.currentThread());
        install(map, owner, first);
        var session = map.beginTick(owner);
        events.add("owner:pending");
        @SuppressWarnings("resource")
        var pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            pool.submit(() -> assertThrows(IllegalStateException.class, session::finish)).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("pre:first:first", "tick:first:first", "owner:pending"), events);
            session.finish();
            assertEquals(List.of("pre:first:first", "tick:first:first", "owner:pending", "post:first:first"), events);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ComponentType<Object, String> install(ComponentMap<Object> map, Object owner, RecordingSystem system) {
        var type = new ComponentType<>(id(system.name), _ -> system.name, List.of(system));
        map.create(type, owner);
        return type;
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("component_session_test", name); }

    private static final class RecordingSystem implements ComponentSystem<Object, String> {
        private final String name;
        private final List<String> events;
        private Runnable preAction = NO_OP;
        private Runnable tickAction = NO_OP;
        private Runnable postAction = NO_OP;

        private RecordingSystem(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override public ResourceLocation id() { return ComponentTickSessionTest.id(name); }
        @Override public void onPreTick(Object owner, String component) {
            events.add("pre:" + name + ":" + component);
            preAction.run();
        }
        @Override public void onTick(Object owner, String component) {
            events.add("tick:" + name + ":" + component);
            tickAction.run();
        }
        @Override public void onPostTick(Object owner, String component) {
            events.add("post:" + name + ":" + component);
            postAction.run();
        }
    }
}
