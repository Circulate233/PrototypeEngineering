package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import org.jetbrains.annotations.Nullable;


/**
 * One live configuration generation, never persisted as permission. Its identity must change on
 * definition, orientation or owner/permission changes, including A -> B -> A. A host keeps the
 * same object until such a change; recreating it on each read would reject every pending result.
 */
@SuppressWarnings("ClassCanBeRecord") // Identity, not value equality, prevents stale-generation publication.
public final class StructureConfiguration {
    private final MachineStructure definition;
    private final BlockOrientation orientation;
    @Nullable
    private final StructureCatalog catalog;

    public StructureConfiguration(MachineStructure definition, BlockOrientation orientation) {
        this(null, definition, orientation);
    }

    public StructureConfiguration(@Nullable StructureCatalog catalog, MachineStructure definition, BlockOrientation orientation) {
        this.definition = definition;
        this.orientation = orientation;
        this.catalog = catalog;
    }

    public MachineStructure definition() {
        return definition;
    }

    public BlockOrientation orientation() {
        return orientation;
    }

    @Nullable
    public StructureCatalog catalog() {
        return catalog;
    }
}
