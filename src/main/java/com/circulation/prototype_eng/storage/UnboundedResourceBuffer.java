package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A key-counted output buffer: no slot limit, with long capacity and exact compensation per key.
 */
public final class UnboundedResourceBuffer implements ResourceStorage {
    private final AEKey2LongMap totals = new AEKey2LongMap.OpenHashMap();
    private final AEKey2LongMap insertHolds = new AEKey2LongMap.OpenHashMap();
    private final AEKey2LongMap extractHolds = new AEKey2LongMap.OpenHashMap();
    private final StorageContext context;
    private int reservations;
    private int externalReservations;
    private boolean destroyed;
    private @Nullable Consumer<List<GenericStack>> destructionDrops;
    private @Nullable StorageSnapshot cachedSnapshot;

    public UnboundedResourceBuffer(Runnable onChanged) {
        context = new StorageContext(() -> {
            try {
                onChanged.run();
            } finally {
                publishDestructionDrops();
            }
        });
    }

    private static void add(AEKey2LongMap map, AEKey key, long delta) {
        long next = Math.addExact(map.getLong(key), delta);
        if (next < 0) throw new IllegalStateException("Negative output resource balance");
        map.addTo(key, delta);
        if (next == 0) map.removeLong(key);
    }

    @Override
    public StorageContext context() {
        return context;
    }

    @Override
    public StorageSnapshot snapshot() {
        context.lock();
        try {
            if (cachedSnapshot != null && context.current(cachedSnapshot.revision())) return cachedSnapshot;
            var available = ImmutableResourceAmounts.builder();
            if (context.isValid()) {
                totals.forEachEntry((key, total) -> {
                    long amount = total - extractHolds.getLong(key);
                    if (amount < 0) throw new IllegalStateException("Extraction holds exceed the output balance");
                    if (amount > 0) available.addTo(key, amount);
                });
            }
            cachedSnapshot = new StorageSnapshot(context, context.revision(), available.build());
            return cachedSnapshot;
        } finally {
            context.unlock();
        }
    }

    @Override
    public StorageSnapshot snapshotFor(AEKey key) {
        context.lock();
        try {
            long available = context.isValid() ? totals.getLong(key) - extractHolds.getLong(key) : 0;
            return new StorageSnapshot(context, context.revision(), ImmutableResourceAmounts.single(key, available));
        } finally {
            context.unlock();
        }
    }

    @Override
    public long amount(AEKey key) {
        context.lock();
        try {
            return context.isValid() ? totals.getLong(key) : 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public long availableAmount(AEKey key) {
        context.lock();
        try {
            return context.isValid() ? totals.getLong(key) - extractHolds.getLong(key) : 0;
        } finally {
            context.unlock();
        }
    }

    private long room(AEKey key) {
        return Long.MAX_VALUE - totals.getLong(key) - insertHolds.getLong(key);
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode) {
        return transfer(key, amount, mode, true);
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode) {
        return transfer(key, amount, mode, false);
    }

    private long transfer(AEKey key, long amount, Actionable mode, boolean insert) {
        if (amount < 0) throw new IllegalArgumentException("Negative output transfer");
        context.lock();
        try {
            if (!context.isValid()) return 0;
            long accepted = Math.min(amount, insert ? room(key) : totals.getLong(key) - extractHolds.getLong(key));
            if (accepted > 0 && mode == Actionable.MODULATE) {
                context.requireChange();
                add(totals, key, insert ? accepted : -accepted);
                context.changed();
            }
            return accepted;
        } finally {
            context.unlock();
        }
    }

    @Override
    public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
        return reserve(snapshot, key, amount, false);
    }

    @Override
    public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
        return reserve(snapshot, key, amount, true);
    }

    private ReservationResult reserve(@Nullable StorageSnapshot snapshot, AEKey key, long amount, boolean insert) {
        if (amount <= 0) throw new IllegalArgumentException("A reservation needs a positive amount");
        context.lock();
        try {
            if (!context.isValid()) return ReservationResult.INVALID_STORAGE;
            if (snapshot != null && (snapshot.context() != context || !context.current(snapshot.revision())))
                return ReservationResult.STALE_SNAPSHOT;
            if (amount > (insert ? room(key) : totals.getLong(key) - extractHolds.getLong(key)))
                return ReservationResult.INSUFFICIENT;
            if (reservations == Integer.MAX_VALUE) throw new IllegalStateException("Too many output reservations");
            context.requireChange(3);
            var token = new Reservation(key, amount, insert);
            var result = new ReservationResult(ReservationResult.Status.ACCEPTED, token);
            add(insert ? insertHolds : extractHolds, key, amount);
            context.reserveChanges(3);
            reservations++;
            context.changed();
            return result;
        } finally {
            context.unlock();
        }
    }

