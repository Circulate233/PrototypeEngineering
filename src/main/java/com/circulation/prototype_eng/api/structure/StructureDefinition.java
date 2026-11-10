package com.circulation.prototype_eng.api.structure;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.localization.StructureTitle;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Complete catalog entry: intrinsic shape presentation plus explicit per-cell overrides.
 */
public record StructureDefinition(MachineStructure shape, @Nullable String translationKey,
                                  Map<BlockPos, StructureDisplay> displays) {
    public StructureDefinition {
        Objects.requireNonNull(shape);
        Objects.requireNonNull(shape.id());
        if (translationKey != null && translationKey.isBlank())
            throw new IllegalArgumentException("Empty structure translation key");
        var copy = new Object2ObjectLinkedOpenHashMap<>(shape.displays());
        displays.forEach((position, display) -> {
            var fixedPosition = Objects.requireNonNull(position).toImmutable();
            copy.put(fixedPosition, Objects.requireNonNull(display));
        });
        displays = Object2ObjectMaps.unmodifiable(copy);
    }

    public StructureDefinition(MachineStructure shape) {
        this(shape, null, Map.of());
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
