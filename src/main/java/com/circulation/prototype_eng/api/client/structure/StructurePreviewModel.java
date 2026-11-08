package com.circulation.prototype_eng.api.client.structure;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.localization.StructureTitle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Complete presentation result. A retained model needs an exact-current catalog check before use.
 */
@SuppressWarnings("ClassCanBeRecord")
// Public construction would expose the private builder's transferred mutable lists.
public final class StructurePreviewModel {
    private final StructureCatalogView generation;
    private final ResourceLocation structureId;
    private final StructurePreviewSettings settings;
    private final List<Cell> cells;
    private final StructureBounds bounds;
    private final List<BomLine> bom;
    private final List<Occurrence> occurrences;

    /**
     * Only this model's Builder transfers its private lists and permanently closes mutation.
     */
    private StructurePreviewModel(StructureCatalogView generation, ResourceLocation structureId, StructurePreviewSettings settings,
                                  ObjectArrayList<Cell> cells, StructureBounds bounds, ObjectArrayList<BomLine> bom,
                                  ObjectArrayList<Occurrence> occurrences) {
        this.generation = Objects.requireNonNull(generation);
        this.structureId = Objects.requireNonNull(structureId);
        this.settings = Objects.requireNonNull(settings);
        this.bounds = Objects.requireNonNull(bounds);
        this.cells = ObjectLists.unmodifiable(cells);
        this.bom = ObjectLists.unmodifiable(bom);
        this.occurrences = ObjectLists.unmodifiable(occurrences);
    }

    public StructureCatalogView generation() {
        return generation;
    }

    public ResourceLocation structureId() {
        return structureId;
    }

    public StructurePreviewSettings settings() {
        return settings;
    }

    public List<Cell> cells() {
        return cells;
    }

    public StructureBounds bounds() {
        return bounds;
    }

    public List<BomLine> bom() {
        return bom;
    }

    public List<Occurrence> occurrences() {
        return occurrences;
    }

    /**
     * Single-owner data construction only; geometry traversal belongs in client.preview.
     */
    public static final class Builder {
        private final Thread owner = Thread.currentThread();
        private final StructureCatalogView generation;
        private final ResourceLocation id;
        private final StructurePreviewSettings settings;
        private final ObjectArrayList<Cell> cells = new ObjectArrayList<>();
        private final ObjectArrayList<BomLine> bom = new ObjectArrayList<>();
        private final ObjectArrayList<Occurrence> occurrences = new ObjectArrayList<>();
        private boolean frozen;

        public Builder(StructureCatalogView generation, ResourceLocation id, StructurePreviewSettings settings) {
            this.generation = Objects.requireNonNull(generation);
            this.id = Objects.requireNonNull(id);
            this.settings = Objects.requireNonNull(settings);
            if (!generation.entries().containsKey(id))
                throw new IllegalArgumentException("Unknown preview entry: " + id);
        }

        public void addCell(Cell cell) {
            requireMutable();
            if (cells.size() == settings.maxCellVisits())
                throw new IllegalArgumentException("Preview cell limit exceeded");
            cells.add(Objects.requireNonNull(cell));
        }

        public void addBom(BomLine line) {
            requireMutable();
            bom.add(Objects.requireNonNull(line));
        }

        public void addOccurrence(Occurrence occurrence) {
            requireMutable();
            if (occurrences.size() == settings.maxOccurrences())
                throw new IllegalArgumentException("Preview occurrence limit exceeded");
            occurrences.add(Objects.requireNonNull(occurrence));
        }

        /**
         * Transfers owned lists without copying; this Builder can never mutate them again.
         */
        public StructurePreviewModel freeze(StructureBounds bounds) {
            requireMutable();
            Objects.requireNonNull(bounds);
            frozen = true;
            return new StructurePreviewModel(generation, id, settings, cells, bounds, bom, occurrences);
        }

        private void requireMutable() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Preview model Builder requires its owner thread");
            if (frozen) throw new IllegalStateException("Preview model Builder is frozen");
        }
    }

    public @Nullable LocalizationEnum title() {
        var entry = generation.entries().get(structureId);
        return entry == null || entry.translationKey() == null ? null : new StructureTitle(entry.translationKey());
    }

    /**
     * The old layer control selects one relative Y plane without changing the whole-structure BOM.
     */
    public record Layer(int y) {
        public boolean includes(Cell cell) {
            return cell.position().getY() == y;
        }
    }

    public record Cell(BlockPos position, StructurePreviewPath occurrence, int node, int slice,
                       @Nullable StructureDisplay display, boolean hideWorldBlocks) {
        public Cell {
            position = Objects.requireNonNull(position).toImmutable();
            Objects.requireNonNull(occurrence);
        }
    }

    /**
     * Null display means no published representation, never an inferred matcher or block identity.
     */
    public record BomLine(@Nullable StructureDisplay display, long count) {
        public BomLine {
            if (count <= 0) throw new IllegalArgumentException("Nonpositive BOM count");
        }
    }

    public record Occurrence(StructurePreviewPath path, int node, BlockPos origin, int selectedSlices,
                             boolean opaque) {
        public Occurrence {
            Objects.requireNonNull(path);
            origin = Objects.requireNonNull(origin).toImmutable();
        }
    }
}
