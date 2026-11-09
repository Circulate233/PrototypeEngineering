package com.circulation.prototype_eng.integration.jei;

import ae2.api.integrations.hei.IngredientConverters;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiQueryIdentityIndex;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;
import mcp.MethodsReturnNonnullByDefault;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import ae2.container.me.patternencode.ContainerPEATerm;
import com.circulation.prototype_eng.api.client.jei.JeiEnergyIngredients;
import mezz.jei.api.ingredients.IModIngredientRegistration;
/**
 * HEI 4.35.1 discovers this entry on the client; common mod initialization never references it.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("unused") // Discovered by HEI's @JEIPlugin scan on the client.
public class PrototypeJeiPlugin implements IModPlugin {
    @Override public void registerIngredients(IModIngredientRegistration registration) { JeiEnergyIngredients.register(registration); }
    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var pools = RecipeRegistry.INSTANCE.pools();
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var displays = RecipeJeiDisplays.INSTANCE;
        var helper = registration.getJeiHelpers().getGuiHelper();
        MinecraftForge.EVENT_BUS.post(new RecipePoolJeiRegistrationEvent(pages, displays, helper, pools));
        displays.freeze();
        pages.freeze(pools, pool -> new AutoLayoutRecipePage(pool, helper, displays));
        for (var page : pages.pages()) {
            registration.addRecipeCategories(page);
        }
        registration.addRecipeCategories(new StructurePreviewJeiCategory(helper));
    }

    @Override
    public void register(IModRegistry registration) {
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var displays = RecipeJeiDisplays.INSTANCE;
        var converters = IngredientConverters.getConverters();
        var allDisplays = new ObjectArrayList<JeiRecipeDisplay>();
        for (var page : pages.pages()) {
            for (var recipe : page.pool().recipes()) {
                allDisplays.add(displays.describe(recipe));
            }
        }
        // Finish the global identity audit before registering any pool's wrappers.
        var identities = JeiQueryIdentityIndex.capture(converters, registration.getIngredientRegistry(), allDisplays);
        var structures = new ObjectArrayList<StructurePreviewJeiRecipe>();
        for (var machine : MachineTypeRegistry.INSTANCE.types()) {
            var controllers = new ObjectArrayList<ItemStack>();
            for (var block : MachineControllerBlocks.all()) {
                if (block.type() != machine) continue;
                var stack = new ItemStack(block);
                if (!stack.isEmpty()) {
                    controllers.add(stack);
                    registration.addRecipeCatalyst(stack.copy(), StructurePreviewJeiCategory.UID);
                }
            }
            structures.add(new StructurePreviewJeiRecipe(machine, controllers));
        }
        registration.addRecipes(structures, StructurePreviewJeiCategory.UID);
        for (var page : pages.pages()) {
            var wrappers = new ObjectArrayList<>();
            var transfer = registration.getRecipeTransferRegistry();
            var helper = registration.getJeiHelpers().recipeTransferHandlerHelper();
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPatternEncodingTerm.class, helper), page.getUid());
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPEATerm.class, helper), page.getUid());
            for (var recipe : page.pool().recipes()) {
                var display = displays.describe(recipe);
                var ingredients = new JeiRecipeIngredients(display, converters, registration.getIngredientRegistry(), identities);
                var wrapper = Objects.requireNonNull(page.createRecipe(recipe, display, ingredients), "JEI page returned null wrapper");
                if (wrapper.page() != page || wrapper.recipe() != recipe || wrapper.display() != display || wrapper.ingredients() != ingredients) {
                    throw new IllegalArgumentException("Custom JEI wrapper must retain the canonical page, recipe and supplied display data");
                }
                wrappers.add(wrapper);
            }
            registration.addRecipes(wrappers, page.getUid());
            for (var catalyst : pages.catalysts(page.pool().id())) {
                registration.addRecipeCatalyst(catalyst, page.getUid());
            }
            for (var machine : page.pool().machines()) {
                for (var block : MachineControllerBlocks.all()) {
                    if (block.type() != machine) continue;
                    var controller = new ItemStack(block);
                    if (!controller.isEmpty()) {
                        registration.addRecipeCatalyst(controller, page.getUid());
                    }
                }
            }
        }
    }

}
