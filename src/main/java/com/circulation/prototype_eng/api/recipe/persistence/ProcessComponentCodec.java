package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Explicit persistence for an external process component. Restore runs on fresh unpublished
 * instances after every saved component has been created, in declared component dependency order.
 * Encode and validation must be pure. Restoration may only modify its fresh component; factories
 * and codecs must not change assembly, execute stages, publish world effects, mutate the owner or
 * the old process. Encoded tags are read-only captures independent of later mutable state and may be
 * reused for one revision. Consumers must not modify them. Restore must not modify input tags;
 * Borrowed child tags remain read-only. Decoded mutable state belongs to the new process.
 * Dependencies on other restored components must be declared.
 */
public interface ProcessComponentCodec<C> {
    NBTTagCompound encode(RecipeProcess process, C component);

    void restore(RecipeProcess process, C component, NBTTagCompound data);

    /**
     * Cross-component checks after all components have been restored, before the process is published.
     */
    @SuppressWarnings("unused") // Optional external extension hook; default codecs need no extra checks.
    default void validate(RecipeProcess process, C component) {
    }
}
