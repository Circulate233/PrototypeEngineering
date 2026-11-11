package com.circulation.prototype_eng.integration.crafttweaker.resource;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Parameter vector; Java APIs perform metadata and quantity validation. */
@ZenRegister
@ZenClass("mods.prototype_eng.resource.ComputingResources")
@SuppressWarnings("unused") // Entry points are called by ZenScript and Java addons.
public final class ZenComputingResources {
    private final Object2LongOpenHashMap<NetworkResourceType> values = new Object2LongOpenHashMap<>();
    @ZenMethod public static ZenComputingResources create() { return new ZenComputingResources(); }
    @ZenMethod public ZenComputingResources add(ZenComputingResource resource, long amount) {
        var type = Objects.requireNonNull(resource).value();
        if (amount < 0) throw new IllegalArgumentException("Computing supply must be nonnegative");
        if (amount > Long.MAX_VALUE - values.getLong(type)) throw new IllegalArgumentException("Computing supply overflows");
        var check = new Object2LongOpenHashMap<>(values);
        check.addTo(type, amount);
        NetworkResourceModifier.copyResources(check);
        values.addTo(type, amount);
        return this;
    }
    @ZenMethod public long amount(ZenComputingResource resource) { return values.getLong(Objects.requireNonNull(resource).value()); }
    public Object2LongMap<NetworkResourceType> value() { return NetworkResourceModifier.copyResources(values); }
}
