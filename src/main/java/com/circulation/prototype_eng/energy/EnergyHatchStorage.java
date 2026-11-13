package com.circulation.prototype_eng.energy;

import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.storage.HatchReplacement;
import com.circulation.prototype_eng.storage.HatchStorageChanges;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Owns both independent FE sides, including their all-or-nothing replacement.
 */
public final class EnergyHatchStorage {
    private final EnergyHatchConfiguration configuration;
    private final @Nullable EnergyBuffer input;
    private final @Nullable EnergyBuffer output;
    private final List<StorageContext> contexts;

    public EnergyHatchStorage(EnergyHatchConfiguration configuration, long inputRate, long outputRate,
                              long inputEnergy, long outputEnergy, Runnable changed) {
        this.configuration = Objects.requireNonNull(configuration);
        if (inputEnergy < 0 || outputEnergy < 0
            || inputEnergy > (configuration.input() == null ? 0 : configuration.input().capacity())
            || outputEnergy > (configuration.output() == null ? 0 : configuration.output().capacity()))
            throw new IllegalArgumentException("Saved FE does not fit its effective side profile");
        input = create(configuration.input(), inputRate, outputRate, inputEnergy, changed);
        output = create(configuration.output(), inputRate, outputRate, outputEnergy, changed);
        contexts = input != null && output != null ? List.of(input.context(), output.context())
            : List.of(primary().context());
    }

    private static @Nullable EnergyBuffer create(@Nullable EnergyHatchConfiguration.Side side, long inRate,
                                                 long outRate, long energy, Runnable changed) {
        if (side == null) return null;
        var buffer = new EnergyBuffer(side.capacity(), side.effectiveRate(inRate), side.effectiveRate(outRate), changed);
        buffer.restore(energy);
        return buffer;
    }

    public EnergyBuffer primary() {
        return Objects.requireNonNull(input == null ? output : input);
    }

    public @Nullable EnergyBuffer input() {
        return input;
    }

    public @Nullable EnergyBuffer output() {
        return output;
    }

    public EnergyHatchConfiguration configuration() {
        return configuration;
    }

    public void invalidate() {
        locked(() -> contexts.forEach(StorageContext::invalidate));
    }

    public void updateRates(long inputRate, long outputRate) {
        locked(() -> {
            if (input != null) {
                var side = Objects.requireNonNull(configuration.input());
                input.requireTransferLimits(side.effectiveRate(inputRate), side.effectiveRate(outputRate));
            }
            if (output != null) {
                var side = Objects.requireNonNull(configuration.output());
                output.requireTransferLimits(side.effectiveRate(inputRate), side.effectiveRate(outputRate));
            }
            if (input != null) {
                var side = Objects.requireNonNull(configuration.input());
                input.setTransferLimits(side.effectiveRate(inputRate), side.effectiveRate(outputRate));
            }
            if (output != null) {
                var side = Objects.requireNonNull(configuration.output());
                output.setTransferLimits(side.effectiveRate(inputRate), side.effectiveRate(outputRate));
            }
        });
    }

    public long[] savedBalances() {
        var saved = new long[2];
        locked(() -> {
            saved[0] = input == null ? 0 : input.savedEnergy();
            saved[1] = output == null ? 0 : output.savedEnergy();
        });
        return saved;
    }

    public HatchReplacement replace(EnergyHatchConfiguration target, long inputRate, long outputRate,
                                    Runnable changed, BooleanSupplier current,
                                    Function<EnergyHatchStorage, Runnable> preparePublication) {
        if (target.mode() != configuration.mode())
            throw new IllegalArgumentException("Cannot change physical hatch direction");
        var fence = StorageFence.tryAcquire(contexts);
        if (fence == null) return HatchReplacement.of(HatchReconfigurationStatus.PENDING_BUSY);
        try {
            var capture = fence.access(() -> {
                if ((input != null && !input.idleForReplacement()) || (output != null && !output.idleForReplacement()))
                    return null;
                return new Capture(input == null ? 0 : input.savedEnergy(), output == null ? 0 : output.savedEnergy(),
                    HatchReplacement.captureVersions(contexts));
            });
            if (capture == null) return HatchReplacement.of(HatchReconfigurationStatus.PENDING_BUSY);
            if (capture.input > (target.input() == null ? 0 : target.input().capacity())
                || capture.output > (target.output() == null ? 0 : target.output().capacity()))
                return new HatchReplacement(HatchReconfigurationStatus.BLOCKED_CAPACITY, capture.versions);
            var changes = new HatchStorageChanges(changed);
            var candidate = new EnergyHatchStorage(target, inputRate, outputRate, capture.input, capture.output, changes);
            var publish = preparePublication.apply(candidate);
            return fence.access(() -> {
                if (!current.getAsBoolean() || !HatchReplacement.current(capture.versions))
                    return HatchReplacement.of(HatchReconfigurationStatus.STALE_TARGET);
                contexts.forEach(StorageContext::requireRetirement);
                contexts.forEach(StorageContext::invalidate);
                publish.run();
                changes.enable();
                return HatchReplacement.of(HatchReconfigurationStatus.APPLIED);
            });
        } finally {
            fence.release();
        }
    }

    private void locked(Runnable action) {
        int acquired = 0;
        try {
            while (acquired < contexts.size() && contexts.get(acquired).tryOrdinaryLock()) acquired++;
        } catch (RuntimeException | Error failure) {
            for (int i = acquired - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
            throw failure;
        }
        if (acquired != contexts.size()) {
            for (int i = acquired - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
            StorageContext.lockAll(contexts);
        }
        try {
            action.run();
        } finally {
            for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).unlockDeferred();
            contexts.forEach(StorageContext::publishChanges);
        }
    }

    private record Capture(long input, long output, List<HatchReplacement.Version> versions) {
    }
}
