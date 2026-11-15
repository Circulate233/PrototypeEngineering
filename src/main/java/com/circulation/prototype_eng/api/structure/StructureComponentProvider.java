package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;

/**
 * Optional tile-entity contract for lightweight structure ports. Discovery and creation run at the
 * joined server owner boundary. Creation must not register external state or mutate the machine or
 * world: a complete candidate can be rejected after creation. Entries are copied immediately, but
 * the port contracts themselves must support their consumers' worker ownership. Type systems and
 * factories are not invoked for ports. Every accepted refresh recreates the complete collection.
 */
@FunctionalInterface
public interface StructureComponentProvider {
    Collection<Entry<?>> createStructureComponents(Context context);

    record Context(MachineInstance machine, BlockPos providerPos, long formationGeneration) {
        public Context {
            providerPos = providerPos.toImmutable();
            if (formationGeneration <= 0) {
                throw new IllegalArgumentException("Formation generation must be positive");
            }
        }
    }

    /**
     * Provider-local port identity; the canonical ComponentType also supplies typed queries.
     */
    record Entry<C>(ComponentType<MachineInstance, C> type, String portId, C component) {
        public Entry {
            if (portId.isBlank()) {
                throw new IllegalArgumentException("Structure port ID must not be blank");
            }
        }
    }
}
