package com.circulation.prototype_eng.storage;

import it.unimi.dsi.fastutil.objects.Reference2LongMap;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ImmutableResourceAmounts;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Independent multi-type slots. Every view and reservation shares one balance and lock.
 */
public final class SlottedResourceBuffer implements SlottedResourceStorage {
    private final StorageContext context;
    private @Nullable StorageSnapshot cachedSnapshot;
    private @Nullable StorageSnapshot cachedKeySnapshot;
    private @Nullable AEKey cachedSnapshotKey;
    private final Reference2LongArrayMap<AEKeyType> capacities = new Reference2LongArrayMap<>();
    private final AEKey[] keys;
    private final long[] amounts;
    private final long[] reservedExtract;
    private final long[] reservedInsert;
    private final AEKey2LongMap totals = new AEKey2LongMap.LinkdOpenHashMap();
    private final AEKey2LongMap extractHolds;
    // Includes pending output and the capacity retained to refund applied input.
    private final AEKey2LongMap insertHolds;
    private final BitSet freeSlots;
    private final BitSet changedSlots;
    private int activeReservations;
    private int externalReservations;
    private boolean destroyed;
    private volatile @Nullable Consumer<List<GenericStack>> destructionDrops;

    public SlottedResourceBuffer(int slots, Reference2LongMap<AEKeyType> capacities, Runnable onChanged) {
        if (slots <= 0 || capacities.isEmpty()) {
            throw new IllegalArgumentException("A buffer needs slots and supported resource types");
        }
        for (var entry : capacities.reference2LongEntrySet()) {
            AEKeyType type = Objects.requireNonNull(entry.getKey(), "type");
            long capacity = entry.getLongValue();
            if (capacity <= 0) {
                throw new IllegalArgumentException("Slot capacities must be positive");
            }
            this.capacities.put(type, capacity);
        }
        // Every positive hold key occupies a physical slot. Fixed expected capacity prevents
        // a no-fence pending install from rehashing after its new entry has already been written.
        extractHolds = new AEKey2LongMap.OpenHashMap(slots);
        insertHolds = new AEKey2LongMap.OpenHashMap(slots);
        Objects.requireNonNull(onChanged);
        context = new StorageContext(() -> {
            try {
                onChanged.run();
            } finally {
                publishDestructionDrops();
            }
        });
        keys = new AEKey[slots];
        amounts = new long[slots];
        reservedExtract = new long[slots];
        reservedInsert = new long[slots];
        changedSlots = new BitSet(slots);
        freeSlots = new BitSet(slots);
        freeSlots.set(0, slots);
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

    /**
     * Applies a delta only after its full balance/slot preflight. AE2 maps retain zero entries.
     */
    private static void addAmount(AEKey2LongMap map, AEKey key, long delta, long nextAmount) {
        if (delta != 0) {
            map.addTo(key, delta);
        }
        if (nextAmount == 0) {
            map.removeLong(key);
        }
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
            if (cachedSnapshot != null && context.current(cachedSnapshot.revision())) return cachedSnapshot;
            var available = ImmutableResourceAmounts.builder();
            if (context.isValid()) {
                extractHolds.forEachEntry((key, held) -> {
                    if (totals.getLong(key) < held) {
                        throw new IllegalStateException("Extraction holds exceed stored resources");
                    }
                });
                totals.forEachEntry((key, total) -> {
                    long amount = total - extractHolds.getLong(key);
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
            Objects.requireNonNull(key);
            if (key.equals(cachedSnapshotKey) && cachedKeySnapshot != null && context.current(cachedKeySnapshot.revision())) return cachedKeySnapshot;
            long amount = context.isValid() ? totals.getLong(key) - extractHolds.getLong(key) : 0;
            if (amount < 0) {
                throw new IllegalStateException("Extraction holds exceed stored resources");
            }
            cachedSnapshotKey = key;
            cachedKeySnapshot = new StorageSnapshot(context, context.revision(), ImmutableResourceAmounts.single(key, amount));
            return cachedKeySnapshot;
        } finally {
            context.unlock();
        }
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The owner capture must retain this source's lock and revision.
    public QuantityCapture beginQuantityCapture() {
        return new AvailableCapture();
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
            // This final native buffer has no extraction filter/rate beyond key identity and
            // per-slot holds. Their aggregate is exact: simulation need not walk sparse slots.
            // Writes and slot-specific requests retain the original per-slot preflight.
            if (!insert && onlySlot < 0 && mode == Actionable.SIMULATE) {
                if (limit < 0) throw new IllegalStateException("Extraction holds exceed stored resources");
                return limit;
            }
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

    public @Nullable NativeTransferReservation reserveTransfer(AEKey key, long amount, boolean insert) {
        return reserveTransfer(-1, key, amount, insert);
    }

    /** A nonnegative slot limits the offer to one slot; -1 retains the aggregate native allocation. */
    @SuppressWarnings("ReturnOfInnerClass") // The public receipt must retain its exact private native token.
    public @Nullable NativeTransferReservation reserveTransfer(int slot, AEKey key, long amount, boolean insert) {
        context.requireOrdinaryLock();
        if (externalReservations == Integer.MAX_VALUE)
            throw new IllegalStateException("Too many active external resource transfers");
        if (slot < -1) throw new IllegalArgumentException("Invalid external transfer slot");
        if (slot >= 0) checkSlot(slot);
        var result = reserve(null, key, amount, insert, slot);
        var token = (Reservation) result.reservation();
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

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ReservationCapture beginReserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
        return new ReserveCapture(snapshot, key, amount, false);
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // Returns the retained cursor bound to its exact original owner.
    public ReservationCapture beginReserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
        return new ReserveCapture(snapshot, key, amount, true);
    }

    private ReservationResult reserve(StorageSnapshot snapshot, AEKey key, long amount, boolean insert) {
        Objects.requireNonNull(snapshot);
        return reserve(snapshot, key, amount, insert, -1);
    }

    private ReservationResult reserve(@Nullable StorageSnapshot snapshot, AEKey key, long amount, boolean insert, int onlySlot) {
        Objects.requireNonNull(key);
        if (amount <= 0) {
            throw new IllegalArgumentException("A reservation needs a positive amount");
        }
        context.lock();
        try {
            if (!context.isValid()) {
                return rejected(ReservationResult.Status.INVALID_STORAGE);
            }
            if (snapshot != null && (snapshot.context() != context || !context.current(snapshot.revision()))) {
                return rejected(ReservationResult.Status.STALE_SNAPSHOT);
            }
            if (insert && amount > insertionHeadroom(key)) {
                return rejected(ReservationResult.Status.INSUFFICIENT);
            }
            Allocation allocation = onlySlot < 0 ? allocate(key, amount, insert)
                : new Allocation(new int[]{onlySlot}, new long[]{amount}, slotTransferable(onlySlot, key, amount, insert));
            if (allocation.total != amount) {
                return rejected(ReservationResult.Status.INSUFFICIENT);
            }
            if (activeReservations == Integer.MAX_VALUE) {
                throw new IllegalStateException("Too many active storage reservations");
            }
            context.requireChange(3);
            long nextInsertHold = insert ? Math.addExact(insertHolds.getLong(key), amount) : 0;
            long nextExtractHold = insert ? 0 : Math.addExact(extractHolds.getLong(key), amount);
            long[] holds = insert ? reservedInsert : reservedExtract;
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                if (allocation.amounts[part] > Long.MAX_VALUE - holds[slot]) {
                    throw new IllegalStateException("Reserved slot amount exceeds long capacity");
                }
            }
            var token = new Reservation(key, allocation, insert);
            var acceptedResult = new ReservationResult(ReservationResult.Status.ACCEPTED, token);
            var heldTotals = insert ? insertHolds : extractHolds;
            if (!heldTotals.containsKey(key) && heldTotals.size() >= keys.length) {
                throw new IllegalStateException("Native hold map exceeds its physical slot key bound");
            }
            // This positive addTo cannot rehash. Its key callbacks happen before the first write.
            // Everything afterwards is preflighted and runs under this same continuous physical lock.
            addAmount(heldTotals, key, amount, insert ? nextInsertHold : nextExtractHold);
            context.reserveChanges(3);
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                keys[slot] = key;
                freeSlots.clear(slot);
                holds[slot] += allocation.amounts[part];
                changedSlots.set(slot);
            }
            activeReservations++;
            context.changed();
            return acceptedResult;
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
            int[] result = new int[changedSlots.cardinality()];
            int cursor = 0;
            for (int slot = changedSlots.nextSetBit(0); slot >= 0; slot = changedSlots.nextSetBit(slot + 1))
                result[cursor++] = slot;
            changedSlots.clear();
            return result;
        } finally {
            context.unlock();
        }
    }

    /**
     * Loads committed slots after validating the entire saved balance; no NBT belongs here.
     */
    public void restore(List<GenericStack> stacks) {
        context.lock();
        try {
            restorePrepared(prepareRestore(stacks));
        } finally {
            context.unlock();
        }
    }

    /**
     * Both sides of a mixed hatch validate first while their context locks remain held.
     */
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

    boolean idleForReplacement() {
        context.requireLock();
        return activeReservations == 0 && context.idleForReplacement();
    }

    List<GenericStack> committedContents() {
        context.requireLock();
        var result = new ObjectArrayList<GenericStack>(keys.length);
        for (int slot = 0; slot < keys.length; slot++)
            result.add(keys[slot] == null ? null : new GenericStack(keys[slot], amounts[slot]));
        return result;
    }

    /** Permanent removal retains exact token journals until their final completion or compensation. */
    List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferredDrops) {
        context.requireLock();
        if (destroyed) return List.of();
        context.invalidate();
        destroyed = true;
        destructionDrops = Objects.requireNonNull(deferredDrops);
        if (activeReservations != 0) return List.of();
        destructionDrops = null;
        return takeDestroyedContents();
    }

    private void publishDestructionDrops() {
        if (destructionDrops == null) return;
        Consumer<List<GenericStack>> receiver;
        List<GenericStack> drops;
        context.lock();
        try {
            receiver = destructionDrops;
            if (receiver == null || activeReservations != 0) return;
            drops = takeDestroyedContents();
            destructionDrops = null;
        } finally {
            context.unlock();
        }
        if (!drops.isEmpty()) receiver.accept(drops);
    }

    private List<GenericStack> takeDestroyedContents() {
        context.requireLock();
        if (!destroyed || context.isValid() || activeReservations != 0)
            throw new IllegalStateException("Destruction requires retired storage and settled reservations");
        var drops = new ObjectArrayList<GenericStack>(totals.size());
        for (var entry : totals.object2LongEntrySet())
            if (entry.getLongValue() > 0) drops.add(new GenericStack(entry.getKey(), entry.getLongValue()));
        // The context is permanently invalid and no token can still address a slot. No new revision
        // credit is spent here: invalidation already revoked every old snapshot and capability.
        totals.clear();
        Arrays.fill(keys, null);
        Arrays.fill(amounts, 0);
        freeSlots.set(0, keys.length);
        changedSlots.set(0, keys.length);
        return List.copyOf(drops);
    }

    private void requireRestorable() {
        if (!context.isValid()) {
            throw new IllegalStateException("Cannot restore invalid storage");
        }
        if (activeReservations != 0) {
            throw new IllegalStateException("Cannot restore storage with active reservations");
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

    /**
     * Normal transfers preflight and apply the same stable traversal without temporary allocations.
     */
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
        context.requireChange();
    }

    private enum State {PENDING, APPLIED, EXPOSED, COMMITTED, ROLLED_BACK}

    private enum WorkKind {APPLY, EXPOSE, COMPLETE, ROLLBACK}

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

    private record Allocation(int[] slots, long[] amounts, long total) {
    }

    /**
     * Copies each entry once; revision changes discard partial work before the live iterator resumes.
     */
    private final class AvailableCapture implements QuantityCapture {
        private long revision = -1;
        private ImmutableResourceAmounts.Builder quantitiesBuilder = ImmutableResourceAmounts.builder();
        @Nullable
        private ImmutableResourceAmounts quantities;
        private Iterator<Object2LongMap.Entry<AEKey>> entries;
        private boolean complete;

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            context.lock();
            try {
                if (complete) {
                    return true;
                }
                if (context.isValid() && !context.current(context.revision())) {
                    return false;
                }
                if (revision != context.revision()) {
                    revision = context.revision();
                    quantitiesBuilder = ImmutableResourceAmounts.builder();
                    quantities = null;
                    entries = totals.object2LongEntrySet().iterator();
                }
                if (context.isValid()) {
                    while (entries.hasNext()) {
                        if (!budget.take()) {
                            return false;
                        }
                        var entry = entries.next();
                        long amount = entry.getLongValue() - extractHolds.getLong(entry.getKey());
                        if (amount < 0) {
                            throw new IllegalStateException("Extraction holds exceed stored resources");
                        }
                        if (amount > 0) {
                            quantitiesBuilder.addTo(entry.getKey(), amount);
                        }
                    }
                }
                quantities = quantitiesBuilder.build();
                complete = true;
                return true;
            } finally {
                context.unlock();
            }
        }

        @Override
        public AEKey2LongMap available() {
            if (!complete) {
                throw new IllegalStateException("Quantity capture is incomplete");
            }
            return Objects.requireNonNull(quantities);
        }

        @Override
        public boolean current() {
            context.lock();
            try {
                return complete && context.current(revision);
            } finally {
                context.unlock();
            }
        }
    }

    /**
     * Each installed part owns only its live hold delta; no private fence escapes this capture.
     */
    private final class ReserveCapture implements ReservationCapture {
        private final Thread owner = Thread.currentThread();
        private final StorageSnapshot snapshot;
        private final AEKey key;
        private final long maximum;
        private final boolean insert;
        private final IntArrayList plannedSlots = new IntArrayList();
        private final LongArrayList plannedAmounts = new LongArrayList();
        private @Nullable StorageFence borrowedFence;
        private @Nullable Reservation token;
        private @Nullable ReservationResult result;
        private int phase, slot, part;
        private long remaining, capturedAmount;
        private int[] frozenSlots;
        private long[] frozenAmounts;
        private boolean transferred, cancelling, cancelled;
        private ReservationResult.Status rejection = ReservationResult.Status.INSUFFICIENT;

        private ReserveCapture(StorageSnapshot snapshot, AEKey key, long maximum, boolean insert) {
            this.snapshot = Objects.requireNonNull(snapshot);
            this.key = Objects.requireNonNull(key);
            if (maximum <= 0) {
                throw new IllegalArgumentException("A reservation needs a positive amount");
            }
            this.maximum = maximum;
            this.insert = insert;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Reservation capture requires its creating owner");
            }
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            requireOwner();
            Objects.requireNonNull(budget);
            while (result == null) {
                if (token != null && token.ambiguous) {
                    throw new IllegalStateException("Native capture retains unknown primitive effects");
                }
                if (cancelling) {
                    return false;
                } // Only the original cleanup source continues cancellation.
                if (!budget.take()) {
                    return false;
                }
                context.lock();
                try {
                    advancePart();
                } finally {
                    context.unlock();
                }
            }
            return true;
        }

        private void reject(ReservationResult.Status status) {
            rejection = status;
            cancelling = true;
            result = rejected(status);
            capturedAmount = 0;
            // Forward refusal is complete. Its installed prefix remains in this exact inventory
            // until the original ACTIVE source actually cancels it.
            if (token == null || !token.initialized) {
                cancelled = true;
            }
        }

        private void advancePart() {
            if (!context.isValid()) {
                reject(ReservationResult.Status.INVALID_STORAGE);
                return;
            }
            if (snapshot.context() != context || snapshot.revision() != context.revision()
                || phase != 0 && context.fenceAccess() != borrowedFence
                || (token == null || !token.initialized) && !context.current(snapshot.revision())) {
                reject(ReservationResult.Status.STALE_SNAPSHOT);
                return;
            }
            if (phase == 0) {
                borrowedFence = context.fenceAccess();
                remaining = Math.min(maximum, insert ? insertionHeadroom(key) : totals.getLong(key) - extractHolds.getLong(key));
                if (remaining <= 0 || !capacities.containsKey(key.getType())) {
                    reject(ReservationResult.Status.INSUFFICIENT);
                    return;
                }
                phase = 1;
                return;
            }
            if (phase == 1 || phase == 2) {
                if (slot == keys.length || remaining == 0) {
                    if (phase == 1 && insert && remaining > 0) {
                        phase = 2;
                        slot = 0;
                        return;
                    }
                    capturedAmount = 0;
                    // Freeze and sum each planned portion in separately budgeted steps.
                    forFreeze();
                    return;
                }
                int currentSlot = slot++;
                if (phase == 1 ? key.equals(keys[currentSlot]) : keys[currentSlot] == null) {
                    long portion = slotTransferable(currentSlot, key, remaining, insert);
                    if (portion > 0) {
                        plannedSlots.add(currentSlot);
                        plannedAmounts.add(portion);
                        remaining -= portion;
                    }
                }
                return;
            }
            if (phase == 3) {
                if (part == frozenSlots.length) {
                    if (capturedAmount == 0) {
                        reject(ReservationResult.Status.INSUFFICIENT);
                        return;
                    }
                    token = new Reservation(key, new Allocation(frozenSlots, frozenAmounts, capturedAmount), insert, false);
                    if (activeReservations == Integer.MAX_VALUE) {
                        throw new IllegalStateException("Too many active storage reservations");
                    }
                    context.requireChange(3);
                    // Overflow is a preflight proof; the actual mutation happens after all checks.
                    long checkedHold = Math.addExact((insert ? insertHolds : extractHolds).getLong(key), capturedAmount);
                    if (checkedHold < 0) throw new IllegalStateException("Negative native hold total");
                    phase = 4;
                    part = 0;
                    return;
                }
                frozenSlots[part] = plannedSlots.getInt(part);
                frozenAmounts[part] = plannedAmounts.getLong(part);
                capturedAmount = Math.addExact(capturedAmount, frozenAmounts[part]);
                part++;
                return;
            }
            var currentToken = Objects.requireNonNull(token);
            if (phase == 4) {
                if (part == frozenSlots.length) {
                    phase = 5;
                    part = 0;
                    return;
                }
                validateInstall(part++);
                return;
            }
            if (phase == 5) {
                if (part == frozenSlots.length) {
                    phase = 6;
                    return;
                }
                if (!validateInstall(part)) {
                    return;
                }
                if (!currentToken.initialized) {
                    context.requireChange(3);
                    if (activeReservations == Integer.MAX_VALUE) {
                        throw new IllegalStateException("Too many active storage reservations");
                    }
                    context.beginDeferredMutation();
                    currentToken.journal = true;
                    context.reserveDeferredChanges(3);
                    currentToken.revisionChanges = 4;
                    activeReservations++;
                    currentToken.initialized = true;
                }
                int currentSlot = frozenSlots[part];
                long portion = frozenAmounts[part];
                var holds = insert ? reservedInsert : reservedExtract;
                var heldTotals = insert ? insertHolds : extractHolds;
                if (!heldTotals.containsKey(key) && heldTotals.size() >= keys.length) {
                    throw new IllegalStateException("Native hold map exceeds its physical slot key bound");
                }
                long held = heldTotals.getLong(key);
                if (held < 0) {
                    throw new IllegalStateException("Native aggregate hold is negative");
                }
                long nextHeld = Math.addExact(held, portion);
                // Constructor minN/maxFill and the key bound exclude rehash. All user hash/equals
                // calls occur before addTo's first write; after it only fixed preallocated writes remain.
                addAmount(heldTotals, key, portion, nextHeld);
                holds[currentSlot] += portion;
                keys[currentSlot] = key;
                freeSlots.clear(currentSlot);
                changedSlots.set(currentSlot);
                currentToken.reservedParts++;
                part++;
                currentToken.ambiguous = false;
                return;
            }
            if (phase == 6) {
                var acceptedResult = new ReservationResult(ReservationResult.Status.ACCEPTED, currentToken);
                currentToken.ambiguous = true;
                context.reservedChanged(currentToken.revisionChanges, false);
                currentToken.revisionChanges--;
                currentToken.endJournal();
                result = acceptedResult;
                phase = 8;
                currentToken.ambiguous = false;
            }
        }

        private void forFreeze() {
            frozenSlots = new int[plannedSlots.size()];
            frozenAmounts = new long[plannedSlots.size()];
            phase = 3;
            part = 0;
        }

        private boolean validateInstall(int index) {
            int currentSlot = frozenSlots[index];
            long portion = frozenAmounts[index];
            if (slotTransferable(currentSlot, key, portion, insert) != portion) {
                reject(ReservationResult.Status.STALE_SNAPSHOT);
                return false;
            }
            if (portion > Long.MAX_VALUE - (insert ? reservedInsert : reservedExtract)[currentSlot]) {
                throw new IllegalStateException("Reserved slot amount exceeds long capacity");
            }
            return true;
        }

        @Override
        public ReservationResult result() {
            requireOwner();
            if (result == null) {
                throw new IllegalStateException("Reservation capture is incomplete");
            }
            transferred = result.status() == ReservationResult.Status.ACCEPTED;
            return result;
        }

        @Override
        public long amount() {
            requireOwner();
            if (result == null) {
                throw new IllegalStateException("Reservation capture is incomplete");
            }
            return result.status() == ReservationResult.Status.ACCEPTED ? capturedAmount : 0;
        }

        @Override
        public boolean hasObligations() {
            requireOwner();
            return !transferred && token != null && token.initialized && token.state != State.ROLLED_BACK;
        }

        @Override
        public ResourceReservation.Resolution resolution() {
            context.requireLock();
            if (token != null && token.ambiguous) {
                return ResourceReservation.Resolution.UNKNOWN;
            }
            if (cancelled || token != null && token.state == State.ROLLED_BACK) {
                return ResourceReservation.Resolution.ROLLED_BACK;
            }
            // Full accepted token proof belongs to the published token, not to a partial capture.
            return ResourceReservation.Resolution.REVERSIBLE;
        }

        @Override
        public @Nullable ResourceReservation.Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            context.requireLock();
            return budget.take() ? resolution() : null;
        }

        @Override
        public boolean cancel(RecipeWorkBudget budget) {
            context.requireLock();
            Objects.requireNonNull(budget);
            if (transferred) {
                return true;
            }
            cancelling = true;
            if (token != null && token.initialized && token.state != State.ROLLED_BACK) {
                if (!token.rollbackWithinBudget(budget)) {
                    return false;
                }
            }
            result = rejected(rejection);
            capturedAmount = 0;
            phase = 8;
            cancelled = true;
            return true;
        }

        @Override
        public boolean cancel() {
            context.requireLock();
            if (transferred) return true;
            cancelling = true;
            if (token != null && token.initialized && token.state != State.ROLLED_BACK) token.rollback();
            result = rejected(rejection);
            capturedAmount = 0;
            phase = 8;
            cancelled = true;
            return true;
        }
    }

