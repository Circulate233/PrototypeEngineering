package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

/**
 * PN's standard computing dimensions; these declare no machines, supply rates or research content.
 */
public final class NetworkResources {
    public static final NetworkResourceType THREAD = new NetworkResourceType(new ResourceLocation(Tags.MOD_ID, "thread"), true);
    public static final NetworkResourceType COMPUTE = new NetworkResourceType(new ResourceLocation(Tags.MOD_ID, "compute"), false);
    public static final NetworkResourceType QUBIT = new NetworkResourceType(new ResourceLocation(Tags.MOD_ID, "qubit"), false);

    private NetworkResources() {
    }
}
