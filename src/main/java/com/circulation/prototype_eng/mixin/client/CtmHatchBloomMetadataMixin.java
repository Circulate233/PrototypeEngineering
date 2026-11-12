package com.circulation.prototype_eng.mixin.client;

import com.circulation.prototype_eng.util.ExternalEnumValues;

import com.circulation.prototype_eng.client.render.HatchBloomMetadata;
import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The typed CTM deserializer sees an opt-in copy only when no provider installed a BLOOM layer. */
@Pseudo
@Mixin(targets = "team.chisel.ctm.client.texture.IMetadataSectionCTM$Serializer", remap = false)
public abstract class CtmHatchBloomMetadataMixin {
    @ModifyVariable(method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Lteam/chisel/ctm/client/texture/IMetadataSectionCTM;",
        at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private @Nullable JsonElement prototype_eng$portableHatchBloom(@Nullable JsonElement metadata) {
        boolean bloomAvailable = false;
        for (var layer : ExternalEnumValues.BLOCK_RENDER_LAYERS) {
            if ("BLOOM".equals(layer.name())) {
                bloomAvailable = true;
                break;
            }
        }
        return HatchBloomMetadata.normalize(metadata, bloomAvailable);
    }
}