    /**
     * Native ME transfers retain refund space around a reentrant external inventory callback.
     */
    @SuppressWarnings("ReturnOfInnerClass") // The receipt retains the exact native compensation token.
    public @Nullable NativeTransferReservation reserveTransfer(AEKey key, long amount, boolean insert) {
        context.requireOrdinaryLock();
        if (externalReservations == Integer.MAX_VALUE) throw new IllegalStateException("Too many external transfers");
        var accepted = reserve(null, key, amount, insert);
        var token = (Reservation) accepted.reservation();
        if (token == null) return null;
        if (!insert) token.apply();
        externalReservations++;
        return new NativeTransferReservation() {
            @Override
            public long amount() {
                return amount;
            }

            @Override
            public void complete(long actual) {
                token.completeTransfer(actual);
            }
        };
    }

    public boolean hasTransferReservation() {
        context.lock();
        try {
            return externalReservations != 0;
        } finally {
            context.unlock();
        }
    }

    boolean hasReservations() {
        context.lock();
        try {
            return reservations != 0;
        } finally {
            context.unlock();
        }
    }

    /**
     * A detached list for save/drop ownership; callers retain this context throughout multi-side capture.
     */
    List<GenericStack> committedContents() {
        context.requireLock();
        var result = new ObjectArrayList<GenericStack>(totals.size());
        totals.forEachEntry((key, amount) -> {
            if (amount > 0) result.add(new GenericStack(key, amount));
        });
        return result;
    }

    /**
     * Validate all entries before replacing any part of the live balance.
     */
    public void restore(List<GenericStack> contents) {
        var next = new AEKey2LongMap.OpenHashMap(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            var stack = contents.get(index);
            if (stack.amount() <= 0 || stack.amount() > Long.MAX_VALUE - next.getLong(stack.what()))
                throw new IllegalArgumentException("Invalid saved output resource amount");
            next.addTo(stack.what(), stack.amount());
        }
        context.lock();
        try {
            if (reservations != 0 || !context.idleForReplacement())
                throw new IllegalStateException("Output buffer has unresolved reservations");
            context.requireChange();
            totals.clear();
            totals.putAll(next);
            context.changed();
        } finally {
            context.unlock();
        }
    }

