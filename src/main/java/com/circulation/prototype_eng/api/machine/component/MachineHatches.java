package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.structure.StructureOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable discovered hatch bindings, installed by the host at its machine ownership boundary.
 * Discovery belongs to the server thread; recipe execution uses only the captured public contracts.
 * Replacing these bindings does not reactivate old contexts or waive their lifecycle validation.
 */
public final class MachineHatches {
    public static final ComponentType<MachineInstance, MachineHatches> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "hatches"), _ -> MachineHatches.EMPTY, ObjectLists.emptyList());
    private static final MachineHatches EMPTY = new MachineHatches(List.of(), List.of(), List.of());
    private final List<ResourceHatch> resources;
    private final List<EnergyInput> energyInputs;
    private final List<EnergyOutput> energyOutputs;

    private MachineHatches(List<ResourceHatch> resources, List<EnergyInput> inputs, List<EnergyOutput> outputs) {
        this.resources = Collections.unmodifiableList(resources);
        energyInputs = Collections.unmodifiableList(inputs);
        energyOutputs = Collections.unmodifiableList(outputs);
    }

    public MachineHatches(Collection<? extends ResourceHatch> resources,
                          Collection<? extends EnergyInput> energyInputs,
                          Collection<? extends EnergyOutput> energyOutputs) {
        this.resources = copy(resources);
        this.energyInputs = copy(energyInputs);
        this.energyOutputs = copy(energyOutputs);
    }

    /** Owned immutable copies freeze incrementally; no later constructor traverses them again. */
    public static StructureOperation<MachineHatches> beginCreate(Collection<? extends ResourceHatch> resources,
                                                                 Collection<? extends EnergyInput> inputs, Collection<? extends EnergyOutput> outputs) {
        var resourceIterator = resources.iterator();
        var inputIterator = inputs.iterator();
        var outputIterator = outputs.iterator();
        return new StructureOperation<>() {
            private final ObjectArrayList<ResourceHatch> frozenResources = new ObjectArrayList<>();
            private final ObjectArrayList<EnergyInput> frozenInputs = new ObjectArrayList<>();
            private final ObjectArrayList<EnergyOutput> frozenOutputs = new ObjectArrayList<>();

            @Override
            public boolean advance(RecipeWorkBudget work) {
                if (complete()) return true;
                while (resourceIterator.hasNext()) {
                    if (!work.take()) return false;
                    frozenResources.add(Objects.requireNonNull(resourceIterator.next()));
                }
                while (inputIterator.hasNext()) {
                    if (!work.take()) return false;
                    frozenInputs.add(Objects.requireNonNull(inputIterator.next()));
                }
                while (outputIterator.hasNext()) {
                    if (!work.take()) return false;
                    frozenOutputs.add(Objects.requireNonNull(outputIterator.next()));
                }
                if (!work.take()) return false;
                finish(new MachineHatches(frozenResources, frozenInputs, frozenOutputs));
                return true;
            }
        };
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

    public List<ResourceHatch> resources() {
        return resources;
    }

    public List<EnergyInput> energyInputs() {
        return energyInputs;
    }

    public List<EnergyOutput> energyOutputs() {
        return energyOutputs;
    }
}
