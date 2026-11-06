package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;

import java.util.Objects;

/**
 * One live configuration generation, never persisted as permission. Its identity must change on
 * definition, orientation or owner/permission changes, including A -> B -> A. A host keeps the
 * same object until such a change; recreating it on each read would reject every pending result.
 */
@SuppressWarnings("ClassCanBeRecord") // Identity, not value equality, prevents stale-generation publication.
public final class StructureConfiguration {
    private final MachineStructure definition;
    private final BlockOrientation orientation;

    public StructureConfiguration(MachineStructure definition, BlockOrientation orientation) {
        this.definition = Objects.requireNonNull(definition);
        this.orientation = Objects.requireNonNull(orientation);
    }

    public MachineStructure definition() { return definition; }
    public BlockOrientation orientation() { return orientation; }
}
