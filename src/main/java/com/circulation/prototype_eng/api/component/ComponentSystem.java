package com.circulation.prototype_eng.api.component;

import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;

import java.util.Set;

/**
 * External processing responsibility; system instances may be shared by many component owners.
 * Identity and ordering metadata must remain immutable while bound to component types.
 */
public interface ComponentSystem<E, C> {
    ResourceLocation id();

    default Set<ResourceLocation> runAfter() { return ObjectSets.emptySet(); }
    default Set<ResourceLocation> runBefore() { return ObjectSets.emptySet(); }

    default void onPreTick(E owner, C component) {}
    default void onTick(E owner, C component) {}
    default void onPostTick(E owner, C component) {}
}
