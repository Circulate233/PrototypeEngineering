package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRecipe;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportRegistry;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Text.of;

/**
 * Enumerates custom download and import declarations without invoking server events or changing research.
 */
@SideOnly(Side.CLIENT)
final class ResearchDownloadJeiRegistration {
    static final String DOWNLOAD_UID = Tags.MOD_ID + ":research_download";
    static final String IMPORT_UID = Tags.MOD_ID + ":research_import";
    private final List<ResearchDownloadJeiRecipe> recipes;
    private final List<ResearchDownloadJeiRecipe> importRecipes;

    ResearchDownloadJeiRegistration(IRecipeCategoryRegistration registration) {
        var font = AutoLayoutRecipePage.minecraftFont();
        recipes = downloads(font);
        importRecipes = imports(font);
        if (!recipes.isEmpty())
            registration.addRecipeCategories(new ResearchDownloadJeiCategory(DOWNLOAD_UID, JeiText.ResearchDownload,
                recipes, registration.getJeiHelpers().getGuiHelper(), font));
        if (!importRecipes.isEmpty())
            registration.addRecipeCategories(new ResearchDownloadJeiCategory(IMPORT_UID, JeiText.ResearchDatabaseImport,
                importRecipes, registration.getJeiHelpers().getGuiHelper(), font));
    }

    private static List<ResearchDownloadJeiRecipe> downloads(RecipeInformationLayout.Font font) {
        var registry = ResearchItemDownloadRegistry.INSTANCE;
        var recipes = registry.recipes();
        var research = ResearchRegistry.INSTANCE;
        var items = new ObjectArrayList<ResearchDownloadJeiRecipe>();
        var definitions = research.sortedById();
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            if (recipe.researchId() != null) {
                appendDownload(recipe, research.require(recipe.researchId()), items, font);
            } else {
                for (int definition = 0; definition < definitions.size(); definition++) {
                    var candidate = definitions.get(definition);
                    if (registry.find(recipe.material(), candidate.id()) == recipe)
                        appendDownload(recipe, candidate, items, font);
                }
            }
        }
        return items;
    }

    private static void appendDownload(ResearchItemDownloadRecipe recipe, ResearchDefinition definition,
                                       List<ResearchDownloadJeiRecipe> items,
                                       RecipeInformationLayout.Font font) {
        if (definition.specialOnly() || recipe.researchLevel() > definition.maxLevel())
            return;
        items.add(new ResearchDownloadJeiRecipe(wrapperId(recipe.id(), definition.id()), recipe.material().toStack(), recipe.outputTemplate(),
            definition, recipe.researchLevel(), recipe.levelCost(), false, font));
    }

    private static List<ResearchDownloadJeiRecipe> imports(RecipeInformationLayout.Font font) {
        var recipes = ResearchItemImportRegistry.INSTANCE.recipes();
        var registry = ResearchRegistry.INSTANCE;
        var items = new ObjectArrayList<ResearchDownloadJeiRecipe>(recipes.size());
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            var research = registry.require(recipe.researchId());
            items.add(new ResearchDownloadJeiRecipe(wrapperId(recipe.id(), research.id()), recipe.material().toStack(),
                recipe.outputTemplate(), research, recipe.researchLevel(), 0, true, font));
        }
        return items;
    }

    static List<JeiRecipeDisplay.Text> downloadInformation(ResearchDefinition definition, int level, int cost) {
        return List.of(of(JeiText.ResearchDownloadRequirement, definition.displayName().getLocal(), level),
            cost == 0 ? of(JeiText.ResearchNoConsumption) : of(JeiText.ResearchLevelConsumption, cost));
    }

    static List<JeiRecipeDisplay.Text> importInformation(ResearchDefinition definition, int level) {
        return List.of(of(JeiText.ResearchImportIncrement, definition.displayName().getLocal(), level));
    }

    private static ResourceLocation wrapperId(ResourceLocation recipe, ResourceLocation research) {
        return new ResourceLocation(recipe.getNamespace(), recipe.getPath() + "/" + research.getNamespace() + "/" + research.getPath());
    }

    void registerRecipes(IModRegistry registration) {
        boolean downloads = !recipes.isEmpty() && !HeiRecipeGeneration.categoryDisabled(DOWNLOAD_UID);
        boolean imports = !importRecipes.isEmpty() && !HeiRecipeGeneration.categoryDisabled(IMPORT_UID);
        if (downloads) registration.addRecipes(recipes, DOWNLOAD_UID);
        if (imports) registration.addRecipes(importRecipes, IMPORT_UID);
        if (!downloads && !imports) return;
        var blocks = MachineControllerBlocks.all();
        for (int block = 0; block < blocks.size(); block++) {
            var controller = blocks.get(block);
            if (!controller.type().componentTypes().contains(DataCenter.DEVICE)) continue;
            var stack = new ItemStack(controller);
            if (downloads) registration.addRecipeCatalyst(stack, DOWNLOAD_UID);
            if (imports) registration.addRecipeCatalyst(stack, IMPORT_UID);
        }
    }
}
