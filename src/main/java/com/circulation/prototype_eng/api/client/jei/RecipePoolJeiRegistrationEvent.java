package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.RecipePool;
import mezz.jei.api.IGuiHelper;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Fired only by HEI's client category phase after the common RecipeRegistry has frozen.
 * The frozen registry's pool list is borrowed read-only; event listeners must not mutate it.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
@SideOnly(Side.CLIENT)
public class RecipePoolJeiRegistrationEvent extends Event {
    private final RecipePoolJeiRegistry pages;
    private final RecipeJeiDisplays displays;
    private final IGuiHelper guiHelper;
    private final List<RecipePool> pools;

    public RecipePoolJeiRegistrationEvent(RecipePoolJeiRegistry pages, RecipeJeiDisplays displays,
                                          IGuiHelper guiHelper, List<RecipePool> pools) {
        this.pages = pages;
        this.displays = displays;
        this.guiHelper = guiHelper;
        this.pools = pools;
    }

    @SuppressWarnings("unused") // Public addon event data.
    public RecipePoolJeiRegistry pages() {
        return pages;
    }

    @SuppressWarnings("unused") // Public addon event data.
    public RecipeJeiDisplays displays() {
        return displays;
    }

    @SuppressWarnings("unused") // Public addon event data.
    public IGuiHelper guiHelper() {
        return guiHelper;
    }

    @SuppressWarnings("unused") // Public addon event data.
    public List<RecipePool> pools() {
        return pools;
    }
}
