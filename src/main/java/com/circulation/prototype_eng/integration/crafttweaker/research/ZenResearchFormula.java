package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchFormula")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchFormula {
 private final ResearchResourceFormula.Builder builder=ResearchResourceFormula.builder();
 @ZenMethod public static ZenResearchFormula create() { return new ZenResearchFormula(); }
 @ZenMethod public ZenResearchFormula constant(ZenComputingResource type,long demand,long segmentLimit) {
  builder.constant(Objects.requireNonNull(type).value(),demand,segmentLimit); return this;
 }
 @ZenMethod public ZenResearchFormula affine(ZenComputingResource type,long base,long slope,long segmentLimit) {
  builder.affine(Objects.requireNonNull(type).value(),base,slope,segmentLimit); return this;
 }
 public ResearchResourceFormula value() { return builder.build(); }
}
