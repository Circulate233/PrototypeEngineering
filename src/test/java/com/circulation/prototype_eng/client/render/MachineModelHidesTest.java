package com.circulation.prototype_eng.client.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineModelHidesTest {
    @Test
    void staleTickAttemptsEveryOwnedRemovalAndPreservesTheFirstNativeError() {
        var error = new AssertionError("native removal");
        var later = new IllegalStateException("later removal");
        var hides = new Hides() {
            @Override
            protected void remove(BlockPos controller) {
                super.remove(controller);
                if (controller.equals(BlockPos.ORIGIN)) throw error;
                throw later;
            }
        };
        var first = new AtomicBoolean(true);
        var second = new AtomicBoolean(true);
        try (hides) {
            assertNotNull(hides.acquire(new Object(), new Object(), BlockPos.ORIGIN, List.of(BlockPos.ORIGIN), first::get));
            var other = new BlockPos(2, 0, 0);
            assertNotNull(hides.acquire(new Object(), new Object(), other, List.of(other), second::get));
            first.set(false);
            second.set(false);
            var actual = assertThrows(Throwable.class, hides::tick);
            assertTrue(actual == error || actual == later);
            assertEquals(1, actual.getSuppressed().length);
            assertTrue(actual.getSuppressed()[0] == error || actual.getSuppressed()[0] == later);
            assertEquals(2, hides.removals);
            assertTrue(hides.nativeEntries.isEmpty());
        }
    }

    @Test
    void withdrawingOneOfTwoOverlappingMachinesKeepsTheOtherNativeRegistration() {
        var world = new Object();
        var first = new Object();
        var second = new Object();
        var overlap = new BlockPos(4, 0, 0);
        try (var hides = new Hides()) {
            var one = hides.acquire(world, first, BlockPos.ORIGIN, List.of(BlockPos.ORIGIN, overlap), () -> true);
            var two = hides.acquire(world, second, new BlockPos(8, 0, 0), List.of(overlap), () -> true);
            assertNotNull(one);
            assertNotNull(two);
            one.close();
            assertEquals(Set.of(overlap), hides.hidden);
            assertTrue(two.current(world, second));
            two.close();
            assertTrue(hides.hidden.isEmpty());
        }
    }

    @Test
    void sameControllerReplacementAndOldCloseCannotRemoveTheNewCollectionOrAForeignRegistration() {
        var world = new Object();
        var tile = new Object();
        try (var hides = new Hides()) {
            var old = hides.acquire(world, tile, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0)), () -> true);
            var next = hides.acquire(world, tile, BlockPos.ORIGIN, List.of(new BlockPos(2, 0, 0)), () -> true);
            assertNotNull(old);
            assertNotNull(next);
            old.close();
            assertTrue(next.current(world, tile));
            var foreign = List.of(new BlockPos(9, 0, 0));
            hides.nativeEntries.put(BlockPos.ORIGIN, foreign);
            next.close();
            assertSame(foreign, hides.nativeEntries.get(BlockPos.ORIGIN));
            assertNull(hides.acquire(world, tile, BlockPos.ORIGIN, List.of(new BlockPos(3, 0, 0)), () -> true));
            hides.close();
            assertSame(foreign, hides.nativeEntries.get(BlockPos.ORIGIN));
        }
    }

    @Test
    void nativeCallbackRevocationAndWorldUnloadReleaseOnlyTheExactOwnedWorld() {
        var current = new AtomicBoolean(true);
        var firstWorld = new Object();
        var secondWorld = new Object();
        try (var hides = new Hides()) {
            hides.afterAdd = () -> current.set(false);
            assertNull(hides.acquire(firstWorld, new Object(), BlockPos.ORIGIN, List.of(BlockPos.ORIGIN), current::get));
            assertTrue(hides.nativeEntries.isEmpty());
            hides.afterAdd = () -> {
            };
            var one = hides.acquire(firstWorld, new Object(), BlockPos.ORIGIN, List.of(BlockPos.ORIGIN), () -> true);
            var two = hides.acquire(secondWorld, new Object(), new BlockPos(2, 0, 0), List.of(new BlockPos(2, 0, 0)), () -> true);
            assertNotNull(one);
            assertNotNull(two);
            hides.unload(firstWorld);
            assertFalse(hides.nativeEntries.containsKey(BlockPos.ORIGIN));
            assertTrue(hides.nativeEntries.containsKey(new BlockPos(2, 0, 0)));
        }
    }

    @Test
    void partialNativeRegistrationFailureClosesItsCollectionAndKeepsTheOriginalError() {
        var original = new AssertionError("native registration");
        try (var hides = new Hides()) {
            hides.afterAdd = () -> {
                throw original;
            };
            assertSame(original, assertThrows(AssertionError.class, () -> hides.acquire(new Object(), new Object(), BlockPos.ORIGIN, List.of(BlockPos.ORIGIN), () -> true)));
            assertTrue(hides.nativeEntries.isEmpty());
            assertEquals(1, hides.removals);
        }
    }

    /**
     * Exercises the actual adapter; only the dependency's native chunk-update calls are replaced.
     */
    private static class Hides extends MachineModelHides {
        final Map<BlockPos, Collection<BlockPos>> nativeEntries = new Object2ObjectOpenHashMap<>();
        final Set<BlockPos> hidden = new ObjectLinkedOpenHashSet<>();
        int removals;
        Runnable afterAdd = () -> {
        };

        @Override
        protected boolean enabled() {
            return true;
        }

        @Override
        protected Map<BlockPos, Collection<BlockPos>> entries() {
            return nativeEntries;
        }

        @Override
        protected void add(BlockPos controller, Collection<BlockPos> positions) {
            nativeEntries.put(controller, positions);
            hidden.addAll(positions);
            afterAdd.run();
        }

        @Override
        protected void remove(BlockPos controller) {
            removals++;
            nativeEntries.remove(controller);
            hidden.clear();
            nativeEntries.values().forEach(hidden::addAll);
        }
    }
}
