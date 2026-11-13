package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroups;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Immutable discovered hatch bindings, installed by the host at its machine ownership boundary.
 * Discovery belongs to the server thread; recipe execution uses only the captured public contracts.
 * Replacing these bindings does not reactivate old contexts or waive their lifecycle validation.
 */
public final class MachineHatches {
    public static final ComponentType<MachineInstance, MachineHatches> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "hatches"), _ -> MachineHatches.EMPTY, ObjectLists.emptyList());
    private static final MachineHatches EMPTY = new MachineHatches(List.of(), List.of(), List.of());
    private final List<? extends ResourceHatch> resources;
    private final List<? extends EnergyInput> energyInputs;
    private final List<? extends EnergyOutput> energyOutputs;
    private volatile ResourceInputGroups.Capture inputGroups;

    /**
     * Borrows these ordered bindings. The producer must stop modifying the lists before publication;
     * consumers may read them for this binding lifetime and must never mutate them.
     */
    public MachineHatches(List<? extends ResourceHatch> resources,
                          List<? extends EnergyInput> energyInputs,
                          List<? extends EnergyOutput> energyOutputs) {
        this.resources = resources;
        this.energyInputs = energyInputs;
        this.energyOutputs = energyOutputs;
    }

    public static MachineHatches get(MachineInstance machine) {
        var hatches = machine.components().get(TYPE);
        return hatches == null ? EMPTY : hatches;
    }

    public List<? extends ResourceHatch> resources() {
        return resources;
    }

    /**
     * Reuse immutable partitions until a physical color/partition membership changes.
     */
    public ResourceInputGroups.Capture inputGroups() {
        var captured = inputGroups;
        if (captured != null && captured.current(this)) return captured;
        captured = ResourceInputGroups.capture(this);
        inputGroups = captured;
        return captured;
    }

    public List<? extends EnergyInput> energyInputs() {
        return energyInputs;
    }

    public List<? extends EnergyOutput> energyOutputs() {
        return energyOutputs;
    }
}
