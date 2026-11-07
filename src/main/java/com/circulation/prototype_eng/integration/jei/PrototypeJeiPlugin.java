package com.circulation.prototype_eng.integration.jei;

import ae2.api.integrations.hei.IngredientConverters;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiQueryIdentityIndex;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;
import java.util.Set;
import javax.annotation.ParametersAreNonnullByDefault;

import mcp.MethodsReturnNonnullByDefault;

/**
 * HEI 4.35.1 discovers this entry on the client; common mod initialization never references it.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("unused") // Discovered by HEI's @JEIPlugin scan on the client.
public class PrototypeJeiPlugin implements IModPlugin {
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
        for (var page : pages.pages()) {
            var wrappers = new ObjectArrayList<>();
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
            for (var block : MachineControllerBlocks.all()) {
                if (uses(block.type(), page.pool())) {
                    var controller = new ItemStack(block);
                    if (!controller.isEmpty()) {
                        registration.addRecipeCatalyst(controller, page.getUid());
                    }
                }
            }
        }
    }

    /**
     * Mirrors the existing scanner's group selection without creating a second recipe directory.
     */
    static boolean uses(MachineType machine, RecipePool pool) {
        for (var recipe : pool.recipes()) {
            if (machine.workSlots().isEmpty()) {
                if (accepts(machine.recipeGroups(), recipe)) {
                    return true;
                }
            } else {
                for (var slot : machine.workSlots()) {
                    if (slot.accepts(recipe)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean accepts(Set<ResourceLocation> groups, MachineRecipe recipe) {
        if (groups.isEmpty()) {
            return true;
        }
        for (var group : groups) {
            if (recipe.recipeGroups().contains(group)) {
                return true;
            }
        }
        return false;
    }
}
