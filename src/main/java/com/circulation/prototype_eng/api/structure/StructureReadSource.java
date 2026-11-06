package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Capture-side world access. A published StructureSnapshot retains no reference to this source. */
public interface StructureReadSource {
    void requireServerThread();

    /** Must not force-load the requested area. Preserve unavailable areas as unavailable facts. */
    boolean isAreaLoaded(StructureBounds bounds);

    /** Called only after the position's own area-loaded check succeeded. */
    @Nullable <T> T read(StructureQuery<T> query, BlockPos position);
}
