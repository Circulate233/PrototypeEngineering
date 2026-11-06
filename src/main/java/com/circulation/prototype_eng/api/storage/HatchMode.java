package com.circulation.prototype_eng.api.storage;

/** Physical direction of a resource hatch, independent of resource type. */
public enum HatchMode {
    INPUT(true, false), OUTPUT(false, true), MIXED(true, true);

    private final boolean input;
    private final boolean output;

    HatchMode(boolean input, boolean output) {
        this.input = input;
        this.output = output;
    }

    public boolean hasInput() {
        return input;
    }

    public boolean hasOutput() {
        return output;
    }
}
