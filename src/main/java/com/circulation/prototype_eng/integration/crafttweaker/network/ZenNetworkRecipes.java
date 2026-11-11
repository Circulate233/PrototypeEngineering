package com.circulation.prototype_eng.integration.crafttweaker.network;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtension;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.network.NetworkRecipes")
@SuppressWarnings("unused") // Script parameter conversion delegates to public Java contracts.
public final class ZenNetworkRecipes {
 private ZenNetworkRecipes() {}
 @ZenMethod public static void register(int priority,ZenNetworkRecipeCallback callback) {
  var extension=extension(callback);
  DefinitionRegistrations.recipes(event -> event.networkExtensions().register(priority,extension));
 }
 public static NetworkRecipeExtension extension(ZenNetworkRecipeCallback callback) {
  Objects.requireNonNull(callback);
  return (recipe,requirements) -> callback.extend(new ZenNetworkRecipeContext(recipe),new ZenNetworkRequirements(requirements));
 }
}
