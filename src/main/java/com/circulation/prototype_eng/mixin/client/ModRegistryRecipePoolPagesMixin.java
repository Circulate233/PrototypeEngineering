package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.integration.jei.ExternalRecipePoolPages;
import mezz.jei.startup.ModRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Resolve category IDs after both modern and legacy plugin registration, before native freezing.
 */
@Mixin(value = ModRegistry.class, remap = false)
public abstract class ModRegistryRecipePoolPagesMixin {
    @Shadow
    @Final
    private Set<String> recipeCategoryUids;

    @Inject(method = "createRecipeRegistry", at = @At("HEAD"), remap = false)
    private void prototype_eng$finishRecipePoolPages(CallbackInfoReturnable<?> callback) {
        ExternalRecipePoolPages.finish(this, recipeCategoryUids);
    }
}
