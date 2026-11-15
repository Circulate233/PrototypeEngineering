package com.circulation.prototype_eng.api.machine.persistence;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Explicit machine-component persistence. Factories and restoration build only unpublished local
 * state; world/registry effects belong to lifecycle onLoaded. Restore may mutate its fresh component
 * or return an immutable replacement, but cannot mutate other components, owner attributes or the
 * component graph. All saved components exist before dependency-ordered restoration begins.
 * Encode and validate are pure. Encoded tags are read-only captures independent of later mutable
 * state and may be reused for one revision. Consumers must not modify them. Restore must not modify
 * input tags; borrowed child tags remain read-only, while later mutable state must be separately owned.
 */
public interface MachineComponentCodec<C> {
    NBTTagCompound encode(MachineInstance owner, C component);

    C restore(MachineInstance owner, C component, NBTTagCompound saved);

    /**
     * Pure checks after complete restoration, before lifecycle load publishes the machine.
     */
    default void validate(MachineInstance owner, C component) {
    }
}
