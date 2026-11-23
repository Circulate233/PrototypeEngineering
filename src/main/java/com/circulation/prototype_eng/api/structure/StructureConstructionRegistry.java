package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class StructureConstructionRegistry {
    public static final StructureConstructionRegistry INSTANCE = new StructureConstructionRegistry();
    private final Object2ObjectLinkedOpenHashMap<IBlockState, Entry> entries = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<IBlockState, Entry> anchors = new Object2ObjectLinkedOpenHashMap<>();
    private final Map<IBlockState, Entry> bindings = Collections.unmodifiableMap(anchors);
    private boolean frozen;

    private synchronized void add(IBlockState anchor, Entry entry) {
        if (frozen) throw new IllegalStateException("Construction registrations are frozen");
        var states = new ObjectLinkedOpenHashSet<IBlockState>();
        states.add(Objects.requireNonNull(anchor));
        if (entry.rotation != null) entry.rotation.collectStates(anchor, states);
        for (var state : states) {
            if (entries.containsKey(state)) throw new IllegalArgumentException("Duplicate construction anchor: " + state);
        }
        for (var state : states) entries.put(state, entry);
        anchors.put(anchor, entry);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         StructureAnchorRotation rotation, @Nullable PositionedFormationCheck formation) {
        add(anchor, new Entry(structure, name, Objects.requireNonNull(rotation), null, formation));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         StructureAnchorRotation rotation, FormationCheck formation) {
        register(anchor, structure, name, rotation, (state, tile, _, _) -> formation.isFormed(state, tile));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name, StructureAnchorRotation rotation) {
        register(anchor, structure, name, rotation, (PositionedFormationCheck) null);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         Function<IBlockState, BlockOrientation> orientation, @Nullable PositionedFormationCheck formation) {
        add(anchor, new Entry(structure, name, null, Objects.requireNonNull(orientation), formation));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         Function<IBlockState, BlockOrientation> orientation, FormationCheck formation) {
        register(anchor, structure, name, orientation, (state, tile, _, _) -> formation.isFormed(state, tile));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name,
                         Function<IBlockState, BlockOrientation> orientation) {
        register(anchor, structure, name, orientation, (PositionedFormationCheck) null);
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name, PositionedFormationCheck formation) {
        add(anchor, new Entry(structure, name, null, null, Objects.requireNonNull(formation)));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name, FormationCheck formation) {
        register(anchor, structure, name, (state, tile, _, _) -> formation.isFormed(state, tile));
    }

    public void register(IBlockState anchor, ResourceLocation structure, LocalizationEnum name) {
        add(anchor, new Entry(structure, name, null, null, null));
    }

    public synchronized void freeze(StructureCatalog catalog) {
        for (var entry : anchors.values()) {
            var definition = catalog.get(entry.structure);
            if (definition == null) throw new IllegalArgumentException("Unknown construction structure: " + entry.structure);
            entry.declaredOrientation = definition.shape().orientation();
        }
        frozen = true;
    }

    /** One representative state per explicit registration, used by item previews. */
    public synchronized Map<IBlockState, Entry> bindings() {
        if (!frozen) throw new IllegalStateException("Construction registrations are not frozen");
        return bindings;
    }

    public synchronized @Nullable Entry find(IBlockState state) {
        return entries.get(state);
    }

    @FunctionalInterface
    public interface FormationCheck {
        boolean isFormed(IBlockState state, @Nullable TileEntity tile);
    }

    @FunctionalInterface
    public interface PositionedFormationCheck {
        boolean isFormed(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position);
    }

    public static final class Entry {
        private final ResourceLocation structure;
        private final LocalizationEnum name;
        private final @Nullable StructureAnchorRotation rotation;
        private final @Nullable Function<IBlockState, BlockOrientation> reader;
        private final @Nullable PositionedFormationCheck formation;
        private @Nullable BlockOrientation declaredOrientation;

        private Entry(ResourceLocation structure, LocalizationEnum name, @Nullable StructureAnchorRotation rotation,
                      @Nullable Function<IBlockState, BlockOrientation> reader, @Nullable PositionedFormationCheck formation) {
            this.structure = Objects.requireNonNull(structure);
            this.name = Objects.requireNonNull(name);
            this.rotation = rotation;
            this.reader = reader;
            this.formation = formation;
        }

        public ResourceLocation structure() {
            return structure;
        }

        public LocalizationEnum name() {
            return name;
        }

        public @Nullable StructureAnchorRotation rotation() {
            return rotation;
        }

        public boolean fixed() {
            return rotation == null && reader == null;
        }

        public BlockOrientation orientation(IBlockState state, @Nullable TileEntity tile) {
            if (rotation != null) return rotation.read(state, tile);
            if (reader != null) return Objects.requireNonNull(reader.apply(state), "Missing construction anchor orientation");
            return Objects.requireNonNull(declaredOrientation, "Construction registrations are not frozen");
        }

        public boolean isFormed(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position) {
            return formation != null && formation.isFormed(state, tile, world, position);
        }
    }
}
