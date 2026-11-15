package com.circulation.prototype_eng.api.structure;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.localization.StructureTitle;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/**
 * Complete catalog entry: intrinsic shape presentation plus explicit per-cell overrides.
 * Supplied display maps are borrowed read-only for the definition's lifetime.
 */
public record StructureDefinition(MachineStructure shape, @Nullable String translationKey,
                                  BlockPos2ObjectMap<StructureDisplay> displays) {
    public StructureDefinition {
        if (translationKey != null && translationKey.isBlank())
            throw new IllegalArgumentException("Empty structure translation key");
        var intrinsic = shape.displays();
        if (displays.isEmpty()) displays = intrinsic;
        else if (!intrinsic.isEmpty() && displays != intrinsic) {
            var merged = new BlockPos2ObjectMap<>(intrinsic);
            merged.putAll(displays);
            displays = merged;
        }
    }

    public StructureDefinition(MachineStructure shape) {
        this(shape, null, BlockPos2ObjectMap.emptyMap());
    }

    public ResourceLocation id() {
        return shape.id();
    }

    /**
     * Absent names use the structure ID in the caller; configured names follow the AE2S contract.
     */
    @SuppressWarnings("unused") // Public Java preview and GUI naming contract.
    @Nullable
    public LocalizationEnum displayName() {
        return translationKey == null ? null : new StructureTitle(translationKey);
    }
}
