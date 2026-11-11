package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A read-only view of already captured facts at a structure base or declared query position.
 * Local query offsets rotate with their declaration; absolute/controller coordinates remain world
 * coordinates. No world, tile, capability handler or capture source is retained here.
 */
public record StructureFacts(StructureSnapshot snapshot, BlockPos position, BlockPos controller,
                             StructureTransform rotation) {
    public StructureFacts {
        Objects.requireNonNull(snapshot);
        position = Objects.requireNonNull(position).toImmutable();
        controller = Objects.requireNonNull(controller).toImmutable();
        Objects.requireNonNull(rotation);
    }

    public boolean available() {
        return snapshot.isAreaLoaded(StructureBounds.at(position));
    }

    public BlockPos relativePosition() {
        return new BlockPos(Math.subtractExact(position.getX(), controller.getX()),
            Math.subtractExact(position.getY(), controller.getY()), Math.subtractExact(position.getZ(), controller.getZ()));
    }

    public StructureFacts at(BlockPos localOffset) {
        return atWorld(StructurePattern.add(position, rotation.apply(localOffset)));
    }

    public StructureFacts atController(BlockPos localOffset) {
        return atWorld(StructurePattern.add(controller, rotation.apply(localOffset)));
    }

    public StructureFacts atWorld(BlockPos absolutePosition) {
        return new StructureFacts(snapshot, absolutePosition, controller, rotation);
    }

    /** Undeclared, unavailable and failed reads keep the snapshot's original error semantics. */
    @Nullable
    public <T> T read(StructureQuery<T> query) {
        return snapshot.read(query, position);
    }
}
