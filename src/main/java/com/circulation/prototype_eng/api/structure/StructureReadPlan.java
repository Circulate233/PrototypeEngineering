package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Frozen potential reads, including optional slices and validator positions outside the pattern.
 * Planning an area is not a requirement that it be loaded: only the branches visited by a matcher
 * decide which recorded facts matter. Pattern bounds must be declared separately from sparse cells.
 */
public final class StructureReadPlan {
    private final List<StructureBounds> areas;
    private final List<Read<?>> reads;

    private StructureReadPlan(Builder builder) {
        areas = List.copyOf(builder.areas);
        reads = List.copyOf(builder.reads);
    }

    public List<StructureBounds> areas() { return areas; }
    public List<Read<?>> reads() { return reads; }

    public record Read<T>(BlockPos position, StructureQuery<T> query) {
        public Read {
            position = Objects.requireNonNull(position).toImmutable();
            Objects.requireNonNull(query);
        }
    }

    public static final class Builder {
        private final ObjectLinkedOpenHashSet<StructureBounds> areas = new ObjectLinkedOpenHashSet<>();
        private final ObjectLinkedOpenHashSet<Read<?>> uniqueReads = new ObjectLinkedOpenHashSet<>();
        private final ObjectArrayList<Read<?>> reads = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<ResourceLocation, StructureQuery<?>> types = new Object2ObjectOpenHashMap<>();

        public Builder area(StructureBounds bounds) { areas.add(Objects.requireNonNull(bounds)); return this; }

        public <T> Builder read(BlockPos position, StructureQuery<T> query) {
            var request = new Read<>(position, query);
            var previous = types.putIfAbsent(query.id(), query);
            if (previous != null && previous != query) {
                throw new IllegalArgumentException("Different structure queries share ID: " + query.id());
            }
            areas.add(StructureBounds.at(request.position()));
            if (uniqueReads.add(request)) { reads.add(request); }
            return this;
        }

        public StructureReadPlan build() { return new StructureReadPlan(this); }
    }
}
