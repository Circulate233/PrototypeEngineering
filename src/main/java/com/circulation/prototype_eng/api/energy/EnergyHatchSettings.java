package com.circulation.prototype_eng.api.energy;

/**
 * User-configurable FE operation limits and retained automation flags, independent of storage.
 * Limits default to -1 (no additional user limit), zero forbids that transfer direction, and positive
 * values are bounded by the effective side profile's configured transfer ceiling.
 */
public interface EnergyHatchSettings {
    long maxInputRate();

    long maxOutputRate();

    boolean autoInput();

    boolean autoOutput();

    void setMaxInputRate(long value);

    void setMaxOutputRate(long value);

    void setAutoInput(boolean value);

    void setAutoOutput(boolean value);
}
