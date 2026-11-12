package com.circulation.prototype_eng.api.client.render;

public enum MachineRenderPass {
    DEFAULT, TRANSPARENT, BLOOM, BLOOM_TRANSPARENT;

    /** Shared declaration order; callers must not modify this array. */
    public static final MachineRenderPass[] VALUES = values();
}
