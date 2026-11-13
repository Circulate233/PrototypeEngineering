package com.circulation.prototype_eng.item;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.localization.CreativeTabText;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

/**
 * Native creative inventory group; its icon resolves after item registration.
 */
public final class PrototypeCreativeTab extends CreativeTabs {
    public static final PrototypeCreativeTab INSTANCE = new PrototypeCreativeTab();

    private PrototypeCreativeTab() {
        super(Tags.MOD_ID);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public @NotNull ItemStack createIcon() {
        return new ItemStack(OrientationToolItems.TOOL);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public @NotNull String getTranslationKey() {
        return CreativeTabText.Main.getTranslationKey();
    }
}
