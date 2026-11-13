package com.circulation.prototype_eng.client.render;

/**
 * Concrete CPU scheduling, sampled animation and native mapped-output choices.
 */
public record MachineRenderOptions(int workers, boolean fixedThreads, double animationStepTicks, int sampleSnap,
                                   boolean mappedOutput) {
    public MachineRenderOptions {
        if (workers < 1 || !Double.isFinite(animationStepTicks) || animationStepTicks <= 0 || sampleSnap < 1)
            throw new IllegalArgumentException("Invalid machine rendering options");
    }

    public static MachineRenderOptions defaults() {
        return new MachineRenderOptions(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), false, 1, 1, true);
    }
}
