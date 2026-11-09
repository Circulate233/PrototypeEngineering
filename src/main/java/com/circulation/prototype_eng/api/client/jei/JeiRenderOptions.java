package com.circulation.prototype_eng.api.client.jei;

import java.util.Objects;

/** Display choices captured per page; changing defaults affects subsequently registered pages only. */
public record JeiRenderOptions(boolean chanceOverlay, CandidateMode candidates, int energyLedYOffset) {
    private static JeiRenderOptions defaults = new JeiRenderOptions(true, CandidateMode.ALTERNATIVES, 0);

    public JeiRenderOptions {
        Objects.requireNonNull(candidates);
    }

    public enum CandidateMode { ALTERNATIVES, EXPANDED }

    public static JeiRenderOptions defaults() {
        return defaults;
    }

    public static void defaults(JeiRenderOptions options) {
        defaults = Objects.requireNonNull(options);
    }
}
