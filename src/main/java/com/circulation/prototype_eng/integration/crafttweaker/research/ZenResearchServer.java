package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import java.util.Locale;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Declaration only; the existing server session captures the same configured Java policy. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchServer")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchServer {
 private ZenResearchServer() {}
 @ZenMethod public static void permissions(ZenResearchPermissionCallback callback) {
  Objects.requireNonNull(callback);
  DefinitionRegistrations.researchServer(event -> event.configuration().permissions((action,context) ->
   ResearchPermissionPolicy.Decision.valueOf(callback.can(action.name(),new ZenResearchPermissionContext(context)).toUpperCase(Locale.ROOT))));
 }
}
