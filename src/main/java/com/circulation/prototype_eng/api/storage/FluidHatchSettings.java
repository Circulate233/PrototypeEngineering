package com.circulation.prototype_eng.api.storage;

/**
 * Optional fluid automation limits in mB per operation. Zero is unlimited; recipes use storage directly.
 */
public interface FluidHatchSettings {
    long maxFluidInputRate();

    long maxFluidOutputRate();

    @SuppressWarnings("unused")
        // Addon automation settings may change the implemented input limit directly.
    void setMaxFluidInputRate(long value);

    void setMaxFluidOutputRate(long value);

    default long limitFluidInput(long requested) {
        long maximum = maxFluidInputRate();
        return maximum <= 0 ? requested : Math.min(requested, maximum);
    }

    default long limitFluidOutput(long requested) {
        long maximum = maxFluidOutputRate();
        return maximum <= 0 ? requested : Math.min(requested, maximum);
    }
}
