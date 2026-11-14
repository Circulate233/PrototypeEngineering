package com.circulation.prototype_eng.api.research.download;

import net.minecraft.item.ItemStack;

/**
 * Computes a candidate output on the authoritative server thread. The callback must not change
 * inventories, the world, team research, the database or persistent state. The caller owns the
 * subsequent item transaction. Return {@link ItemStack#EMPTY} to refuse this download; invalid
 * outputs and callback exceptions propagate to the caller. Successful output is transferred read-only;
 * the producer must not change it after returning.
 */
@FunctionalInterface
public interface ResearchItemDownloadEvent {
    ItemStack createOutput(ResearchItemDownloadContext context);
}
