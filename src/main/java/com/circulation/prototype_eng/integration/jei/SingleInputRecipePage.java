package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import mezz.jei.api.IGuiHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public final class SingleInputRecipePage extends AutoLayoutRecipePage {
    public SingleInputRecipePage(SimpleRecipePool pool, IGuiHelper helper, RecipeJeiDisplays displays, JeiRenderOptions options,
                                 RecipeInformationLayout.Font font) {
        super(pool, helper, displays::describe, options, font, pool.auxiliaryInputs().isEmpty());
    }

    public SingleInputRecipePage(SimpleRecipePool pool, IGuiHelper helper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options) {
        super(pool, helper, displays::describe, options, minecraftFont(), pool.auxiliaryInputs().isEmpty());
    }

    @Override
    public @Nullable PreparedUpdate prepareUpdate(@NotNull RecipePool target, RecipeJeiDisplays.@NotNull Snapshot displays) {
        return target instanceof SimpleRecipePool simple ? prepareLayoutUpdate(target, displays, simple.auxiliaryInputs().isEmpty()) : null;
    }
}
