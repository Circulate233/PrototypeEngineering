package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.research.ResearchDefinition;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Static research conversion display. Item templates and information are borrowed read-only;
 * creating or drawing a wrapper never evaluates the conversion event.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class ResearchDownloadJeiRecipe implements IRecipeWrapper {
    static final int INFORMATION_Y = 28;
    private final ResourceLocation id;
    private final ItemStack input;
    private final ItemStack output;
    private final ResearchDefinition research;
    private final int level;
    private final int levelCost;
    private final boolean importing;
    private RecipeInformationLayout layout;

    ResearchDownloadJeiRecipe(ResourceLocation id, ItemStack input, ItemStack output,
                              ResearchDefinition research, int level, int levelCost, boolean importing,
                              RecipeInformationLayout.Font font) {
        this.id = id;
        this.input = input;
        this.output = output;
        this.research = research;
        this.level = level;
        this.levelCost = levelCost;
        this.importing = importing;
        prepare(font);
    }

    ResourceLocation id() {
        return id;
    }

    ItemStack input() {
        return input;
    }

    ItemStack output() {
        return output;
    }

    int prepare(RecipeInformationLayout.Font font) {
        var information = importing ? ResearchDownloadJeiRegistration.importInformation(research, level)
            : ResearchDownloadJeiRegistration.downloadInformation(research, level, levelCost);
        layout = RecipeInformationLayout.measure(information, ResearchDownloadJeiCategory.WIDTH, font);
        return height();
    }

    int height() {
        return INFORMATION_Y + layout.height() + 6;
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        ingredients.setInput(VanillaTypes.ITEM, input);
        ingredients.setOutput(VanillaTypes.ITEM, output);
    }

    @Override
    public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
        var paragraphs = layout.paragraphs();
        for (int index = 0; index < paragraphs.size(); index++) {
            var paragraph = paragraphs.get(index);
            var lines = paragraph.lines();
            for (int line = 0; line < lines.size(); line++) {
                minecraft.fontRenderer.drawString(lines.get(line), paragraph.x(),
                    INFORMATION_Y + paragraph.y() + line * minecraft.fontRenderer.FONT_HEIGHT, 0x404040);
            }
        }
    }
}
