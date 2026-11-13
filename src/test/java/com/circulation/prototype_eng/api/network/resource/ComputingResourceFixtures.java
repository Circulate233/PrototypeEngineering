package com.circulation.prototype_eng.api.network.resource;

import net.minecraft.util.ResourceLocation;

/**
 * Test-owned resource singletons. They are not library defaults and require no registration.
 */
public final class ComputingResourceFixtures {
    public static final NetworkResourceType COMPUTE = network(new ResourceLocation("test", "compute"));
    public static final NetworkResourceType QUBIT = network(new ResourceLocation("test", "qubit"));

    private ComputingResourceFixtures() {
    }

    public static NetworkResourceType network(ResourceLocation id) {
        return NetworkResourceType.network(id, id::toString, "%s");
    }

    public static NetworkResourceType local(ResourceLocation id) {
        return NetworkResourceType.computer(id, id::toString, "%s");
    }
}
