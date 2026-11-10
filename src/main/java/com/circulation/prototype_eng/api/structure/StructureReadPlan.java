package com.circulation.prototype_eng.api.structure;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
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

    private StructureReadPlan(List<StructureBounds> areas, List<Read<?>> reads) {
        this.areas = Collections.unmodifiableList(areas);
        this.reads = Collections.unmodifiableList(reads);
    }

    public List<StructureBounds> areas() {
        return areas;
    }

    public List<Read<?>> reads() {
        return reads;
    }

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

        public Builder area(StructureBounds bounds) {
            areas.add(Objects.requireNonNull(bounds));
            return this;
        }

        public <T> Builder read(BlockPos position, StructureQuery<T> query) {
            var request = new Read<>(position, query);
            var previous = types.putIfAbsent(query.id(), query);
            if (previous != null && previous != query) {
                throw new IllegalArgumentException("Different structure queries share ID: " + query.id());
            }
            areas.add(StructureBounds.at(request.position()));
            if (uniqueReads.add(request)) {
                reads.add(request);
            }
            return this;
        }

        public StructureReadPlan build() {
            return Objects.requireNonNull(beginBuild().run());
        }

        /** The builder must remain unchanged until this private freeze computation completes. */
        @SuppressWarnings("ReturnOfInnerClass")
        // The private freeze cursor owns its builder traversal until completion.
        public StructureOperation<StructureReadPlan> beginBuild() {
            var areaIterator = areas.iterator();
            var readIterator = reads.iterator();
            return new StructureOperation<>() {
                private final ObjectArrayList<StructureBounds> frozenAreas = new ObjectArrayList<>();
                private final ObjectArrayList<Read<?>> frozenReads = new ObjectArrayList<>();

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    Objects.requireNonNull(work);
                    if (complete()) return true;
                    while (areaIterator.hasNext()) {
                        if (!work.take()) return false;
                        frozenAreas.add(areaIterator.next());
                    }
                    while (readIterator.hasNext()) {
                        if (!work.take()) return false;
                        frozenReads.add(readIterator.next());
                    }
                    if (!work.take()) return false;
                    finish(new StructureReadPlan(frozenAreas, frozenReads));
                    return true;
                }
            };
        }
    }
}
