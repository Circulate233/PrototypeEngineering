package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;

import javax.annotation.ParametersAreNonnullByDefault;

/** Virtual machine structure information only; this category never owns a RecipePool. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class StructurePreviewJeiCategory implements IRecipeCategory<StructurePreviewJeiRecipe> {
    public static final String UID = Tags.MOD_ID + ":structure_preview";
    public static final int WIDTH = 184, HEIGHT = 220;
    private final IDrawable background;

    public StructurePreviewJeiCategory(IGuiHelper helper) {
        background = helper.createBlankDrawable(WIDTH, HEIGHT);
    }

    @Override
    public String getUid() {
        return UID;
    }

    @Override
    public String getTitle() {
        return StructurePreviewText.Title.getLocal();
    }

    @Override
    public String getModName() {
        return Tags.MOD_NAME;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public void setRecipe(IRecipeLayout layout, StructurePreviewJeiRecipe wrapper, IIngredients ingredients) {
        wrapper.retirePanel();
    }
}
