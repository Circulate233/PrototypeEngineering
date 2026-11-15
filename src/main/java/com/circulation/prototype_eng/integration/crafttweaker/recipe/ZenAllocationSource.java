package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


/**
 * Java source snapshot addressed by its index in this allocation. Returned arrays are borrowed read-only.
 */
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.AllocationSource")
public final class ZenAllocationSource {
    private final InputAllocation.Source source;
    private ZenResource[] resources;

    public ZenAllocationSource(InputAllocation.Source source) {
        this.source = source;
    }

    @ZenGetter("index")
    public int index() {
        return source.index();
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
        if (resources == null) {
            var values = new ObjectArrayList<ZenResource>(source.resources().size());
            for (var entry : source.resources().object2LongEntrySet()) {
                if (entry.getLongValue() > 0) values.add(new ZenResource(entry.getKey(), entry.getLongValue()));
            }
            resources = values.toArray(ZenResource[]::new);
        }
        return resources;
    }

    /**
     * Exact initial snapshot balance, including zero for keys absent from this source.
     */
    @ZenMethod
    public long amountOf(ZenResource resource) {
        return source.resources().getLong(resource.key());
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
