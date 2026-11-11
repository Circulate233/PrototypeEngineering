package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/** Immutable strategy handle. Its callback and captured state must remain immutable for its lifetime. */
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.InputAllocation")
public record ZenInputAllocation(InputAllocation value) {
    public ZenInputAllocation {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenInputAllocation create(String id, ZenAllocationCallback callback) {
        Objects.requireNonNull(callback);
        return new ZenInputAllocation(InputAllocation.custom(ZenRecipeSupport.id(id), context -> {
            var proposed = Objects.requireNonNull(callback.allocate(new ZenAllocationContext(context)), "Allocation returned null");
            var portions = new ObjectArrayList<InputAllocation.Portion>(proposed.length);
            for (var portion : proposed) portions.add(Objects.requireNonNull(portion).value());
            return portions;
        }));
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }
}
