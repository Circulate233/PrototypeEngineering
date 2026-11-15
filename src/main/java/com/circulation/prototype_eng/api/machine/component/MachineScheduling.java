package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Replace this immutable selection only at the scheduler's exclusive machine boundary.
 */
public record MachineScheduling(ExecutionMode mode, @Nullable ControllerExecutionPolicy policy) {
    public static final ComponentType<MachineInstance, MachineScheduling> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "scheduling"), owner -> new MachineScheduling(owner.executionMode(), owner.executionPolicy()), List.of());

    /**
     * Explicit old-mode selection does not opt callbacks into the controller policy.
     */
    public MachineScheduling(ExecutionMode mode) {
        this(mode, null);
    }

}
