package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/** Immutable Java source snapshot. Identity/revision are facts, never authority to reserve a source. */
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.AllocationSource")
public final class ZenAllocationSource {
    private final InputAllocation.Source source;

    public ZenAllocationSource(InputAllocation.Source source) {
        this.source = Objects.requireNonNull(source);
    }

    @ZenGetter("index")
    public int index() {
        return source.index();
    }

    @ZenGetter("identity")
    public String identity() {
        return source.identity();
    }

    @ZenGetter("revision")
    public long revision() {
        return source.revision();
    }

    @ZenGetter("available")
    public long available() {
        return source.available();
    }

    @ZenGetter("resources")
    public ZenResource[] resources() {
        var resources = new ObjectArrayList<ZenResource>(source.resources().size());
        for (var entry : source.resources().object2LongEntrySet()) {
            if (entry.getLongValue() > 0) resources.add(new ZenResource(entry.getKey(), entry.getLongValue()));
        }
        return resources.toArray(ZenResource[]::new);
    }

    /** Exact initial snapshot balance, including zero for keys absent from this source. */
    @ZenMethod
    public long amountOf(ZenResource resource) {
        return source.resources().getLong(Objects.requireNonNull(resource).key());
    }

    @ZenGetter("energyAvailable")
    public long energyAvailable() {
        return source.energyAvailable();
    }

    @ZenGetter("maxExtract")
    public long maxExtract() {
        return source.maxExtract();
    }
}
