package com.circulation.prototype_eng.api.structure;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.localization.StructureTitle;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Complete catalog entry: intrinsic shape presentation plus explicit per-cell overrides.
 */
public record StructureDefinition(MachineStructure shape, @Nullable String translationKey,
                                  BlockPos2ObjectMap<StructureDisplay> displays) {
    public StructureDefinition {
        Objects.requireNonNull(shape);
        Objects.requireNonNull(shape.id());
        if (translationKey != null && translationKey.isBlank())
            throw new IllegalArgumentException("Empty structure translation key");
        var copy = new BlockPos2ObjectMap<>(shape.displays());
        for (var entry : displays.long2ObjectEntrySet())
            copy.put(entry.getLongKey(), Objects.requireNonNull(entry.getValue()));
        displays = copy;
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
