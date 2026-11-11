package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.structure.SliceStructure;
import com.circulation.prototype_eng.structure.TemplateStructure;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Public construction of the existing budgeted shapes shared by Java and scripting declarations. */
public final class StructureDefinitions {
    private StructureDefinitions() {
    }

    public static MachineStructure template(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                                            boolean hideWorldBlocks, StructurePattern pattern,
                                            List<StructurePredicate> validators, List<MachineStructure> children) {
        return TemplateStructure.create(id, orientation, offset, hideWorldBlocks, pattern, validators, children);
    }

    public static MachineStructure slice(ResourceLocation id, BlockOrientation orientation, BlockPos offset,
                                         boolean hideWorldBlocks, StructurePattern pattern, int minCount, int maxCount,
                                         BlockPos sliceOffset, List<StructurePredicate> validators, List<MachineStructure> children) {
        return SliceStructure.create(id, orientation, offset, hideWorldBlocks, pattern, minCount, maxCount,
            sliceOffset, validators, children);
    }
}
