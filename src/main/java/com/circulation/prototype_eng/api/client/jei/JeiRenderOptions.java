package com.circulation.prototype_eng.api.client.jei;


/**
 * Display choices captured per page; changing defaults affects subsequently registered pages only.
 */
public record JeiRenderOptions(boolean chanceOverlay, CandidateMode candidates, int energyLedYOffset) {
    private static JeiRenderOptions defaults = new JeiRenderOptions(true, CandidateMode.ALTERNATIVES, 0);


    public static JeiRenderOptions defaults() {
        return defaults;
    }

    public static void defaults(JeiRenderOptions options) {
        defaults = options;
    }

    public enum CandidateMode {ALTERNATIVES, EXPANDED}
}
