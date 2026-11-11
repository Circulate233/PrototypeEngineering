package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import net.minecraft.util.ResourceLocation;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.annotations.ZenGetter;

@ZenRegister
@ZenClass("mods.prototype_eng.network.NetworkRecipeContext")
@SuppressWarnings("unused") // Script parameter conversion delegates to public Java contracts.
public record ZenNetworkRecipeContext(MachineRecipe value) {
 public ZenNetworkRecipeContext { Objects.requireNonNull(value); }
 @ZenGetter("id") public String id() { return value.id().toString(); }
 @ZenGetter("durationTicks") public int durationTicks() { return value.durationTicks(); }
 @ZenGetter("maxConcurrentThreads") public int maxConcurrentThreads() { return value.maxConcurrentThreads(); }
 @ZenMethod public boolean matches(ZenRecipe recipe) { return value==Objects.requireNonNull(recipe).recipe(); }
 @ZenMethod public boolean inGroup(String groupId) { return value.recipeGroups().contains(new ResourceLocation(groupId)); }
 @ZenGetter("requirements") public ZenRequirement[] requirements() { return value.orderedRequirements().stream().map(ZenRequirement::new).toArray(ZenRequirement[]::new); }
}
