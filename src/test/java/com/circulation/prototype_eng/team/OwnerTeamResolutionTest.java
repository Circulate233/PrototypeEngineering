package com.circulation.prototype_eng.team;

import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OwnerTeamResolutionTest {
    @Test void membershipRemainsFrozenAcrossProviderChangesAndCannotBeMutatedByConsumers() {
        var owner = UUID.randomUUID();
        var leader = UUID.randomUUID();
        var source = new ObjectOpenHashSet<>(ObjectSet.of(owner, leader));
        var captured = OwnerTeamResolution.resolved(owner, "0001", OwnerTeamType.PLAYER, source, Optional.of(leader));
        source.clear();
        source.add(owner);
        var next = OwnerTeamResolution.resolved(owner, "0002", OwnerTeamType.SERVER, source, Optional.empty());
        assertEquals(ObjectSet.of(owner), next.members());
        assertTrue(next.teamOwnerId().isEmpty());
        assertEquals(ObjectSet.of(owner, leader), captured.members());
        assertEquals(Optional.of(leader), captured.teamOwnerId());
        assertThrows(UnsupportedOperationException.class, () -> captured.members().remove(owner));
        assertThrows(UnsupportedOperationException.class, () -> captured.members().removeIf(_ -> true));
        var iterator = captured.members().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals(ObjectSet.of(owner, leader), captured.members());
    }

}
