package com.circulation.prototype_eng.container;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * PM double-tap transfers. Player slot zero is the cursor, followed by main inventory and offhand.
 */
final class ResourceHatchBulkTransfer {
    private ResourceHatchBulkTransfer() {
    }

    static void withSlotKey(SlottedResourceStorage storage, int slot, @Nullable AEKey expected, Consumer<AEKey> action) {
        storage.context().lock();
        try {
            var stored = storage.getStack(slot);
            if (stored != null && (expected == null || expected.equals(stored.what()))) action.accept(stored.what());
        } finally {
            storage.context().unlock();
        }
    }

    static void doubleClick(SlottedResourceStorage storage, int slot, boolean input, int button,
                            PlayerSlots player, HeldContainers containers) {
        storage.context().lock();
        try {
            var cursor = player.get(0);
            var stored = storage.getStack(slot);
            if (cursor != null && input && button == 1 && containers.tryEmpty()) return;
            if (cursor != null && stored != null && !containers.isItem(stored.what()) && button == 0
                && containers.tryFill(stored)) return;
            if (input && cursor != null && containers.canInsertCursor()) insertAllSame(storage, cursor.what(), player);
            else if (cursor == null && stored != null && containers.isItem(stored.what()))
                extractAllSame(storage, stored.what(), player);
        } finally {
            storage.context().unlock();
        }
    }

    static void insertAllSame(SlottedResourceStorage storage, AEKey key, PlayerSlots player) {
        storage.context().lock();
        try {
            for (int slot = 0; slot < player.size(); slot++) {
                var held = player.get(slot);
                if (held == null || !held.what().equals(key) || player.limit(slot, key) == 0) continue;
                long inserted = storage.insert(key, held.amount(), Actionable.MODULATE);
                if (inserted > 0)
                    player.set(slot, inserted == held.amount() ? null : new GenericStack(key, held.amount() - inserted));
            }
        } finally {
            storage.context().unlock();
        }
    }

    static void extractAllSame(SlottedResourceStorage storage, AEKey key, PlayerSlots player) {
        storage.context().lock();
        try {
            // Fill matching partial stacks before empty inventory cells, including the offhand.
            for (int pass = 0; pass < 2; pass++)
                for (int slot = 1; slot < player.size(); slot++) {
                    var held = player.get(slot);
                    if ((held == null) != (pass == 1)) continue;
                    if (held != null && !held.what().equals(key)) continue;
                    long present = held == null ? 0 : held.amount();
                    long room = Math.max(0, player.limit(slot, key) - present);
                    if (room == 0) continue;
                    long extracted = storage.extract(key, room, Actionable.MODULATE);
                    if (extracted == 0) break;
                    player.set(slot, new GenericStack(key, present + extracted));
                }
        } finally {
            storage.context().unlock();
        }
    }

    interface PlayerSlots {
        int size();

        @Nullable GenericStack get(int slot);

        void set(int slot, @Nullable GenericStack stack);

        long limit(int slot, AEKey key);
    }

    interface HeldContainers {
        boolean isItem(AEKey key);

        boolean canInsertCursor();

        boolean tryEmpty();

        boolean tryFill(GenericStack selected);
    }
}
