package com.circulation.prototype_eng.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiActionCodecTest {
    private final Gson codec = new GsonBuilder().create();

    @Test
    void machineViewUsesTheExistingInboundAndOutboundShape() {
        String json = "{\"offset\":12,\"rows\":6}";
        var decoded = codec.fromJson(json, ContainerMachine.View.class);
        assertEquals(12, decoded.offset);
        assertEquals(6, decoded.rows);
        String encoded = codec.toJson(new ContainerMachine.View(12, 6));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
    }
}
