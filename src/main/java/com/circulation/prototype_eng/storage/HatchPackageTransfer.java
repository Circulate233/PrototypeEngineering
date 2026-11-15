package com.circulation.prototype_eng.storage;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import org.jetbrains.annotations.Nullable;


/**
 * Routes one unpacked AE resource package to a single hatch side and keeps any remainder.
 */
public final class HatchPackageTransfer {
    private HatchPackageTransfer() {
    }

    public static @Nullable GenericStack insert(@Nullable SlottedResourceStorage input,
                                                @Nullable SlottedResourceStorage output,
                                                GenericStack packaged) {
        if (input != null) {
            var remaining = HatchSlotActions.insertPackage(input, -1, packaged);
            if (remaining != packaged || output == null) return remaining;
        }
        return output == null ? packaged : HatchSlotActions.insertPackage(output, -1, packaged);
    }
}