    /**
     * One exact allocation journal; legacy calls synchronously drain this same native cursor.
     */
    private final class Reservation implements ResourceReservation {
        private final AEKey key;
        private final Allocation allocation;
        private final boolean insert;
        private State state = State.PENDING;
        private byte revisionChanges = 3;
        private int reservedParts, appliedParts, exposedParts;
        private long appliedAmount, exposedAmount;
        private boolean initialized, journal, ambiguous;
        private @Nullable StorageFence exposedFence;
        private @Nullable StorageFence mutationFence;
        private boolean mutationRetained;
        private @Nullable Work work;

        private Reservation(AEKey key, Allocation allocation, boolean insert) {
            this(key, allocation, insert, true);
        }

        private Reservation(AEKey key, Allocation allocation, boolean insert, boolean initialized) {
            this.key = key;
            this.allocation = allocation;
            this.insert = insert;
            this.initialized = initialized;
            reservedParts = initialized ? allocation.slots.length : 0;
        }

        @Override
        public StorageContext context() {
            return context;
        }

        @Override
        public Resolution resolution() {
            context.requireLock();
            if (ambiguous) {
                return Resolution.UNKNOWN;
            }
            if (state == State.COMMITTED) {
                return Resolution.COMPLETED;
            }
            if (state == State.ROLLED_BACK) {
                return Resolution.ROLLED_BACK;
            }
            if (exposedFence != null) {
                requireExposure();
            }
            if (journal) {
                return Resolution.REVERSIBLE;
            }
            return state == State.PENDING ? Resolution.RESERVED : Resolution.APPLIED;
        }

