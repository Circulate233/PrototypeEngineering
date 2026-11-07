package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.AmountFormat;
import ae2.client.gui.me.common.StackSizeRenderer;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import mezz.jei.api.gui.IGuiIngredientGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.IIngredientRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Collection;
import javax.annotation.ParametersAreNonnullByDefault;

import mcp.MethodsReturnNonnullByDefault;

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
        this(display, converters, registry.getRegisteredIngredientTypes(), registry, identities);
    }

    JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                         Collection<?> registeredTypes, JeiQueryIdentityIndex identities) {
        this(display, converters, registeredTypes, null, identities);
    }

    private JeiRecipeIngredients(JeiRecipeDisplay display, List<IngredientConverter<?>> converters,
                                 Collection<?> registeredTypes, @Nullable IIngredientRegistry registry,
                                 JeiQueryIdentityIndex identities) {
        nativeRegistry = registry;
        for (var converter : converters) {
            if (registeredTypes.contains(converter.getIngredientType())) {
                buckets.add(new Bucket<>(converter));
            }
        }
        for (var row : display.resources()) {
            for (var bucket : buckets) {
                if (bucket.add(row, identities, queryConflicts)) {
                    converted.put(row, bucket);
                    break;
                }
            }
        }
    }

    /**
     * Native helper query support after known-set collision screening, not full-domain AEKey equality.
     */
    public boolean supports(JeiRecipeDisplay.Resource resource) {
        return converted.containsKey(resource);
    }

    public boolean queryConflict(JeiRecipeDisplay.Resource resource) {
        return queryConflicts.contains(resource);
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
        for (var bucket : buckets) {
            bucket.initialize(layout, inputSlots, outputSlots, columns, inputX, outputX, y, stride, nativeRegistry);
        }
    }

    /**
     * Every slot is explicitly cleared before filling a panel, including slots absent in this recipe.
     */
    public void show(IRecipeLayout layout, List<JeiRecipeDisplay.Resource> inputs, List<JeiRecipeDisplay.Resource> outputs,
                     int inputSlots, int outputSlots) {
        if (inputs.size() > inputSlots || outputs.size() > outputSlots) {
            throw new IllegalArgumentException("JEI panel exceeds initialized slot capacity");
        }
        for (var bucket : buckets) {
            bucket.show(layout, inputs, outputs, inputSlots, outputSlots);
        }
    }

    public static List<String> tooltip(JeiRecipeDisplay.Resource resource) {
        var lines = new ObjectArrayList<String>();
        lines.add(resource.stack().what().getDisplayName().getFormattedText());
        lines.add(JeiText.Amount.getLocal(Long.toString(resource.stack().amount()), resource.stack().what().getAmountPerUnit()));
        for (var text : resource.details()) {
            lines.add(text.local());
        }
        return lines;
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

    private static class Bucket<T> {
        private final IngredientConverter<T> converter;
        private final Reference2ObjectOpenHashMap<JeiRecipeDisplay.Resource, T> entries = new Reference2ObjectOpenHashMap<>();
        private final ObjectArrayList<List<T>> inputs = new ObjectArrayList<>();
        private final ObjectArrayList<List<T>> outputs = new ObjectArrayList<>();
        private final ObjectArrayList<JeiRecipeDisplay.Resource> visible = new ObjectArrayList<>();

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
            entries.put(resource, ingredient);
            (resource.role() == JeiRecipeDisplay.Role.INPUT ? inputs : outputs).add(ObjectArrayList.of(ingredient));
            return true;
        }

        void index(IIngredients ingredients) {
            ingredients.setInputLists(converter.getIngredientType(), inputs);
            ingredients.setOutputLists(converter.getIngredientType(), outputs);
        }

        void initialize(IRecipeLayout layout, int inputSlots, int outputSlots, int columns,
                        int inputX, int outputX, int y, int stride, @Nullable IIngredientRegistry registry) {
            var group = converter.getIngredientGroup(layout);
            for (int i = 0; i < inputSlots + outputSlots; i++) {
                boolean input = i < inputSlots;
                int relative = input ? i : i - inputSlots;
                int x = (input ? inputX : outputX) + relative % columns * stride;
                int py = y + relative / columns * stride;
                if (registry == null) {
                    group.init(i, input, x, py);
                } else {
                    // HEI draws ingredients after wrapper.drawInfo; amounts must overlay in the native renderer.
                    group.init(i, input, renderer(registry.getIngredientRenderer(converter.getIngredientType()), i),
                        x, py, 16, 16, 0, 0);
                }
            }
            group.addTooltipCallback((slot, _, _, lines) -> {
                if (slot >= 0 && slot < visible.size() && visible.get(slot) != null) {
                    var details = tooltip(visible.get(slot));
                    lines.addAll(details.subList(1, details.size()));
                }
            });
        }

        private IIngredientRenderer<T> renderer(IIngredientRenderer<T> original, int slot) {
            return new IIngredientRenderer<>() {
                @Override
                public void render(Minecraft minecraft, int x, int y, @Nullable T ingredient) {
                    if (ingredient != null) {
                        original.render(minecraft, x, y, ingredient);
                        if (slot < visible.size() && visible.get(slot) != null) {
                            renderAmount(minecraft, x, y, visible.get(slot));
                        }
                    }
                }

                @Override
                public List<String> getTooltip(Minecraft minecraft, T ingredient, ITooltipFlag tooltipFlag) {
                    return original.getTooltip(minecraft, ingredient, tooltipFlag);
                }

                @Override
                public FontRenderer getFontRenderer(Minecraft minecraft, T ingredient) {
                    return original.getFontRenderer(minecraft, ingredient);
                }
            };
        }

        void show(IRecipeLayout layout, List<JeiRecipeDisplay.Resource> inputRows, List<JeiRecipeDisplay.Resource> outputRows,
                  int inputSlots, int outputSlots) {
            var group = converter.getIngredientGroup(layout);
            visible.clear();
            for (int i = 0; i < inputSlots + outputSlots; i++) {
                visible.add(null);
                group.set(i, (T) null);
            }
            fill(group, inputRows, 0);
            fill(group, outputRows, inputSlots);
        }

        private void fill(IGuiIngredientGroup<T> group, List<JeiRecipeDisplay.Resource> rows, int offset) {
            for (int i = 0; i < rows.size(); i++) {
                var ingredient = entries.get(rows.get(i));
                if (ingredient != null) {
                    visible.set(offset + i, rows.get(i));
                    group.set(offset + i, ingredient);
                }
            }
        }
    }
}
