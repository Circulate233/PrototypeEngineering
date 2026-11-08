package com.circulation.prototype_eng.client.preview;

import net.minecraft.inventory.Slot;
import net.minecraft.inventory.InventoryBasic;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the observed native post/close/assignment/InitPre/binding order without FML or Minecraft bootstrap. */
class PreviewScreenNavigationTest {
    private static final class NativeSequence {
        final Object parent = new Object(), viewer = new Object(), foreign = new Object();
        final Object originalPlayer = new Object(), originalWorld = new Object(), originalConnection = new Object();
        final Object originalContainer = new Object(), originalCatalog = new Object();
        final Object heldCursor = new Object();
        Object cursor = heldCursor;
        PreviewScreenGuard.State state = new PreviewScreenGuard.State(parent, originalPlayer, originalWorld,
            originalConnection, originalContainer, 17, originalCatalog, true);
        PreviewScreenGuard.Retired retired;
        PreviewScreenGuard.Retired cleanup;
        int openPosts, prePosts, postPosts, parentGuiClosed, containerClosed, dropped, bindings, releases, returnedHooks, focusChanges;
        int windowClicks, closePackets, wirelessPackets;
        boolean released;
        Runnable afterOldClose = () -> {}, beforeContainerClose = () -> {}, beforeParentSuper = () -> {}, afterInitPost = () -> {};
        void screen(Object screen) { state = new PreviewScreenGuard.State(screen, state.player(), state.world(), state.connection(), state.container(), state.window(), state.catalog(), state.alive()); }
        void owner(Object player, Object world, Object connection, Object container, int window, Object catalog, boolean alive) {
            state = new PreviewScreenGuard.State(state.screen(), player, world, connection, container, window, catalog, alive);
        }
        PreviewScreenGuard.State guardedState(boolean entering) {
            return entering ? state : new PreviewScreenGuard.State(state.screen(), state.player(), state.world(), state.connection(), state.container(), state.window(), null, state.alive());
        }
        PreviewScreenGuard guard(Object target) { return new PreviewScreenGuard(guardedState(target == viewer), target, () -> guardedState(target == viewer)); }
        void release() { if (!released) { released = true; releases++; } }
        void close(Object old, Object closingContainer, PreviewScreenGuard guard, boolean entering) {
            if (retired != null && retired.alreadyClosed(old)) return;
            if (old == viewer) { release(); return; }
            if (old == parent) parentGuiClosed++;
            var ownCleanup = cleanup;
            beforeContainerClose.run();
            if (entering && guard.suspends(old, closingContainer) || retired != null && retired.suspends(old, closingContainer)
                || ownCleanup != null && ownCleanup.suspends(old, closingContainer)) return;
            containerClosed++;
            if (cursor != null) { dropped++; cursor = null; }
        }
        boolean display(PreviewScreenGuard guard, Object requested, Object finalTarget, boolean entering,
                        boolean cancelOpen, Runnable openCallback, boolean cancelPre, Runnable preCallback) {
            Object old = state.screen();
            cleanup = requested == null && retired != null && retired.blocks(old) ? retired : null;
            if (!guard.requested(requested) || !guard.current()) guard.revoke();
            openPosts++;
            openCallback.run();
            if (guard.afterPost(cancelOpen, finalTarget)) {
                if (old != null && guard.sourceRetired()) retired = new PreviewScreenGuard.Retired(old, originalContainer);
                if (entering) release(); return false;
            }
            close(old, originalContainer, guard, entering);
            afterOldClose.run();
            if (!guard.current()) { // Exact original Event local identifies the outer before-store fence.
                if (state.screen() == old) retired = new PreviewScreenGuard.Retired(old, originalContainer, true);
                if (entering) release(); return false;
            }
            focusChanges++;
            screen(finalTarget);
            if (finalTarget == null) return guard.atDisplayed() && guard.displayedRequested(); // Native null/world focus has no InitPre/Post.
            prePosts++;
            preCallback.run();
            boolean ownInit = (requested == viewer || requested == parent) && guard.requested(finalTarget);
            boolean canceled = cancelPre || retired != null && retired.blocks(finalTarget) || ownInit && guard.afterInitPre(cancelPre);
            if (ownInit && !guard.atDisplayed() && state.screen() == finalTarget) retired = new PreviewScreenGuard.Retired(finalTarget, originalContainer);
            if (!canceled && finalTarget == parent) {
                beforeParentSuper.run();
                if (guard.atDisplayed()) { bindings++; owner(state.player(), state.world(), state.connection(), originalContainer, 17, state.catalog(), state.alive()); }
                else if (state.screen() == parent) retired = new PreviewScreenGuard.Retired(parent, originalContainer);
            }
            // Viewer field-store is skipped at its exact native callsite. Ordinary InitPost still runs.
            postPosts++;
            afterInitPost.run();
            if (ownInit && guard.displayedOwnerRetired()) retired = new PreviewScreenGuard.Retired(finalTarget, originalContainer);
            if (entering && (!guard.atDisplayed() || !guard.displayedRequested())) release();
            if (!entering && finalTarget == parent && guard.atDisplayed() && guard.displayedRequested()) returnedHooks++;
            return guard.atDisplayed() && guard.displayedRequested();
        }
        boolean enter(boolean cancelOpen, Runnable openCallback, boolean cancelPre, Runnable preCallback) {
            try { return display(guard(viewer), viewer, viewer, true, cancelOpen, openCallback, cancelPre, preCallback); }
            catch (RuntimeException | Error failure) { release(); throw failure; }
        }
        boolean back(boolean cancelOpen, Runnable preCallback) {
            return display(guard(parent), parent, parent, false, cancelOpen, () -> {}, false, preCallback);
        }
        void resizeRetired(Runnable callback) {
            Object target = state.screen();
            prePosts++;
            callback.run();
            if (retired == null || !retired.blocks(target)) { bindings++; owner(state.player(), state.world(), state.connection(), originalContainer, 17, state.catalog(), state.alive()); }
            postPosts++;
        }
        void parentInput() {
            if (state.screen() != parent || retired != null && retired.blocks(parent)) return;
            windowClicks++; closePackets++; wirelessPackets++;
        }
        void assertHeldAndQuiet() {
            assertSame(heldCursor, cursor);
            assertEquals(0, containerClosed); assertEquals(0, dropped);
            assertEquals(0, windowClicks); assertEquals(0, closePackets); assertEquals(0, wirelessPackets);
        }
    }
    @Test void enterAndReturnExecuteGuiCallbacksWithoutCursorDropOrWindowRebindingInViewer() {
        var n = new NativeSequence();
        assertTrue(n.enter(false, () -> {}, false, () -> {}));
        assertSame(n.viewer, n.state.screen()); assertSame(n.originalContainer, n.state.container());
        assertEquals(1, n.parentGuiClosed); assertEquals(0, n.bindings);
        assertTrue(n.back(false, () -> {}));
        assertSame(n.parent, n.state.screen()); assertEquals(1, n.bindings); assertEquals(1, n.returnedHooks);
        assertEquals(1, n.releases); assertEquals(2, n.postPosts); n.assertHeldAndQuiet();
    }
    @Test void canceledEnterHasNoCloseOrInitAndReleasesPreparedResourcesOnce() {
        var n = new NativeSequence();
        assertFalse(n.enter(true, () -> {}, false, () -> {}));
        n.release();
        assertSame(n.parent, n.state.screen()); assertEquals(0, n.parentGuiClosed); assertEquals(0, n.prePosts); assertEquals(1, n.releases);
        n.assertHeldAndQuiet();
    }
    @Test void canceledInitPreStillDisplaysPreparedViewerAndPostsOrdinaryInitPost() {
        var n = new NativeSequence();
        assertTrue(n.enter(false, () -> {}, true, () -> {}));
        assertSame(n.viewer, n.state.screen()); assertEquals(1, n.postPosts); assertEquals(0, n.bindings); assertEquals(0, n.releases);
        assertTrue(n.back(false, () -> {})); n.assertHeldAndQuiet();
    }
    @Test void replacementTargetPreservesActualEventTargetAndRetiresUnusedViewer() {
        var n = new NativeSequence(); var guard = n.guard(n.viewer);
        assertFalse(n.display(guard, n.viewer, n.foreign, true, false, () -> {}, false, () -> {}));
        assertSame(n.foreign, n.state.screen()); assertEquals(1, n.releases); assertEquals(1, n.parentGuiClosed); n.assertHeldAndQuiet();
    }
    @Test void reentrantForeignCloseInsideOpenPostCannotDropNewPlayerCursorOrBeOverwritten() {
        var n = new NativeSequence(); var guard = n.guard(n.viewer); var newerPlayer = new Object(); var newerContainer = new Object();
        assertFalse(n.display(guard, n.viewer, n.viewer, true, false, () -> {
            n.owner(newerPlayer, n.originalWorld, n.originalConnection, newerContainer, 29, n.originalCatalog, true);
            n.close(n.parent, n.originalContainer, guard, true); // Runs before the outer EventBus.post returns.
            n.screen(n.foreign);
        }, false, () -> {}));
        assertSame(n.foreign, n.state.screen()); assertSame(newerPlayer, n.state.player()); assertSame(newerContainer, n.state.container());
        assertEquals(0, n.prePosts); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void eachOwnerRevocationInOpenPostStopsBeforeOldCloseOrInitialization() {
        List<Consumer<NativeSequence>> changes = ownerChanges();
        for (var change : changes) {
            var n = new NativeSequence();
            assertFalse(n.enter(false, () -> change.accept(n), false, () -> {}));
            assertEquals(0, n.parentGuiClosed); assertEquals(0, n.prePosts); assertEquals(1, n.releases);
            if (n.state.catalog() == n.originalCatalog) {
                assertNotNull(n.retired); n.parentInput(); n.resizeRetired(() -> {}); assertEquals(0, n.bindings);
            } else assertNull(n.retired, "Catalog-only replacement leaves the physical machine window valid");
            n.assertHeldAndQuiet();
        }
    }
    @Test void returnInitPreRevocationBlocksBindingInputUpdateAndResizeEvenAfterCleanupCancellation() {
        for (var change : physicalChanges()) {
            var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
            assertFalse(n.back(false, () -> change.accept(n)));
            var revoked = n.state;
            assertSame(n.parent, n.state.screen()); assertNotNull(n.retired); assertEquals(0, n.bindings);
            n.parentInput(); // Models the native input/update/wheel routes, each fenced for the precise retired target.
            n.resizeRetired(() -> {});
            assertSame(revoked.container(), n.state.container()); assertSame(revoked.player(), n.state.player()); assertEquals(0, n.bindings);
            var closeGuard = n.guard(null);
            assertFalse(n.display(closeGuard, null, null, false, true, () -> {}, false, () -> {}));
            n.resizeRetired(() -> {}); assertEquals(0, n.bindings);
            n.close(n.parent, n.originalContainer, closeGuard, false); // Uses the current player after Pre changed it.
            assertEquals(1, n.releases); n.assertHeldAndQuiet();
        }
    }
    @Test void catalogReplacementDuringReturnDoesNotRevokeThePhysicalParentWindow() {
        var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
        assertTrue(n.back(false, () -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, n.originalContainer, 17, new Object(), true)));
        assertNull(n.retired); assertEquals(1, n.bindings); n.assertHeldAndQuiet();
    }
    @Test void replacementForeignPreMayInstallItsOwnWindowWithoutBeingRetired() {
        var n = new NativeSequence(); var newContainer = new Object(); var guard = n.guard(n.viewer);
        assertFalse(n.display(guard, n.viewer, n.foreign, true, false, () -> {}, false,
            () -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, newContainer, 39, n.originalCatalog, true)));
        assertSame(n.foreign, n.state.screen()); assertSame(newContainer, n.state.container()); assertNull(n.retired); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void oldCloseReentrantForeignScreenOrWorldFocusSkipsOuterStoreDrainAndInit() {
        for (boolean foreign : new boolean[] {false, true}) {
            var n = new NativeSequence(); n.afterOldClose = () -> n.screen(foreign ? n.foreign : null);
            assertFalse(n.enter(false, () -> {}, false, () -> {}));
            assertSame(foreign ? n.foreign : null, n.state.screen());
            assertEquals(0, n.focusChanges); assertEquals(0, n.prePosts); assertEquals(1, n.parentGuiClosed); assertEquals(1, n.releases); n.assertHeldAndQuiet();
        }
    }
    @Test void oldCloseOwnerRevocationRetiresAlreadyDisposedSourceWithoutASecondGuiClose() {
        var n = new NativeSequence(); var newerContainer = new Object();
        n.afterOldClose = () -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, newerContainer, 39, n.originalCatalog, true);
        assertFalse(n.enter(false, () -> {}, false, () -> {}));
        assertTrue(n.retired.alreadyClosed(n.parent));
        n.close(n.parent, n.originalContainer, n.guard(null), false);
        assertEquals(1, n.parentGuiClosed); assertEquals(0, n.focusChanges); assertEquals(0, n.prePosts); n.assertHeldAndQuiet();
    }
    @Test void parentOverrideBeforeSuperAndInitPostCannotRebindOrLeaveOldWindowActionable() {
        for (boolean beforeSuper : new boolean[] {false, true}) {
            var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
            Runnable revoke = () -> n.owner(new Object(), n.originalWorld, n.originalConnection, new Object(), 39, n.originalCatalog, true);
            if (beforeSuper) n.beforeParentSuper = revoke; else n.afterInitPost = revoke;
            assertFalse(n.back(false, () -> {})); assertNotNull(n.retired);
            var revoked = n.state; n.parentInput(); n.resizeRetired(() -> {});
            assertSame(revoked.container(), n.state.container()); assertEquals(beforeSuper ? 0 : 1, n.bindings); n.assertHeldAndQuiet();
        }
    }
    @Test void reentrantForeignInitPreScreenIsPreservedWithoutRetiringItsInput() {
        var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
        assertFalse(n.back(false, () -> n.screen(n.foreign)));
        assertSame(n.foreign, n.state.screen()); assertNull(n.retired); assertEquals(0, n.bindings); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void canceledReturnAllowsRetryAndOriginalSlotSnapshotOutlivesConsumedNativeSave() {
        var n = new NativeSequence(); var slot = new Slot(new InventoryBasic("preview fixture", false, 1), 0, 12, 34);
        var original = new PreviewScreenTransitions.SavedSlot(slot);
        slot.xPos = slot.yPos = -9999;
        assertTrue(n.enter(false, () -> {}, false, () -> {}));
        var beforeReturn = new PreviewScreenTransitions.SavedSlot(slot);
        assertFalse(n.back(true, () -> {}));
        original.restore(); // AE's private saved array is consumed even when display was canceled.
        beforeReturn.restore(); assertEquals(-9999, slot.xPos); assertEquals(-9999, slot.yPos);
        assertTrue(n.back(false, () -> {})); original.restore();
        assertEquals(12, slot.xPos); assertEquals(34, slot.yPos); assertEquals(1, n.returnedHooks); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void backMouseAndEscapeDuringDragConsumeViewerReleaseBeforeParentReceivesInput() {
        for (boolean escape : new boolean[] {false, true}) {
            var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
            var held = new AtomicBoolean(true);
            var back = new PreviewBackRequest(() -> !held.get(), () -> assertTrue(n.back(false, () -> {})));
            if (escape) back.keyPress(); else back.mousePress();
            back.finishReleased(); assertSame(n.viewer, n.state.screen());
            var viewerReleases = new AtomicInteger();
            Runnable releaseEvent = () -> {
                assertSame(n.viewer, n.state.screen());
                viewerReleases.incrementAndGet();
                held.set(false); back.finishReleased();
            };
            releaseEvent.run();
            assertEquals(1, viewerReleases.get()); assertSame(n.parent, n.state.screen()); assertEquals(1, n.returnedHooks);
            back.finishReleased(); assertEquals(1, n.returnedHooks); n.assertHeldAndQuiet();
        }
    }
    @Test void unheldEscapeReturnsImmediatelyAndCanceledNullCloseCanRetry() {
        var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
        var back = new PreviewBackRequest(() -> true, () -> assertTrue(n.back(false, () -> {})));
        back.keyPress(); assertSame(n.parent, n.state.screen());
        n.screen(n.viewer);
        assertFalse(n.display(n.guard(null), null, null, false, true, () -> {}, false, () -> {}));
        assertSame(n.viewer, n.state.screen());
        assertTrue(n.display(n.guard(null), null, null, false, false, () -> {}, false, () -> {}));
        assertNull(n.state.screen()); n.assertHeldAndQuiet();
    }
    @Test void ordinaryUnscopedParentCloseStillDropsAndForeignTargetIsNotBlocked() {
        var n = new NativeSequence(); var retirement = new PreviewScreenGuard.Retired(n.parent, n.originalContainer);
        assertFalse(retirement.blocks(n.foreign)); assertFalse(retirement.suspends(n.parent, new Object()));
        n.close(n.parent, n.originalContainer, n.guard(n.foreign), false);
        assertEquals(1, n.containerClosed); assertEquals(1, n.dropped); assertNull(n.cursor);
    }
    @Test void commandQueueWaitsUntilChatClosedAndActualEndTick() {
        var queue = PreviewClientOpenQueue.INSTANCE;
        var chat = new Object(); var viewer = new Object(); var screen = new AtomicReference<>(chat);
        queue.enqueue(() -> { if (screen.get() == null) screen.set(viewer); });
        assertSame(chat, screen.get());
        screen.set(null); // GuiChat closes after executing the command.
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.START)); assertNull(screen.get());
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END)); assertSame(viewer, screen.get());
    }
    @Test void callbackEnqueuedDuringOpenWaitsForFollowingTick() {
        var queue = PreviewClientOpenQueue.INSTANCE; var calls = new AtomicInteger();
        queue.enqueue(() -> { calls.incrementAndGet(); queue.enqueue(calls::incrementAndGet); });
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END)); assertEquals(1, calls.get());
        queue.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END)); assertEquals(2, calls.get());
    }
    @Test void queuedCommandWindowRevocationRejectsOpenEvenWhenCurrentScreenStayedNull() {
        for (boolean sameContainer : new boolean[] {false, true}) {
            var n = new NativeSequence(); n.screen(null); var captured = n.guard(n.viewer); var opened = new AtomicInteger();
            PreviewClientOpenQueue.INSTANCE.enqueue(() -> { if (captured.current()) { opened.incrementAndGet(); n.screen(n.viewer); } });
            n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, sameContainer ? n.originalContainer : new Object(), 39, n.originalCatalog, true);
            PreviewClientOpenQueue.INSTANCE.tick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            assertEquals(0, opened.get()); assertNull(n.state.screen()); n.assertHeldAndQuiet();
        }
    }
    @Test void normalizedDeathAndWorldNullCloseTargetsStillFenceReentrantOpenPost() {
        for (boolean death : new boolean[] {false, true}) {
            var n = new NativeSequence(); n.screen(n.viewer);
            n.owner(n.originalPlayer, death ? n.originalWorld : null, n.originalConnection, n.originalContainer, 17, n.originalCatalog, !death);
            var nativeMenu = new Object(); var guard = n.guard(null); guard.normalizeNullTarget(nativeMenu);
            assertFalse(n.display(guard, nativeMenu, nativeMenu, false, false, () -> n.screen(n.foreign), false, () -> {}));
            assertSame(n.foreign, n.state.screen()); assertEquals(0, n.focusChanges); assertEquals(0, n.prePosts); n.assertHeldAndQuiet();
        }
    }
    @Test void cleanupKeepsItsOldContainerCloseProtectionWhenForeignInitClearsGlobalRetirement() {
        var n = new NativeSequence(); assertTrue(n.enter(false, () -> {}, false, () -> {}));
        assertFalse(n.back(false, () -> n.owner(new Object(), n.originalWorld, n.originalConnection, new Object(), 39, n.originalCatalog, true)));
        n.beforeContainerClose = () -> { n.screen(n.foreign); n.retired = null; };
        assertFalse(n.display(n.guard(null), null, null, false, false, () -> {}, false, () -> {}));
        assertSame(n.foreign, n.state.screen()); assertNull(n.retired); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void acceptedViewerInitFailureReleasesPreparedResourcesBeforePropagating() {
        var n = new NativeSequence(); var failure = new IllegalStateException("native InitPre callback failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> n.enter(false, () -> {}, false, () -> { throw failure; })));
        assertSame(n.viewer, n.state.screen()); assertEquals(1, n.releases); n.release(); assertEquals(1, n.releases); n.assertHeldAndQuiet();
    }
    @Test void returnPreReentrantViewerCapturesVisibleOriginalSlotsAndSurvivesOuterNativeRestore() {
        var n = new NativeSequence(); var slot = new Slot(new InventoryBasic("preview fixture", false, 1), 0, 12, 34);
        var original = new PreviewScreenTransitions.SavedSlot(slot); original.suspend();
        n.screen(n.viewer); var returning = n.guard(n.parent);
        assertFalse(returning.afterPost(false, n.parent)); n.screen(n.parent);
        original.restore(); // New Session.capture detects the outer return target and restores its exact persistent original snapshot.
        var nestedOriginal = new PreviewScreenTransitions.SavedSlot(slot); nestedOriginal.suspend();
        var nextViewer = new Object(); n.screen(nextViewer);
        assertTrue(returning.afterInitPre(false));
        original.restore(); // The outer AE switch consumes/restores the target's private saved array after the nested open returned.
        nestedOriginal.suspend(); // Its exact current owned viewer reapplies native suspension; foreign screens are excluded.
        assertEquals(-9999, slot.xPos); assertEquals(-9999, slot.yPos);
        var nestedReturn = n.guard(n.parent); assertFalse(nestedReturn.afterPost(false, n.parent)); n.screen(n.parent);
        assertFalse(nestedReturn.afterInitPre(false)); nestedOriginal.restore();
        assertEquals(12, slot.xPos); assertEquals(34, slot.yPos); n.assertHeldAndQuiet();
    }
    private static List<Consumer<NativeSequence>> physicalChanges() {
        var changes = ownerChanges();
        return List.of(changes.get(0), changes.get(1), changes.get(2), changes.get(3), changes.get(4), changes.get(6));
    }
    private static List<Consumer<NativeSequence>> ownerChanges() {
        return List.of(
            n -> n.owner(new Object(), n.originalWorld, n.originalConnection, n.originalContainer, 17, n.originalCatalog, true),
            n -> n.owner(n.originalPlayer, new Object(), n.originalConnection, n.originalContainer, 17, n.originalCatalog, true),
            n -> n.owner(n.originalPlayer, n.originalWorld, new Object(), n.originalContainer, 17, n.originalCatalog, true),
            n -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, new Object(), 29, n.originalCatalog, true),
            n -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, n.originalContainer, 18, n.originalCatalog, true),
            n -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, n.originalContainer, 17, new Object(), true),
            n -> n.owner(n.originalPlayer, n.originalWorld, n.originalConnection, n.originalContainer, 17, n.originalCatalog, false));
    }
}
