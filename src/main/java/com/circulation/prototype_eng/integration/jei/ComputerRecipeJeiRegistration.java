package com.circulation.prototype_eng.integration.jei;

import ae2.api.integrations.hei.IngredientConverter;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.JeiQueryIdentityIndex;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collection;
import java.util.List;

import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role.INPUT;
import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Text.of;

/**
 * 从装配声明生成计算机供给页面，复用普通材料布局及文字区，不调用运行实例或条件回调。
 */
@SideOnly(Side.CLIENT)
final class ComputerRecipeJeiRegistration {
    private final List<Page> pages = new ObjectArrayList<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, JeiRecipeDisplay> descriptions = new Reference2ObjectOpenHashMap<>();

    ComputerRecipeJeiRegistration(IRecipeCategoryRegistration registration, RecipeJeiDisplays displayProviders) {
        var helper = registration.getJeiHelpers().getGuiHelper();
        var registry = MachineTypeRegistry.INSTANCE;
        for (var machine : registry.types()) {
            var recipes = registry.assembly(machine).computerRecipes();
            if (recipes.isEmpty() && machine.computerInputRecipes().isEmpty()) continue;
            var materials = new ObjectArrayList<MachineRecipe>(recipes.size() + machine.computerInputRecipes().size());
            for (var recipe : recipes) {
                materials.add(recipe.materialRecipe());
                descriptions.computeIfAbsent(recipe.materialRecipe(), _ -> describe(recipe));
            }
            for (var recipe : machine.computerInputRecipes()) {
                materials.add(recipe.inputDefinition());
                descriptions.put(recipe.inputDefinition(), displayProviders.describe(recipe.inputDefinition()));
            }
            var id = new ResourceLocation(Tags.MOD_ID, "computer/" + machine.id().getNamespace() + "/" + machine.id().getPath());
            var pool = new DefaultRecipePool(id, machine.displayName().getTranslationKey(), materials);
            var page = new AutoLayoutRecipePage(pool, helper, descriptions::get, JeiRenderOptions.defaults(),
                AutoLayoutRecipePage.minecraftFont(), false);
            pages.add(new Page(machine, page));
            registration.addRecipeCategories(page);
        }
    }

    private static JeiRecipeDisplay describe(ComputerRecipe recipe) {
        var display = new JeiRecipeDisplay.Builder();
        for (var stack : recipe.unitInputs()) display.resource(stack, INPUT, of(JeiText.ComputerUnit));
        display.information(of(JeiText.ComputerSupplyRecipe));
        for (var entry : recipe.unitResources().reference2DoubleEntrySet()) {
            if (entry.getDoubleValue() == 0) continue;
            var resource = entry.getKey();
            display.information(of(JeiText.ComputerInternalSupply,
                resource.displayName(), resource.format(entry.getDoubleValue())));
        }
        return display.build();
    }

    Collection<JeiRecipeDisplay> displays() {
        return descriptions.values();
    }

    List<RecipePoolJeiRecipe> createWrappers(List<IngredientConverter<?>> converters, IIngredientRegistry registry,
                                             JeiQueryIdentityIndex identities, RecipePoolJeiRecipe.DisplayToken token) {
        var result = new ObjectArrayList<RecipePoolJeiRecipe>();
        for (var entry : pages) {
            var page = entry.category();
            if (!HeiRecipeGeneration.categoryDisabled(page.getUid()))
                appendWrappers(page, converters, registry, identities, token, result);
        }
        return result;
    }

    private void appendWrappers(AutoLayoutRecipePage page, List<IngredientConverter<?>> converters,
                                IIngredientRegistry registry, JeiQueryIdentityIndex identities,
                                RecipePoolJeiRecipe.DisplayToken token, List<RecipePoolJeiRecipe> result) {
        for (var recipe : page.pool().recipes()) {
            var display = descriptions.get(recipe);
            var ingredients = new JeiRecipeIngredients(display, converters, registry, identities);
            var wrapper = page.createRecipe(recipe, display, ingredients);
            wrapper.bindDisplayToken(token);
            result.add(wrapper);
        }
    }

    List<RecipePoolJeiRecipe> registerRecipes(IModRegistry registration, List<IngredientConverter<?>> converters,
                                              JeiQueryIdentityIndex identities, RecipePoolJeiRecipe.DisplayToken token) {
        var result = new ObjectArrayList<RecipePoolJeiRecipe>();
        for (var entry : pages) {
            var page = entry.category();
            if (HeiRecipeGeneration.categoryDisabled(page.getUid())) continue;
            var wrappers = new ObjectArrayList<RecipePoolJeiRecipe>();
            appendWrappers(page, converters, registration.getIngredientRegistry(), identities, token, wrappers);
            registration.addRecipes(wrappers, page.getUid());
            result.addAll(wrappers);
            for (var block : MachineControllerBlocks.all()) {
                if (block.type() == entry.machine())
                    registration.addRecipeCatalyst(new ItemStack(block), page.getUid());
            }
        }
        return result;
    }

    private record Page(MachineType machine, AutoLayoutRecipePage category) {
    }
}
