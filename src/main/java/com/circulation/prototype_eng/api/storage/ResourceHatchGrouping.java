package com.circulation.prototype_eng.api.storage;

import ae2.api.util.AEColor;

/**
 * Optional physical color configuration; energy hatches deliberately do not implement this.
 */
public interface ResourceHatchGrouping {
    AEColor resourceColor();

    void setResourceColor(AEColor color);
}
