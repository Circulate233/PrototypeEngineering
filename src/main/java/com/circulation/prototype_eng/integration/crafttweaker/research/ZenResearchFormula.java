package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;


@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchFormula")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchFormula {
    private final ResearchResourceFormula.Builder builder = ResearchResourceFormula.builder();

    @ZenMethod
    public static ZenResearchFormula create() {
        return new ZenResearchFormula();
    }

    @ZenMethod
    public ZenResearchFormula constant(ZenComputingResource type, double demand) {
        builder.constant(type.value(), demand);
        return this;
    }

    @ZenMethod
    public ZenResearchFormula affine(ZenComputingResource type, double base, double slope) {
        builder.affine(type.value(), base, slope);
        return this;
    }

    public ResearchResourceFormula value() {
        return builder.build();
    }
}
