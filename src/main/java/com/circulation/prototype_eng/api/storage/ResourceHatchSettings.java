package com.circulation.prototype_eng.api.storage;

/**
 * The original item-hatch automation switches, independent of its resource storage.
 */
public interface ResourceHatchSettings {
    boolean autoInput();

    boolean autoOutput();

    void setAutoInput(boolean value);

    void setAutoOutput(boolean value);
}
