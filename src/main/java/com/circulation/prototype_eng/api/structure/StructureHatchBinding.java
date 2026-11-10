package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergyOutput;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import org.jetbrains.annotations.Nullable;

/**
 * Server-only discovery at a matched position, including provider identity and absent capabilities.
 */
public record StructureHatchBinding(@Nullable Object provider, @Nullable ResourceHatch resource,
                                    @Nullable EnergyInput energyInput, @Nullable EnergyOutput energyOutput) {
    public static final StructureHatchBinding EMPTY = new StructureHatchBinding(null, null, null, null);

    public StructureHatchBinding {
        if (provider == null && (resource != null || energyInput != null || energyOutput != null)) {
            throw new IllegalArgumentException("Hatch contracts require their provider identity");
        }
    }

    public boolean isCurrent(StructureHatchBinding current) {
        return provider == current.provider && resource == current.resource
            && energyInput == current.energyInput && energyOutput == current.energyOutput
            && (resource == null || resource.isValid())
            && (energyInput == null || energyInput.isValid())
            && (energyOutput == null || energyOutput.isValid());
    }
}
