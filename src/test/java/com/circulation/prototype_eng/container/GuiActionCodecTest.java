package com.circulation.prototype_eng.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiActionCodecTest {
    private final Gson codec = new GsonBuilder().create();

    @Test
    void researchViewDecodesAndEncodesTheActualWireFields() {
        String json = """
            {"tab":2,"offset":6,"research":"test:research/path","level":3,
             "entryId":9223372036854775806,"detailOffset":12}
            """;
        var decoded = codec.fromJson(json, ContainerResearch.View.class);
        assertEquals(2, decoded.tab);
        assertEquals(6, decoded.offset);
        assertEquals("test:research/path", decoded.research);
        assertEquals(3, decoded.level);
        assertEquals(Long.MAX_VALUE - 1, decoded.entryId);
        assertEquals(12, decoded.detailOffset);
        String encoded = codec.toJson(new ContainerResearch.View(2, 6, "test:research/path", 3, Long.MAX_VALUE - 1, 12));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
        assertEquals(Long.MAX_VALUE - 1, codec.fromJson(encoded, ContainerResearch.View.class).entryId);
    }

    @Test
    void researchActionPreservesEnumStringsBooleanAndLongIdentity() {
        String inbound = """
            {"kind":"CLAIM","research":"test:research/path","level":4,
             "entryId":9223372036854775806,"group":"test:choice/path","blacklisted":true,"expectedTeam":"team_a"}
            """;
        var decoded = codec.fromJson(inbound, ContainerResearch.Action.class);
        assertEquals(ContainerResearch.Kind.CLAIM, decoded.kind);
        assertEquals("test:research/path", decoded.research);
        assertEquals(4, decoded.level);
        assertEquals(Long.MAX_VALUE - 1, decoded.entryId);
        assertEquals("test:choice/path", decoded.group);
        assertTrue(decoded.blacklisted);
        assertEquals("team_a", decoded.expectedTeam);
        String outbound = """
            {"kind":"BLACKLIST","research":"test:research/path","level":4,
             "entryId":9223372036854775806,"group":"","blacklisted":false,"expectedTeam":"team_b"}
            """;
        String encoded = codec.toJson(new ContainerResearch.Action(ContainerResearch.Kind.BLACKLIST,
            "test:research/path", 4, Long.MAX_VALUE - 1, "", false, "team_b"));
        assertEquals(codec.fromJson(outbound, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
        assertEquals(Long.MAX_VALUE - 1, codec.fromJson(encoded, ContainerResearch.Action.class).entryId);
        assertEquals("team_b", codec.fromJson(encoded, ContainerResearch.Action.class).expectedTeam);
    }

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
