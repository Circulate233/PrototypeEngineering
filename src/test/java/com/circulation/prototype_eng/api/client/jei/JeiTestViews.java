package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import com.circulation.prototype_eng.integration.jei.RecipeInformationLayout;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.ICraftingGridHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IDrawableAnimated;
import mezz.jei.api.gui.IDrawableBuilder;
import mezz.jei.api.gui.IDrawableStatic;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.gui.ITickTimer;
import mezz.jei.api.gui.ITooltipCallback;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Explicit native API fakes; they do not bootstrap Forge/Minecraft or use reflection.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class JeiTestViews {
    static final RecipeInformationLayout.Font FONT = new RecipeInformationLayout.Font(9,
        (text, _) -> List.of(text.text().getTranslationKey()));

    static AutoLayoutRecipePage page(RecipePool pool, IGuiHelper helper, RecipeJeiDisplays displays) {
        return new AutoLayoutRecipePage(pool, helper, displays, JeiRenderOptions.defaults(), FONT);
    }

    static class IdentityHelper<T> implements IIngredientHelper<T> {
        private final Function<T, String> uid;

        IdentityHelper(Function<T, String> uid) {
            this.uid = uid;
        }

        @Override
        public String getUniqueId(T ingredient) {
            return uid.apply(ingredient);
        }

        @Override
        public @Nullable T getMatch(Iterable<T> ignored, T ignoredValue) {
            throw new AssertionError("Unexpected helper matching");
        }

        @Override
        public String getDisplayName(T ignored) {
            throw new AssertionError("Unexpected helper display");
        }

        @Override
        public String getWildcardId(T ignored) {
            throw new AssertionError("Unexpected helper wildcard");
        }

        @Override
        public String getModId(T ignored) {
            throw new AssertionError("Unexpected helper mod id");
        }

        @Override
        public String getResourceId(T ignored) {
            throw new AssertionError("Unexpected helper resource id");
        }

        @Override
        public T copyIngredient(T ignored) {
            throw new AssertionError("Unexpected helper copy");
        }

        @Override
        public String getErrorInfo(@Nullable T ignored) {
            throw new AssertionError("Unexpected helper error info");
        }
    }

    record Blank(int width, int height) implements IDrawableStatic {
        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public void draw(Minecraft ignored1, int ignored2, int ignored3) {
        }

        @Override
        public void draw(Minecraft ignored4, int ignored5, int ignored6, int ignored7, int ignored8, int ignored9, int ignored10) {
        }
    }

    static class Helper implements IGuiHelper {
        @Override
        public IDrawableStatic createBlankDrawable(int width, int height) {
            return new Blank(width, height);
        }

        @Override
        public IDrawableBuilder drawableBuilder(ResourceLocation ignored11, int ignored12, int ignored13, int ignored14, int ignored15) {
            throw new AssertionError("Unexpected textured drawable");
        }

        @Override
        public IDrawableAnimated createAnimatedDrawable(IDrawableStatic ignored16, int ignored17, IDrawableAnimated.StartDirection ignored18, boolean ignored19) {
            throw new AssertionError("Unexpected animated drawable");
        }

        @Override
        public IDrawableStatic getSlotDrawable() {
            throw new AssertionError("Unexpected slot drawable");
        }

        @Override
        public <V> IDrawable createDrawableIngredient(V ignored20) {
            throw new AssertionError("Unexpected icon drawable");
        }

        @Override
        public ICraftingGridHelper createCraftingGridHelper(int ignored21, int ignored22) {
            throw new AssertionError("Unexpected crafting grid");
        }

        @Override
        public ITickTimer createTickTimer(int ignored23, int ignored24, boolean ignored25) {
            throw new AssertionError("Unexpected tick timer");
        }
    }

    static class Ingredients implements IIngredients {
        private final Reference2ObjectOpenHashMap<IIngredientType<?>, List<?>> inputs = new Reference2ObjectOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<IIngredientType<?>, List<?>> outputs = new Reference2ObjectOpenHashMap<>();

        @Override
        public <T> void setInput(IIngredientType<T> type, T input) {
            setInputLists(type, ObjectArrayList.of(ObjectArrayList.of(input)));
        }

        @Override
        public <T> void setInputs(IIngredientType<T> type, List<T> values) {
            var lists = new ObjectArrayList<List<T>>();
            for (var value : values) {
                lists.add(ObjectArrayList.of(value));
            }
            setInputLists(type, lists);
        }

        @Override
        public <T> void setInputLists(IIngredientType<T> type, List<List<T>> values) {
            inputs.put(type, values);
        }

        @Override
        public <T> void setOutput(IIngredientType<T> type, T value) {
            setOutputLists(type, ObjectArrayList.of(ObjectArrayList.of(value)));
        }

        @Override
        public <T> void setOutputs(IIngredientType<T> type, List<T> values) {
            var lists = new ObjectArrayList<List<T>>();
            for (var value : values) {
                lists.add(ObjectArrayList.of(value));
            }
            setOutputLists(type, lists);
        }

        @Override
        public <T> void setOutputLists(IIngredientType<T> type, List<List<T>> values) {
            outputs.put(type, values);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<List<T>> getInputs(IIngredientType<T> type) {
            return (List<List<T>>) inputs.getOrDefault(type, ObjectArrayList.of());
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<List<T>> getOutputs(IIngredientType<T> type) {
            return (List<List<T>>) outputs.getOrDefault(type, ObjectArrayList.of());
        }

        @Override
        public <T> void setInput(Class<? extends T> ignored26, T ignored27) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> void setInputs(Class<? extends T> ignored28, List<T> ignored29) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> void setInputLists(Class<? extends T> ignored30, List<List<T>> ignored31) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> void setOutput(Class<? extends T> ignored32, T ignored33) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> void setOutputs(Class<? extends T> ignored34, List<T> ignored35) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> void setOutputLists(Class<? extends T> ignored36, List<List<T>> ignored37) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> List<List<T>> getInputs(Class<? extends T> ignored38) {
            throw new AssertionError("Legacy API");
        }

        @Override
        public <T> List<List<T>> getOutputs(Class<? extends T> ignored39) {
            throw new AssertionError("Legacy API");
        }
    }

    static class Group<T> implements IGuiIngredientGroup<T> {
        final Int2ObjectOpenHashMap<List<T>> values = new Int2ObjectOpenHashMap<>();
        final Int2BooleanOpenHashMap roles = new Int2BooleanOpenHashMap();
        final ObjectArrayList<ITooltipCallback<T>> tooltips = new ObjectArrayList<>();
        final Int2ObjectOpenHashMap<Slot<T>> slots = new Int2ObjectOpenHashMap<>();

        @Override
        public void set(IIngredients ignored40) {
            throw new AssertionError("Unexpected bulk implicit mapping");
        }

        @Override
        public void set(int slot, @Nullable List<T> ingredients) {
            if (ingredients == null) {
                values.remove(slot);
            } else {
                values.put(slot, ingredients);
            }
            var previous = slots.get(slot);
            if (previous != null) slots.put(slot, new Slot<>(previous.input, previous.x, previous.y,
                previous.width, previous.height, ingredients == null ? List.of() : ingredients));
        }

        @Override
        public void set(int slot, @Nullable T ingredient) {
            set(slot, ingredient == null ? null : ObjectArrayList.of(ingredient));
        }

        @Override
        public void setBackground(int ignored41, IDrawable ignored42) {
        }

        @Override
        public void addTooltipCallback(ITooltipCallback<T> callback) {
            tooltips.add(callback);
        }

        @Override
        public Map<Integer, ? extends IGuiIngredient<T>> getGuiIngredients() {
            return slots;
        }

        @Override
        public void init(int slot, boolean input, int x, int y) {
            roles.put(slot, input);
            slots.put(slot, new Slot<>(input, x, y, 16, 16, List.of()));
        }

        @Override
        public void init(int slot, boolean input, IIngredientRenderer<T> ignored45, int x, int y, int width, int height, int ignored50, int ignored51) {
            roles.put(slot, input);
            slots.put(slot, new Slot<>(input, x, y, width, height, List.of()));
        }

        @Override
        public void setOverrideDisplayFocus(@Nullable IFocus<T> ignored52) {
        }
    }

    record Slot<T>(boolean input, int x, int y, int width, int height, List<T> ingredients) implements IGuiIngredient<T> {
        @Override public @Nullable T getDisplayedIngredient() { return ingredients.isEmpty() ? null : ingredients.getFirst(); }
        @Override public List<T> getAllIngredients() { return ingredients; }
        @Override public boolean isInput() { return input; }
        @Override public void drawHighlight(Minecraft ignored, Color ignoredColor, int ignoredX, int ignoredY) {}
    }

    static class Layout implements IRecipeLayout {
        private final Reference2ObjectOpenHashMap<IIngredientType<?>, Group<?>> groups = new Reference2ObjectOpenHashMap<>();

        @SuppressWarnings("unchecked")
        <T> Group<T> group(IIngredientType<T> type) {
            return (Group<T>) groups.computeIfAbsent(type, ignored53 -> new Group<>());
        }

        @Override
        public <T> IGuiIngredientGroup<T> getIngredientsGroup(IIngredientType<T> type) {
            return group(type);
        }

        @Override
        public IGuiItemStackGroup getItemStacks() {
            throw new AssertionError("Untyped item API");
        }

        @Override
        public IGuiFluidStackGroup getFluidStacks() {
            throw new AssertionError("Untyped fluid API");
        }

        @Override
        public @Nullable IFocus<?> getFocus() {
            return null;
        }

        @Override
        public IRecipeCategory<?> getRecipeCategory() {
            throw new AssertionError("Unexpected category access");
        }

        @Override
        public void setRecipeTransferButton(int ignored54, int ignored55) {
        }

        @Override
        public void setShapeless() {
        }

        @Override
        public <T> IGuiIngredientGroup<T> getIngredientsGroup(Class<T> ignored56) {
            throw new AssertionError("Legacy API");
        }
    }
}
