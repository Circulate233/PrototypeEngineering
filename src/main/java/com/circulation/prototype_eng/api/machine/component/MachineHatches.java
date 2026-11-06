package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable discovered hatch bindings, installed by the host at its machine ownership boundary.
 * Discovery belongs to the server thread; recipe execution uses only the captured public contracts.
 * Replacing these bindings does not reactivate old contexts or waive their lifecycle validation.
 */
public final class MachineHatches {
    private static final MachineHatches EMPTY = new MachineHatches(List.of(), List.of(), List.of());
    public static final ComponentType<MachineInstance, MachineHatches> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "hatches"), _ -> EMPTY, ObjectLists.emptyList());

    private final List<ResourceHatch> resources;
    private final List<EnergyInput> energyInputs;
    private final List<EnergyOutput> energyOutputs;

    public MachineHatches(Collection<? extends ResourceHatch> resources,
                          Collection<? extends EnergyInput> energyInputs,
                          Collection<? extends EnergyOutput> energyOutputs) {
        this.resources = copy(resources);
        this.energyInputs = copy(energyInputs);
        this.energyOutputs = copy(energyOutputs);
    }

    private static <T> List<T> copy(Collection<? extends T> values) {
        var result = new ObjectArrayList<T>(values.size());
        for (var value : values) {
            result.add(Objects.requireNonNull(value));
        }
        return ObjectLists.unmodifiable(result);
    }

    public static MachineHatches get(MachineInstance machine) {
        var hatches = machine.components().get(TYPE);
        return hatches == null ? EMPTY : hatches;
    }

    public List<ResourceHatch> resources() { return resources; }
    public List<EnergyInput> energyInputs() { return energyInputs; }
    public List<EnergyOutput> energyOutputs() { return energyOutputs; }
}
