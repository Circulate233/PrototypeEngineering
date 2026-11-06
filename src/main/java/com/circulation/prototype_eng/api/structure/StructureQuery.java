package com.circulation.prototype_eng.api.structure;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/**
 * Typed, explicitly declared world fact. Capture is server-only and must be observational.
 * The copier must detach mutable values both on capture and on delivery to a predicate. Immutable
 * values may use identity. Never use a TileEntity, World, handler or other live authority as a fact.
 * Descriptor identity determines its type; an ID cannot identify two descriptors in one read plan.
 */
public final class StructureQuery<T> {
    private final ResourceLocation id;
    private final BiFunction<World, BlockPos, T> reader;
    private final UnaryOperator<T> copier;
    private final BiPredicate<T, T> equivalence;

    /**
     * Equivalence must imply indistinguishable values for every consumer of this descriptor.
     * It receives detached, non-null copies; it must be pure. False may conservatively cause a
     * fresh match, but true must never hide a change visible to a predicate. No default equals
     * assumption is made for external facts. Null/absence is handled separately.
     */
    public StructureQuery(ResourceLocation id, BiFunction<World, BlockPos, T> reader, UnaryOperator<T> copier,
                          BiPredicate<T, T> equivalence) {
        this.id = Objects.requireNonNull(id);
        this.reader = Objects.requireNonNull(reader);
        this.copier = Objects.requireNonNull(copier);
        this.equivalence = Objects.requireNonNull(equivalence);
    }

    public ResourceLocation id() { return id; }

    /** World-adapter boundary. A capture callback must not force chunks to load. */
    @Nullable
    public T capture(World world, BlockPos position) {
        Objects.requireNonNull(world);
        var server = world.getMinecraftServer();
        if (world.isRemote || server == null || !server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Structure facts must be captured on their server thread");
        }
        return reader.apply(world, Objects.requireNonNull(position));
    }

    @Nullable
    T copy(@Nullable T value) { return value == null ? null : Objects.requireNonNull(copier.apply(value), "Copied structure fact"); }

    boolean equivalent(@Nullable T first, @Nullable T second) {
        if (first == null || second == null) { return first == second; }
        return equivalence.test(copy(first), copy(second));
    }
}
