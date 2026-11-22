package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.api.recipe.importing.thaumcraft.InfusionRecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.item.ItemStack;
import thaumcraft.api.aspects.AspectList;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.InfusionRecipePool")
public final class ZenInfusionRecipePool extends ZenRecipePool {
    private ZenInfusionRecipePool(InfusionRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenInfusionRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenInfusionRecipePool create(String id, String titleKey, String revision, @Nullable ZenInfusionImportCallback onCreate) {
        return new ZenInfusionRecipePool(new InfusionRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : new CreationHandler(onCreate)), revision);
    }

    private record CreationHandler(ZenInfusionImportCallback callback) implements InfusionRecipePool.OnCreate {
        @Override
        public boolean create(RecipeBuilder recipe, ResourceLocation sourceId, AspectList aspects,
                              int instability, String research, ItemStack mainInput) {
            var tag = new NBTTagCompound();
            for (var aspect : aspects.getAspects()) tag.setInteger(aspect.getTag(), aspects.getAmount(aspect));
            return callback.create(new ZenRecipeBuilder(recipe), sourceId.toString(), NBTConverter.from(tag, true),
                instability, research, CraftTweakerMC.getIItemStack(mainInput));
        }
    }
}
