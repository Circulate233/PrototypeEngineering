package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Capture-side world access. A published StructureSnapshot retains no reference to this source.
 */
public interface StructureReadSource {
    void requireServerThread();

    /**
     * Must not force-load the requested area. Preserve unavailable areas as unavailable facts.
     */
    boolean isAreaLoaded(StructureBounds bounds);

    /**
     * Called only after the position's own area-loaded check succeeded.
     */
    @Nullable <T> T read(StructureQuery<T> query, BlockPos position);

    /** Unknown area overrides keep their actual whole-area cooperative call. */
    default StructureOperation<Boolean> beginAreaRead(StructureBounds bounds) {
        requireServerThread();
        return StructureOperation.call(() -> {
            requireServerThread();
            return isAreaLoaded(bounds);
        });
    }

    /** Explicit native preparation may visit loaded columns incrementally; final proof uses beginAreaRead. */
    default StructureOperation<Boolean> beginWorkerAreaRead(StructureBounds bounds) {
        return beginAreaRead(bounds);
    }

    /**
     * Owner preparation keeps this source's real virtual read, including old host overrides.
     * Null from an explicit source means that its position became unavailable before borrowing.
     * A scheduler physically joins the returned worker read and closes it before World resumes.
     */
    @Nullable
    default <T> StructureReadWindow<T> beginWorkerRead(StructureQuery<T> query, BlockPos position) {
        requireServerThread();
        return StructureReadWindow.detached(query.copy(read(query, position)));
    }
}
