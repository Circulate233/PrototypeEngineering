package com.circulation.prototype_eng.api.structure;

import net.minecraftforge.fml.common.eventhandler.Event;

/** Posted after structures resolve on both sides; register ordinary external construction anchors here. */
public final class StructureConstructionRegistrationEvent extends Event {
    public StructureConstructionRegistry registry() { return StructureConstructionRegistry.INSTANCE; }
}
