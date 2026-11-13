package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.localization.RecipePoolTitle;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * One native JEI category per canonical pool. Custom pages need not extend the default page.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public interface RecipePoolJeiPage extends IRecipeCategory<RecipePoolJeiRecipe> {
    static String uid(ResourceLocation poolId) {
        return new ResourceLocation(Tags.MOD_ID, "pool/" + poolId.getNamespace() + "/" + poolId.getPath()).toString();
    }

    RecipePool pool();

    /**
     * Pure preparation for the same category. Prepared wrapper ingredients must be stable before
     * page installation and while its display token is inactive; custom interactions honor isCurrent().
     */
    default @Nullable PreparedUpdate prepareUpdate(RecipePool target, RecipeJeiDisplays.Snapshot displays) {
        return null;
    }

    @Override
    default String getUid() {
        return uid(pool().id());
    }

    @Override
    default String getTitle() {
        return new RecipePoolTitle(pool().translationKey()).getLocal();
    }

    @Override
    default String getModName() {
        return Tags.MOD_NAME;
    }

    /**
     * Override with a wrapper subclass for native per-recipe drawing, tooltip, click, scroll and drag.
     */
    default RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        return new RecipePoolJeiRecipe(this, recipe, display, ingredients);
    }

    interface PreparedUpdate {
        RecipePoolJeiPage page();

        RecipePool pool();

        RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients);

        /**
         * Single use: validate the captured owner data, then assign prepared data without external callbacks.
         */
        void apply();
    }
}
