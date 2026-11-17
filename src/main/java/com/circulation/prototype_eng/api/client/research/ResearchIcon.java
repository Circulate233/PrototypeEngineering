package com.circulation.prototype_eng.api.client.research;

import ae2.api.stacks.AEKey;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

/**
 * A client icon drawn in a 16-pixel square; item icons expose their key for native tooltips.
 */
@SideOnly(Side.CLIENT)
@FunctionalInterface
public interface ResearchIcon {
    void draw(Minecraft minecraft, int x, int y);

    default @Nullable AEKey key() {
        return null;
    }
}
