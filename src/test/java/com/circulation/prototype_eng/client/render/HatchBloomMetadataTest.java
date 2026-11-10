package com.circulation.prototype_eng.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HatchBloomMetadataTest {
    private static JsonObject metadata() {
        return JsonParser.parseString("""
            {"ctm_version":1,"prototype_eng_bloom":true,"layer":"BLOOM","gregtech":true,
             "extra":{"light":15,"keep":[1,2]},"textures":["prototype_eng:blocks/test"]}
            """).getAsJsonObject();
    }

    @Test
    void missingProviderCopiesOnlyLayerAndKeepsLightAndOriginalNestedData() {
        var original = metadata();
        var expected = original.deepCopy();
        expected.addProperty("layer", "CUTOUT");
        var fallback = HatchBloomMetadata.normalize(original, false);
        assertNotSame(original, fallback);
        assertEquals(expected, fallback);
        assertEquals("BLOOM", original.get("layer").getAsString());
        var result = assertInstanceOf(JsonObject.class, fallback);
        result.getAsJsonObject("extra").getAsJsonArray("keep").add(3);
        assertEquals(2, original.getAsJsonObject("extra").getAsJsonArray("keep").size());
        assertEquals(15, result.getAsJsonObject("extra").get("light").getAsInt());
    }

    @Test
    void actualProviderLeavesSameOriginalAndAvailabilityIsNotCachedAcrossParses() {
        var original = metadata();
        var preserved = original.deepCopy();
        assertSame(original, HatchBloomMetadata.normalize(original, true));
        assertNotSame(original, HatchBloomMetadata.normalize(original, false));
        assertSame(original, HatchBloomMetadata.normalize(original, true));
        assertEquals(preserved, original);
    }

    @Test
    void foreignDisabledAndWrongTypeMarkersDoNotAlterCtmMetadata() {
        var foreign = metadata();
        foreign.remove("prototype_eng_bloom");
        assertSame(foreign, HatchBloomMetadata.normalize(foreign, false));
        for (JsonElement marker : new JsonElement[] { JsonParser.parseString("false"), JsonParser.parseString("\"true\""),
            JsonParser.parseString("1"), JsonParser.parseString("{}"), JsonParser.parseString("null") }) {
            var original = metadata();
            original.add("prototype_eng_bloom", marker);
            assertSame(original, HatchBloomMetadata.normalize(original, false));
        }
    }

    @Test
    void existingOrdinaryOrInvalidLayersStayUnderTheNativeParserContract() {
        for (JsonElement layer : new JsonElement[] { JsonParser.parseString("\"SOLID\""), JsonParser.parseString("\"CUTOUT\""),
            JsonParser.parseString("\"TRANSLUCENT\""), JsonParser.parseString("\"bloom\""), JsonParser.parseString("true"), JsonParser.parseString("{}") }) {
            var original = metadata();
            original.add("layer", layer);
            assertSame(original, HatchBloomMetadata.normalize(original, false));
        }
        var noLayer = metadata();
        noLayer.remove("layer");
        assertSame(noLayer, HatchBloomMetadata.normalize(noLayer, false));
        assertNull(HatchBloomMetadata.normalize(null, false));
        var array = JsonParser.parseString("[]");
        assertSame(array, HatchBloomMetadata.normalize(array, false));
    }
}
