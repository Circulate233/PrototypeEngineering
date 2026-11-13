package com.circulation.prototype_eng.mixin.client;

import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reads the actual selected domain; the resource manager mutates in place before reload listeners.
 */
@Mixin(SimpleReloadableResourceManager.class)
public interface SimpleReloadableResourceManagerAccessor {
    @Accessor("domainResourceManagers")
    Map<String, FallbackResourceManager> prototype_eng$domainResourceManagers();
}
