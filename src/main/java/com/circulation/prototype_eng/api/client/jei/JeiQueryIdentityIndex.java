package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collection;
import java.util.List;

/**
 * Collision snapshot over every declared pool and currently registered ingredient, captured before
 * publishing wrappers. Queries retain HEI helper semantics; unknown later variants cannot be proven
 * globally injective by this snapshot. No subtype interpreter or execution identity is overwritten.
 */
@SideOnly(Side.CLIENT)
public class JeiQueryIdentityIndex {
    private final Reference2ObjectOpenHashMap<IIngredientType<?>, IIngredientHelper<?>> helpers = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<IIngredientType<?>, Object2ObjectOpenHashMap<String, AEKey>> keys = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<IIngredientType<?>, ObjectOpenHashSet<String>> collisions = new Reference2ObjectOpenHashMap<>();

    JeiQueryIdentityIndex() {
    }

    public static JeiQueryIdentityIndex capture(List<IngredientConverter<?>> converters, IIngredientRegistry registry,
                                                Collection<JeiRecipeDisplay> allPoolDisplays) {
        var index = new JeiQueryIdentityIndex();
        for (var converter : converters) {
            if (registry.getRegisteredIngredientTypes().contains(converter.getIngredientType())) {
                index.captureConverter(converter, registry, allPoolDisplays);
            }
        }
        return index;
    }

    private <T> void captureConverter(IngredientConverter<T> converter, IIngredientRegistry registry,
                                      Collection<JeiRecipeDisplay> displays) {
        captureConverter(converter, registry.getIngredientHelper(converter.getIngredientType()),
            registry.getAllIngredients(converter.getIngredientType()), displays);
    }

    <T> void captureConverter(IngredientConverter<T> converter, IIngredientHelper<T> helper,
                              Collection<T> registeredIngredients, Collection<JeiRecipeDisplay> displays) {
        var type = converter.getIngredientType();
        helpers.put(type, helper);
        var observed = new Object2ObjectOpenHashMap<String, AEKey>();
        var ambiguous = new ObjectOpenHashSet<String>();
        keys.put(type, observed);
        collisions.put(type, ambiguous);
        for (var ingredient : registeredIngredients) {
            var stack = converter.getStackFromIngredient(ingredient);
            if (stack != null) {
                observe(helper.getUniqueId(ingredient), stack.what(), observed, ambiguous);
            }
        }
        for (var display : displays) {
            for (var row : display.resources()) {
                var ingredient = converter.getIngredientFromStack(new GenericStack(row.stack().what(), 1));
                if (ingredient != null) {
                    var roundTrip = converter.getStackFromIngredient(ingredient);
                    if (roundTrip != null && row.stack().what().equals(roundTrip.what())) {
                        observe(helper.getUniqueId(ingredient), row.stack().what(), observed, ambiguous);
                    }
                }
            }
        }
    }

    private static void observe(String uid, AEKey key, Object2ObjectOpenHashMap<String, AEKey> observed,
                                ObjectOpenHashSet<String> ambiguous) {
        if (uid == null || uid.isBlank()) {
            return;
        }
        var previous = observed.putIfAbsent(uid, key);
        if (previous != null && !previous.equals(key)) {
            ambiguous.add(uid);
        }
    }

    /**
     * Exact converter type registration owns this erased helper binding.
     */
    @SuppressWarnings("unchecked")
    <T> boolean supports(IngredientConverter<T> converter, T ingredient, AEKey key) {
        var helper = (IIngredientHelper<T>) helpers.get(converter.getIngredientType());
        if (helper == null) {
            return false;
        }
        var uid = helper.getUniqueId(ingredient);
        return uid != null && !uid.isBlank() && !collisions.get(converter.getIngredientType()).contains(uid)
            && key.equals(keys.get(converter.getIngredientType()).get(uid));
    }

    @SuppressWarnings("unchecked")
        // Same exact ingredient type binding as supports.
    <T> boolean conflicts(IngredientConverter<T> converter, T ingredient) {
        var helper = (IIngredientHelper<T>) helpers.get(converter.getIngredientType());
        return helper != null && collisions.get(converter.getIngredientType()).contains(helper.getUniqueId(ingredient));
    }
}
