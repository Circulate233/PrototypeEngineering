package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Replace this immutable selection only at the scheduler's exclusive machine boundary.
 */
public record MachineScheduling(ExecutionMode mode) {
    public static final ComponentType<MachineInstance, MachineScheduling> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "scheduling"), owner -> new MachineScheduling(owner.executionMode()), List.of());

    public MachineScheduling {
        Objects.requireNonNull(mode);
    }
}
