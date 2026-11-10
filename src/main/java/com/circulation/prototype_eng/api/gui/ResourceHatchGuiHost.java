package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;

/**
 * Optional default resource screen access, with no dependency on the built-in buffer implementation.
 */
public interface ResourceHatchGuiHost extends HatchGuiHost {
    SlottedResourceHatch storage();
}
