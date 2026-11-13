package com.circulation.prototype_eng.api.structure;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One explicitly prepared read in the original scheduler's joined owner window. The owner must
 * wait for the accepted reader's actual exit before closing this window or advancing World state.
 * Only an explicit safe source may borrow live loaded references; ordinary sources prepare a
 * detached fact on their real virtual owner read path. No window survives a work slice.
 */
public final class StructureReadWindow<T> implements AutoCloseable {
    private final Thread owner;
    private final Supplier<? extends T> reader;
    private boolean closed;

    private StructureReadWindow(Supplier<? extends T> reader) {
        owner = Thread.currentThread();
        this.reader = Objects.requireNonNull(reader);
    }

    public static <T> StructureReadWindow<T> detached(@Nullable T fact) {
        return new StructureReadWindow<>(() -> fact);
    }

    /**
     * The callback must neither load chunks nor access the live provider map.
     */
    public static <T> StructureReadWindow<T> joined(Supplier<? extends T> reader) {
        return new StructureReadWindow<>(reader);
    }

    @Nullable
    public T read() {
        if (Thread.currentThread() == owner || closed)
            throw new IllegalStateException("Structure window read requires its joined worker");
        return reader.get();
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner || closed)
            throw new IllegalStateException("Structure window close requires its original owner after reader exit");
        closed = true;
    }

    /**
     * Finite loaded references expose block reads, not a World, provider, tile map or load method.
     */
    @FunctionalInterface
    public interface BlockStates {
        IBlockState getBlockState(BlockPos position);
    }
}
