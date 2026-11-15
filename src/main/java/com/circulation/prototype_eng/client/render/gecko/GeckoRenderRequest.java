package com.circulation.prototype_eng.client.render.gecko;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Frozen CPU input. Owner and epoch tokens carry identity, never a World/TE callback.
 * Layer lists and occurrence paths are borrowed read-only while this request or a derived cache key is retained.
 */
public record GeckoRenderRequest(Owner owner, StructureRenderAnchors.Part part, Epoch bindingEpoch, Epoch resourceEpoch,
                                 GeckoModelBinding binding, List<String> layers, GeckoModelBaker.BakeMode mode,
                                 BlockPos position, BlockPos origin, BlockOrientation orientation, Vec3d localOffset,
                                 Sample sample) {
    public GeckoRenderRequest {
        position = position.toImmutable();
        origin = origin.toImmutable();
    }

    public static GeckoRenderRequest from(Owner owner, Epoch bindingEpoch, Epoch resourceEpoch,
                                          StructureRenderAnchors.Anchor anchor, BlockPos origin,
                                          GeckoModelBaker.BakeMode mode, Sample sample) {
        var model = anchor.binding();
        return new GeckoRenderRequest(owner, anchor.part(), bindingEpoch, resourceEpoch, model, anchor.layers(),
            mode, anchor.origin(), origin, anchor.orientation(), new Vec3d(model.offsetX(), model.offsetY(), model.offsetZ()), sample);
    }

    /**
     * Sampling time alone may change while a completed previous frame remains drawable.
     */
    public boolean sameBase(GeckoRenderRequest other) {
        return owner == other.owner && part.equals(other.part) && bindingEpoch == other.bindingEpoch
            && resourceEpoch == other.resourceEpoch && binding.equals(other.binding) && mode == other.mode
            && (mode == GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY || layers.equals(other.layers))
            && position.equals(other.position) && origin.equals(other.origin) && orientation.equals(other.orientation)
            && localOffset.equals(other.localOffset);
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

        public static Sample at(double ticks, double stepTicks, int snap) {
            if (!Double.isFinite(ticks) || ticks < 0 || snap < 1 || !Double.isFinite(stepTicks) || stepTicks <= 0)
                throw new IllegalArgumentException("Invalid animation clock");
            double raw = Math.floor(ticks / stepTicks);
            if (raw > Long.MAX_VALUE) throw new IllegalArgumentException("Animation clock overflow");
            long key = (long) raw;
            return new Sample(key - key % snap, stepTicks);
        }

        public double ticks() {
            return timeKey * stepTicks;
        }
    }
}
