package com.circulation.prototype_eng.integration.ftb;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FtbOwnerTeamResolverTest {
    @Test
    void defaultTeamSurvivesFtbUidReassignmentAndJoiningARealTeamChangesTheKey() {
        var first = new UUID(0, 1);
        var second = new UUID(0, 2);
        assertEquals(first.toString(), FtbOwnerTeamResolver.teamId(true, first, "0002"));
        assertEquals(first.toString(), FtbOwnerTeamResolver.teamId(true, first, "A123"));
        assertNotEquals(FtbOwnerTeamResolver.teamId(true, first, "A123"), FtbOwnerTeamResolver.teamId(true, second, "A123"));
        assertEquals("B234", FtbOwnerTeamResolver.teamId(false, first, "B234"));
        assertEquals("B234", FtbOwnerTeamResolver.teamId(false, second, "B234"));
    }
}
