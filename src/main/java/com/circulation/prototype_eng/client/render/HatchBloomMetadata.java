package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.util.ExternalEnumValues;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import team.chisel.ctm.client.texture.IMetadataSectionCTM;

import java.lang.reflect.Type;

/**
 * Resource opt-in fallback before CTM resolves its optional BLOOM enum value.
 */
public final class HatchBloomMetadata extends IMetadataSectionCTM.Serializer {
    private final boolean bloomLayerAvailable;

    public static void register() {
        Minecraft.getMinecraft().metadataSerializer.registerMetadataSectionType(new HatchBloomMetadata(), IMetadataSectionCTM.class);
    }

    public HatchBloomMetadata() {
        boolean available = false;
        for (var layer : ExternalEnumValues.BLOCK_RENDER_LAYERS) {
            if ("BLOOM".equals(layer.name())) {
                available = true;
                break;
            }
        }
        bloomLayerAvailable = available;
    }

    @Override
    public IMetadataSectionCTM deserialize(JsonElement section, Type type, JsonDeserializationContext context) {
        if (!bloomLayerAvailable) normalize(section.getAsJsonObject());
        return super.deserialize(section, type, context);
    }

    private static void normalize(JsonObject metadata) {
        var marker = metadata.get("prototype_eng_bloom");
        if (!(marker instanceof JsonPrimitive enabled) || !enabled.isBoolean() || !enabled.getAsBoolean())
            return;
        var layer = metadata.get("layer");
        if (!(layer instanceof JsonPrimitive name) || !name.isString() || !"BLOOM".equals(name.getAsString()))
            return;
        metadata.addProperty("layer", "CUTOUT");
    }
}
