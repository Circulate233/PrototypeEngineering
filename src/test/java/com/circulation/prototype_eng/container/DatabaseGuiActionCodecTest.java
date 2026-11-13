package com.circulation.prototype_eng.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseGuiActionCodecTest {
    private final Gson codec = new GsonBuilder().create();

    @Test
    void databaseViewUsesDefaultGsonForItsActualInboundAndOutboundShape() {
        String json = "{\"offset\":12,\"research\":\"test:research\",\"level\":3}";
        var decoded = codec.fromJson(json, ContainerResearch.DatabaseView.class);
        assertEquals(12, decoded.offset);
        assertEquals("test:research", decoded.research);
        assertEquals(3, decoded.level);
        String encoded = codec.toJson(new ContainerResearch.DatabaseView(12, "test:research", 3));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
    }

    @Test
    void downloadWireRetainsTheLastDisplayedTeamAndPhysicalDatabase() {
        String database = "00000000-0000-0000-0000-000000000041";
        String json = """
            {"team":"team","database":"00000000-0000-0000-0000-000000000041","research":"test:research","level":3}
            """;
        var decoded = codec.fromJson(json, ContainerResearch.DatabaseAction.class);
        assertEquals("team", decoded.team);
        assertEquals(database, decoded.database);
        assertEquals("test:research", decoded.research);
        assertEquals(3, decoded.level);
        String encoded = codec.toJson(new ContainerResearch.DatabaseAction("team", database, "test:research", 3));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
    }

    @Test
    void oldDrawnTeamRejectsSameResearchAndLevelInTheNewTeam() {
        for (var kind : new ContainerResearch.Kind[]{ContainerResearch.Kind.START, ContainerResearch.Kind.BLACKLIST, ContainerResearch.Kind.TAKEOVER}) {
            var sent = new ContainerResearch.Action(kind, "test:research", 3, 41, "", true, "team_a");
            var received = codec.fromJson(codec.toJson(sent), ContainerResearch.Action.class);
            assertEquals("test:research", received.research);
            assertEquals(3, received.level);
            assertEquals(41, received.entryId);
            assertEquals("team_a", received.expectedTeam);
            assertTrue(ContainerResearch.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_a"));
            assertFalse(ContainerResearch.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_b"));
        }
        var missingTeam = codec.fromJson("{\"kind\":\"START\",\"research\":\"test:research\",\"level\":3}", ContainerResearch.Action.class);
        assertFalse(ContainerResearch.matchesDisplayedTeam(missingTeam.kind, missingTeam.expectedTeam, "team_b"));
    }

    @Test
    void permanentRewardClaimRemainsAvailableAfterChangingTeams() {
        var sent = new ContainerResearch.Action(ContainerResearch.Kind.CLAIM, "test:research", 3, 0, "test:group", false, "team_a");
        var received = codec.fromJson(codec.toJson(sent), ContainerResearch.Action.class);
        assertTrue(ContainerResearch.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_b"));
        assertTrue(ContainerResearch.matchesDisplayedTeam(received.kind, null, ""));
    }

}
