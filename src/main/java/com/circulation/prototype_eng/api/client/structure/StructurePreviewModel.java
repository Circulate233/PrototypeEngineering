package com.circulation.prototype_eng.api.client.structure;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.localization.StructureTitle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Complete presentation result. A retained model needs an exact-current catalog check before use.
 * Lists are transferred from the builder and are read-only for the model's lifetime.
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
     * The builder transfers its private lists and must not mutate them after this call.
     */
    private StructurePreviewModel(StructureCatalogView generation, ResourceLocation structureId, StructurePreviewSettings settings,
                                  ObjectArrayList<Cell> cells, StructureBounds bounds, ObjectArrayList<BomLine> bom,
                                  ObjectArrayList<Occurrence> occurrences) {
        this.generation = generation;
        this.structureId = structureId;
        this.settings = settings;
        this.bounds = bounds;
        this.cells = cells;
        this.bom = bom;
        this.occurrences = occurrences;
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

    public @Nullable LocalizationEnum title() {
        var entry = generation.entries().get(structureId);
        return entry == null || entry.translationKey() == null ? null : new StructureTitle(entry.translationKey());
    }

    /**
     * Single-owner data construction only; geometry traversal belongs in client.preview.
     * Call freeze once, then discard the builder without further mutation.
     */
    public static final class Builder {
        private final StructureCatalogView generation;
        private final ResourceLocation id;
        private final StructurePreviewSettings settings;
        private final ObjectArrayList<Cell> cells = new ObjectArrayList<>();
        private final ObjectArrayList<BomLine> bom = new ObjectArrayList<>();
        private final ObjectArrayList<Occurrence> occurrences = new ObjectArrayList<>();

        public Builder(StructureCatalogView generation, ResourceLocation id, StructurePreviewSettings settings) {
            this.generation = generation;
            this.id = id;
            this.settings = settings;
            if (!generation.entries().containsKey(id))
                throw new IllegalArgumentException("Unknown preview entry: " + id);
        }

        public void addCell(Cell cell) {
            if (cells.size() == settings.maxCellVisits())
                throw new IllegalArgumentException("Preview cell limit exceeded");
            cells.add(cell);
        }

        public void addBom(BomLine line) {
            bom.add(line);
        }

        public void addOccurrence(Occurrence occurrence) {
            if (occurrences.size() == settings.maxOccurrences())
                throw new IllegalArgumentException("Preview occurrence limit exceeded");
            occurrences.add(occurrence);
        }

        /**
         * Transfers owned lists without copying. The caller must not use this builder again.
         */
        public StructurePreviewModel freeze(StructureBounds bounds) {
            return new StructurePreviewModel(generation, id, settings, cells, bounds, bom, occurrences);
        }
    }

    /**
     * The old layer control selects one relative Y plane without changing the whole-structure BOM.
     * List arguments and results, including nested lists, must support constant-time indexed access.
     */
    public record Layer(int y) {
        public boolean includes(Cell cell) {
            return cell.position().getY() == y;
        }
    }

    public record Cell(BlockPos position, StructurePreviewPath occurrence, int node, int slice,
                       @Nullable StructureDisplay display, boolean hideWorldBlocks) {
        public Cell {
            position = position.toImmutable();
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
            origin = origin.toImmutable();
        }
    }
}
