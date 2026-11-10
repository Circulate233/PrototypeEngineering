package com.circulation.prototype_eng.api.team;

import java.util.UUID;

/**
 * Resolves a saved machine owner's current persistent team on the authoritative server thread.
 * Consumers capture a new resolution for each planning batch; an accepted snapshot does not grant
 * permission in a later batch or replace the current machine registration.
 */
@FunctionalInterface
public interface OwnerTeamResolver {
    /**
     * Queries persistent player state, including owners who are currently offline.
     */
    OwnerTeamResolution resolve(UUID ownerId);
}
