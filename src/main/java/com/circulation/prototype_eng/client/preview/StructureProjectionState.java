package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Incremental presentation state; the native adapter supplies only observations on the client thread.
 */
public final class StructureProjectionState {
    private final StructurePreviews previews;
    @Nullable
    private Object world;
    @Nullable
    private StructurePreviews.Projection intent;
    @Nullable
    private StructurePreviews.Request request;
    @Nullable
    private StructurePreviewModel model;
    private Status[] statuses = new Status[0];
    private int scanCursor;
    private boolean sweepMatched = true;
    private int renderCursor;

    public StructureProjectionState(StructurePreviews previews) {
        this.previews = Objects.requireNonNull(previews);
    }

    private static BlockPos add(BlockPos left, BlockPos right) {
        return new BlockPos(Math.addExact(left.getX(), right.getX()), Math.addExact(left.getY(), right.getY()), Math.addExact(left.getZ(), right.getZ()));
    }

    public void tick(@Nullable Object currentWorld, Observer observer) {
        Objects.requireNonNull(observer);
        if (previews.closed()) {
            clear();
            return;
        }
        var nextIntent = previews.projection();
        try {
            advance(currentWorld, nextIntent, observer);
        } catch (RuntimeException failure) {
            cancelFailedProjection(nextIntent);
            throw failure;
        }
    }

    private void advance(@Nullable Object currentWorld, @Nullable StructurePreviews.Projection nextIntent, Observer observer) {
        if (world != null && world != currentWorld) {
            previews.stopProjection();
            clear();
            return;
        }
        world = currentWorld;
        if (currentWorld == null || nextIntent == null) {
            clearModel();
            intent = nextIntent;
            return;
        }
        var nextRequest = previews.request(nextIntent.id(), nextIntent.settings());
        if (nextRequest == null) {
            previews.stopProjection();
            clear();
            return;
        }
        if (nextIntent != intent || nextRequest != request) {
            clearModel();
            intent = nextIntent;
            request = nextRequest;
        }
        if (nextRequest.state() == StructurePreviews.State.FAILED) throw Objects.requireNonNull(nextRequest.failure());
        nextRequest.advance(nextIntent.options().expansionStepsPerTick());
        var completed = nextRequest.model();
        if (completed == null) {
            clearObservations();
            model = null;
            return;
        }
        if (completed != model) {
            model = completed;
            statuses = new Status[model.cells().size()];
            scanCursor = 0;
            sweepMatched = true;
            renderCursor = 0;
        }
        if (completed.cells().isEmpty() && nextIntent.options().hideDisplayedMatches()) {
            previews.stopProjection();
            clear();
            return;
        }
        int visits = Math.min(nextIntent.options().scanChecksPerTick(), completed.cells().size());
        for (int i = 0; i < visits; i++) {
            var cell = completed.cells().get(scanCursor);
            var observed = observer.observe(completed, cell, add(nextIntent.anchor(), cell.position()));
            statuses[scanCursor] = observed;
            sweepMatched &= cell.position().equals(BlockPos.ORIGIN) || observed == Status.DISPLAY_MATCH;
            scanCursor = scanCursor + 1 == completed.cells().size() ? 0 : scanCursor + 1;
            if (scanCursor == 0) {
                if (sweepMatched && nextIntent.options().hideDisplayedMatches()) {
                    previews.stopProjection();
                    clear();
                    return;
                }
                sweepMatched = true;
            }
        }
    }

    void cancelFailedProjection(@Nullable StructurePreviews.Projection expectedIntent) {
        if (previews.closed() || previews.projection() != expectedIntent) return;
        previews.stopProjection();
        clear();
    }

    /**
     * All visited cells consume budget, including filtered planes and already displayed choices.
     */
    public List<Sample> frame(Object currentWorld) {
        if (previews.closed() || currentWorld != world || intent == null || intent != previews.projection()
            || model == null || !previews.current(model)) return List.of();
        int visits = Math.min(intent.options().renderVisitsPerFrame(), model.cells().size());
        var samples = new ObjectArrayList<Sample>(visits);
        for (int i = 0; i < visits; i++) {
            var cell = model.cells().get(renderCursor);
            var status = statuses[renderCursor] == null ? Status.UNKNOWN : statuses[renderCursor];
            renderCursor = renderCursor + 1 == model.cells().size() ? 0 : renderCursor + 1;
            if (intent.options().layer() != null && !intent.options().layer().includes(cell)) continue;
            if (intent.options().hideDisplayedMatches() && status == Status.DISPLAY_MATCH) continue;
            samples.add(new Sample(cell, add(intent.anchor(), cell.position()), status));
        }
        return samples;
    }

    public void clear() {
        world = null;
        intent = null;
        clearModel();
    }

    private void clearModel() {
        request = null;
        model = null;
        clearObservations();
    }

    private void clearObservations() {
        statuses = new Status[0];
        scanCursor = 0;
        sweepMatched = true;
        renderCursor = 0;
    }

    /**
     * These statuses compare display choices only and never grant formation/execution authority.
     */
    public enum Status {DISPLAY_MATCH, MISSING, DIFFERENT, UNLOADED, UNKNOWN}

    /**
     * Reads the captured model and world on the client thread without changing projection state.
     */
    @FunctionalInterface
    public interface Observer {
        Status observe(StructurePreviewModel model, StructurePreviewModel.Cell cell, BlockPos position);
    }

    public record Sample(StructurePreviewModel.Cell cell, BlockPos position, Status status) {
    }
}
