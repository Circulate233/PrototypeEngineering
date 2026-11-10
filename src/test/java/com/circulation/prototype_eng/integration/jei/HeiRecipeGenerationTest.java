package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol fault injection only; this fixture does not claim to run the native HEI registry. */
class HeiRecipeGenerationTest {
    private record Entry(String name, long materialHash) {}

    private static final class Protocol implements HeiRecipeGeneration.Access<Entry> {
        private final ObjectOpenHashSet<Entry> visible = new ObjectOpenHashSet<>();
        private final ObjectOpenHashSet<Entry> focused = new ObjectOpenHashSet<>();
        private final ObjectOpenHashSet<Entry> hidden = new ObjectOpenHashSet<>();
        private final Object2LongOpenHashMap<Entry> ids = new Object2LongOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Entry> tail = new Long2ObjectOpenHashMap<>();
        private final ObjectArrayList<Entry> added = new ObjectArrayList<>();
        private final ObjectArrayList<Entry> immediate = new ObjectArrayList<>();
        private Entry swallowedFailure;

        @Override public void add(Entry entry) {
            added.add(entry);
            // HEI re-add of a hidden wrapper only unhides it; it never repairs a missing tail/index.
            if (hidden.remove(entry)) return;
            visible.add(entry);
            focused.add(entry);
            if (entry == swallowedFailure) return;
            ids.put(entry, entry.materialHash);
            tail.put(entry.materialHash, entry);
        }
        @Override public void hide(Entry entry) { hidden.add(entry); }
        @Override public boolean confirmed(Entry entry, boolean immediateTail) {
            if (immediateTail) immediate.add(entry);
            return visible.contains(entry) && focused.contains(entry) && !hidden.contains(entry)
                && (!immediateTail || tail.get(ids.getLong(entry)) == entry);
        }
    }

    @Test
    void equalNativeHashesAreConfirmedBeforeTheNextLegitimateOverwrite() {
        var first = new Entry("first", 0);
        var second = new Entry("second", 0);
        var protocol = new Protocol();
        assertTrue(HeiRecipeGeneration.install(protocol, List.of(), List.of(first, second)));
        assertEquals(List.of(first, second), protocol.immediate);
        assertSame(second, protocol.tail.get(0));
        assertTrue(protocol.confirmed(first, false));
        assertTrue(protocol.confirmed(second, false));
    }

    @Test
    void swallowedTailFailureCannotBeMaskedByALaterRecipeWithNativeIdZero() {
        var previous = new Entry("previous", 0);
        var broken = new Entry("broken", 0);
        var later = new Entry("later", 0);
        var protocol = new Protocol();
        protocol.add(previous);
        protocol.added.clear();
        protocol.swallowedFailure = broken;
        assertFalse(HeiRecipeGeneration.install(protocol, List.of(previous), List.of(broken, later)));
        assertEquals(List.of(broken), protocol.added);
        assertTrue(protocol.hidden.containsAll(List.of(previous, broken, later)));
        assertFalse(protocol.confirmed(broken, false));
        protocol.add(broken);
        assertFalse(protocol.confirmed(broken, true));
        var fresh = new Entry("fresh retry", 0);
        protocol.swallowedFailure = null;
        assertTrue(HeiRecipeGeneration.install(protocol, List.of(previous), List.of(fresh)));
        assertTrue(protocol.confirmed(fresh, true));
        assertTrue(protocol.hidden.contains(previous));
    }

    @Test
    void transferCallbackReplacementAndAlreadyStaleSimulationNeverProceed() {
        var token = new RecipePoolJeiRecipe.DisplayToken();
        token.activate();
        var result = PatternRecipeTransfer.dispatch(token::current, () -> {
            token.revoke();
            return false;
        });
        assertEquals(PatternRecipeTransfer.EventOutcome.STALE, result);
        var called = new boolean[1];
        assertEquals(PatternRecipeTransfer.EventOutcome.STALE, PatternRecipeTransfer.dispatch(token::current, () -> {
            called[0] = true;
            return false;
        }));
        assertFalse(called[0]);
        var next = new RecipePoolJeiRecipe.DisplayToken();
        next.activate();
        assertEquals(PatternRecipeTransfer.EventOutcome.CANCELLED, PatternRecipeTransfer.dispatch(next::current, () -> true));
        assertEquals(PatternRecipeTransfer.EventOutcome.PROCEED, PatternRecipeTransfer.dispatch(next::current, () -> false));
    }
}
