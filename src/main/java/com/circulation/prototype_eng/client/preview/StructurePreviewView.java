package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** One viewer/wrapper's choices and bounded observations; all geometry is supplied by the C service. */
public final class StructurePreviewView {
    private final StructurePreviews previews;
    private final ResourceLocation id;
    private final @Nullable MachineType machine;
    private final Object2IntOpenHashMap<StructureDisplay> alternatives = new Object2IntOpenHashMap<>();
    private final Object2ObjectOpenHashMap<BlockPos, StructurePreviewModel.Cell> cells = new Object2ObjectOpenHashMap<>();
    private final Object2LongOpenHashMap<StructureDisplay> mismatchCounts = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<StructureDisplay> unloadedCounts = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<StructureDisplay> knownCounts = new Object2LongOpenHashMap<>();
    private final ObjectOpenHashSet<StructureDisplay> membership = new ObjectOpenHashSet<>();
    private final Object2ObjectOpenHashMap<StructureDisplay, String> names = new Object2ObjectOpenHashMap<>();
    public String search = "";
    public Sort sort = Sort.ISSUES;
    public boolean showMatch = true, showMismatch = true, showUnloaded = true, showUnknown = true;
    public boolean scan, threeDimensional = true, materialsOpen;
    public boolean autoRotate, wireframe = true;
    public int layer;
    private boolean formedPreview, rootHideWorldBlocks;
    private StructureBounds controllerBounds;
    private StructurePreviewSettings settings;
    private StructurePreviews.Request request;
    private StructurePreviewModel model;
    private Object token = new Object();
    private Int2ObjectOpenHashMap<StructureProjectionState.Status> statuses = new Int2ObjectOpenHashMap<>();
    private ObjectIterator<StructureDisplay> prune;
    private int cellCursor, bomCursor;
    private boolean indexed;
    private long countsRevision, namesRevision;
    private MaterialKey materialKey;
    private List<Material> materialRows = List.of();
    private int scanCursor, sceneCursor, overlayCursor;
    private Object world;
    private BlockPos anchor = BlockPos.ORIGIN;
    private StructureDisplay selected;
    private StructurePreviewModel.Cell picked;
    private StructurePreviewModel.Cell controllerCell;
    private BlockPos restorePick;
    public StructurePreviewView(StructurePreviews previews, ResourceLocation id, StructurePreviewSettings settings) {
        this(previews, id, settings, null);
    }
    /** Explicit declaration context is presentation only; bare structures never infer a machine. */
    public StructurePreviewView(StructurePreviews previews, ResourceLocation id, StructurePreviewSettings settings,
                                @Nullable MachineType machine) {
        this.previews = Objects.requireNonNull(previews);
        this.id = Objects.requireNonNull(id);
        if (machine != null && !machine.structureId().equals(id))
            throw new IllegalArgumentException("Preview machine does not reference the requested structure");
        this.machine = machine;
        this.settings = Objects.requireNonNull(settings);
        settings.displayOptions().forEach((display, option) -> alternatives.put(display, option.intValue()));
    }

    private static void addCount(Object2LongOpenHashMap<StructureDisplay> counts, @Nullable StructureDisplay display, long delta) {
        long total = Math.addExact(counts.getLong(display), delta);
        if (total < 0) throw new IllegalStateException("Negative observed material count");
        counts.addTo(display, delta);
    }

    private static BlockPos add(BlockPos left, BlockPos right) {
        return new BlockPos(Math.addExact(left.getX(), right.getX()), Math.addExact(left.getY(), right.getY()), Math.addExact(left.getZ(), right.getZ()));
    }

    public StructurePreviews previews() {
        return previews;
    }

    public ResourceLocation id() {
        return id;
    }

    public StructurePreviewSettings settings() {
        return settings;
    }

    public Object token() {
        return token;
    }

    public @Nullable MachineType machine() {
        return machine;
    }

    public boolean formedPreview() {
        return formedPreview;
    }

    public boolean formingModelsVisible() {
        return formedPreview && threeDimensional;
    }

    /** Include the independent controller only in 3D framing; complete geometry/BOM stay authoritative. */
    public @Nullable StructureBounds sceneBounds() {
        var complete = model();
        return complete == null ? null : controllerBounds;
    }

    public boolean formedPreview(Object expected, boolean next) {
        if (expected != token || !inputCurrent() || machine == null) return false;
        if (formedPreview != next) {
            formedPreview = next;
            token = new Object();
            picked = null;
            sceneCursor = overlayCursor = 0;
        }
        return true;
    }

