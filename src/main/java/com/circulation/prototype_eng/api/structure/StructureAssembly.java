package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Complete server-side candidate: the matched tree and hatch bindings must describe the same attempt.
 */
public record StructureAssembly(@Nullable StructureMatch match, @Nullable MachineHatches hatches,
                                StructureComponents components) {
    /**
     * A completed current match proved the structure absent, distinct from an obsolete candidate.
     */
    public static final StructureAssembly UNFORMED = new StructureAssembly(null, null);

    public StructureAssembly(@Nullable StructureMatch match, @Nullable MachineHatches hatches) {
        this(match, hatches, StructureComponents.EMPTY);
    }

    public StructureAssembly {
        Objects.requireNonNull(components);
        if ((match == null) != (hatches == null)) {
            throw new IllegalArgumentException("Structure tree and hatches must be installed together");
        }
        if (match == null && !components.bindings().isEmpty()) {
            throw new IllegalArgumentException("Unformed structure cannot have ports");
        }
    }
}
