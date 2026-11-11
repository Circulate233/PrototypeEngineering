package com.circulation.prototype_eng.integration.crafttweaker.hatch;

import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.hatch.ResourceSide")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenResourceHatchSide(ResourceHatchConfiguration.Side value) {
    public ZenResourceHatchSide {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenResourceHatchSide itemEquivalent(int slots, long capacity) {
        return new ZenResourceHatchSide(ResourceHatchConfiguration.Side.fromItemEquivalent(slots, capacity,
            List.of(AEKeyType.items(), AEKeyType.fluids())));
    }

    @ZenMethod
    public static ZenResourceHatchSide itemEquivalent(int slots, long capacity, ZenResource[] representatives) {
        var types = new ObjectArrayList<AEKeyType>(representatives.length);
        for (var representative : representatives) types.add(Objects.requireNonNull(representative).key().getType());
        return new ZenResourceHatchSide(ResourceHatchConfiguration.Side.fromItemEquivalent(slots, capacity, types));
    }

    @ZenMethod
    public static ZenResourceHatchSide itemEquivalentWithRatios(int slots, long capacity, ZenResource[] representatives, long[] ratios) {
        if (representatives.length != ratios.length) throw new IllegalArgumentException("Resource types and ratios must have equal lengths");
        var types = new ObjectArrayList<AEKeyType>(representatives.length);
        var overrides = new Reference2LongOpenHashMap<AEKeyType>();
        for (int i = 0; i < representatives.length; i++) {
            var type = Objects.requireNonNull(representatives[i]).key().getType();
            types.add(type);
            if (overrides.containsKey(type)) throw new IllegalArgumentException("Duplicate resource type: " + type.getId());
            overrides.put(type, ratios[i]);
        }
        return new ZenResourceHatchSide(ResourceHatchConfiguration.Side.fromItemEquivalent(slots, capacity, types, overrides));
    }

    @ZenMethod
    public static ZenResourceHatchSide nativeCapacities(int slots, ZenResource[] capacities) {
        var values = new Reference2LongOpenHashMap<AEKeyType>();
        for (var resource : capacities) {
            var type = Objects.requireNonNull(resource).key().getType();
            if (values.containsKey(type)) throw new IllegalArgumentException("Duplicate resource type: " + type.getId());
            values.put(type, resource.amount());
        }
        return new ZenResourceHatchSide(new ResourceHatchConfiguration.Side(slots, values));
    }
}
