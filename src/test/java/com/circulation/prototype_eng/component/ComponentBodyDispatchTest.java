package com.circulation.prototype_eng.component;

import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentBodyDispatchTest {
    private static final Runnable NO_OP = () -> {
    };

    private static ComponentType<Object, String> install(ComponentMap<Object> map, Object owner, String name,
                                                         List<String> events) {
        return install(map, owner, new RecordingSystem(name, events));
    }

    private static ComponentType<Object, String> install(ComponentMap<Object> map, Object owner, RecordingSystem system) {
        var type = new ComponentType<>(id(system.name), _ -> system.name, List.of(system));
        map.create(type, owner);
        return type;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("component_body_test", name);
    }

    @Test
    void blockedBusinessReturnStillRunsAllPostsAfterOnePreAndTickDispatch() {
        var map = new ComponentMap<>();
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        install(map, owner, "first", events);
        install(map, owner, "second", events);
        var blocked = new ProcessResult.Blocked("waiting");
        ProcessResult[] result = {ProcessResult.SUCCESS};

        map.tick(owner, () -> {
            events.add("body");
            result[0] = blocked;
        });

        assertSame(blocked, result[0]);
        assertEquals(List.of("pre:first:first", "pre:second:second", "tick:first:first", "tick:second:second",
            "body", "post:first:first", "post:second:second"), events);
    }

    @Test
    void bodyReplacementAndInstallationDoNotChangeTheCapturedPostDispatch() {
        var map = new ComponentMap<>();
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var original = install(map, owner, "first", events);

        map.tick(owner, () -> {
            events.add("body:replace");
            map.put(original, "replacement");
            install(map, owner, "second", events);
        });

        assertEquals(List.of("pre:first:first", "tick:first:first", "body:replace", "post:first:first"), events);
        events.clear();
        map.tick(owner, () -> events.add("body:next"));
        assertEquals(List.of("pre:first:replacement", "pre:second:second", "tick:first:replacement", "tick:second:second",
            "body:next", "post:first:replacement", "post:second:second"), events);
    }

    @Test
    void bodyFailureRetainsEveryPostCleanupFaultAndAttemptsLaterPosts() {
        var map = new ComponentMap<>();
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var first = new RecordingSystem("first", events);
        var second = new RecordingSystem("second", events);
        var third = new RecordingSystem("third", events);
        var primary = new AssertionError("business fault");
        var firstCleanup = new IllegalStateException("first post fault");
        var secondCleanup = new IllegalStateException("second post fault");
        first.postAction = () -> {
            throw firstCleanup;
        };
        second.postAction = () -> {
            throw secondCleanup;
        };
        install(map, owner, first);
        install(map, owner, second);
        install(map, owner, third);

        assertSame(primary, assertThrows(AssertionError.class, () -> map.tick(owner, () -> {
            events.add("body");
            throw primary;
        })));
        assertArrayEquals(new Throwable[]{firstCleanup, secondCleanup}, primary.getSuppressed());
        assertEquals(List.of("pre:first:first", "pre:second:second", "pre:third:third",
            "tick:first:first", "tick:second:second", "tick:third:third", "body",
            "post:first:first", "post:second:second", "post:third:third"), events);
    }

    @Test
    void successfulBodyStillAttemptsAllPostsAndAggregatesTheirFailures() {
        var map = new ComponentMap<>();
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var first = new RecordingSystem("first", events);
        var second = new RecordingSystem("second", events);
        var third = new RecordingSystem("third", events);
        var primary = new IllegalStateException("first post fault");
        var cleanup = new IllegalStateException("second post fault");
        first.postAction = () -> {
            throw primary;
        };
        second.postAction = () -> {
            throw cleanup;
        };
        install(map, owner, first);
        install(map, owner, second);
        install(map, owner, third);

        assertSame(primary, assertThrows(IllegalStateException.class, () -> map.tick(owner, () -> events.add("body"))));
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        assertEquals(List.of("pre:first:first", "pre:second:second", "pre:third:third",
            "tick:first:first", "tick:second:second", "tick:third:third", "body",
            "post:first:first", "post:second:second", "post:third:third"), events);
    }

    @Test
    void preOrTickFaultSkipsBusinessAndPostAsInTheOriginalProcessBoundary() {
        for (boolean preFault : new boolean[]{true, false}) {
            var map = new ComponentMap<>();
            var owner = new Object();
            var events = new ObjectArrayList<String>();
            var first = new RecordingSystem("first", events);
            var primary = new IllegalStateException("callback failed before business");
            Runnable fail = () -> {
                throw primary;
            };
            if (preFault) {
                first.preAction = fail;
            } else {
                first.tickAction = fail;
            }
            install(map, owner, first);
            install(map, owner, "second", events);

            assertSame(primary, assertThrows(IllegalStateException.class, () -> map.tick(owner, () -> events.add("body"))));
            assertEquals(preFault ? List.of("pre:first:first")
                : List.of("pre:first:first", "pre:second:second", "tick:first:first"), events);
        }
    }

    @Test
    void plainTickStillStopsImmediatelyWhenAPostCallbackFails() {
        var map = new ComponentMap<>();
        var owner = new Object();
        var events = new ObjectArrayList<String>();
        var first = new RecordingSystem("first", events);
        var primary = new IllegalStateException("post fault");
        first.postAction = () -> {
            throw primary;
        };
        install(map, owner, first);
        install(map, owner, "second", events);

        assertSame(primary, assertThrows(IllegalStateException.class, () -> map.tick(owner)));
        assertEquals(List.of("pre:first:first", "pre:second:second", "tick:first:first", "tick:second:second",
            "post:first:first"), events);
    }

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

        @Override
        public ResourceLocation id() {
            return ComponentBodyDispatchTest.id(name);
        }

        @Override
        public void onPreTick(Object owner, String component) {
            events.add("pre:" + name + ":" + component);
            preAction.run();
        }

        @Override
        public void onTick(Object owner, String component) {
            events.add("tick:" + name + ":" + component);
            tickAction.run();
        }

        @Override
        public void onPostTick(Object owner, String component) {
            events.add("post:" + name + ":" + component);
            postAction.run();
        }
    }
}
