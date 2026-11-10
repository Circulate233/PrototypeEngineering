package com.circulation.prototype_eng.api.structure;

import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Visual projection of a declaration. Incomplete projections must never imply matching success. */
public record StructureDisplay(String key, List<BlockOption> options, @Nullable String tileNbt, boolean complete) {
    public StructureDisplay(String key, List<BlockOption> options, @Nullable String tileNbt) {
        this(key, options, tileNbt, true);
    }

    public StructureDisplay {
        Objects.requireNonNull(key);
        if (key.isBlank()) throw new IllegalArgumentException("Empty structure display key");
        options = List.copyOf(options);
        if (options.isEmpty()) throw new IllegalArgumentException("A display needs at least one block option");
    }

    /** Properties describe the full state; shallow NBT fields are a separate matching constraint. */
    public record BlockOption(ResourceLocation blockId, int meta, Map<String, String> nbt, Map<String, String> properties) {
        public BlockOption(ResourceLocation blockId, int meta, Map<String, String> nbt) {
            this(blockId, meta, nbt, Map.of());
        }

        public BlockOption {
            Objects.requireNonNull(blockId);
            if (meta < 0 || meta > 15) throw new IllegalArgumentException("Block metadata must be in 0..15");
            nbt = Map.copyOf(nbt);
            properties = Map.copyOf(properties);
        }

        public static BlockOption of(IBlockState state, Map<String, String> nbt) {
            var properties = new TreeMap<String, String>();
            for (var property : state.getPropertyKeys()) properties.put(property.getName(), propertyName(state, property));
            return new BlockOption(Objects.requireNonNull(state.getBlock().getRegistryName()), state.getBlock().getMetaFromState(state), nbt, properties);
        }

        private static <T extends Comparable<T>> String propertyName(IBlockState state, IProperty<T> property) {
            return property.getName(state.getValue(property));
        }

        /** Rejects unknown names/values instead of silently reducing an exact state to metadata. */
        public IBlockState applyProperties(IBlockState state) {
            var resolved = resolveProperties(state);
            if (resolved == null) throw new IllegalArgumentException("Invalid block state properties for " + blockId + ": " + properties);
            return resolved;
        }

        /** Nullable resolution for preview/build consumers; unsupported properties remain unknown. */
        public @Nullable IBlockState resolveProperties(IBlockState state) {
            var result = state;
            for (var entry : properties.entrySet()) {
                IProperty<?> found = null;
                for (var property : state.getPropertyKeys()) if (property.getName().equals(entry.getKey())) found = property;
                if (found == null) return null;
                result = apply(result, found, entry.getValue());
                if (result == null) return null;
            }
            return result;
        }

        private static <T extends Comparable<T>> @Nullable IBlockState apply(IBlockState state, IProperty<T> property, String text) {
            var value = property.parseValue(text);
            if (!value.isPresent()) return null;
            return state.withProperty(property, value.get());
        }
    }
}
