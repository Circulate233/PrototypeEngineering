package com.circulation.prototype_eng.api.energy;

import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import org.jetbrains.annotations.Nullable;

/** Independent FE capacities and default transfer ceilings. User limits remain host settings. */
public record EnergyHatchConfiguration(@Nullable Side input, @Nullable Side output) implements HatchConfiguration {
    public EnergyHatchConfiguration {
        if (input == null && output == null) throw new IllegalArgumentException("A hatch needs a side");
    }

    public static EnergyHatchConfiguration symmetric(HatchMode mode, long capacity, long transfer) {
        var side = new Side(capacity, transfer);
        return new EnergyHatchConfiguration(mode.hasInput() ? side : null, mode.hasOutput() ? side : null);
    }

    @Override
    public HatchMode mode() {
        return input == null ? HatchMode.OUTPUT : output == null ? HatchMode.INPUT : HatchMode.MIXED;
    }

    public record Side(long capacity, long transfer) {
        public Side {
            if (capacity <= 0 || transfer <= 0)
                throw new IllegalArgumentException("Capacity and transfer must be positive");
        }

        public long effectiveRate(long requested) {
            if (requested < -1) throw new IllegalArgumentException("User transfer limit must be -1 or nonnegative");
            return requested == -1 ? transfer : Math.min(requested, transfer);
        }
    }
}
