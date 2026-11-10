package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Explicit persistence for an external process component. Restore runs on fresh unpublished
 * instances after every saved component has been created, in declared component dependency order.
 * Encode and validation must be pure. Restoration may only modify its fresh component; factories
 * and codecs must not change assembly, execute stages, publish world effects, mutate the owner or
 * the old process. Mutable tags are isolated at the boundary; decoded mutable state belongs solely
 * to the new process. A codec requiring other restored state must declare the dependency explicitly.
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
