package com.circulation.prototype_eng.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

/** Resource opt-in fallback before CTM resolves its optional BLOOM enum value. */
public final class HatchBloomMetadata {
    private HatchBloomMetadata() {}

    public static @Nullable JsonElement normalize(@Nullable JsonElement section, boolean bloomLayerAvailable) {
        if (bloomLayerAvailable || !(section instanceof JsonObject metadata)) return section;
        var marker = metadata.get("prototype_eng_bloom");
        if (!(marker instanceof JsonPrimitive enabled) || !enabled.isBoolean() || !enabled.getAsBoolean()) return section;
        var layer = metadata.get("layer");
        if (!(layer instanceof JsonPrimitive name) || !name.isString() || !"BLOOM".equals(name.getAsString())) return section;
        var fallback = metadata.deepCopy();
        fallback.addProperty("layer", "CUTOUT");
        return fallback;
    }
}
