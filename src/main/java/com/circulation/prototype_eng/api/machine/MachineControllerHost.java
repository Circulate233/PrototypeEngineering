package com.circulation.prototype_eng.api.machine;

import ae2.api.orientation.BlockOrientation;
import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Physical controller interaction and GUI access, independent of the default controller tile.
 */
public interface MachineControllerHost {
    AEBaseTile blockEntity();

    MachineType type();

    @Nullable
    MachineInstance machine();

    /**
     * Exact currently attached or pending-save ownership; null means the host cannot prove it.
     */
    @Nullable
    default PersistentMachineIdentity savedIdentity() {
        return null;
    }

    BlockOrientation orientation();

    boolean formed();

    /**
     * Runs after workers join; callers must recheck their open container inside the callback.
     */
    void readGuiAtBoundary(Runnable read);

    /**
     * Relative transforms use the orientation current at the host's ownership boundary.
     */
    void transformOrientation(UnaryOperator<BlockOrientation> transformation);

    void placedBy(EntityLivingBase placer);

    void controllerBroken();
}
