package com.circulation.prototype_eng.api.client.jei;

import ae2.api.client.AEKeyRendering;
import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.api.stacks.GenericStack;
import ae2.client.gui.me.common.StackSizeRenderer;
import ae2.core.localization.GuiText;
import ae2.util.Platform;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.plugins.vanilla.ingredients.fluid.FluidStackRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * AE2S converter bridge. Indexes all declared rows, independently of the current visible panel.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class JeiRecipeIngredients {
    private final ObjectArrayList<Bucket<?>> buckets = new ObjectArrayList<>();
    private final Reference2ObjectOpenHashMap<JeiRecipeDisplay.Resource, Bucket<?>> converted = new Reference2ObjectOpenHashMap<>();
    private final ReferenceOpenHashSet<JeiRecipeDisplay.Resource> queryConflicts = new ReferenceOpenHashSet<>();
    @Nullable
    private final IIngredientRegistry nativeRegistry;

    public JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                                IIngredientRegistry registry, JeiQueryIdentityIndex identities) {
        this(display, converters, registry.getRegisteredIngredientTypes(), registry, identities, new WrappedIngredientConverter(), AEItemKey.class::isInstance);
    }

    JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                         Collection<?> registeredTypes, JeiQueryIdentityIndex identities) {
        this(display, converters, registeredTypes, null, identities, null, _ -> true);
    }

    JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                         Collection<?> registeredTypes, JeiQueryIdentityIndex identities, IngredientConverter<?> fallback) {
        this(display, converters, registeredTypes, null, identities, fallback, _ -> true);
    }

    JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                         Collection<?> registeredTypes, JeiQueryIdentityIndex identities, IngredientConverter<?> fallback,
                         Predicate<AEKey> nativeKeys) {
        this(display, converters, registeredTypes, null, identities, fallback, nativeKeys);
    }

    private JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                                 Collection<?> registeredTypes, @Nullable IIngredientRegistry registry,
                                 JeiQueryIdentityIndex identities, @Nullable IngredientConverter<?> fallback, Predicate<AEKey> nativeKeys) {
        nativeRegistry = registry;
        for (var converter : converters) {
            if (registeredTypes.contains(converter.getIngredientType())) {
                buckets.add(new Bucket<>(converter));
            }
        }
        for (var row : display.resources()) {
            if (!nativeKeys.test(row.stack().what())) continue;
            for (var bucket : buckets) {
                if (bucket.add(row, identities, queryConflicts)) {
                    converted.put(row, bucket);
                    break;
                }
            }
        }
        if (fallback != null && registeredTypes.contains(fallback.getIngredientType())) addFallback(display, fallback);
    }

    private static List<String> genericTooltip(JeiRecipeDisplay.Resource resource) {
        var key = resource.stack().what();
        var lines = new ObjectArrayList<String>();
        if (AEKeyRendering.get(key.getType()) != null) {
            for (var line : AEKeyRendering.getTooltip(key)) lines.add(line.getFormattedText());
        } else lines.add(key.getDisplayName().getFormattedText());
        appendRecipeDetails(lines, resource, key.formatAmount(resource.stack().amount(), AmountFormat.FULL));
        var mod = Platform.getModName(key.getModId());
        if (!mod.isEmpty() && !lines.contains(mod)) lines.add(GuiText.CellViewMod.getLocal(mod));
        return lines;
    }

    public static List<String> tooltip(JeiRecipeDisplay.Resource resource) {
        var lines = new ObjectArrayList<String>();
        lines.add(resource.stack().what().getDisplayName().getFormattedText());
        appendRecipeDetails(lines, resource, Long.toString(resource.stack().amount()));
        return lines;
    }

    private static void appendRecipeDetails(List<String> lines, JeiRecipeDisplay.Resource resource, String amount) {
        JeiRecipeDisplay.Text timing = null;
        for (var detail : resource.details()) if (isTiming(detail)) timing = detail;
        boolean input = resource.role() == JeiRecipeDisplay.Role.INPUT;
        lines.add(timing == null ? (input ? JeiText.Consume : JeiText.Produce).getLocal(amount)
            : (input ? JeiText.ConsumeAt : JeiText.ProduceAt).getLocal(timing.local(), amount));
        for (var detail : resource.details()) {
            if (isTiming(detail)) continue;
            if (detail.text() == JeiText.Chance) {
                lines.add(new JeiRecipeDisplay.Text(input ? JeiText.ConsumeChance : JeiText.ProduceChance,
                    detail.arguments(), detail.context(), detail.energy()).local());
            } else lines.add(detail.local());
        }
    }

    private static boolean isTiming(JeiRecipeDisplay.Text text) {
        return text.text() == JeiText.Start || text.text() == JeiText.Tick || text.text() == JeiText.End;
    }

    /**
     * Reuses AE2S's native compact long label; the tooltip always retains exact raw units.
     */
    public static void renderAmount(Minecraft minecraft, int x, int y, JeiRecipeDisplay.Resource resource) {
        if (resource.stack().amount() != 1) {
            StackSizeRenderer.renderSizeLabel(minecraft.fontRenderer, x, y,
                resource.stack().what().formatAmount(resource.stack().amount(), AmountFormat.SLOT), false);
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
        }
    }

    public static void renderChance(Minecraft minecraft, int x, int y, JeiRecipeDisplay.Resource resource, JeiRenderOptions options) {
        var chance = resource.chance();
        if (!options.chanceOverlay() || chance == null || chance.percent() == 100 && !chance.attributeDriven()) return;
        String percent = String.format(Locale.ROOT, "%.2f", chance.percent());
        while (percent.endsWith("0")) percent = percent.substring(0, percent.length() - 1);
        if (percent.endsWith(".")) percent = percent.substring(0, percent.length() - 1);
        String label = percent + "%" + (chance.attributeDriven() ? "*" : "");
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x + 1, y + 1, 0);
            GlStateManager.scale(.6, .6, 1);
            minecraft.fontRenderer.drawString(label, 0, 0, 0xffead76c, true);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.enableBlend();
        }
    }

    /** Same layer order for a key without an exact native query converter. */
    public static void renderFallback(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource resource, JeiRenderOptions options) {
        slot.skin().background(minecraft, slot, resource);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(slot.x() + slot.inset(), slot.y() + slot.inset(), 0);
            GlStateManager.scale((slot.width() - slot.inset() * 2) / 16.0, (slot.height() - slot.inset() * 2) / 16.0, 1);
            if (AEKeyRendering.get(resource.stack().what().getType()) != null) {
                AEKeyRendering.drawInGui(minecraft, 0, 0, resource.stack().what());
            } else {
                minecraft.fontRenderer.drawString("?", 4, 4, 0xaa2222);
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.enableBlend();
            GlStateManager.disableDepth();
            GlStateManager.disableLighting();
            GlStateManager.color(1, 1, 1, 1);
        }
        foreground(minecraft, slot, resource, options);
    }

    private static void foreground(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource row, JeiRenderOptions options) {
        slot.skin().foreground(minecraft, slot, row);
        renderChance(minecraft, slot.x(), slot.y(), row, options);
        renderAmount(minecraft, slot.x() + slot.width() - slot.inset() - 16, slot.y() + slot.height() - slot.inset() - 16, row);
    }

    @SuppressWarnings("unchecked") // The exact IIngredientType binding also owns the native group's generic type.
    private <T> void addFallback(JeiRecipeDisplay display, IngredientConverter<T> fallback) {
        Bucket<T> target = null;
        for (var bucket : buckets)
            if (bucket.converter.getIngredientType() == fallback.getIngredientType()) {
                target = (Bucket<T>) bucket;
                break;
            }
        if (target == null) {
            target = new Bucket<>(fallback);
            buckets.add(target);
        }
        target.fallback = fallback;
        for (var row : display.resources())
            if (!converted.containsKey(row)) {
                // Like AE2S CellViewRecipe: preserve the actual key and long amount in its wrapped ingredient.
                var ingredient = fallback.getIngredientFromStack(row.stack());
                if (ingredient != null) {
                    target.put(row, ingredient);
                    target.wrapped.add(row);
                    converted.put(row, target);
                }
            }
    }

    /**
     * A registered native ingredient, or AE2S's wrapped GenericStack fallback, is present in the JEI group.
     */
    public boolean supports(JeiRecipeDisplay.Resource resource) {
        return converted.containsKey(resource);
    }

    public boolean queryConflict(JeiRecipeDisplay.Resource resource) {
        return queryConflicts.contains(resource);
    }

    /** Native alternatives must use one ingredient type; equal keys retain their individual rows. */
    public boolean canCombine(List<JeiRecipeDisplay.Resource> rows) {
        if (rows.isEmpty()) return false;
        var bucket = converted.get(rows.getFirst());
        if (bucket == null) return false;
        for (var row : rows) {
            if (converted.get(row) != bucket) return false;
        }
        return true;
    }

    public void index(IIngredients ingredients) {
        for (var bucket : buckets) {
            bucket.index(ingredients);
        }
    }

    /**
     * Custom pages can use native groups directly, or initialize their own coordinates through this bridge.
     */
    public void initialize(IRecipeLayout layout, int inputSlots, int outputSlots, int columns,
                           int inputX, int outputX, int y, int stride) {
        var inputs = new ObjectArrayList<JeiSlot>();
        var outputs = new ObjectArrayList<JeiSlot>();
        for (int i = 0; i < inputSlots + outputSlots; i++) {
            boolean input = i < inputSlots;
            int relative = input ? i : i - inputSlots;
            (input ? inputs : outputs).add(new JeiSlot((input ? inputX : outputX) + relative % columns * stride,
                y + relative / columns * stride, 16, 16, 0, JeiSlot.PLAIN));
        }
        initialize(layout, inputs, outputs, JeiRenderOptions.defaults());
    }

    public void initialize(IRecipeLayout layout, List<JeiSlot> inputs, List<JeiSlot> outputs, JeiRenderOptions options) {
        for (var bucket : buckets) bucket.initialize(layout, inputs, outputs, nativeRegistry, options);
    }

    /**
     * Every slot is explicitly cleared before filling a panel, including slots absent in this recipe.
     */
    public void show(IRecipeLayout layout, List<JeiRecipeDisplay.Resource> inputs, List<JeiRecipeDisplay.Resource> outputs,
                     int inputSlots, int outputSlots) {
        var inputGroups = new ObjectArrayList<List<JeiRecipeDisplay.Resource>>();
        var outputGroups = new ObjectArrayList<List<JeiRecipeDisplay.Resource>>();
        for (var row : inputs) inputGroups.add(ObjectArrayList.of(row));
        for (var row : outputs) outputGroups.add(ObjectArrayList.of(row));
        showGroups(layout, inputGroups, outputGroups, inputSlots, outputSlots);
    }

    /** Candidates cycle inside native groups; index() still contains every original candidate. */
    public void showGroups(IRecipeLayout layout, List<List<JeiRecipeDisplay.Resource>> inputs,
                           List<List<JeiRecipeDisplay.Resource>> outputs, int inputSlots, int outputSlots) {
        if (inputs.size() > inputSlots || outputs.size() > outputSlots) {
            throw new IllegalArgumentException("JEI panel exceeds initialized slot capacity");
        }
        for (var bucket : buckets) {
            bucket.show(layout, inputs, outputs, inputSlots, outputSlots);
        }
    }

    private static final class WrappedIngredientConverter implements IngredientConverter<ItemStack> {
        @Override
        public IIngredientType<ItemStack> getIngredientType() {
            return VanillaTypes.ITEM;
        }

        @Override
        public ItemStack getIngredientFromStack(GenericStack stack) {
            return GenericStack.wrapInItemStack(stack);
        }

        @Override
        public @Nullable GenericStack getStackFromIngredient(ItemStack ingredient) {
            return GenericStack.unwrapItemStack(ingredient);
        }
    }

    private static class Bucket<T> {
        private final IngredientConverter<T> converter;
        private final ReferenceOpenHashSet<JeiRecipeDisplay.Resource> wrapped = new ReferenceOpenHashSet<>();
        private final Reference2ObjectOpenHashMap<JeiRecipeDisplay.Resource, T> entries = new Reference2ObjectOpenHashMap<>();
        private final ObjectArrayList<List<T>> inputs = new ObjectArrayList<>();
        private final ObjectArrayList<List<T>> outputs = new ObjectArrayList<>();
        private final ObjectArrayList<List<JeiRecipeDisplay.Resource>> visible = new ObjectArrayList<>();
        @Nullable
        private IngredientConverter<T> fallback;

        Bucket(IngredientConverter<T> converter) {
            this.converter = converter;
        }

        boolean add(JeiRecipeDisplay.Resource resource, JeiQueryIdentityIndex identities,
                    ReferenceOpenHashSet<JeiRecipeDisplay.Resource> queryConflicts) {
            // JEI's vanilla icons have int counts. Use one-unit identity icons; real long amounts stay on the row.
            var ingredient = converter.getIngredientFromStack(new GenericStack(resource.stack().what(), 1));
            if (ingredient == null) {
                return false;
            }
            var roundTrip = converter.getStackFromIngredient(ingredient);
            if (roundTrip == null || !resource.stack().what().equals(roundTrip.what())) {
                return false;
            }
            if (!identities.supports(converter, ingredient, resource.stack().what())) {
                if (identities.conflicts(converter, ingredient)) {
                    queryConflicts.add(resource);
                }
                return false;
            }
            put(resource, ingredient);
            return true;
        }

        private void put(JeiRecipeDisplay.Resource resource, T ingredient) {
            entries.put(resource, ingredient);
            (resource.role() == JeiRecipeDisplay.Role.INPUT ? inputs : outputs).add(ObjectArrayList.of(ingredient));
        }

        void index(IIngredients ingredients) {
            ingredients.setInputLists(converter.getIngredientType(), inputs);
            ingredients.setOutputLists(converter.getIngredientType(), outputs);
        }

        void initialize(IRecipeLayout layout, List<JeiSlot> inputs, List<JeiSlot> outputs,
                        @Nullable IIngredientRegistry registry, JeiRenderOptions options) {
            var group = converter.getIngredientGroup(layout);
            for (int i = 0; i < inputs.size() + outputs.size(); i++) {
                boolean input = i < inputs.size();
                var slot = input ? inputs.get(i) : outputs.get(i - inputs.size());
                if (registry == null) {
                    group.init(i, input, slot.x() + slot.inset(), slot.y() + slot.inset());
                } else {
                    // HEI draws ingredients after wrapper.drawInfo; amounts must overlay in the native renderer.
                    group.init(i, input, renderer(registry.getIngredientRenderer(converter.getIngredientType()), i, slot, options),
                        slot.x(), slot.y(), slot.width(), slot.height(), 0, 0);
                }
            }
            group.addTooltipCallback((slot, _, ingredient, lines) -> {
                var row = displayed(slot, ingredient);
                if (row != null) {
                    if (wrapped.contains(row)) {
                        lines.clear();
                        lines.addAll(genericTooltip(row));
                    } else {
                        var details = tooltip(row);
                        lines.addAll(details.subList(1, details.size()));
                    }
                }
            });
        }

        @Nullable
        private JeiRecipeDisplay.Resource displayed(int slot, T ingredient) {
            if (slot < 0 || slot >= visible.size()) return null;
            for (var row : visible.get(slot)) if (entries.get(row) == ingredient) return row;
            var stack = fallback == null ? null : fallback.getStackFromIngredient(ingredient);
            if (stack == null) stack = converter.getStackFromIngredient(ingredient);
            if (stack != null) {
                for (var row : visible.get(slot)) if (row.stack().what().equals(stack.what())) return row;
            }
            return null;
        }

        private IIngredientRenderer<T> renderer(IIngredientRenderer<T> original, int index, JeiSlot slot, JeiRenderOptions options) {
            // The converter retains a one-unit query identity. Capacity one fills the entire native
            // panel and tiles the still sprite at its real dimensions, without narrowing the row's long amount.
            var fluid = new FluidStackRenderer(1, false,
                slot.width() - slot.inset() * 2, slot.height() - slot.inset() * 2, null);
            return new IIngredientRenderer<>() {
                @Override
                public void render(Minecraft minecraft, int x, int y, @Nullable T ingredient) {
                    if (ingredient != null) {
                        var row = displayed(index, ingredient);
                        // Native coordinates include the category origin, while skin slots are category-relative.
                        var located = new JeiSlot(x, y, slot.width(), slot.height(), slot.inset(), slot.skin());
                        if (row != null && wrapped.contains(row)) {
                            renderFallback(minecraft, located, row, options);
                            return;
                        }
                        if (row != null) located.skin().background(minecraft, located, row);
                        GlStateManager.pushMatrix();
                        try {
                            GlStateManager.translate(x + slot.inset(), y + slot.inset(), 0);
                            if (ingredient instanceof FluidStack stack) {
                                fluid.render(minecraft, 0, 0, stack);
                            } else {
                                GlStateManager.scale((slot.width() - slot.inset() * 2) / 16.0, (slot.height() - slot.inset() * 2) / 16.0, 1);
                                original.render(minecraft, 0, 0, ingredient);
                            }
                        } finally {
                            GlStateManager.popMatrix();
                        }
                        if (row != null) foreground(minecraft, located, row, options);
                    }
                }

                @Override
                public List<String> getTooltip(Minecraft minecraft, T ingredient, ITooltipFlag tooltipFlag) {
                    var row = displayed(index, ingredient);
                    if (row != null && wrapped.contains(row)) return ObjectArrayList.of();
                    return original.getTooltip(minecraft, ingredient, tooltipFlag);
                }

                @Override
                public FontRenderer getFontRenderer(Minecraft minecraft, T ingredient) {
                    var row = displayed(index, ingredient);
                    if (row != null && wrapped.contains(row)) return minecraft.fontRenderer;
                    return original.getFontRenderer(minecraft, ingredient);
                }
            };
        }

        void show(IRecipeLayout layout, List<List<JeiRecipeDisplay.Resource>> inputRows, List<List<JeiRecipeDisplay.Resource>> outputRows,
                  int inputSlots, int outputSlots) {
            var group = converter.getIngredientGroup(layout);
            visible.clear();
            for (int i = 0; i < inputSlots + outputSlots; i++) {
                visible.add(ObjectArrayList.of());
                group.set(i, (T) null);
            }
            fill(group, inputRows, 0);
            fill(group, outputRows, inputSlots);
        }

        private void fill(IGuiIngredientGroup<T> group, List<List<JeiRecipeDisplay.Resource>> rows, int offset) {
            for (int i = 0; i < rows.size(); i++) {
                var values = new ObjectArrayList<T>();
                var shown = new ObjectArrayList<JeiRecipeDisplay.Resource>();
                for (var row : rows.get(i)) {
                    var ingredient = entries.get(row);
                    if (ingredient != null) {
                        values.add(ingredient);
                        shown.add(row);
                    }
                }
                visible.set(offset + i, shown);
                if (!values.isEmpty()) group.set(offset + i, values);
            }
        }
    }
}
