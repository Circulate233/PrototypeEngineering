package com.circulation.prototype_eng.api.machine.component;

import java.util.Set;

/**
 * Optional component declaration of shared execution sources, including external devices and caches.
 * The scheduler calls this on its owner thread before dispatch. Capture must be observational and
 * return a non-null set of non-null keys; an empty set is valid. Keys, their equals/hashCode and the
 * resources they identify must remain stable until every worker has joined. A source change belongs
 * to a joined owner boundary. MachineInstance copies each result immediately and never caches it.
 */
@FunctionalInterface
public interface AffinityKeyProvider {
    Set<?> affinityKeys();
}
