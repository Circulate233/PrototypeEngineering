package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import net.minecraft.util.ResourceLocation;

/** Ordinary component-system entry; scheduling and runtime authority belong to the calling host. */
public final class WorkSlotProcessingSystem implements MachineSystem<MachineWorkSlots> {
    public static final ResourceLocation ID = new ResourceLocation(Tags.MOD_ID, "work_slot_processing");

    @Override public ResourceLocation id() { return ID; }

    @Override public void onTick(MachineInstance machine, MachineWorkSlots component) {
        if (component.owner() != machine) { throw new IllegalArgumentException("Work slots installed on a foreign machine"); }
        if (!machine.canProcessRecipes()) { return; }
        component.tickProcesses();
    }
}
