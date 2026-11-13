package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import net.minecraft.util.ResourceLocation;

/**
 * Fixed geometry shared by a resource container and its screen.
 */
public record ResourceHatchLayout(int columns, int visibleRows, int width, int height, int textureWidth,
                                  int resourceTop, int inputLeft, int outputLeft, int playerLeft,
                                  boolean scrollbars, ResourceLocation background) {
    public static ResourceHatchLayout standard(HatchMode mode, HatchTier tier) {
        int level = tier.level();
        boolean mixed = mode == HatchMode.MIXED;
        String folder = mixed ? "gui_material_io_hatch" : "gui_material_hatch";
        return new ResourceHatchLayout(mixed ? (level <= 4 ? 4 : 5) : (level <= 2 ? 8 : 9),
            level <= 2 ? 3 : level <= 4 ? 4 : 7,
            mixed ? (level <= 2 ? 204 : level <= 4 ? 209 : 245) : (level <= 2 ? 195 : 213),
            level <= 2 ? 168 : level <= 4 ? 186 : mixed ? 240 : 239,
            mixed && level >= 5 ? 304 : 256, 24, 26,
            mixed ? (level <= 2 ? 114 : level <= 4 ? 119 : 137) : 26, 27, true,
            new ResourceLocation(Tags.MOD_ID, "textures/gui/" + folder + "/" + folder + "_" + level + ".png"));
    }
}
