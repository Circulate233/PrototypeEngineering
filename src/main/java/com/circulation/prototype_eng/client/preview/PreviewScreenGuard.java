package com.circulation.prototype_eng.client.preview;

import java.util.Objects;
import java.util.function.Supplier;

/** One native display transaction's exact owner; callbacks cannot revive an older screen transition. */
public final class PreviewScreenGuard {
    public record State(Object screen, Object player, Object world, Object connection, Object container, int window, Object catalog, boolean alive) {}
    public record Retired(Object screen, Object container, boolean guiClosed) {
        public Retired(Object screen, Object container) { this(screen, container, false); }
        public boolean blocks(Object target) { return target == screen; }
        public boolean suspends(Object target, Object closingContainer) { return target == screen && closingContainer == container; }
        public boolean alreadyClosed(Object target) { return target == screen && guiClosed; }
    }
    private final State before;
    private Object requested;
    private final Supplier<State> current;
    private boolean revoked, accepted;
    private Object displayed;
    public PreviewScreenGuard(State before, Object requested, Supplier<State> current) {
        this.before = Objects.requireNonNull(before); this.requested = requested; this.current = Objects.requireNonNull(current);
    }
    public boolean current() { return !revoked && same(before, current.get()); }
    /** Catalog replacement does not revoke a machine's server window; physical owner changes do. */
    public boolean sourceRetired() {
        var state = current.get();
        return state != null && state.screen == before.screen && !samePhysicalOwner(before, state);
    }
    public boolean displayedOwnerRetired() {
        var state = current.get();
        return state != null && state.screen == displayed && !samePhysicalOwner(before, state);
    }
    /** Minecraft normalizes a null local close to its actual MainMenu/GameOver object before posting. */
    public void normalizeNullTarget(Object target) {
        if (requested != null) throw new IllegalStateException("Only native null close targets are normalized");
        requested = target;
    }
    public void revoke() { revoked = true; }
    /** Return the actual cancellation decision after all GuiOpenEvent callbacks completed. */
    public boolean afterPost(boolean canceled, Object finalTarget) {
        if (!current()) { revoked = true; return true; }
        accepted = !canceled; displayed = canceled ? null : finalTarget;
        return canceled;
    }
    public boolean suspends(Object screen, Object container) {
        // A reentrant parent.onGuiClosed must not let its old C clear a new window's cursor.
        // The native adapter confines this proof to the exact captured entering call stack,
        // including reentrant closes inside GuiOpenEvent before its outer post has returned.
        return before.screen == screen && before.container == container;
    }
    public boolean requested(Object screen) { return requested == screen; }
    public boolean displayedRequested() { return accepted && !revoked && displayed == requested; }
    public boolean displayed(Object screen) { return accepted && displayed == screen; }
    public boolean atDisplayed() {
        var state = current.get();
        return accepted && !revoked && state != null && state.screen == displayed && sameOwner(before, state);
    }
    /** The second post happens with target=current, after Minecraft's real screen assignment. */
    public boolean afterInitPre(boolean canceled) {
        if (!atDisplayed()) { revoked = true; return true; }
        return canceled;
    }
    private static boolean same(State left, State right) {
        return right != null && left.screen == right.screen && sameOwner(left, right);
    }
    private static boolean sameOwner(State left, State right) {
        return samePhysicalOwner(left, right) && left.catalog == right.catalog;
    }
    private static boolean samePhysicalOwner(State left, State right) {
        return left.player == right.player && left.world == right.world && left.connection == right.connection
            && left.container == right.container && left.window == right.window && left.alive == right.alive;
    }
}
