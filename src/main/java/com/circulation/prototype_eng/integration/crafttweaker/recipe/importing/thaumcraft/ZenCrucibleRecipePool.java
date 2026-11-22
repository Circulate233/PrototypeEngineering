package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing.thaumcraft;

import com.circulation.prototype_eng.api.recipe.importing.thaumcraft.CrucibleRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBuilder;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.nbt.NBTTagCompound;
import thaumcraft.api.aspects.AspectList;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.importing.thaumcraft.CrucibleRecipePool")
public final class ZenCrucibleRecipePool extends ZenRecipePool {
    private ZenCrucibleRecipePool(CrucibleRecipePool pool, String revision) {
        super(pool, revision);
    }

    @ZenMethod
    public static ZenCrucibleRecipePool create(String id, String titleKey, String revision) {
        return create(id, titleKey, revision, null);
    }

    @ZenMethod
    public static ZenCrucibleRecipePool create(String id, String titleKey, String revision, @Nullable ZenCrucibleImportCallback onCreate) {
        return new ZenCrucibleRecipePool(new CrucibleRecipePool(new ResourceLocation(id), titleKey,
            onCreate == null ? null : new CreationHandler(onCreate)), revision);
    }

    private record CreationHandler(ZenCrucibleImportCallback callback) implements CrucibleRecipePool.OnCreate {
        @Override
        public boolean create(RecipeBuilder recipe, ResourceLocation sourceId, AspectList aspects, String research) {
            var tag = new NBTTagCompound();
            for (var aspect : aspects.getAspects()) tag.setInteger(aspect.getTag(), aspects.getAmount(aspect));
            return callback.create(new ZenRecipeBuilder(recipe), sourceId.toString(), NBTConverter.from(tag, true), research);
        }
    }
}