    List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferredDrops) {
        context.requireLock();
        if (destroyed) return List.of();
        context.invalidate();
        destroyed = true;
        destructionDrops = deferredDrops;
        if (reservations != 0) return List.of();
        return takeDestructionDrops();
    }

    private List<GenericStack> takeDestructionDrops() {
        context.requireLock();
        var contents = committedContents();
        totals.clear();
        destructionDrops = null;
        return contents;
    }

    private void publishDestructionDrops() {
        Consumer<List<GenericStack>> receiver;
        List<GenericStack> contents;
        context.lock();
        try {
            if (!destroyed || reservations != 0 || destructionDrops == null) return;
            receiver = destructionDrops;
            contents = takeDestructionDrops();
        } finally {
            context.unlock();
        }
        receiver.accept(contents);
    }

    private enum State {RESERVED, APPLIED, EXPOSED, COMPLETED, ROLLED_BACK}

    private final class Reservation implements ResourceReservation {
        private final AEKey key;
        private final long amount;
        private final boolean insert;
        private State state = State.RESERVED;
        private int changes = 3;
        private boolean ambiguous;
        private @Nullable StorageFence exposedFence;

        private Reservation(AEKey key, long amount, boolean insert) {
            this.key = key;
            this.amount = amount;
            this.insert = insert;
        }

        @Override
        public StorageContext context() {
            return context;
        }

        @Override
        public Resolution resolution() {
            context.requireLock();
            if (ambiguous) return Resolution.UNKNOWN;
            requireExposure();
            return switch (state) {
                case RESERVED -> Resolution.RESERVED;
                case APPLIED, EXPOSED -> Resolution.APPLIED;
                case COMPLETED -> Resolution.COMPLETED;
                case ROLLED_BACK -> Resolution.ROLLED_BACK;
            };
        }

        @Override
        public boolean canApply() {
            context.requireLock();
            requireState(State.RESERVED);
            return context.isValid();
        }

        @Override
        public void apply() {
            context.requireLock();
            requireState(State.RESERVED);
            if (!context.isValid()) throw new IllegalStateException("Cannot apply an invalid output buffer");
            context.requireReservedChange(changes);
            long delta = insert ? amount : -amount;
            long total = Math.addExact(totals.getLong(key), delta);
            long nextInsert = Math.subtractExact(insertHolds.getLong(key), delta);
            long nextExtract = Math.addExact(extractHolds.getLong(key), delta);
            if (total < 0 || nextInsert < 0 || nextExtract < 0)
                throw new IllegalStateException("Output reservation lost its held resources");
            ambiguous = true;
            add(totals, key, delta);
            add(insertHolds, key, -delta);
            add(extractHolds, key, delta);
            state = State.APPLIED;
            changed(false);
            ambiguous = false;
        }

        @Override
        public boolean supportsSequentialUse() {
            return true;
        }

        @Override
        public void exposeApplied() {
            context.requireLock();
            requireState(State.APPLIED);
            context.requireReservedChange(changes);
            ambiguous = true;
            exposedFence = context.retainSequentialReservation();
            add(insert ? extractHolds : insertHolds, key, -amount);
            state = State.EXPOSED;
            changed(false);
            ambiguous = false;
        }

        @Override
        public void complete() {
            context.requireLock();
            if (state != State.APPLIED && state != State.EXPOSED)
                throw new IllegalStateException("Only applied output reservations may complete");
            requireExposure();
            context.requireReservedChange(changes);
            if (ambiguous) throw new IllegalStateException("Output reservation retains unknown mutation effects");
            ambiguous = true;
            if (state == State.APPLIED) add(insert ? extractHolds : insertHolds, key, -amount);
            finish(State.COMPLETED);
            ambiguous = false;
        }

        @Override
        public void rollback() {
            context.requireLock();
            if (state == State.COMPLETED || state == State.ROLLED_BACK)
                throw new IllegalStateException("Output reservation is already terminal");
            requireExposure();
            context.requireReservedChange(changes);
            if (ambiguous) throw new IllegalStateException("Output reservation retains unknown mutation effects");
            if (state != State.RESERVED) {
                if (state == State.EXPOSED && amount > (insert ? totals.getLong(key) - extractHolds.getLong(key) : room(key)))
                    throw new IllegalStateException("Resolve later dependent reservations before rollback");
                ambiguous = true;
                add(totals, key, insert ? -amount : amount);
            }
            ambiguous = true;
            if (state != State.EXPOSED) {
                boolean holdsInsert = (state == State.APPLIED) != insert;
                add(holdsInsert ? insertHolds : extractHolds, key, -amount);
            }
            finish(State.ROLLED_BACK);
            ambiguous = false;
        }

        private void completeTransfer(long actual) {
            context.requireOrdinaryLock();
            requireState(insert ? State.RESERVED : State.APPLIED);
            if (actual < 0 || actual > amount) throw new IllegalArgumentException("Invalid external transfer result");
            if (externalReservations <= 0) throw new IllegalStateException("Missing native external transfer");
            context.requireReservedChange(changes);
            ambiguous = true;
            add(totals, key, insert ? actual : amount - actual);
            add(insertHolds, key, -amount);
            externalReservations--;
            finish(State.COMPLETED);
            ambiguous = false;
        }

        private void requireState(State expected) {
            if (ambiguous) throw new IllegalStateException("Output reservation retains unknown mutation effects");
            if (state != expected) throw new IllegalStateException("Unexpected output reservation state " + state);
        }

        private void requireExposure() {
            if (exposedFence != null && context.requireFenceAccess() != exposedFence)
                throw new IllegalStateException("Sequential reservation belongs to another fence");
        }

        private void changed(boolean terminal) {
            context.reservedChanged(changes, terminal);
            changes = terminal ? 0 : changes - 1;
        }

        private void finish(State next) {
            if (reservations <= 0) throw new IllegalStateException("Missing output reservation");
            reservations--;
            state = next;
            changed(true);
            if (exposedFence != null) {
                context.releaseSequentialReservation(exposedFence);
                exposedFence = null;
            }
        }
    }
}
