package com.circulation.prototype_eng.api.storage;

/**
 * Immutable configuration values; a profile is never storage access authority.
 */
public interface HatchConfiguration {
    HatchMode mode();
}
