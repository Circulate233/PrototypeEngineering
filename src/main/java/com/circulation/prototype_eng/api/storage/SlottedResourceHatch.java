package com.circulation.prototype_eng.api.storage;

import org.jetbrains.annotations.Nullable;

/**
 * Optional slot access for shared GUI and automation adapters; machine recipes need only ResourceHatch.
 */
public interface SlottedResourceHatch extends ResourceHatch {
    @Override
    @Nullable
    SlottedResourceStorage input();

    @Override
    @Nullable
    SlottedResourceStorage output();
}
