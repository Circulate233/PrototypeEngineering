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
    private int renderCursor;
    @Nullable
    private Object round;

    public StructureProjectionState(StructurePreviews previews) {
        this.previews = Objects.requireNonNull(previews);
    }

    /**
     * These statuses compare display choices only and never grant formation/execution authority.
     */
    public enum Status {DISPLAY_MATCH, MISSING, DIFFERENT, UNLOADED, UNKNOWN}

    @FunctionalInterface
    public interface Observer {
        Status observe(StructurePreviewModel.Cell cell, BlockPos position);
    }

    public record Sample(StructurePreviewModel.Cell cell, BlockPos position, Status status) {
    }

    public void tick(@Nullable Object currentWorld, Observer observer) {
        Objects.requireNonNull(observer);
        if (previews.closed()) {
            clear();
            return;
        }
        var nextIntent = previews.projection();
        var activeRound = new Object();
        round = activeRound;
        try {
            tickRound(activeRound, currentWorld, nextIntent, observer);
        } catch (RuntimeException failure) {
            cancelFailedRound(nextIntent, activeRound);
            throw failure;
        }
    }

    private void tickRound(Object activeRound, @Nullable Object currentWorld,
                           @Nullable StructurePreviews.Projection nextIntent, Observer observer) {
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
            renderCursor = 0;
        }
        var observations = statuses;
        int visits = Math.min(nextIntent.options().scanChecksPerTick(), completed.cells().size());
        for (int i = 0; i < visits; i++) {
            int observedCursor = scanCursor;
            var cell = completed.cells().get(observedCursor);
            var observed = observer.observe(cell, add(nextIntent.anchor(), cell.position()));
            if (!currentRound(activeRound, currentWorld, nextIntent, nextRequest, completed, observations)
                || scanCursor != observedCursor) return;
            observations[observedCursor] = Objects.requireNonNull(observed);
            scanCursor = observedCursor + 1 == completed.cells().size() ? 0 : observedCursor + 1;
        }
    }

    private boolean currentRound(Object activeRound, Object expectedWorld, StructurePreviews.Projection expectedIntent,
                                 StructurePreviews.Request expectedRequest, StructurePreviewModel expectedModel,
                                 Status[] expectedObservations) {
        return round == activeRound && world == expectedWorld && intent == expectedIntent && request == expectedRequest
            && model == expectedModel && statuses == expectedObservations && !previews.closed()
            && previews.projection() == expectedIntent && previews.current(expectedModel);
    }

    /**
     * Native render failure captures the same private round identity before invoking GL callbacks.
     */
    @Nullable Object roundToken() {
        return round;
    }

    void cancelFailedRound(@Nullable StructurePreviews.Projection expectedIntent, @Nullable Object expectedRound) {
        if (expectedIntent == null || expectedRound == null || round != expectedRound || intent != expectedIntent
            || previews.closed() || previews.projection() != expectedIntent || request == null
            || request.state() == StructurePreviews.State.STALE || (model != null && !previews.current(model)))
            return;
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
        var samples = new ObjectArrayList<Sample>();
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
        round = null;
        world = null;
        intent = null;
        clearModel();
    }

    private static BlockPos add(BlockPos left, BlockPos right) {
        return new BlockPos(Math.addExact(left.getX(), right.getX()), Math.addExact(left.getY(), right.getY()), Math.addExact(left.getZ(), right.getZ()));
    }

    private void clearModel() {
        request = null;
        model = null;
        clearObservations();
    }

    private void clearObservations() {
        statuses = new Status[0];
        scanCursor = 0;
        renderCursor = 0;
    }
}
