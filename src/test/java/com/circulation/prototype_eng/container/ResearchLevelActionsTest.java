package com.circulation.prototype_eng.container;

import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.view.ResearchPlayerView;
import com.circulation.prototype_eng.research.ServerResearch;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchLevelActionsTest {
    private static final ResearchGuiSnapshot.Entry ROW = new ResearchGuiSnapshot.Entry(new ResourceLocation("level_gui", "research"),
        3, 4, 4, 1, false, 0, null, null, null, null, null);

    private static ResearchGuiSnapshot snapshot(Decision forget, Decision admin) {
        return new ResearchGuiSnapshot(true, false, ResearchPlayerView.TeamStatus.AVAILABLE,
            Decision.ALLOWED, Decision.ALLOWED, Decision.ALLOWED, Decision.ALLOWED, Decision.NOT_TEAM_LEADER,
            ResearchGuiSnapshot.Tab.RESEARCH, 1, 0, ObjectList.of(ROW), ROW.selection(), 0, 0, ObjectList.of(), forget, admin);
    }

    @Test
    void operationPermissionsRoundTripWithIndependentAdmissionAndFollowingGuiSyncField() {
        var view = snapshot(Decision.ALLOWED, Decision.NOT_SERVER_OPERATOR);
        var buffer = Unpooled.buffer();
        try {
            view.writeToPacket(buffer);
            buffer.writeLong(91723);
            var decoded = new ResearchGuiSnapshot(buffer);
            assertEquals(view, decoded);
            assertTrue(ResearchContainer.allowed(decoded, ROW, ResearchContainer.Kind.FORGET));
            assertFalse(ResearchContainer.allowed(decoded, ROW, ResearchContainer.Kind.ADMIN_PLAYER));
            assertEquals(91723, buffer.readLong());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
        var adminOnly = snapshot(Decision.TARGET_PLAYER_MISMATCH, Decision.ALLOWED);
        assertFalse(ResearchContainer.allowed(adminOnly, ROW, ResearchContainer.Kind.FORGET));
        assertTrue(ResearchContainer.allowed(adminOnly, ROW, ResearchContainer.Kind.ADMIN_TEAM));
        assertFalse(ResearchContainer.matchesDisplayedTeam(ResearchContainer.Kind.FORGET, "old", "new"));
        assertFalse(ResearchContainer.matchesDisplayedTeam(ResearchContainer.Kind.ADMIN_TEAM, "old", "new"));
    }

    @Test
    void malformedNewPermissionCannotConsumeTheNextSyncField() {
        var buffer = Unpooled.buffer();
        try {
            snapshot(Decision.ALLOWED, Decision.ALLOWED).writeToPacket(buffer);
            int length = buffer.getInt(0);
            buffer.writeLong(417);
            buffer.setByte(4 + 10, 255);
            assertThrows(IllegalArgumentException.class, () -> new ResearchGuiSnapshot(buffer));
            assertEquals(4 + length, buffer.readerIndex());
            assertEquals(417, buffer.readLong());
        } finally {
            buffer.release();
        }
    }

    @Test
    void asynchronousOperationOrFeedbackFaultReleasesOwnerAndKeepsPrimaryFailure() {
        var queued = new AtomicBoolean(true);
        var failure = new IllegalStateException("operation or feedback failed");
        var cleanup = new IllegalStateException("cleanup failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ServerResearch.runFinalized(() -> {
            throw failure;
        }, () -> {
            queued.set(false);
            throw cleanup;
        })));
        assertFalse(queued.get());
        assertSame(cleanup, failure.getSuppressed()[0]);
        queued.set(true);
        ServerResearch.runFinalized(() -> {
        }, () -> queued.set(false));
        assertFalse(queued.get());
    }
}
