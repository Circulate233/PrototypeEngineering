package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Independent multi-type slots. Every view and reservation shares one balance and lock. */
public final class SlottedResourceBuffer implements SlottedResourceStorage {
    private final StorageContext context;
    private final Reference2LongArrayMap<AEKeyType> capacities = new Reference2LongArrayMap<>();
    private final AEKey[] keys;
    private final long[] amounts;
    private final long[] reservedExtract;
    private final long[] reservedInsert;
    private final AEKey2LongMap totals = new AEKey2LongMap.LinkdOpenHashMap();
    private final AEKey2LongMap extractHolds = new AEKey2LongMap.OpenHashMap();
    // Includes pending output and the capacity retained to refund applied input.
    private final AEKey2LongMap insertHolds = new AEKey2LongMap.OpenHashMap();
    private final BitSet freeSlots;
    private final BitSet changedSlots = new BitSet();
    private int activeReservations;

    public SlottedResourceBuffer(int slots, Map<AEKeyType, Long> capacities, Runnable onChanged) {
        if (slots <= 0 || capacities.isEmpty()) {
            throw new IllegalArgumentException("A buffer needs slots and supported resource types");
        }
        for (var entry : capacities.entrySet()) {
            AEKeyType type = Objects.requireNonNull(entry.getKey(), "type");
            long capacity = Objects.requireNonNull(entry.getValue(), "capacity");
            if (capacity <= 0) {
                throw new IllegalArgumentException("Slot capacities must be positive");
            }
            this.capacities.put(type, capacity);
        }
        context = new StorageContext(onChanged);
        keys = new AEKey[slots];
        amounts = new long[slots];
        reservedExtract = new long[slots];
        reservedInsert = new long[slots];
        freeSlots = new BitSet(slots);
        freeSlots.set(0, slots);
    }

