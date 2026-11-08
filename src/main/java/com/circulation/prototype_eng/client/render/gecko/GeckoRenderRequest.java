package com.circulation.prototype_eng.client.render.gecko;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;

/** Frozen CPU input. Owner and epoch tokens carry identity, never a World/TE callback. */
public record GeckoRenderRequest(Owner owner, StructureRenderAnchors.Part part, Epoch bindingEpoch, Epoch resourceEpoch,
                                 GeckoModelBinding binding, List<String> layers, GeckoModelBaker.BakeMode mode,
                                 BlockPos position, BlockPos origin, BlockOrientation orientation, Vec3d localOffset,
                                 Sample sample) {
    public GeckoRenderRequest {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(part);
        Objects.requireNonNull(bindingEpoch);
        Objects.requireNonNull(resourceEpoch);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(orientation);
        Objects.requireNonNull(localOffset);
        Objects.requireNonNull(sample);
        position = position.toImmutable();
        origin = origin.toImmutable();
        layers = new MachineAnimationState.Snapshot(0, layers).layers();
        if (!Double.isFinite(localOffset.x) || !Double.isFinite(localOffset.y) || !Double.isFinite(localOffset.z))
            throw new IllegalArgumentException("Model offset must be finite");
    }

    public static GeckoRenderRequest from(Owner owner, Epoch bindingEpoch, Epoch resourceEpoch,
                                          StructureRenderAnchors.Anchor anchor, BlockPos origin,
                                          GeckoModelBaker.BakeMode mode, Sample sample) {
        var model = anchor.binding();
        // Anchor.modelOffset is already world rotated and includes yOffset. The baker consumes raw local units once.
        return new GeckoRenderRequest(owner, anchor.part(), bindingEpoch, resourceEpoch, model, anchor.layers(),
            mode, anchor.origin(), origin, anchor.orientation(), new Vec3d(model.offsetX(), model.offsetY(), model.offsetZ()), sample);
    }

    /** Sampling time alone may change while a completed previous frame remains drawable. */
    public boolean sameBase(GeckoRenderRequest other) {
        return baseKey().equals(other.baseKey());
    }

    public BaseKey baseKey() {
        return new BaseKey(owner, part, bindingEpoch, resourceEpoch, binding,
            mode == GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY ? List.of() : layers,
            mode, position, origin, orientation, localOffset);
    }

    public record BaseKey(Owner owner, StructureRenderAnchors.Part part, Epoch bindingEpoch, Epoch resourceEpoch,
                          GeckoModelBinding binding, List<String> layers, GeckoModelBaker.BakeMode mode,
                          BlockPos position, BlockPos origin, BlockOrientation orientation, Vec3d localOffset) {
    }

    public static final class Owner {
        private volatile boolean retired;

        public boolean retired() {
            return retired;
        }

        public void retire() {
            retired = true;
        }
    }

    public static final class Epoch {
    }

    public record Sample(long timeKey, double stepTicks) {
        public Sample {
            if (timeKey < 0 || !Double.isFinite(stepTicks) || stepTicks <= 0)
                throw new IllegalArgumentException("Invalid animation sample");
        }

        public double ticks() {
            return timeKey * stepTicks;
        }

        public static Sample at(double ticks, double stepTicks, int snap) {
            if (!Double.isFinite(ticks) || ticks < 0 || snap < 1 || !Double.isFinite(stepTicks) || stepTicks <= 0)
                throw new IllegalArgumentException("Invalid animation clock");
            double raw = Math.floor(ticks / stepTicks);
            if (raw > Long.MAX_VALUE) throw new IllegalArgumentException("Animation clock overflow");
            long key = (long) raw;
            return new Sample(key - key % snap, stepTicks);
        }
    }
}
