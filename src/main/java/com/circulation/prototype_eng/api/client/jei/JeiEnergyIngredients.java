package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Energy;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role;
import com.circulation.prototype_eng.localization.JeiEnergyText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** PM's FE query ingredient: item icons in the list, transparent recipe slots over the energy widget. */
@SideOnly(Side.CLIENT)
public final class JeiEnergyIngredients {
    public static final IIngredientType<Energy> TYPE = () -> Energy.class;
    public static final Helper HELPER = new Helper();
    private static final IIngredientRenderer<Energy> SLOT = new Renderer(false);

    private JeiEnergyIngredients() {
    }

    public static List<Energy> entries() {
        return List.of(new Energy(1, Role.INPUT, false), new Energy(1, Role.INPUT, true), new Energy(1, Role.OUTPUT, false), new Energy(1, Role.OUTPUT, true));
    }

    public static void register(IModIngredientRegistration registration) {
        registration.register(TYPE, entries(), HELPER, new Renderer(true));
    }

    public static List<Energy> values(JeiRecipeDisplay display, Role role) {
        var result = new ObjectArrayList<Energy>();
        for (var line : display.information())
            if (line.energy() != null && line.energy().role() == role) result.add(line.energy());
        return List.copyOf(result);
    }

    public static void index(IIngredients ingredients, JeiRecipeDisplay display) {
        ingredients.setInputs(TYPE, values(display, Role.INPUT));
        ingredients.setOutputs(TYPE, values(display, Role.OUTPUT));
    }

    /** Custom pages can attach the same transparent focus/tooltip area to their own energy drawing. */
    public static void slot(IRecipeLayout layout, int index, Energy energy, int x, int y, int width, int height) {
        var group = layout.getIngredientsGroup(TYPE);
        group.init(index, energy.role() == Role.INPUT, SLOT, x, y, width, height, 0, 0);
        group.set(index, energy);
    }

    public static final class Helper implements IIngredientHelper<Energy> {
        @Override
        public @Nullable Energy getMatch(Iterable<Energy> ingredients, @NotNull Energy match) {
            for (var ingredient : ingredients)
                if (ingredient.role() == match.role() && ingredient.perTick() == match.perTick()) return ingredient;
            return null;
        }

        @Override
        public @NotNull String getDisplayName(@NotNull Energy ingredient) {
            return JeiEnergyText.Display.getLocal((ingredient.role() == Role.INPUT ? JeiEnergyText.Consume : JeiEnergyText.Produce).getLocal(),
                (ingredient.perTick() ? JeiEnergyText.PerTick : JeiEnergyText.Once).getLocal());
        }

        @Override
        public @NotNull String getUniqueId(@NotNull Energy ingredient) {
            return Tags.MOD_ID + ":pm_energy:" + (ingredient.role() == Role.INPUT ? "consume" : "produce") + ":" + (ingredient.perTick() ? "per_tick" : "once");
        }

        @Override
        public @NotNull String getWildcardId(@NotNull Energy ingredient) {
            return getUniqueId(ingredient);
        }

        @Override
        public @NotNull String getModId(@NotNull Energy ingredient) {
            return Tags.MOD_ID;
        }

        @Override
        public @NotNull String getResourceId(@NotNull Energy ingredient) {
            return "pm_energy";
        }

        @Override
        public @NotNull Energy copyIngredient(@NotNull Energy ingredient) {
            return ingredient;
        }

        @Override
        public @NotNull String getErrorInfo(@Nullable Energy ingredient) {
            return String.valueOf(ingredient);
        }
    }

    private record Renderer(boolean list) implements IIngredientRenderer<Energy> {
        @Override
        public void render(@NotNull Minecraft minecraft, int x, int y, @Nullable Energy ingredient) {
            if (list && ingredient != null)
                minecraft.getRenderItem().renderItemIntoGUI(new ItemStack(ingredient.role() == Role.INPUT ? Items.REDSTONE : Items.GLOWSTONE_DUST), x, y);
        }

        @Override
        public @NotNull List<String> getTooltip(@NotNull Minecraft minecraft, @NotNull Energy ingredient, @NotNull ITooltipFlag flag) {
            return new ObjectArrayList<>(List.of(HELPER.getDisplayName(ingredient), (ingredient.perTick() ? JeiEnergyText.TickAmount : JeiEnergyText.OnceAmount).getLocal(ingredient.amount())));
        }
    }
}
