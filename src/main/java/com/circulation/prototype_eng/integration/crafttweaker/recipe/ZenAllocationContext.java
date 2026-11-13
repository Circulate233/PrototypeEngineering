package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import crafttweaker.annotations.ZenRegister;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

import java.util.Objects;

/**
 * Detached projection facts; no process, storage, lease or owner is exposed.
 */
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.AllocationContext")
public final class ZenAllocationContext {
    private final String stage;
    private final int projection;
    private final @Nullable ZenResource resource;
    private final long amount;
    private final ZenAllocationSource[] sources;

    public ZenAllocationContext(InputAllocation.Context context) {
        Objects.requireNonNull(context);
        stage = context.stage().name();
        projection = context.projection();
        amount = context.amount();
        var key = context.resource();
        resource = key == null ? null : new ZenResource(key, amount);
        var facts = context.sources();
        sources = new ZenAllocationSource[facts.size()];
        for (int i = 0; i < sources.length; i++) sources[i] = new ZenAllocationSource(facts.get(i));
    }

    @ZenGetter("stage")
    public String stage() {
        return stage;
    }

    @ZenGetter("projection")
    public int projection() {
        return projection;
    }

    @ZenGetter("resource")
    public @Nullable ZenResource resource() {
        return resource;
    }

    /**
     * The Java allocation contract uses a null AEKey to denote independent FE.
     */
    @ZenGetter("isEnergy")
    public boolean isEnergy() {
        return resource == null;
    }

    @ZenGetter("amount")
    public long amount() {
        return amount;
    }

    @ZenGetter("sources")
    public ZenAllocationSource[] sources() {
        return sources.clone();
    }
}