    @Override
    public StorageContext context() {
        return context;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public StorageSnapshot snapshot() {
        context.lock();
        try {
            var available = new AEKey2LongMap.LinkdOpenHashMap();
            if (context.isValid()) {
                totals.forEachEntry(available::addTo);
                extractHolds.forEachEntry((key, held) -> {
                    long previous = available.addTo(key, -held);
                    if (previous < held) { throw new IllegalStateException("Extraction holds exceed stored resources"); }
                    if (previous == held) { available.removeLong(key); }
                });
            }
            return new StorageSnapshot(context, context.revision(), available);
        } finally {
            context.unlock();
        }
    }

    @Override
    public long amount(AEKey key) {
        Objects.requireNonNull(key);
        context.lock();
        try {
            return context.isValid() ? totals.getLong(key) : 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public long availableAmount(AEKey key) {
        Objects.requireNonNull(key);
        context.lock();
        try {
            return context.isValid() ? totals.getLong(key) - extractHolds.getLong(key) : 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public long availableAmount(int slot) {
        context.lock();
        try {
            checkSlot(slot);
            return context.isValid() ? amounts[slot] - reservedExtract[slot] : 0;
        } finally {
            context.unlock();
        }
    }

    @Override
    public @Nullable GenericStack getStack(int slot) {
        context.lock();
        try {
            checkSlot(slot);
            return amounts[slot] == 0 ? null : new GenericStack(keys[slot], amounts[slot]);
        } finally {
            context.unlock();
        }
    }

    @Override
    public long getAmount(int slot) {
        context.lock();
        try {
            checkSlot(slot);
            return amounts[slot];
        } finally {
            context.unlock();
        }
    }

    @Override
    public long getCapacity(AEKeyType type) {
        Objects.requireNonNull(type);
        return capacities.getLong(type);
    }

    @Override
    public long getMaxAmount(AEKey key) {
        Objects.requireNonNull(key);
        return getCapacity(key.getType());
    }

    @Override
    public boolean isSupportedType(AEKeyType type) {
        Objects.requireNonNull(type);
        return capacities.containsKey(type);
    }

    @Override
    public boolean isAllowedIn(int slot, AEKey key) {
        Objects.requireNonNull(key);
        context.lock();
        try {
            checkSlot(slot);
            return context.isValid() && capacities.containsKey(key.getType())
                && (reservedExtract[slot] == 0 && reservedInsert[slot] == 0 || key.equals(keys[slot]));
        } finally {
            context.unlock();
        }
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode) {
        return transfer(key, amount, mode, true, -1);
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode) {
        return transfer(key, amount, mode, false, -1);
    }

    @Override
    public long insert(int slot, AEKey key, long amount, Actionable mode) {
        checkSlot(slot);
        return transfer(key, amount, mode, true, slot);
    }

    @Override
    public long extract(int slot, AEKey key, long amount, Actionable mode) {
        checkSlot(slot);
        return transfer(key, amount, mode, false, slot);
    }

    private long transfer(AEKey key, long amount, Actionable mode, boolean insert, int onlySlot) {
        checkRequest(key, amount, mode);
        context.lock();
        try {
            if (!context.isValid() || amount == 0) {
                return 0;
            }
            long limit = Math.min(amount, insert ? insertionHeadroom(key) : totals.getLong(key) - extractHolds.getLong(key));
            long transferred = onlySlot >= 0 ? slotTransferable(onlySlot, key, limit, insert)
                : transferSlots(key, limit, insert, false);
            if (transferred == 0 || mode == Actionable.SIMULATE) {
                return transferred;
            }
            requireChange();
            long total = insert ? Math.addExact(totals.getLong(key), transferred)
                : Math.subtractExact(totals.getLong(key), transferred);
            if (onlySlot >= 0) {
                checkedSlotChange(onlySlot, key, transferred, insert);
                writeSlot(onlySlot, key, transferred, insert);
            } else {
                transferSlots(key, transferred, insert, true);
            }
            addAmount(totals, key, insert ? transferred : -transferred, total);
            context.changed();
            return transferred;
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

    private ReservationResult reserve(StorageSnapshot snapshot, AEKey key, long amount, boolean insert) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(key);
        if (amount <= 0) {
            throw new IllegalArgumentException("A reservation needs a positive amount");
        }
        context.lock();
        try {
            if (!context.isValid()) {
                return rejected(ReservationResult.Status.INVALID_STORAGE);
            }
            if (snapshot.context() != context || snapshot.revision() != context.revision()) {
                return rejected(ReservationResult.Status.STALE_SNAPSHOT);
            }
            if (insert && amount > insertionHeadroom(key)) {
                return rejected(ReservationResult.Status.INSUFFICIENT);
            }
            Allocation allocation = allocate(key, amount, insert);
            if (allocation.total != amount) {
                return rejected(ReservationResult.Status.INSUFFICIENT);
            }
            if (activeReservations == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many active storage reservations");
            }
            requireChange();
            long nextInsertHold = insert ? Math.addExact(insertHolds.getLong(key), amount) : 0;
            long nextExtractHold = insert ? 0 : Math.addExact(extractHolds.getLong(key), amount);
            long[] holds = insert ? reservedInsert : reservedExtract;
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                if (allocation.amounts[part] > Long.MAX_VALUE - holds[slot]) {
                    throw new IllegalStateException("Reserved slot amount exceeds long capacity");
                }
            }
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                keys[slot] = key;
                freeSlots.clear(slot);
                holds[slot] += allocation.amounts[part];
                changedSlots.set(slot);
            }
            if (insert) {
                addAmount(insertHolds, key, amount, nextInsertHold);
            } else {
                addAmount(extractHolds, key, amount, nextExtractHold);
            }
            activeReservations++;
            context.changed();
            return new ReservationResult(ReservationResult.Status.ACCEPTED, new Reservation(key, allocation, insert));
        } finally {
            context.unlock();
        }
    }

    @Override
    public boolean tryReplaceSlot(int slot, @Nullable GenericStack expected, @Nullable GenericStack next) {
        if (expected != null && expected.amount() < 0) {
            throw new IllegalArgumentException("Negative expected slot amount");
        }
        context.lock();
        try {
            checkSlot(slot);
            validateStack(next);
            if (!context.isValid() || reservedExtract[slot] != 0 || reservedInsert[slot] != 0) {
                return false;
            }
            GenericStack current = amounts[slot] == 0 ? null : new GenericStack(keys[slot], amounts[slot]);
            GenericStack replacement = next == null || next.amount() == 0 ? null : next;
            if (!Objects.equals(current, expected)) {
                return false;
            }
            if (Objects.equals(current, replacement)) {
                return true;
            }
            AEKey previousKey = keys[slot];
            AEKey nextKey = replacement == null ? null : replacement.what();
            long nextAmount = replacement == null ? 0 : replacement.amount();
            long nextTotal = 0;
            if (nextKey != null) {
                long base = totals.getLong(nextKey) - (nextKey.equals(previousKey) ? amounts[slot] : 0);
                if (nextAmount > Long.MAX_VALUE - base - insertHolds.getLong(nextKey)) {
                    return false;
                }
                nextTotal = base + nextAmount;
            }
            requireChange();
            if (previousKey != null) {
                long previousTotal = totals.getLong(previousKey) - amounts[slot];
                addAmount(totals, previousKey, -amounts[slot], previousTotal);
            }
            if (nextKey != null) {
                addAmount(totals, nextKey, nextAmount, nextTotal);
            }
            keys[slot] = nextKey;
            amounts[slot] = nextAmount;
            if (nextKey == null) {
                freeSlots.set(slot);
            } else {
                freeSlots.clear(slot);
            }
            changedSlots.set(slot);
            context.changed();
            return true;
        } finally {
            context.unlock();
        }
    }

    @Override
    public int[] drainChangedSlots() {
        context.lock();
        try {
            int[] result = changedSlots.stream().toArray();
            changedSlots.clear();
            return result;
        } finally {
            context.unlock();
        }
    }

    /** Loads committed slots after validating the entire saved balance; no NBT belongs here. */
    public void restore(List<GenericStack> stacks) {
        context.lock();
        try {
            restorePrepared(prepareRestore(stacks));
        } finally {
            context.unlock();
        }
    }

    /** Both sides of a mixed hatch validate first while their context locks remain held. */
    PreparedRestore prepareRestore(List<GenericStack> stacks) {
        context.requireLock();
        Objects.requireNonNull(stacks);
        requireRestorable();
        requireChange();
        if (stacks.size() != keys.length) {
            throw new IllegalArgumentException("Saved slot count differs from storage");
        }
        var nextTotals = new AEKey2LongMap.LinkdOpenHashMap();
        AEKey[] nextKeys = new AEKey[keys.length];
        long[] nextAmounts = new long[keys.length];
        for (int slot = 0; slot < keys.length; slot++) {
            GenericStack stack = stacks.get(slot);
            validateStack(stack);
            if (stack != null && stack.amount() > 0) {
                nextKeys[slot] = stack.what();
                nextAmounts[slot] = stack.amount();
                long total = nextTotals.getLong(stack.what());
                if (stack.amount() > Long.MAX_VALUE - total) {
                    throw new IllegalArgumentException("Saved resource total exceeds long capacity");
                }
                nextTotals.addTo(stack.what(), stack.amount());
            }
        }
        return new PreparedRestore(context, context.revision(), nextKeys, nextAmounts, nextTotals);
    }

    void restorePrepared(PreparedRestore prepared) {
        context.requireLock();
        Objects.requireNonNull(prepared);
        requireRestorable();
        if (prepared.context != context || prepared.revision != context.revision()) {
            throw new IllegalStateException("Prepared restoration is stale or belongs to another storage");
        }
        requireChange();
        totals.clear();
        totals.putAll(prepared.totals);
        for (int slot = 0; slot < keys.length; slot++) {
            keys[slot] = prepared.keys[slot];
            amounts[slot] = prepared.amounts[slot];
            freeSlots.set(slot, prepared.keys[slot] == null);
            changedSlots.set(slot);
        }
        context.changed();
    }

    private void requireRestorable() {
        if (!context.isValid()) {
            throw new IllegalStateException("Cannot restore invalid storage");
        }
        if (activeReservations != 0) {
            throw new IllegalStateException("Cannot restore storage with active reservations");
        }
    }

    static final class PreparedRestore {
        private final StorageContext context;
        private final long revision;
        private final AEKey[] keys;
        private final long[] amounts;
        private final AEKey2LongMap totals;

        private PreparedRestore(StorageContext context, long revision, AEKey[] keys, long[] amounts,
                                AEKey2LongMap totals) {
            this.context = context;
            this.revision = revision;
            this.keys = keys;
            this.amounts = amounts;
            this.totals = totals;
        }
    }

    private void validateStack(@Nullable GenericStack stack) {
        if (stack != null && (stack.amount() < 0
            || stack.amount() > capacities.getLong(stack.what().getType())
            || !capacities.containsKey(stack.what().getType()))) {
            throw new IllegalArgumentException("Slot resource or amount is outside configured capacity");
        }
    }

    private long insertionHeadroom(AEKey key) {
        return Long.MAX_VALUE - totals.getLong(key) - insertHolds.getLong(key);
    }

    private Allocation allocate(AEKey key, long requested, boolean insert) {
        var slots = new IntArrayList();
        var portions = new LongArrayList();
        long remaining = requested;
        // PM's stable assignment: use the same resource's slots before claiming free slots.
        for (int slot = 0; slot < keys.length && remaining > 0; slot++) {
            if (key.equals(keys[slot])) {
                remaining -= allocateSlot(slot, key, remaining, insert, slots, portions);
            }
        }
        if (insert) {
            for (int slot = freeSlots.nextSetBit(0); slot >= 0 && remaining > 0; slot = freeSlots.nextSetBit(slot + 1)) {
                remaining -= allocateSlot(slot, key, remaining, true, slots, portions);
            }
        }
        return new Allocation(slots.toIntArray(), portions.toLongArray(), requested - remaining);
    }

    private long allocateSlot(int slot, AEKey key, long requested, boolean insert,
                              IntArrayList slots, LongArrayList portions) {
        long amount = slotTransferable(slot, key, requested, insert);
        if (amount > 0) {
            slots.add(slot);
            portions.add(amount);
        }
        return amount;
    }

    private long slotTransferable(int slot, AEKey key, long requested, boolean insert) {
        if (requested == 0 || keys[slot] != null && !key.equals(keys[slot])) {
            return 0;
        }
        long available = insert ? capacities.getLong(key.getType()) - amounts[slot] - reservedInsert[slot]
            : amounts[slot] - reservedExtract[slot];
        return Math.min(requested, available);
    }

    /** Normal transfers preflight and apply the same stable traversal without temporary allocations. */
    private long transferSlots(AEKey key, long requested, boolean insert, boolean write) {
        long remaining = requested;
        for (int slot = 0; slot < keys.length && remaining > 0; slot++) {
            if (key.equals(keys[slot])) {
                remaining -= transferSlot(slot, key, remaining, insert, write);
            }
        }
        if (insert) {
            for (int slot = freeSlots.nextSetBit(0); slot >= 0 && remaining > 0; slot = freeSlots.nextSetBit(slot + 1)) {
                remaining -= transferSlot(slot, key, remaining, true, write);
            }
        }
        return requested - remaining;
    }

    private long transferSlot(int slot, AEKey key, long requested, boolean insert, boolean write) {
        long amount = slotTransferable(slot, key, requested, insert);
        if (amount > 0) {
            if (write) {
                writeSlot(slot, key, amount, insert);
            } else {
                checkedSlotChange(slot, key, amount, insert);
            }
        }
        return amount;
    }

    private void writeSlot(int slot, AEKey key, long amount, boolean insert) {
        // The first pass validated this arithmetic while holding the same lock.
        amounts[slot] += insert ? amount : -amount;
        keys[slot] = key;
        freeSlots.clear(slot);
        cleanSlot(slot);
        changedSlots.set(slot);
    }

    private long checkedSlotChange(int slot, AEKey key, long amount, boolean insert) {
        long next = insert ? Math.addExact(amounts[slot], amount) : Math.subtractExact(amounts[slot], amount);
        if (next < 0 || next > capacities.getLong(key.getType())) {
            throw new IllegalStateException("Slot balance is outside capacity");
        }
        return next;
    }

    private void cleanSlot(int slot) {
        if (amounts[slot] == 0 && reservedExtract[slot] == 0 && reservedInsert[slot] == 0) {
            keys[slot] = null;
            freeSlots.set(slot);
        }
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= keys.length) {
            throw new IndexOutOfBoundsException(slot);
        }
    }

    private void requireChange() {
        context.requireLock();
        if (context.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Storage revision exhausted");
        }
    }

    private static void checkRequest(AEKey key, long amount, Actionable mode) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(mode);
        if (amount < 0) {
            throw new IllegalArgumentException("Negative resource amount");
        }
    }

    private static ReservationResult rejected(ReservationResult.Status status) {
        return new ReservationResult(status, null);
    }

    /** Applies a delta only after its full balance/slot preflight. AE2 maps retain zero entries. */
    private static void addAmount(AEKey2LongMap map, AEKey key, long delta, long nextAmount) {
        if (delta != 0) {
            map.addTo(key, delta);
        }
        if (nextAmount == 0) {
            map.removeLong(key);
        }
    }

    private record Allocation(int[] slots, long[] amounts, long total) {
    }

    private enum State { PENDING, APPLIED, COMMITTED, ROLLED_BACK }

    private final class Reservation implements ResourceReservation {
        private final AEKey key;
        private final Allocation allocation;
        private final boolean insert;
        private State state = State.PENDING;

        private Reservation(AEKey key, Allocation allocation, boolean insert) {
            this.key = key;
            this.allocation = allocation;
            this.insert = insert;
        }

        @Override
        public StorageContext context() {
            return context;
        }

        @Override
        public Resolution resolution() {
            context.requireLock();
            return switch (state) {
                case PENDING -> Resolution.RESERVED;
                case APPLIED -> Resolution.APPLIED;
                case COMMITTED -> Resolution.COMPLETED;
                case ROLLED_BACK -> Resolution.ROLLED_BACK;
            };
        }

        @Override
        public boolean canApply() {
            context.requireLock();
            requireState(State.PENDING);
            return context.isValid();
        }

        @Override
        public void apply() {
            context.requireLock();
            requireState(State.PENDING);
            if (!context.isValid()) {
                throw new IllegalStateException("Cannot apply an invalid storage reservation");
            }
            requireChange();
            long totalDelta = insert ? allocation.total : -allocation.total;
            long nextTotal = Math.addExact(totals.getLong(key), totalDelta);
            long nextInsertHold = Math.subtractExact(insertHolds.getLong(key), totalDelta);
            long nextExtractHold = Math.addExact(extractHolds.getLong(key), totalDelta);
            long[] previousHolds = insert ? reservedInsert : reservedExtract;
            long[] nextHolds = insert ? reservedExtract : reservedInsert;
            long[] nextAmounts = new long[allocation.slots.length];
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                long portion = allocation.amounts[part];
                if (!key.equals(keys[slot]) || previousHolds[slot] < portion) {
                    throw new IllegalStateException("Reservation lost its slot allocation");
                }
                if (portion > Long.MAX_VALUE - nextHolds[slot]) {
                    throw new IllegalStateException("Compensation slot amount exceeds long capacity");
                }
                nextAmounts[part] = checkedSlotChange(slot, key, portion, insert);
            }
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                long portion = allocation.amounts[part];
                previousHolds[slot] -= portion;
                nextHolds[slot] += portion;
                amounts[slot] = nextAmounts[part];
                changedSlots.set(slot);
            }
            addAmount(totals, key, totalDelta, nextTotal);
            addAmount(insertHolds, key, -totalDelta, nextInsertHold);
            addAmount(extractHolds, key, totalDelta, nextExtractHold);
            state = State.APPLIED;
            context.changed();
        }

        @Override
        public void complete() {
            context.requireLock();
            requireState(State.APPLIED);
            release(false);
            state = State.COMMITTED;
        }

        @Override
        public void rollback() {
            context.requireLock();
            if (state != State.PENDING && state != State.APPLIED) {
                throw new IllegalStateException("Cannot roll back terminal storage reservation");
            }
            release(state == State.APPLIED);
            state = State.ROLLED_BACK;
        }

        private void release(boolean restore) {
            requireChange();
            if (activeReservations <= 0) {
                throw new IllegalStateException("Reservation count is inconsistent");
            }
            // Applying switches output capacity to held quantity, and input quantity to refund capacity.
            boolean holdsInsert = (state == State.PENDING) == insert;
            long[] holds = holdsInsert ? reservedInsert : reservedExtract;
            long nextInsertHold = holdsInsert ? Math.subtractExact(insertHolds.getLong(key), allocation.total)
                : insertHolds.getLong(key);
            long nextExtractHold = holdsInsert ? extractHolds.getLong(key)
                : Math.subtractExact(extractHolds.getLong(key), allocation.total);
            long nextTotal = totals.getLong(key);
            if (restore) {
                nextTotal = insert ? Math.subtractExact(nextTotal, allocation.total) : Math.addExact(nextTotal, allocation.total);
            }
            long[] nextAmounts = new long[allocation.slots.length];
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                if (!key.equals(keys[slot]) || holds[slot] < allocation.amounts[part]) {
                    throw new IllegalStateException("Reservation lost its compensation rights");
                }
                nextAmounts[part] = restore ? checkedSlotChange(slot, key, allocation.amounts[part], !insert) : amounts[slot];
            }
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                long portion = allocation.amounts[part];
                amounts[slot] = nextAmounts[part];
                holds[slot] -= portion;
                cleanSlot(slot);
                changedSlots.set(slot);
            }
            addAmount(totals, key, restore ? (insert ? -allocation.total : allocation.total) : 0, nextTotal);
            addAmount(insertHolds, key, holdsInsert ? -allocation.total : 0, nextInsertHold);
            addAmount(extractHolds, key, holdsInsert ? 0 : -allocation.total, nextExtractHold);
            activeReservations--;
            context.changed();
        }

        private void requireState(State expected) {
            if (state != expected) {
                throw new IllegalStateException("Reservation is " + state + ", expected " + expected);
            }
        }
    }
}
