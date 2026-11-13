package com.circulation.prototype_eng.integration.crafttweaker.jei;

import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Locale;

/**
 * Common display parameters; no client/HEI type is linked while scripts load on a server.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.jei.Options")
@SuppressWarnings("unused") // ZenScript and Java addon entry points.
public record ZenJeiOptions(boolean chanceOverlay, String candidates, int energyLedYOffset) {
    public ZenJeiOptions {
        candidates = candidates.trim().toUpperCase(Locale.ROOT);
        if (!candidates.equals("ALTERNATIVES") && !candidates.equals("EXPANDED"))
            throw new IllegalArgumentException("Candidate mode must be alternatives or expanded");
    }

    @ZenMethod
    public static ZenJeiOptions create(boolean chanceOverlay, String candidates) {
        return new ZenJeiOptions(chanceOverlay, candidates, 0);
    }

    @ZenMethod
    public static void defaults(boolean chanceOverlay, String candidates) {
        ClientJeiDeclarations.defaults(create(chanceOverlay, candidates));
    }
}
