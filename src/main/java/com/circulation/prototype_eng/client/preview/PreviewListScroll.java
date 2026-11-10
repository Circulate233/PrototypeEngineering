package com.circulation.prototype_eng.client.preview;

import java.util.Objects;

/** Clipped list viewport; changing the selected requirement resets its independent scroll. */
final class PreviewListScroll {
    private Object identity;
    private int first;

    void identity(Object next) {
        if (!Objects.equals(identity, next)) {
            identity = next;
            first = 0;
        }
    }

    int first(int size, int visible) {
        first = Math.clamp(first, 0, Math.max(0, size - visible));
        return first;
    }

    void wheel(int delta, int size, int visible) {
        first = Math.clamp(first(size, visible) - Integer.signum(delta), 0, Math.max(0, size - visible));
    }
}
