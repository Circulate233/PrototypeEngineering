package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * Associates an ordinary external block state with an already declared construction structure.
 */
public final class StructureConstructionRegistry {
    public static final StructureConstructionRegistry INSTANCE = new StructureConstructionRegistry();
    private final Object2ObjectLinkedOpenHashMap<IBlockState, Entry> entries = new Object2ObjectLinkedOpenHashMap<>();
    private final Map<IBlockState, Entry> bindings = Collections.unmodifiableMap(entries);
    private boolean frozen;

    private static BlockOrientation orientation(IBlockState state) {
        var property = state.getBlock().getBlockState().getProperty("facing");
        if (property != null && state.getValue(property) instanceof EnumFacing face)
            return BlockOrientation.get(face, face.getAxis() == EnumFacing.Axis.Y ? EnumFacing.NORTH : EnumFacing.UP);
        return BlockOrientation.NORTH_UP;
    }

    public synchronized void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                                      Function<IBlockState, BlockOrientation> orientation,
                                      @Nullable PositionedFormationCheck formation) {
        if (frozen) throw new IllegalStateException("Construction registrations are frozen");
        var entry = new Entry(structure, name, orientation, formation);
        if (entries.putIfAbsent(anchor, entry) != null)
            throw new IllegalArgumentException("Duplicate construction anchor: " + anchor);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         Function<IBlockState, BlockOrientation> orientation) {
        register(anchor, structure, name, orientation, (PositionedFormationCheck) null);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         Function<IBlockState, BlockOrientation> orientation, FormationCheck formation) {
        register(anchor, structure, name, orientation, (state, tile, _, _) -> formation.isFormed(state, tile));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name, FormationCheck formation) {
        register(anchor, structure, name, StructureConstructionRegistry::orientation, formation);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name) {
        register(anchor, structure, name, StructureConstructionRegistry::orientation);
    }

    public synchronized void freeze(StructureCatalog catalog) {
        for (var entry : entries.values())
            if (!catalog.definitions().containsKey(entry.structure))
                throw new IllegalArgumentException("Unknown construction structure: " + entry.structure);
        frozen = true;
    }

    public synchronized Map<IBlockState, Entry> bindings() {
        if (!frozen) throw new IllegalStateException("Construction registrations are not frozen");
        return bindings;
    }

    public synchronized @Nullable Entry find(IBlockState state) {
        return entries.get(state);
    }

    /** Called on the server once when starting a construction task. Return true to dismantle. */
    @FunctionalInterface
    public interface FormationCheck {
        boolean isFormed(IBlockState state, @Nullable TileEntity tile);
    }

    /** Same check with the anchor location, including when the anchor has no block entity. */
    @FunctionalInterface
    public interface PositionedFormationCheck {
        boolean isFormed(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position);
    }

    public record Entry(ResourceLocation structure, LocalizationEnum name,
                        Function<IBlockState, BlockOrientation> orientation, @Nullable PositionedFormationCheck formation) {
        public boolean isFormed(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position) {
            return formation != null && formation.isFormed(state, tile, world, position);
        }
    }
}
