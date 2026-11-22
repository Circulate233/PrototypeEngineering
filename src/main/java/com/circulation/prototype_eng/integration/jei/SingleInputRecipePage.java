package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.ImportedRecipePool;
import mezz.jei.api.IGuiHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

@SideOnly(Side.CLIENT)
public final class SingleInputRecipePage extends AutoLayoutRecipePage {
    public SingleInputRecipePage(SimpleRecipePool pool, IGuiHelper helper, RecipeJeiDisplays displays, JeiRenderOptions options,
                                 RecipeInformationLayout.Font font) {
        this((RecipePool) pool, helper, displays, options, font);
    }

    public SingleInputRecipePage(SimpleRecipePool pool, IGuiHelper helper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options) {
        this((RecipePool) pool, helper, displays, options);
    }

    public SingleInputRecipePage(RecipePool pool, IGuiHelper helper, RecipeJeiDisplays displays, JeiRenderOptions options,
                                 RecipeInformationLayout.Font font) {
        this(pool, helper, displays::describe, options, font);
    }

    public SingleInputRecipePage(RecipePool pool, IGuiHelper helper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options) {
        this(pool, helper, displays::describe, options, minecraftFont());
    }

    public SingleInputRecipePage(RecipePool pool, IGuiHelper helper, RecipeJeiDisplays.Snapshot displays, JeiRenderOptions options,
                                 RecipeInformationLayout.Font font) {
        this(pool, helper, displays::describe, options, font);
    }

    private SingleInputRecipePage(RecipePool pool, IGuiHelper helper, Function<MachineRecipe, JeiRecipeDisplay> describe,
                                  JeiRenderOptions options, RecipeInformationLayout.Font font) {
        super(pool, helper, describe, options, font, compactLayout(pool, describe, options));
    }

    public static boolean supports(RecipePool pool) {
        return pool instanceof SimpleRecipePool
            || pool instanceof ImportedRecipePool imported && imported.implementation() instanceof SimpleRecipePool;
    }

    private static boolean compactLayout(RecipePool pool, Function<MachineRecipe, JeiRecipeDisplay> describe, JeiRenderOptions options) {
        if (!supports(pool)) throw new IllegalArgumentException("Single-input JEI page requires a single-input pool: " + pool.id());
        for (var recipe : pool.recipes()) {
            if (RecipePanelLayout.maximumSlots(describe.apply(recipe), JeiRecipeDisplay.Role.INPUT, options, Integer.MAX_VALUE) > 1)
                return false;
        }
        return true;
    }

    @Override
    public @Nullable PreparedUpdate prepareUpdate(@NotNull RecipePool target, RecipeJeiDisplays.@NotNull Snapshot displays) {
        return supports(target) ? prepareLayoutUpdate(target, displays, compactLayout(target, displays::describe, renderOptions())) : null;
    }
}
