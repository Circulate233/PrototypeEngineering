package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/** Associates an ordinary external block state with an already declared construction structure. */
public final class StructureConstructionRegistry {
    public static final StructureConstructionRegistry INSTANCE = new StructureConstructionRegistry();
    private final Object2ObjectOpenHashMap<IBlockState, Entry> entries = new Object2ObjectOpenHashMap<>();
    private boolean frozen;

    public synchronized void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                                      Function<IBlockState, BlockOrientation> orientation) {
        if (frozen) throw new IllegalStateException("Construction registrations are frozen");
        var entry = new Entry(structure, name, orientation);
        if (entries.putIfAbsent(Objects.requireNonNull(anchor), entry) != null)
            throw new IllegalArgumentException("Duplicate construction anchor: " + anchor);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name) {
        register(anchor, structure, name, StructureConstructionRegistry::orientation);
    }

    private static BlockOrientation orientation(IBlockState state) {
        for (var property : state.getPropertyKeys())
            if (property.getName().equals("facing") && state.getValue(property) instanceof EnumFacing face)
                return BlockOrientation.get(face, face.getAxis() == EnumFacing.Axis.Y ? EnumFacing.NORTH : EnumFacing.UP);
        return BlockOrientation.NORTH_UP;
    }

    public synchronized void freeze(StructureCatalog catalog) {
        for (var entry : entries.values())
            if (!catalog.definitions().containsKey(entry.structure))
                throw new IllegalArgumentException("Unknown construction structure: " + entry.structure);
        frozen = true;
    }

    public synchronized @Nullable Entry find(IBlockState state) { return entries.get(state); }

    public record Entry(ResourceLocation structure, LocalizationEnum name, Function<IBlockState, BlockOrientation> orientation) {
        public Entry {
            Objects.requireNonNull(structure);
            Objects.requireNonNull(name);
            Objects.requireNonNull(orientation);
        }
    }
}
