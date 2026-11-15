package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.JeiVisuals;
import com.circulation.prototype_eng.localization.JeiText;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class ResearchDownloadJeiCategory implements IRecipeCategory<ResearchDownloadJeiRecipe> {
    static final int WIDTH = 174;
    private static final int INPUT_X = 57, OUTPUT_X = 99, SLOT_Y = 4;
    private static final JeiVisuals.Texture SLOT = new JeiVisuals.Texture(JeiVisuals.texture("item_module/plaid_base"), 18, 18);
    private static final JeiVisuals.Texture FRAME = new JeiVisuals.Texture(JeiVisuals.texture("jei_base"), 184, 220);
    private final List<ResearchDownloadJeiRecipe> recipes;
    private final String uid;
    private final JeiText title;
    private final IDrawable arrow;
    private final RecipeInformationLayout.Font font;
    private String language;
    private IDrawable background;

    ResearchDownloadJeiCategory(String uid, JeiText title, List<ResearchDownloadJeiRecipe> recipes,
                                IGuiHelper helper, RecipeInformationLayout.Font font) {
        this.uid = uid;
        this.title = title;
        this.recipes = recipes;
        this.font = font;
        arrow = helper.createDrawable(new ResourceLocation("jei", "textures/gui/gui_vanilla.png"), 82, 128, 24, 17);
        language = Minecraft.getMinecraft().gameSettings.language;
        int height = 34;
        for (int index = 0; index < recipes.size(); index++) height = Math.max(height, recipes.get(index).height());
        background = new JeiVisuals.NineSlice(FRAME, WIDTH, height, 2, false, 0xffffffff);
    }

    private void prepare(String currentLanguage) {
        int height = 34;
        for (int index = 0; index < recipes.size(); index++)
            height = Math.max(height, recipes.get(index).prepare(font));
        background = new JeiVisuals.NineSlice(FRAME, WIDTH, height, 2, false, 0xffffffff);
        language = currentLanguage;
    }

    @Override
    public String getUid() {
        return uid;
    }

    @Override
    public String getTitle() {
        return title.getLocal();
    }

    @Override
    public String getModName() {
        return Tags.MOD_NAME;
    }

    @Override
    public IDrawable getBackground() {
        var current = Minecraft.getMinecraft().gameSettings.language;
        if (!language.equals(current)) prepare(current);
        return background;
    }

    @Override
    public void drawExtras(Minecraft minecraft) {
        SLOT.draw(minecraft, INPUT_X, SLOT_Y);
        SLOT.draw(minecraft, OUTPUT_X, SLOT_Y);
        arrow.draw(minecraft, 77, SLOT_Y);
    }

    @Override
    public void setRecipe(IRecipeLayout layout, ResearchDownloadJeiRecipe wrapper, IIngredients ingredients) {
        var slots = layout.getItemStacks();
        slots.init(0, true, INPUT_X, SLOT_Y);
        slots.init(1, false, OUTPUT_X, SLOT_Y);
        slots.set(0, wrapper.input());
        slots.set(1, wrapper.output());
        layout.setRecipeId(wrapper.id().getNamespace(), wrapper.id());
    }
}
