package com.circulation.prototype_eng.mixin.client;

import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.IResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exposes native low-to-high ordering, without identifying a pack by its non-unique display name. */
@Mixin(FallbackResourceManager.class)
public interface FallbackResourceManagerAccessor {
    @Accessor("resourcePacks")
    List<IResourcePack> prototype_eng$resourcePacks();
}
