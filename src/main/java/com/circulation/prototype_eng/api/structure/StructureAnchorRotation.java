package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import ae2.block.AEBaseTileBlock;
import ae2.tile.AEBaseTile;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

public final class StructureAnchorRotation {
    private static final BlockOrientation[] ORIENTATIONS = BlockOrientation.values();
    private final Directions directions;
    private final OrientationReader reader;
    private final StatePreview preview;
    private final @Nullable RotationCallback callback;

    private StructureAnchorRotation(Directions directions, OrientationReader reader, StatePreview preview,
                                    @Nullable RotationCallback callback) {
        this.directions = directions;
        this.reader = Objects.requireNonNull(reader);
        this.preview = Objects.requireNonNull(preview);
        this.callback = callback;
    }

    public static StructureAnchorRotation fourWay(PropertyDirection facing) {
        return facing(Directions.HORIZONTAL, facing);
    }

    public static StructureAnchorRotation sixWay(PropertyDirection facing) {
        return facing(Directions.FACING, facing);
    }

    public static StructureAnchorRotation twentyFourWay(PropertyDirection front, PropertyDirection top, RotationCallback callback) {
        return twentyFourWay(front, top, (state, _) -> {
            var forward = state.getValue(front);
            var up = state.getValue(top);
            if (forward.getAxis() == up.getAxis()) throw new IllegalArgumentException("Parallel construction front and top: " + state);
            return BlockOrientation.get(forward, up);
        }, callback);
    }

    /** The callback must persist and synchronize orientations that cannot fit in block metadata. */
    public static StructureAnchorRotation twentyFourWay(PropertyDirection front, PropertyDirection top, OrientationReader reader,
                                                       RotationCallback callback) {
        if (front.equals(top)) throw new IllegalArgumentException("Construction front and top need distinct properties");
        return twentyFourWay(reader, (state, orientation) -> state.withProperty(front, orientation.getSide(RelativeSide.FRONT))
            .withProperty(top, orientation.getSide(RelativeSide.TOP)), callback);
    }

    /** Uses an AE tile's native orientation storage and the block's model state strategy. */
    public static StructureAnchorRotation twentyFourWay(AEBaseTileBlock<?> block) {
        var strategy = block.getOrientationStrategy();
        StatePreview preview = (state, orientation) -> strategy.setOrientation(state, orientation.getSide(RelativeSide.FRONT),
            orientation.getSide(RelativeSide.TOP));
        if (!strategy.allowsPlayerRotation()) throw new IllegalArgumentException("AE construction anchor disallows rotation: " + block);
        var poseForModel = new BlockOrientation[ORIENTATIONS.length];
        for (var orientation : ORIENTATIONS) {
            var model = block.getOrientation(preview.state(block.getDefaultState(), orientation));
            if (poseForModel[model.ordinal()] != null)
                throw new IllegalArgumentException("AE construction anchor does not support all orientations: " + block);
            poseForModel[model.ordinal()] = orientation;
        }
        return twentyFourWay((state, tile) -> tile instanceof AEBaseTile ae ? ae.getOrientation()
                : poseForModel[block.getOrientation(state).ordinal()], preview,
            (_, tile, _, _, _, orientation) -> {
                if (!(tile instanceof AEBaseTile ae) || !ae.canBeRotated() || ae.getOrientation() == orientation) return false;
                ae.setOrientation(orientation.getSide(RelativeSide.FRONT), orientation.getSide(RelativeSide.TOP));
                return ae.getOrientation() == orientation;
            });
    }

    private static StructureAnchorRotation facing(Directions directions, PropertyDirection facing) {
        return new StructureAnchorRotation(directions, (state, _) -> BlockOrientation.get(state.getValue(facing)),
            (state, orientation) -> state.withProperty(facing, orientation.getSide(RelativeSide.FRONT)), null);
    }

    public static StructureAnchorRotation fourWay(OrientationReader reader, StatePreview preview, RotationCallback callback) {
        return new StructureAnchorRotation(Directions.HORIZONTAL, reader, preview, Objects.requireNonNull(callback));
    }

    public static StructureAnchorRotation sixWay(OrientationReader reader, StatePreview preview, RotationCallback callback) {
        return new StructureAnchorRotation(Directions.FACING, reader, preview, Objects.requireNonNull(callback));
    }

    public static StructureAnchorRotation twentyFourWay(OrientationReader reader, StatePreview preview, RotationCallback callback) {
        return new StructureAnchorRotation(Directions.FULL, reader, preview, Objects.requireNonNull(callback));
    }

    public StructureAnchorRotation withCallback(RotationCallback callback) {
        return new StructureAnchorRotation(directions, reader, preview, Objects.requireNonNull(callback));
    }

    public BlockOrientation read(IBlockState state, @Nullable TileEntity tile) {
        var result = project(Objects.requireNonNull(reader.read(state, tile), "Missing construction anchor orientation"));
        if (result == null) throw new IllegalArgumentException("Unsupported construction anchor orientation: " + state);
        return result;
    }

    /** Returns the supported pose for the requested front, or null if that front is unavailable. */
    public @Nullable BlockOrientation project(BlockOrientation requested) {
        if (directions == Directions.FULL) return requested;
        var front = requested.getSide(RelativeSide.FRONT);
        return directions == Directions.HORIZONTAL && front.getAxis() == EnumFacing.Axis.Y ? null : BlockOrientation.get(front);
    }

    public IBlockState preview(IBlockState state, BlockOrientation orientation) {
        requireSupported(orientation);
        var result = Objects.requireNonNull(preview.state(state, orientation), "Missing construction anchor preview state");
        if (result.getBlock() != state.getBlock()) throw new IllegalArgumentException("Construction rotation changed the anchor block: " + result);
        return result;
    }

    public boolean rotate(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position, EntityPlayer player,
                          BlockOrientation orientation) {
        if (world.isRemote) throw new IllegalStateException("Construction anchor rotation is server-side");
        requireSupported(orientation);
        if (callback != null) return callback.rotate(state, tile, world, position, player, orientation);
        var next = preview(state, orientation);
        return !next.equals(state) && world.setBlockState(position, next, Constants.BlockFlags.DEFAULT);
    }

    void collectStates(IBlockState anchor, Set<IBlockState> states) {
        read(anchor, null);
        for (var orientation : ORIENTATIONS) {
            if (project(orientation) == orientation) states.add(preview(anchor, orientation));
        }
    }

    private void requireSupported(BlockOrientation orientation) {
        if (project(orientation) != orientation) throw new IllegalArgumentException("Unsupported construction anchor orientation: " + orientation);
    }

    @FunctionalInterface
    public interface OrientationReader {
        /** Read-only on both sides; tile is null in item previews and when absent from the world. */
        BlockOrientation read(IBlockState state, @Nullable TileEntity tile);
    }

    @FunctionalInterface
    public interface StatePreview {
        /** Pure state prediction for both registration and preview. Tile-only anchors can return the input state. */
        IBlockState state(IBlockState state, BlockOrientation orientation);
    }

    @FunctionalInterface
    public interface RotationCallback {
        /** Apply and synchronize the change on the server. Return true only when the anchor rotated. */
        boolean rotate(IBlockState state, @Nullable TileEntity tile, World world, BlockPos position, EntityPlayer player,
                       BlockOrientation orientation);
    }

    private enum Directions {HORIZONTAL, FACING, FULL}
}
