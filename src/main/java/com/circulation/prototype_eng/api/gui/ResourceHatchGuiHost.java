package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.localization.HatchGuiTitle;
import net.minecraft.util.text.ITextComponent;

/**
 * Optional default resource screen access, with no dependency on the built-in buffer implementation.
 */
public interface ResourceHatchGuiHost extends HatchGuiHost {
    SlottedResourceHatch storage();

    default ResourceHatchLayout layout() {
        return ResourceHatchLayout.standard(mode(), tier());
    }

    default ITextComponent guiTitle() {
        return HatchGuiTitle.of(false, mode(), tier()).text();
    }
}
