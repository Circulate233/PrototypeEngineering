package com.circulation.prototype_eng.client.preview;

import java.util.function.BooleanSupplier;

/**
 * Viewer-local Back control: consume the held mouse release before displaying its parent.
 */
public final class PreviewBackRequest {
    private final BooleanSupplier buttonsReleased;
    private final Runnable back;
    private boolean pending;

    public PreviewBackRequest(BooleanSupplier buttonsReleased, Runnable back) {
        this.buttonsReleased = buttonsReleased;
        this.back = back;
    }

    public void mousePress() {
        pending = true;
    }

    public void keyPress() {
        pending = true;
        finishReleased();
    }

    public void finishReleased() {
        if (!pending || !buttonsReleased.getAsBoolean()) return;
        pending = false;
        back.run();
    }
}
