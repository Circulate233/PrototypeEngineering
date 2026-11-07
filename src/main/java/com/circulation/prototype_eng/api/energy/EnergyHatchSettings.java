package com.circulation.prototype_eng.api.energy;

/** User-configurable FE operation limits and retained automation flags, independent of storage. */
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
