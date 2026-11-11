package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

final class ZenRecipeSupport {
    private ZenRecipeSupport() {
    }

    static ResourceLocation id(String value) {
        return StrictNbt.requireId(Objects.requireNonNull(value));
    }

    static List<GenericStack> stacks(ZenResource[] values) {
        var result = new ObjectArrayList<GenericStack>(values.length);
        for (var value : values) result.add(Objects.requireNonNull(value).stack());
        return result;
    }

    static @Nullable List<GenericStack> optionalStacks(@Nullable ZenResource[] values) {
        return values == null ? null : stacks(values);
    }

    static NumericAttributeModifier numericModifier(String id, double amount, String operation) {
        return new NumericAttributeModifier(id, amount, AttributeModifier.Operation.valueOf(Objects.requireNonNull(operation)));
    }

    static List<AEKey> keys(ZenResource[] values) {
        var result = new ObjectArrayList<AEKey>(values.length);
        for (var value : values) result.add(Objects.requireNonNull(value).key());
        return result;
    }

    static List<RecipeRequirement<?>> requirements(ZenRequirement[] values) {
        var result = new ObjectArrayList<RecipeRequirement<?>>(values.length);
        for (var value : values) result.add(Objects.requireNonNull(value).value());
        return result;
    }

    static List<SelectiveModifierDescriptor> modifiers(ZenSelectiveModifier[] values) {
        var result = new ObjectArrayList<SelectiveModifierDescriptor>(values.length);
        for (var value : values) result.add(Objects.requireNonNull(value).value());
        return result;
    }
}
