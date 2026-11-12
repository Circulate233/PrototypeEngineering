package com.circulation.prototype_eng.util;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.inventory.ClickType;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.Mirror;

/** Shared declaration-order arrays for dependency enums without a public cache. Do not modify them. */
public final class ExternalEnumValues {
    public static final BlockOrientation[] BLOCK_ORIENTATIONS = BlockOrientation.values();
    public static final BlockRenderLayer[] BLOCK_RENDER_LAYERS = BlockRenderLayer.values();
    public static final ClickType[] CLICK_TYPES = ClickType.values();
    public static final Mirror[] MIRRORS = Mirror.values();

    private ExternalEnumValues() {
    }
}
