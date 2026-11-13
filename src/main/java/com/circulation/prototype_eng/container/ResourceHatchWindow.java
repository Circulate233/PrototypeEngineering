package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Server-owned logical window. Its local revision identifies both sides of the current page.
 */
public final class ResourceHatchWindow {
    private final int columns;
    private final int visibleRows;
    private final int cells;
    private final SideState input;
    private final SideState output;
    private long revision = 1;

    public ResourceHatchWindow(SlottedResourceHatch hatch, int columns, int visibleRows) {
        if (columns < 1 || columns > 9 || visibleRows < 1 || visibleRows > 7)
            throw new IllegalArgumentException("Invalid resource window geometry");
        this.columns = columns;
        this.visibleRows = visibleRows;
        cells = columns * (visibleRows + 1);
        input = new SideState(hatch.input());
        output = new SideState(hatch.output());
    }

    public static int rows(int slots, int columns) {
        return (int) ((slots + (long) columns - 1) / columns);
    }

    public boolean move(long expectedRevision, int inputRow, int outputRow) {
        if (expectedRevision != revision || !input.acceptsRow(inputRow) || !output.acceptsRow(outputRow)) return false;
        if (input.firstSlot == (long) inputRow * columns && output.firstSlot == (long) outputRow * columns) return true;
        input.firstSlot = inputRow * columns;
        output.firstSlot = outputRow * columns;
        revision++;
        return true;
    }

    /**
     * Validates before entering any action, including fill/empty and bulk transfers.
     */
    public boolean interact(long expectedRevision, boolean inputSide, int logicalSlot, Consumer<SlottedResourceStorage> action) {
        var side = inputSide ? input : output;
        var storage = side.storage;
        if (expectedRevision != revision || storage == null || logicalSlot < side.firstSlot
            || logicalSlot >= storage.size() || (long) logicalSlot >= side.firstSlot + (long) cells) return false;
        if (!storage.isValid()) return false;
        // The container dispatches on the server thread; each transfer takes its own storage lock.
        // Do not retain one side's lock around collect actions that also visit the other side.
        action.accept(storage);
        return true;
    }

    public ResourceHatchWindowSnapshot capture() {
        return new ResourceHatchWindowSnapshot(revision, input.capture(), output.capture());
    }

    private final class SideState {
        private final @Nullable SlottedResourceStorage storage;
        private int firstSlot;
        private long countedRevision = -1;
        private int usedSlots, usedTypes;
        private long capturedRevision = -1;
        private int capturedFirstSlot = -1;
        @Nullable
        private ResourceHatchWindowSnapshot.Side captured;

        private SideState(@Nullable SlottedResourceStorage storage) {
            this.storage = storage;
        }

        private boolean acceptsRow(int row) {
            return row >= 0 && row <= Math.max(0, rows(storage == null ? 0 : storage.size(), columns) - visibleRows);
        }

        private ResourceHatchWindowSnapshot.Side capture() {
            if (storage == null) return ResourceHatchWindowSnapshot.Side.EMPTY;
            storage.context().lock();
            try {
                long current = storage.context().revision();
                if (captured != null && capturedFirstSlot == firstSlot && storage.context().current(capturedRevision))
                    return captured;
                if (countedRevision != current) {
                    int occupied = 0;
                    var keys = new ObjectOpenHashSet<AEKey>();
                    for (int i = 0; i < storage.size(); i++) {
                        var stack = storage.getStack(i);
                        if (stack != null) {
                            occupied++;
                            keys.add(stack.what());
                        }
                    }
                    usedSlots = occupied;
                    usedTypes = keys.size();
                    countedRevision = current;
                }
                int count = Math.min(cells, storage.size() - firstSlot);
                var entries = new ObjectArrayList<ResourceHatchWindowSnapshot.Entry>(count);
                for (int cell = 0; cell < count; cell++) {
                    var stack = storage.getStack(firstSlot + cell);
                    entries.add(new ResourceHatchWindowSnapshot.Entry(stack, stack == null ? 0 : storage.getMaxAmount(stack.what())));
                }
                var snapshot = new ResourceHatchWindowSnapshot.Side(firstSlot, storage.size(), usedSlots, usedTypes, entries);
                captured = snapshot;
                capturedRevision = current;
                capturedFirstSlot = firstSlot;
                return snapshot;
            } finally {
                storage.context().unlock();
            }
        }
    }
}