    public boolean inputCurrent() {
        return !previews.closed() && request != null && request.state() != StructurePreviews.State.STALE
            && request.state() != StructurePreviews.State.FAILED && (model == null || previews.current(model));
    }

    public @Nullable StructurePreviewModel model() {
        return indexed && model != null && !previews.closed() && previews.current(model) ? model : null;
    }

    public @Nullable StructureDisplay selected() {
        return selected;
    }

    public @Nullable StructurePreviewModel.Cell picked() {
        return picked;
    }

    void controllerDisplay(StructureDisplay display) {
        if (controllerCell != null && display.equals(controllerCell.display())) return;
        controllerCell = new StructurePreviewModel.Cell(BlockPos.ORIGIN, StructurePreviewPath.ROOT, -1, -1, display, false);
        token = new Object();
    }

    @Nullable StructurePreviewModel.Cell controllerCell() {
        return controllerCell;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public void anchor(BlockPos next) {
        if (!anchor.equals(next)) {
            anchor = next.toImmutable();
            resetObservations();
        }
    }

    public StructurePreviews.State state() {
        if (request == null) return StructurePreviews.State.STALE;
        var state = request.state();
        return state == StructurePreviews.State.READY && !indexed ? StructurePreviews.State.BUILDING : state;
    }

    public void advance(int visits) {
        if (visits < 0) throw new IllegalArgumentException("Negative index visits");
        if (previews.closed()) {
            forgetModel();
            return;
        }
        var next = previews.request(id, settings);
        if (next != request) {
            request = next;
            forgetModel();
        }
        if (next == null || next.state() == StructurePreviews.State.FAILED) return;
        int remaining = visits - next.advance(visits);
        var completed = next.model();
        if (completed != model) {
            forgetModel();
            model = completed;
            if (completed != null) {
                var entry = completed.generation().entries().get(completed.structureId());
                rootHideWorldBlocks = completed.generation().nodes().get(entry.node()).hideWorldBlocks();
                var bounds = completed.bounds();
                controllerBounds = new StructureBounds(new BlockPos(Math.min(0, bounds.min().getX()), Math.min(0, bounds.min().getY()), Math.min(0, bounds.min().getZ())),
                    new BlockPos(Math.max(0, bounds.max().getX()), Math.max(0, bounds.max().getY()), Math.max(0, bounds.max().getZ())));
                layer = Math.clamp(controllerBounds.min().getY() + (controllerBounds.max().getY() - controllerBounds.min().getY() + 1) / 2,
                    completed.bounds().min().getY(), completed.bounds().max().getY());
            }
        }
        if (completed == null || indexed) return;
        while (remaining > 0 && cellCursor < completed.cells().size()) {
            var cell = completed.cells().get(cellCursor++);
            cells.put(cell.position(), cell);
            remaining--;
        }
        while (remaining > 0 && bomCursor < completed.bom().size()) {
            membership.add(completed.bom().get(bomCursor++).display());
            remaining--;
        }
        if (cellCursor != completed.cells().size() || bomCursor != completed.bom().size()) return;
        if (prune == null) prune = alternatives.keySet().iterator();
        while (remaining > 0 && prune.hasNext()) {
            if (!membership.contains(prune.next())) prune.remove();
            remaining--;
        }
        if (prune.hasNext()) return;
        if (selected != null && !membership.contains(selected)) selected = null;
        if (restorePick != null) {
            picked = cells.get(restorePick);
            selected = picked == null ? null : picked.display();
            restorePick = null;
        }
        indexed = true;
    }

    private void forgetModel() {
        model = null;
        controllerBounds = null;
        rootHideWorldBlocks = false;
        cells.clear();
        membership.clear();
        prune = null;
        cellCursor = bomCursor = 0;
        indexed = false;
        token = new Object();
        picked = null;
        invalidateMaterials();
        resetObservations();
    }

    private void resetObservations() {
        scanCursor = sceneCursor = overlayCursor = 0;
        statuses = new Int2ObjectOpenHashMap<>();
        mismatchCounts.clear();
        unloadedCounts.clear();
        knownCounts.clear();
        countsRevision++;
    }

    public void scan(@Nullable Object nextWorld, StructureProjectionState.Observer observer, int visits) {
        if (visits < 0) throw new IllegalArgumentException("Negative observation budget");
        if (world != nextWorld) {
            world = nextWorld;
            resetObservations();
            token = new Object();
        }
        var complete = model();
        if (!scan || nextWorld == null || complete == null || complete.cells().isEmpty()) return;
        var round = token;
        var values = statuses;
        for (int step = 0; step < Math.min(visits, complete.cells().size()); step++) {
            var index = scanCursor;
            var cell = complete.cells().get(index);
            var observed = Objects.requireNonNull(observer.observe(cell, add(anchor, cell.position())));
            if (round != token || complete != model() || values != statuses || nextWorld != world) return;
            if (values.get(index) != observed) {
                updateCount(cell.display(), values.get(index), -1);
                updateCount(cell.display(), observed, 1);
                values.put(index, observed);
                countsRevision++;
            }
            scanCursor = (index + 1) % complete.cells().size();
        }
    }

    private void updateCount(@Nullable StructureDisplay display, @Nullable StructureProjectionState.Status status, long delta) {
        if (status == StructureProjectionState.Status.MISSING || status == StructureProjectionState.Status.DIFFERENT)
            addCount(mismatchCounts, display, delta);
        else if (status == StructureProjectionState.Status.UNLOADED) addCount(unloadedCounts, display, delta);
        if (status != null && status != StructureProjectionState.Status.UNKNOWN) addCount(knownCounts, display, delta);
    }

    public int alternative(@Nullable StructureDisplay display) {
        return display == null ? 0 : alternatives.getInt(display);
    }

    public boolean alternative(Object expected, StructureDisplay display, int option) {
        var complete = model();
        if (expected != token || complete == null || option < 0 || option >= display.options().size()
            || !membership.contains(display)) return false;
        alternatives.put(display, option);
        var options = new Object2ObjectOpenHashMap<>(settings.displayOptions());
        options.put(display, option);
        settings = new StructurePreviewSettings(settings.orientation(), settings.sliceCounts(), settings.maxOccurrences(), settings.maxCellVisits(), options);
        request = null;
        restorePick = picked == null ? null : picked.position();
        forgetModel();
        return true;
    }

    public boolean slices(Object expected, StructurePreviewPath path, int count) {
        var complete = model();
        if (expected != token || complete == null) return false;
        for (var occurrence : complete.occurrences()) {
            if (!occurrence.path().equals(path)) continue;
            var node = complete.generation().nodes().get(occurrence.node());
            if (node.kind() != StructurePreviewProvider.Kind.SLICE || count < node.minCount() || count > node.maxCount())
                return false;
            var choices = new Object2ObjectOpenHashMap<>(settings.sliceCounts());
            choices.put(path, count);
            settings = new StructurePreviewSettings(settings.orientation(), choices, settings.maxOccurrences(), settings.maxCellVisits(), settings.displayOptions());
            request = null;
            forgetModel();
            return true;
        }
        return false;
    }

    public boolean choose(Click click) {
        if (click == null || click.token() != token || model() == null || !visible(click.cell())
            || click.cell() != controllerCell && cells.get(click.cell().position()) != click.cell())
            return false;
        picked = picked != null && picked.position().equals(click.cell().position()) ? null : click.cell();
        selected = picked == null ? null : picked.display();
        return true;
    }

    public void select(@Nullable StructureDisplay display) {
        selected = display;
        picked = null;
    }

    public boolean visible(StructurePreviewModel.Cell cell) {
        if (cell == controllerCell) return threeDimensional || layer == 0;
        if (controllerCell != null && cell.position().equals(BlockPos.ORIGIN)) return false;
        return (threeDimensional || cell.position().getY() == layer)
            && (!formingModelsVisible() || !rootHideWorldBlocks && !cell.hideWorldBlocks());
    }

    public List<StructurePreviewModel.Cell> sceneSamples(int visits) {
        return sample(visits, false);
    }

    public List<StructurePreviewModel.Cell> overlaySamples(int visits) {
        return sample(visits, true);
    }

    private List<StructurePreviewModel.Cell> sample(int visits, boolean overlay) {
        if (visits < 0) throw new IllegalArgumentException("Negative scene visits");
        var result = new ObjectArrayList<StructurePreviewModel.Cell>();
        var complete = model();
        if (complete == null || complete.cells().isEmpty()) return result;
        int cursor = overlay ? overlayCursor : sceneCursor;
        for (int step = 0; step < Math.min(visits, complete.cells().size()); step++) {
            var cell = complete.cells().get(cursor);
            cursor = (cursor + 1) % complete.cells().size();
            if (visible(cell)) result.add(cell);
        }
        if (overlay) overlayCursor = cursor;
        else sceneCursor = cursor;
        return result;
    }

    public @Nullable StructurePreviewModel.Cell at(BlockPos position) {
        return model() == null ? null : cells.get(position);
    }

    @Nullable StructureProjectionState.Status observation(int index) {
        return statuses.get(index);
    }

    @Nullable StructureProjectionState.Status pickedStatus() {
        var complete = model();
        return picked == null || complete == null ? null : statuses.get(complete.cells().indexOf(picked));
    }

    /** Full-structure counts deliberately never use the visible layer. Counts remain long throughout. */
    public List<Material> materials(Function<StructureDisplay, String> names, String unknownName) {
        var complete = model();
        if (complete == null) return List.of();
        var key = new MaterialKey(token, countsRevision, namesRevision, search.trim().toLowerCase(Locale.ROOT), sort,
            showMatch, showMismatch, showUnloaded, showUnknown, scan, unknownName);
        if (key.equals(materialKey)) return materialRows;
        var result = materialRows(complete, names, unknownName);
        if (key.token != token || key.names != namesRevision || complete != model()) return List.of();
        materialRows = List.copyOf(filterAndSort(result));
        materialKey = key;
        return materialRows;
    }

    /** Resource and language reloads invalidate native names even when geometry and counts stay the same. */
    public void invalidateMaterials() {
        names.clear();
        namesRevision++;
        materialKey = null;
        materialRows = List.of();
    }

    private List<Material> materialRows(StructurePreviewModel complete, Function<StructureDisplay, String> nameProvider, String unknownName) {
        var result = new ObjectArrayList<Material>();
        var round = token;
        long nameEpoch = namesRevision;
        for (var line : complete.bom()) {
            var display = line.display();
            var name = display == null ? unknownName : names.get(display);
            if (name == null) {
                name = Objects.requireNonNull(nameProvider.apply(display));
                if (complete != model() || round != token || nameEpoch != namesRevision) return List.of();
                names.put(display, name);
            }
            result.add(new Material(display, name, line.count(), scan ? mismatchCounts.getLong(display) : 0,
                scan ? unloadedCounts.getLong(display) : 0, scan ? line.count() - knownCounts.getLong(display) : line.count()));
        }
        return result;
    }

    public List<Material> filterAndSort(List<Material> rows) {
        var result = filteredRows(rows);
        result.sort(switch (sort) {
            case COUNT -> Comparator.comparingLong(Material::count).reversed().thenComparing(Material::name);
            case NAME -> Comparator.comparing(Material::name).thenComparingLong(Material::count);
            case ISSUES -> Comparator.comparingLong(Material::mismatch).reversed()
                                     .thenComparing(Comparator.comparingLong(Material::unloaded).reversed())
                                     .thenComparing(Comparator.comparingLong(Material::unknown).reversed())
                                     .thenComparing(Comparator.comparingLong(Material::count).reversed()).thenComparing(Material::name);
        });
        return result;
    }

    private ObjectArrayList<Material> filteredRows(List<Material> rows) {
        var result = new ObjectArrayList<Material>();
        var query = search.trim().toLowerCase(Locale.ROOT);
        for (var row : rows) {
            var display = row.display();
            var name = row.name();
            if (!query.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(query)
                && (display == null || !display.key().toLowerCase(Locale.ROOT).contains(query))) continue;
            if ((showMatch && row.count - row.mismatch - row.unloaded > row.unknown)
                || (showMismatch && row.mismatch > 0) || (showUnloaded && row.unloaded > 0) || (showUnknown && row.unknown > 0))
                result.add(row);
        }
        return result;
    }

    public enum Sort {ISSUES, COUNT, NAME}

    public record Material(@Nullable StructureDisplay display, String name, long count,
                           long mismatch, long unloaded, long unknown) {
    }

    /** Identity token prevents a click drawn for a previous choice/generation from selecting new geometry. */
    public record Click(Object token, StructurePreviewModel.Cell cell) {
    }

    private record MaterialKey(Object token, long counts, long names, String query, Sort sort,
                               boolean match, boolean mismatch, boolean unloaded, boolean unknown,
                               boolean scan, String unknownName) {
    }
}
