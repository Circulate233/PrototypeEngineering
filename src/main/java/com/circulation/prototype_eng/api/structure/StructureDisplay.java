package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Visual choices and BOM grouping only; none of these fields participate in matching.
 */
public record StructureDisplay(String key, List<BlockOption> options, @Nullable String tileNbt) {
    public StructureDisplay {
        Objects.requireNonNull(key);
        if (key.isBlank()) throw new IllegalArgumentException("Empty structure display key");
        options = List.copyOf(options);
        if (options.isEmpty()) throw new IllegalArgumentException("A display needs at least one block option");
    }

    public record BlockOption(ResourceLocation blockId, int meta, Map<String, String> nbt) {
        public BlockOption {
            Objects.requireNonNull(blockId);
            if (meta < 0 || meta > 15) throw new IllegalArgumentException("Block metadata must be in 0..15");
            nbt = Map.copyOf(nbt);
        }
    }
}
