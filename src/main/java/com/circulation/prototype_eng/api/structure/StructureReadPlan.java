package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Frozen potential reads, including optional slices and validator positions outside the pattern.
 * Planning an area is not a requirement that it be loaded: only the branches visited by a matcher
 * decide which recorded facts matter. Pattern bounds must be declared separately from sparse cells.
 * The completed lists are borrowed read-only; neither the builder nor consumers may modify them.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class StructureReadPlan {
    private final List<StructureBounds> areas;
    private final List<Read<?>> reads;

    private StructureReadPlan(List<StructureBounds> areas, List<Read<?>> reads) {
        this.areas = areas;
        this.reads = reads;
    }

    public List<StructureBounds> areas() {
        return areas;
    }

    public List<Read<?>> reads() {
        return reads;
    }

    public record Read<T>(BlockPos position, StructureQuery<T> query) {
        public Read {
            position = position.toImmutable();
        }
    }

    public static final class Builder {
        private final ObjectLinkedOpenHashSet<StructureBounds> uniqueAreas = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<StructureBounds> areas = new ObjectArrayList<>();
        private final ObjectLinkedOpenHashSet<Read<?>> uniqueReads = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<Read<?>> reads = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<ResourceLocation, StructureQuery<?>> types = new Object2ObjectOpenHashMap<>();

        public Builder area(StructureBounds bounds) {
            if (uniqueAreas.add(bounds)) areas.add(bounds);
            return this;
        }

        public <T> Builder read(BlockPos position, StructureQuery<T> query) {
            var request = new Read<>(position, query);
            var previous = types.putIfAbsent(query.id(), query);
            if (previous != null && previous != query) {
                throw new IllegalArgumentException("Different structure queries share ID: " + query.id());
            }
            area(StructureBounds.at(request.position()));
            if (uniqueReads.add(request)) {
                reads.add(request);
            }
            return this;
        }

        public StructureReadPlan build() {
            return beginBuild().run();
        }

        /**
         * Transfers the completed lists. Do not use this builder again or mutate the published lists.
         */
        public StructureOperation<StructureReadPlan> beginBuild() {
            return StructureOperation.call(() -> new StructureReadPlan(areas, reads));
        }
    }
}