        @Override
        public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            context.requireLock();
            return budget.take() ? resolution() : null;
        }

        @Override
        public boolean supportsSequentialUse() {
            return true;
        }

        @Override
        public boolean canApply() {
            context.requireLock();
            requireState(State.PENDING);
            if (ambiguous || !initialized || reservedParts != allocation.slots.length) {
                throw new IllegalStateException("Reservation has incomplete native obligations");
            }
            return context.isValid();
        }

        @Override
        public @Nullable Boolean canApplyWithinBudget(RecipeWorkBudget budget) {
            context.requireLock();
            return budget.take() ? canApply() : null;
        }

        @Override
        public void apply() {
            drain(WorkKind.APPLY);
        }

        @Override
        public void exposeApplied() {
            drain(WorkKind.EXPOSE);
        }

        @Override
        public void complete() {
            drain(WorkKind.COMPLETE);
        }

        private void completeTransfer(long actual) {
            context.requireOrdinaryLock();
            requireState(insert ? State.PENDING : State.APPLIED);
            if (actual < 0 || actual > allocation.total)
                throw new IllegalArgumentException("External resource result is outside its reservation");
            if (ambiguous || journal || work != null || mutationFence != null || exposedFence != null
                || !initialized || reservedParts != allocation.slots.length || activeReservations <= 0 || externalReservations <= 0)
                throw new IllegalStateException("External transfer lost its synchronous native reservation");
            context.requireReservedChange(revisionChanges);
            long addition = insert ? actual : allocation.total - actual;
            long nextTotal = checkedNonnegative(Math.addExact(totals.getLong(key), addition));
            long nextInsert = checkedNonnegative(Math.subtractExact(insertHolds.getLong(key), allocation.total));
            long remaining = actual;
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                long portion = allocation.amounts[part];
                long accepted = Math.min(remaining, portion);
                requireHold(slot, reservedInsert, portion);
                checkedSlotChange(slot, key, insert ? accepted : portion - accepted, true);
                remaining -= accepted;
            }
            // Held slot and aggregate capacity make every addition valid even after reentrant access.
            ambiguous = true;
            context.beginDeferredMutation();
            journal = true;
            addAmount(totals, key, addition, nextTotal);
            addAmount(insertHolds, key, -allocation.total, nextInsert);
            remaining = actual;
            for (int part = 0; part < allocation.slots.length; part++) {
                int slot = allocation.slots[part];
                long portion = allocation.amounts[part];
                long accepted = Math.min(remaining, portion);
                amounts[slot] += insert ? accepted : portion - accepted;
                reservedInsert[slot] -= portion;
                cleanSlot(slot);
                changedSlots.set(slot);
                remaining -= accepted;
            }
            context.reservedChanged(revisionChanges, true);
            revisionChanges = 0;
            reservedParts = appliedParts = 0;
            appliedAmount = 0;
            activeReservations--;
            externalReservations--;
            state = State.COMMITTED;
            endJournal();
            ambiguous = false;
        }

        @Override
        public void rollback() {
            drain(WorkKind.ROLLBACK);
        }

        @Override
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            return advance(WorkKind.APPLY, budget, false);
        }

        @Override
        public boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
            return advance(WorkKind.EXPOSE, budget, false);
        }

        @Override
        public boolean completeWithinBudget(RecipeWorkBudget budget) {
            return advance(WorkKind.COMPLETE, budget, false);
        }

        @Override
        public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
            return advance(WorkKind.ROLLBACK, budget, false);
        }

        private void drain(WorkKind kind) {
            try {
                if (!advance(kind, new RecipeWorkBudget(Long.MAX_VALUE), true)) {
                    throw new IllegalStateException("Native reservation cannot settle ambiguous effects");
                }
            } catch (RuntimeException | Error failure) {
                if (ambiguous && mutationFence == null) {
                    // A raw legacy caller has no retained descriptor. Retire this exact context so
                    // later planning/consumption/restore cannot treat an unknown map as healthy.
                    try {
                        context.invalidate();
                    } catch (RuntimeException | Error retirement) {
                        if (retirement != failure) {
                            failure.addSuppressed(retirement);
                        }
                    }
                }
                throw failure;
            }
        }

        private boolean advance(WorkKind kind, RecipeWorkBudget budget, boolean synchronous) {
            context.requireLock();
            Objects.requireNonNull(budget);
            // Pending reserve/cancel owns coherent live holds. Primary effects need original isolation
            // across every yielded physical scope; a version barrier never grants that isolation.
            if (mutationFence != null || !synchronous && (kind == WorkKind.APPLY || kind == WorkKind.EXPOSE
                || kind == WorkKind.COMPLETE || reservedParts != 0 || appliedParts != 0 || state == State.APPLIED || state == State.EXPOSED)) {
                var currentFence = context.requireFenceAccess();
                if (mutationFence == null) {
                    mutationFence = currentFence;
                } else if (mutationFence != currentFence) {
                    throw new IllegalStateException("Native journal belongs to another fence");
                }
            }
            if (ambiguous) {
                throw new IllegalStateException("Native reservation retains unknown primitive effects");
            }
            if (work != null && work.kind != kind) {
                if (kind != WorkKind.ROLLBACK || work.kind == WorkKind.COMPLETE) {
                    throw new IllegalStateException("Native reservation cannot change its decided operation");
                }
                work = null; // The exact applied/exposed/reserved prefixes remain the rollback journal.
            }
            if (work == null) {
                if (kind == WorkKind.APPLY) {
                    requireState(State.PENDING);
                } else if (kind == WorkKind.EXPOSE) {
                    requireState(State.APPLIED);
                    context.requireFenceAccess();
                } else if (kind == WorkKind.COMPLETE) {
                    if (state != State.APPLIED && state != State.EXPOSED) {
                        throw new IllegalStateException("Only applied storage reservations can complete");
                    }
                } else if (state != State.PENDING && state != State.APPLIED && state != State.EXPOSED) {
                    throw new IllegalStateException("Cannot roll back terminal storage reservation");
                }
                work = new Work(kind);
            }
            var currentWork = work;
            while (work != null) {
                if (!budget.take()) {
                    return false;
                }
                if (currentWork.phase == 0) {
                    if (!initialized) {
                        throw new IllegalStateException("Native reservation has not retained its revision credits");
                    }
                    context.requireReservedChange(revisionChanges);
                    if (currentWork.kind == WorkKind.APPLY) {
                        if (!context.isValid()) {
                            throw new IllegalStateException("Cannot apply an invalid storage reservation");
                        }
                        if (reservedParts != allocation.slots.length) {
                            throw new IllegalStateException("Reservation allocation is incomplete");
                        }
                        long delta = insert ? allocation.total : -allocation.total;
                        checkedNonnegative(Math.addExact(totals.getLong(key), delta));
                        checkedNonnegative(Math.subtractExact(insertHolds.getLong(key), delta));
                        checkedNonnegative(Math.addExact(extractHolds.getLong(key), delta));
                    }
                    if (currentWork.kind == WorkKind.EXPOSE) {
                        context.requireFenceAccess();
                    }
                    if (exposedFence != null) {
                        requireExposure();
                    }
                    if (activeReservations <= 0) {
                        throw new IllegalStateException("Reservation count is inconsistent");
                    }
                    if (currentWork.kind == WorkKind.ROLLBACK) {
                        validateRollbackTotals();
                    }
                    currentWork.phase = 1;
                    continue;
                }
                if (currentWork.phase == 1) {
                    if (currentWork.index == currentWork.limit) {
                        currentWork.phase = 2;
                        currentWork.index = 0;
                        continue;
                    }
                    validatePart(currentWork, currentWork.part());
                    currentWork.index++;
                    continue;
                }
                if (currentWork.phase == 2) {
                    if (currentWork.index == currentWork.limit) {
                        currentWork.phase = 3;
                        continue;
                    }
                    int part = currentWork.part();
                    validatePart(currentWork, part);
                    if (mutationFence != null && !mutationRetained) {
                        if (context.retainSequentialReservation() != mutationFence) {
                            throw new IllegalStateException("Native journal lost its original fence");
                        }
                        mutationRetained = true;
                    }
                    if (!journal) {
                        context.beginDeferredMutation();
                        journal = true;
                    }
                    if (currentWork.kind == WorkKind.EXPOSE && exposedFence == null) {
                        exposedFence = context.retainSequentialReservation();
                    }
                    mutatePart(currentWork.kind, part);
                    currentWork.index++;
                    continue;
                }
                finishWork(currentWork.kind);
                work = null;
            }
            return true;
        }

        private void validatePart(Work currentWork, int part) {
            int slot = allocation.slots[part];
            long portion = allocation.amounts[part];
            if (currentWork.kind == WorkKind.APPLY) {
                if (!context.isValid()) {
                    throw new IllegalStateException("Cannot apply an invalid storage reservation");
                }
                var previousHolds = insert ? reservedInsert : reservedExtract;
                var nextHolds = insert ? reservedExtract : reservedInsert;
                requireHold(slot, previousHolds, portion);
                if (portion > Long.MAX_VALUE - nextHolds[slot]) {
                    throw new IllegalStateException("Compensation slot amount exceeds long capacity");
                }
                checkedSlotChange(slot, key, portion, insert);
                return;
            }
            boolean applied = part < appliedParts;
            boolean exposed = part < exposedParts;
            if (currentWork.kind == WorkKind.EXPOSE) {
                requireHold(slot, insert ? reservedExtract : reservedInsert, portion);
                return;
            }
            if (exposed) {
                requireExposure();
            } else {
                boolean holdsInsert = applied != insert;
                requireHold(slot, holdsInsert ? reservedInsert : reservedExtract, portion);
            }
            if (currentWork.kind == WorkKind.ROLLBACK && applied) {
                // Recheck the whole remaining journal after every yielded physical scope.
                validateRollbackTotals();
                if (exposed && slotTransferable(slot, key, portion, !insert) != portion) {
                    throw new IllegalStateException("Sequential rollback requires later dependent reservations to resolve first");
                }
                checkedSlotChange(slot, key, portion, !insert);
            }
        }

        private void validateRollbackTotals() {
            long nextTotal = Math.addExact(totals.getLong(key), insert ? -appliedAmount : appliedAmount);
            if (nextTotal < 0 || !insert && exposedAmount > insertionHeadroom(key)) {
                throw new IllegalStateException("Sequential rollback lost aggregate compensation capacity");
            }
        }

        private void requireHold(int slot, long[] holds, long portion) {
            if (!key.equals(keys[slot]) || holds[slot] < portion) {
                throw new IllegalStateException("Reservation lost its exact slot hold");
            }
        }

        private void mutatePart(WorkKind kind, int part) {
            int slot = allocation.slots[part];
            long portion = allocation.amounts[part];
            long totalDelta = 0, insertDelta = 0, extractDelta = 0;
            boolean applied = part < appliedParts, exposed = part < exposedParts;
            if (kind == WorkKind.APPLY) {
                totalDelta = insert ? portion : -portion;
                insertDelta = -totalDelta;
                extractDelta = totalDelta;
            } else if (kind == WorkKind.EXPOSE) {
                if (insert) {
                    extractDelta = -portion;
                } else {
                    insertDelta = -portion;
                }
            } else {
                if (kind == WorkKind.ROLLBACK && applied) {
                    totalDelta = insert ? -portion : portion;
                }
                if (!exposed) {
                    if (applied != insert) {
                        insertDelta = -portion;
                    } else {
                        extractDelta = -portion;
                    }
                }
            }
            long nextTotal = checkedNonnegative(Math.addExact(totals.getLong(key), totalDelta));
            long nextInsert = checkedNonnegative(Math.addExact(insertHolds.getLong(key), insertDelta));
            long nextExtract = checkedNonnegative(Math.addExact(extractHolds.getLong(key), extractDelta));
            long nextAmount = totalDelta == 0 ? amounts[slot] : checkedSlotChange(slot, key, portion, totalDelta > 0);
            ambiguous = true; // Any primitive failure after this boundary keeps the original obligation UNKNOWN.
            addAmount(totals, key, totalDelta, nextTotal);
            addAmount(insertHolds, key, insertDelta, nextInsert);
            addAmount(extractHolds, key, extractDelta, nextExtract);
            if (kind == WorkKind.APPLY) {
                (insert ? reservedInsert : reservedExtract)[slot] -= portion;
                (insert ? reservedExtract : reservedInsert)[slot] += portion;
                appliedParts++;
                appliedAmount += portion; // Exact allocation prefixes are bounded by allocation.total.
            } else if (kind == WorkKind.EXPOSE) {
                (insert ? reservedExtract : reservedInsert)[slot] -= portion;
                exposedParts++;
                exposedAmount += portion;
            } else {
                if (!exposed) {
                    (applied != insert ? reservedInsert : reservedExtract)[slot] -= portion;
                }
                reservedParts--;
                if (applied) {
                    appliedParts--;
                }
                if (exposed) {
                    exposedParts--;
                }
                if (applied) {
                    appliedAmount -= portion;
                }
                if (exposed) {
                    exposedAmount -= portion;
                }
            }
            amounts[slot] = nextAmount;
            if (nextAmount > 0) {
                keys[slot] = key;
                freeSlots.clear(slot);
            }
            cleanSlot(slot);
            changedSlots.set(slot);
            ambiguous = false;
        }

        private long checkedNonnegative(long value) {
            if (value < 0) {
                throw new IllegalStateException("Reservation aggregate hold or balance is negative");
            }
            return value;
        }

        private void finishWork(WorkKind kind) {
            context.requireReservedChange(revisionChanges);
            boolean terminal = kind == WorkKind.COMPLETE || kind == WorkKind.ROLLBACK;
            if (terminal && activeReservations <= 0) {
                throw new IllegalStateException("Reservation count is inconsistent");
            }
            if (exposedFence != null) {
                requireExposure();
            }
            // Final revision publication precedes removing the pending-proof barrier.
            ambiguous = true;
            context.reservedChanged(revisionChanges, terminal);
            revisionChanges = terminal ? 0 : (byte) (revisionChanges - 1);
            state = switch (kind) {
                case APPLY -> State.APPLIED;
                case EXPOSE -> State.EXPOSED;
                case COMPLETE -> State.COMMITTED;
                case ROLLBACK -> State.ROLLED_BACK;
            };
            if (terminal) {
                activeReservations--;
                if (exposedFence != null) {
                    context.releaseSequentialReservation(exposedFence);
                    exposedFence = null;
                }
                if (mutationRetained) {
                    context.releaseSequentialReservation(Objects.requireNonNull(mutationFence));
                    mutationRetained = false;
                }
                mutationFence = null;
            }
            endJournal();
            ambiguous = false;
        }

        private void endJournal() {
            if (journal) {
                context.endDeferredMutation();
                journal = false;
            }
        }

        private void requireExposure() {
            var fence = Objects.requireNonNull(exposedFence, "Reservation lost its sequential fence");
            if (context.requireFenceAccess() != fence) {
                throw new IllegalStateException("Sequential reservation belongs to another fence");
            }
        }

        private void requireState(State expected) {
            if (state != expected) {
                throw new IllegalStateException("Reservation is " + state + ", expected " + expected);
            }
        }

        private final class Work {
            private final WorkKind kind;
            private final int limit;
            private int phase, index;

            private Work(WorkKind kind) {
                this.kind = kind;
                limit = reservedParts;
            }

            private int part() {
                return kind == WorkKind.COMPLETE || kind == WorkKind.ROLLBACK ? limit - index - 1 : index;
            }
        }
    }
}
