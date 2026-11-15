package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/**
 * Typed, explicitly declared world fact. Capture is server-only and must be observational.
 * The copier detaches mutable values once on capture. Predicates and equivalence callbacks borrow
 * the captured value read-only; they must not modify it. Immutable values may use identity.
 * Never use a TileEntity, World, handler or other live authority as a fact.
 * Descriptor identity determines its type; an ID cannot identify two descriptors in one read plan.
 */
public final class StructureQuery<T> {
    private final ResourceLocation id;
    private final BiFunction<World, BlockPos, T> reader;
    private final UnaryOperator<T> copier;
    private final BiPredicate<T, T> equivalence;
    @Nullable
    private final BiFunction<StructureReadWindow.BlockStates, BlockPos, T> workerReader;

    /**
     * Equivalence must imply indistinguishable values for every consumer of this descriptor.
     * It receives the detached, non-null captured values and must leave both unchanged. False may cause a
     * fresh match, but true must never hide a change visible to a predicate. No default equals
     * assumption is made for external facts. Null/absence is handled separately.
     */
    public StructureQuery(ResourceLocation id, BiFunction<World, BlockPos, T> reader, UnaryOperator<T> copier,
                          BiPredicate<T, T> equivalence) {
        this(id, reader, copier, equivalence, null);
    }

    /**
     * An explicit block-only reader is valid solely inside a source's physically joined window.
     */
    public StructureQuery(ResourceLocation id, BiFunction<World, BlockPos, T> reader, UnaryOperator<T> copier,
                          BiPredicate<T, T> equivalence,
                          @Nullable BiFunction<StructureReadWindow.BlockStates, BlockPos, T> workerReader) {
        this.id = id;
        this.reader = reader;
        this.copier = copier;
        this.equivalence = equivalence;
        this.workerReader = workerReader;
    }

    public ResourceLocation id() {
        return id;
    }

    /**
     * World-adapter boundary. A capture callback must not force chunks to load.
     */
    public T capture(World world, BlockPos position) {
        var server = world.getMinecraftServer();
        if (world.isRemote || server == null || !server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Structure facts must be captured on their server thread");
        }
        return reader.apply(world, position);
    }

    public boolean supportsWorkerBlocks() {
        return workerReader != null;
    }

    /**
     * The source has already bound finite loaded references on the owner before this operation.
     * Call only when supportsWorkerBlocks() is true. The worker reader must read only the supplied position.
     */
    @SuppressWarnings("DataFlowIssue") // supportsWorkerBlocks() is required before entering this worker path.
    public StructureReadWindow<T> beginWorkerCapture(StructureReadWindow.BlockStates blocks, BlockPos position) {
        var callback = workerReader;
        var immutable = position.toImmutable();
        return StructureReadWindow.joined(() -> callback.apply(blocks, immutable));
    }

    T copy(T value) {
        return value == null ? null : copier.apply(value);
    }

    boolean equivalent(@Nullable T first, @Nullable T second) {
        if (first == null || second == null) {
            return first == second;
        }
        return equivalence.test(first, second);
    }
}
