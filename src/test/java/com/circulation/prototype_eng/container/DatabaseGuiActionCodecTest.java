package com.circulation.prototype_eng.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseGuiActionCodecTest {
    private final Gson codec = new GsonBuilder().create();

    @Test void databaseViewUsesDefaultGsonForItsActualInboundAndOutboundShape() {
        String json = "{\"offset\":12,\"research\":\"test:research\",\"level\":3}";
        var decoded = codec.fromJson(json, ResearchContainer.DatabaseView.class);
        assertEquals(12, decoded.offset);
        assertEquals("test:research", decoded.research);
        assertEquals(3, decoded.level);
        String encoded = codec.toJson(new ResearchContainer.DatabaseView(12, "test:research", 3));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
    }

    @Test void downloadWireRetainsTheLastDisplayedTeamAndPhysicalDatabase() {
        String database = "00000000-0000-0000-0000-000000000041";
        String json = """
            {"team":"team","database":"00000000-0000-0000-0000-000000000041","research":"test:research","level":3}
            """;
        var decoded = codec.fromJson(json, ResearchContainer.DatabaseAction.class);
        assertEquals("team", decoded.team);
        assertEquals(database, decoded.database);
        assertEquals("test:research", decoded.research);
        assertEquals(3, decoded.level);
        String encoded = codec.toJson(new ResearchContainer.DatabaseAction("team", database, "test:research", 3));
        assertEquals(codec.fromJson(json, JsonObject.class), codec.fromJson(encoded, JsonObject.class));
    }

    @Test void oldDrawnTeamRejectsSameResearchAndLevelInTheNewTeam() {
        for (var kind : new ResearchContainer.Kind[] { ResearchContainer.Kind.START, ResearchContainer.Kind.BLACKLIST, ResearchContainer.Kind.TAKEOVER }) {
            var sent = new ResearchContainer.Action(kind, "test:research", 3, 41, "", true, "team_a");
            var received = codec.fromJson(codec.toJson(sent), ResearchContainer.Action.class);
            assertEquals("test:research", received.research);
            assertEquals(3, received.level);
            assertEquals(41, received.entryId);
            assertEquals("team_a", received.expectedTeam);
            assertTrue(ResearchContainer.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_a"));
            assertFalse(ResearchContainer.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_b"));
        }
        var missingTeam = codec.fromJson("{\"kind\":\"START\",\"research\":\"test:research\",\"level\":3}", ResearchContainer.Action.class);
        assertFalse(ResearchContainer.matchesDisplayedTeam(missingTeam.kind, missingTeam.expectedTeam, "team_b"));
    }

    @Test void permanentRewardClaimRemainsAvailableAfterChangingTeams() {
        var sent = new ResearchContainer.Action(ResearchContainer.Kind.CLAIM, "test:research", 3, 0, "test:group", false, "team_a");
        var received = codec.fromJson(codec.toJson(sent), ResearchContainer.Action.class);
        assertTrue(ResearchContainer.matchesDisplayedTeam(received.kind, received.expectedTeam, "team_b"));
        assertTrue(ResearchContainer.matchesDisplayedTeam(received.kind, null, ""));
    }

}
