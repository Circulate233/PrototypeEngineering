package com.circulation.prototype_eng.api.machine.attribute;

import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

/**
 * Standard IDs; machine concurrency, per-process quantity and progress speed remain independent.
 */
public final class StandardMachineAttributes {
    // Admission limit only; running work is preserved when the baseline later decreases.
    public static final ResourceLocation MAX_CONCURRENT_PROCESSES = new ResourceLocation(Tags.MOD_ID, "max_concurrent_processes");
    public static final ResourceLocation PROCESS_PARALLELISM = new ResourceLocation(Tags.MOD_ID, "process_parallelism");
    public static final ResourceLocation MAX_PROCESS_PARALLELISM = new ResourceLocation(Tags.MOD_ID, "max_process_parallelism");
    public static final ResourceLocation PROCESS_SPEED = new ResourceLocation(Tags.MOD_ID, "process_speed");
    // Declared for extensions; no energy requirement currently applies an efficiency formula.
    @SuppressWarnings("unused")
    public static final ResourceLocation ENERGY_EFFICIENCY = new ResourceLocation(Tags.MOD_ID, "energy_efficiency");

    private StandardMachineAttributes() {
    }
}
