package com.circulation.prototype_eng.client.preview;

import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Viewer-local representatives with one exact retirement owner. Never inserted, ticked or networked. */
public final class PreviewTileEntities<T> implements AutoCloseable {
    private final Thread ownerThread = Thread.currentThread();
    private final Owner<T> owner;
    private final ObjectArrayList<Entry<T>> entries = new ObjectArrayList<>();
    private Object token, world;
    private int buildCursor, drawCursor;
    private boolean closed;
    public PreviewTileEntities(Owner<T> owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    static @Nullable NBTTagCompound parse(@Nullable String snbt) {
        if (snbt == null) return null;
        try {
            return JsonToNBT.getTagFromJson(snbt);
        } catch (NBTException invalidDisplay) {
            throw new IllegalArgumentException("Invalid structure preview tile SNBT: " + snbt, invalidDisplay);
        }
    }

    static @Nullable NBTTagCompound positionTag(@Nullable NBTTagCompound template, BlockPos position) {
        if (template == null) return null;
        var owned = template.copy();
        owned.setInteger("x", position.getX());
        owned.setInteger("y", position.getY());
        owned.setInteger("z", position.getZ());
        return owned;
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Preview TE requires client owner");
    }

    public void prepare(StructurePreviewView view, PreviewBlockAccess access, @Nullable Object nextWorld, int visits) {
        requireOwner();
        if (closed) return;
        if (visits < 0) throw new IllegalArgumentException("Negative TE visits");
        if (view.model() == null) {
            clear();
            return;
        }
        synchronize(view.token(), nextWorld);
        var model = view.model();
        if (model == null || nextWorld == null || !access.ready()) return;
        for (int step = 0; step < visits && buildCursor < model.cells().size(); step++) {
            var cell = model.cells().get(buildCursor++);
            var state = access.represented(cell.position());
            if (state == null) continue;
            var tag = parse(cell.display() == null ? null : cell.display().tileNbt());
            capture(view.token(), nextWorld, cell, state, tag, () -> model == view.model());
            if (closed || world != nextWorld || token != view.token() || model != view.model()) return;
        }
    }

    void synchronize(Object expectedToken, @Nullable Object nextWorld) {
        requireOwner();
        if (closed) return;
        if (world != nextWorld || token != expectedToken) {
            clear();
            world = nextWorld;
            token = expectedToken;
        }
    }

    /** Real per-representative acquisition, separated from native state lookup for precise ownership tests. */
    void capture(Object expectedToken, Object currentWorld, StructurePreviewModel.Cell cell, @Nullable IBlockState state,
                 @Nullable NBTTagCompound template, BooleanSupplier currentModel) {
        requireOwner();
        if (closed || token != expectedToken || world != currentWorld || !currentModel.getAsBoolean()) return;
        T created = owner.create(currentWorld, state, cell.position(), positionTag(template, cell.position()));
        if (created == null) return;
        if (closed || world != currentWorld || token != expectedToken || !currentModel.getAsBoolean()) {
            owner.release(created);
            return;
        }
        entries.add(new Entry<>(cell, created));
    }

    public void draw(StructurePreviewView view, float partialTicks, int visits) {
        requireOwner();
        if (visits < 0) throw new IllegalArgumentException("Negative TE draw visits");
        if (closed || token != view.token() || view.model() == null) {
            clear();
            return;
        }
        for (int step = 0; step < Math.min(visits, entries.size()); step++) {
            var entry = entries.get(drawCursor);
            drawCursor = (drawCursor + 1) % entries.size();
            if (!view.visible(entry.cell)) continue;
            owner.draw(entry.tile, entry.cell.position(), partialTicks);
            if (closed || token != view.token() || view.model() == null) {
                clear();
                return;
            }
        }
    }

    public void clear() {
        requireOwner();
        buildCursor = drawCursor = 0;
        token = null;
        world = null;
        Throwable failure = null;
        var retired = new ObjectArrayList<>(entries);
        entries.clear();
        while (!retired.isEmpty()) {
            var tile = retired.removeLast().tile;
            try {
                owner.release(tile);
            } catch (RuntimeException | Error caught) {
                failure = PreviewBlockModelCache.merge(failure, caught);
            }
        }
        PreviewBlockModelCache.rethrow(failure);
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        clear();
    }

    /** Narrow seam for actual TE ownership and tests; not a rendering-provider hierarchy. */
    public interface Owner<T> {
        @Nullable T create(Object world, IBlockState state, BlockPos position, @Nullable NBTTagCompound ownTag);

        void release(T tile);

        void draw(T tile, BlockPos position, float partialTicks);
    }

    private record Entry<T>(StructurePreviewModel.Cell cell, T tile) {
    }
}
