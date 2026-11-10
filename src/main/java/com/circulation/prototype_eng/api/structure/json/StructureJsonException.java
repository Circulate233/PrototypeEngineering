package com.circulation.prototype_eng.api.structure.json;

/**
 * A definition error retains the source and exact field/reference path across both loading phases.
 */
public final class StructureJsonException extends IllegalArgumentException {
    private final String source;
    private final String path;

    public StructureJsonException(String source, String path, String message) {
        super(source + " " + path + ": " + message);
        this.source = source;
        this.path = path;
    }

    public StructureJsonException(String source, String path, String message, Throwable cause) {
        super(source + " " + path + ": " + message, cause);
        this.source = source;
        this.path = path;
    }

    public String source() {
        return source;
    }

    public String path() {
        return path;
    }
}
