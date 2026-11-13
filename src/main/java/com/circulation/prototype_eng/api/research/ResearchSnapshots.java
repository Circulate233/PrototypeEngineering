package com.circulation.prototype_eng.api.research;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.util.ResourceLocation;

/**
 * Captures detached native maps. Returned maps and their entry views are read-only by contract;
 * callers must not mutate a map while any declaration, view or persistence checkpoint retains it.
 */
public final class ResearchSnapshots {
    private ResearchSnapshots() {
    }

    public static Object2IntMap<ResourceLocation> levels(Object2IntMap<ResourceLocation> source) {
        var values = new Object2IntOpenHashMap<>(source);
        values.defaultReturnValue(0);
        return values;
    }

    static <V> Int2ObjectMap<V> sparse(Int2ObjectMap<V> source) {
        return new Int2ObjectOpenHashMap<>(source);
    }
}
