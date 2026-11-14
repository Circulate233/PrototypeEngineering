package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Independent input markers and real input/output balances for the universal ME assemblies.
 */
public final class MEResourceHatch implements ResourceHatch {
    private static final ResourceKeyNbt KEYS = ResourceKeyNbt.INSTANCE;
    private final HatchMode mode;
    private final @Nullable SlottedResourceBuffer input;
    private final @Nullable UnboundedResourceBuffer output;
    private final GenericStack[] inputMarks;
    private final List<StorageContext> contexts;
    private final Supplier<AEColor> color;
    private long markRevision;

    public MEResourceHatch(HatchMode mode, int inputSlots, Runnable onChanged, Supplier<AEColor> color) {
        this(mode, inputSlots, onChanged, color, AEKeyTypes.getAll());
    }

    /**
     * Explicit type set supports external storage hosts without accessing the global resource registry.
     */
    public MEResourceHatch(HatchMode mode, int inputSlots, Runnable onChanged, Supplier<AEColor> color,
                           Iterable<AEKeyType> types) {
        this.mode = Objects.requireNonNull(mode);
        this.color = Objects.requireNonNull(color);
        if (inputSlots <= 0) throw new IllegalArgumentException("ME input slot count must be positive");
        var capacities = new Reference2LongOpenHashMap<AEKeyType>();
        for (var type : Objects.requireNonNull(types)) capacities.put(Objects.requireNonNull(type), Long.MAX_VALUE);
        if (capacities.isEmpty()) throw new IllegalStateException("ME resources require registered AE key types");
        input = mode.hasInput() ? new SlottedResourceBuffer(inputSlots, capacities, onChanged) : null;
        output = mode.hasOutput() ? new UnboundedResourceBuffer(onChanged) : null;
        inputMarks = new GenericStack[input == null ? 0 : inputSlots];
        contexts = input != null && output != null ? List.of(input.context(), output.context())
            : input != null ? List.of(input.context()) : List.of(Objects.requireNonNull(output).context());
    }

    /**
     * Reads into unpublished storage, allowing the tile to validate both sides before replacing a live instance.
     */
    public static MEResourceHatch load(NBTTagCompound tag, Runnable onChanged) {
        return load(tag, onChanged, () -> AEColor.TRANSPARENT);
    }

    public static MEResourceHatch load(NBTTagCompound tag, Runnable onChanged, Supplier<AEColor> color) {
        var mode = HatchMode.valueOf(tag.getString("Mode"));
        int savedInputSlots = tag.getInteger("InputSlots");
        if (mode.hasInput() ? savedInputSlots <= 0 : savedInputSlots != 0)
            throw new IllegalArgumentException("Saved ME input layout does not match the hatch mode");
        var next = new MEResourceHatch(mode, Math.max(1, savedInputSlots), onChanged, color);
        if (tag.hasKey("MarkRevision")) {
            long savedRevision = tag.getLong("MarkRevision");
            if (savedRevision < 0 || savedRevision == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid saved ME marker revision");
            next.markRevision = savedRevision + 1;
        }
        var marks = tag.getTagList("InputMarks", 10);
        readSlotted(marks, next.inputMarks, next.input);
        if (next.input != null) {
            var savedInput = new GenericStack[savedInputSlots];
            readSlotted(tag.getTagList("Input", 10), savedInput, next.input);
            next.input.restore(Arrays.asList(savedInput));
        } else if (tag.hasKey("Input"))
            throw new IllegalArgumentException("Saved input exists on an output-only assembly");
        if (next.output != null) next.output.restore(KEYS.readStacks(tag.getTagList("Output", 10)));
        else if (tag.hasKey("Output"))
            throw new IllegalArgumentException("Saved output exists on an input-only assembly");
        return next;
    }

    private static void readSlotted(NBTTagList list, GenericStack[] destination, @Nullable SlottedResourceBuffer input) {
        for (int index = 0; index < list.tagCount(); index++) {
            var entry = list.getCompoundTagAt(index);
            int slot = Objects.checkIndex(entry.getInteger("Slot"), destination.length);
            var key = KEYS.decode(entry.getCompoundTag("Key"));
            long amount = entry.getLong("Quantity");
            if (destination[slot] != null || amount <= 0 || input == null || !input.isSupportedType(key.getType()))
                throw new IllegalArgumentException("Invalid saved ME input slot");
            destination[slot] = new GenericStack(key, amount);
        }
    }

    @Override
    public ResourceInputGroup inputGroup() {
        return new ResourceInputGroup(color.get());
    }

    public HatchMode mode() {
        return mode;
    }

    @Override
    public @Nullable SlottedResourceBuffer input() {
        return input;
    }

    @Override
    public @Nullable UnboundedResourceBuffer output() {
        return output;
    }

    public int inputSlots() {
        return inputMarks.length;
    }

    @SuppressWarnings("unused") // Public downstream single-marker query; built-in GUI captures complete views.
    public @Nullable GenericStack inputMark(int slot) {
        var buffer = Objects.requireNonNull(input, "ME assembly has no input");
        var context = buffer.context();
        context.lock();
        try {
            return inputMarks[Objects.checkIndex(slot, inputMarks.length)];
        } finally {
            context.unlock();
        }
    }

    /**
     * Marker revision is independent of refill changes, so typing does not race an ordinary ME tick.
     */
    public boolean compareInputMark(int slot, long expectedRevision, @Nullable GenericStack next) {
        var buffer = Objects.requireNonNull(input, "ME assembly has no input");
        Objects.checkIndex(slot, inputMarks.length);
        if (next != null && (next.amount() <= 0 || !buffer.isSupportedType(next.what().getType())))
            throw new IllegalArgumentException("Invalid ME input marker");
        var context = buffer.context();
        if (!context.tryOrdinaryLock()) return false;
        try {
            if (!context.isValid() || markRevision != expectedRevision) return false;
            if (Objects.equals(inputMarks[slot], next)) return true;
            if (markRevision == Long.MAX_VALUE) throw new IllegalStateException("ME marker revision is exhausted");
            context.requireChange();
            inputMarks[slot] = next;
            markRevision++;
            context.changed();
            return true;
        } finally {
            context.unlock();
        }
    }

    public InputView inputView() {
        if (input == null) return new InputView(-1, List.of());
        var context = input.context();
        context.lock();
        try {
            var slots = new ObjectArrayList<InputSlot>(inputMarks.length);
            for (int slot = 0; slot < inputMarks.length; slot++)
                slots.add(new InputSlot(inputMarks[slot], input.getStack(slot)));
            return new InputView(markRevision, slots);
        } finally {
            context.unlock();
        }
    }

    /**
     * Slot indexes belong only to this immutable revision; output has one row per available key.
     */
    public OutputView outputView() {
        if (output == null) return new OutputView(-1, List.of());
        var snapshot = output.snapshot();
        var stacks = new ObjectArrayList<GenericStack>(snapshot.available().size());
        snapshot.available().forEachEntry((key, amount) -> stacks.add(new GenericStack(key, amount)));
        return new OutputView(snapshot.revision(), stacks);
    }

    /**
     * Runs on the real ME owner thread; native receipts cover every reentrant network transfer.
     */
    public void transfer(METransfer transfer) {
        Objects.requireNonNull(transfer);
        if (hasTransferReservation()) return;
        if (input != null) {
            var context = input.context();
            for (int slot = 0; slot < inputMarks.length; slot++) {
                if (!context.tryOrdinaryLock()) continue;
                try {
                    if (!input.isValid()) return;
                    var mark = inputMarks[slot];
                    var actual = input.getStack(slot);
                    // Changed or cleared markers return old/excess contents before requesting new material.
                    long excess = actual == null ? 0 : mark == null || !actual.what().equals(mark.what())
                        ? actual.amount() : Math.max(0, actual.amount() - mark.amount());
                    if (excess > 0) {
                        long offer = input.extract(slot, actual.what(), excess, Actionable.SIMULATE);
                        if (offer > 0) {
                            var receipt = input.reserveTransfer(slot, actual.what(), offer, false);
                            if (receipt != null) receipt.complete(transfer.insert(actual.what(), offer));
                        }
                    }
                    actual = input.getStack(slot);
                    // A reentrant ME callback may edit the marker; only the current target may be filled.
                    mark = inputMarks[slot];
                    if (mark == null || actual != null && !actual.what().equals(mark.what())) continue;
                    long missing = mark.amount() - (actual == null ? 0 : actual.amount());
                    long room = missing > 0 ? input.insert(slot, mark.what(), missing, Actionable.SIMULATE) : 0;
                    if (room > 0) {
                        var receipt = input.reserveTransfer(slot, mark.what(), room, true);
                        if (receipt != null) receipt.complete(transfer.extract(mark.what(), room));
                    }
                } finally {
                    context.unlock();
                }
            }
        }
        if (output != null) {
            var snapshot = output.snapshot();
            for (var entry : snapshot.available().object2LongEntrySet()) {
                var context = output.context();
                if (!context.tryOrdinaryLock()) continue;
                try {
                    long offer = output.extract(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE);
                    if (offer == 0) continue;
                    var receipt = output.reserveTransfer(entry.getKey(), offer, false);
                    if (receipt != null) receipt.complete(transfer.insert(entry.getKey(), offer));
                } finally {
                    context.unlock();
                }
            }
        }
    }

    @Override
    public boolean isValid() {
        return (input == null || input.isValid()) && (output == null || output.isValid());
    }

    public void invalidate() {
        lockAll();
        try {
            for (int index1 = 0; index1 < contexts.size(); index1++) {
                var context = contexts.get(index1);
                context.invalidate();
            }
        } finally {
            unlockAll();
        }
    }

    public boolean hasTransferReservation() {
        return input != null && input.hasTransferReservation() || output != null && output.hasTransferReservation();
    }

    public boolean hasReservations() {
        return input != null && input.hasReservations() || output != null && output.hasReservations();
    }

    /**
     * The final idle check and one-way retirement share both physical locks.
     */
    public boolean retireIfIdle() {
        lockAll();
        try {
            if (input != null && !input.idleForReplacement() || output != null && !output.context().idleForReplacement())
                return false;
            for (int index4 = 0; index4 < contexts.size(); index4++) {
                var context = contexts.get(index4);
                context.requireRetirement();
            }
            for (int index2 = 0; index2 < contexts.size(); index2++) {
                var context = contexts.get(index2);
                context.invalidate();
            }
            return true;
        } finally {
            unlockAll();
        }
    }

    public void save(NBTTagCompound tag) {
        lockAll();
        try {
            tag.setString("Mode", mode.name());
            tag.setInteger("InputSlots", inputMarks.length);
            tag.setLong("MarkRevision", markRevision);
            var markers = new NBTTagList();
            for (int slot = 0; slot < inputMarks.length; slot++) {
                var marker = inputMarks[slot];
                if (marker == null) continue;
                var entry = new NBTTagCompound();
                entry.setInteger("Slot", slot);
                entry.setTag("Key", KEYS.encode(marker.what()));
                entry.setLong("Quantity", marker.amount());
                markers.appendTag(entry);
            }
            tag.setTag("InputMarks", markers);
            if (input != null) {
                var slots = new NBTTagList();
                for (int slot = 0; slot < input.size(); slot++) {
                    var stack = input.getStack(slot);
                    if (stack == null) continue;
                    var entry = new NBTTagCompound();
                    entry.setInteger("Slot", slot);
                    entry.setTag("Key", KEYS.encode(stack.what()));
                    entry.setLong("Quantity", stack.amount());
                    slots.appendTag(entry);
                }
                tag.setTag("Input", slots);
            } else tag.removeTag("Input");
            if (output != null) tag.setTag("Output", KEYS.writeStacks(output.committedContents()));
            else tag.removeTag("Output");
        } finally {
            unlockAll();
        }
    }

    public List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferredDrops) {
        Objects.requireNonNull(deferredDrops);
        lockAll();
        try {
            var result = new ObjectArrayList<GenericStack>();
            if (input != null) result.addAll(input.removeForDrops(deferredDrops));
            if (output != null) result.addAll(output.removeForDrops(deferredDrops));
            return result;
        } finally {
            unlockAll();
        }
    }

    private void lockAll() {
        StorageContext.lockAll(contexts);
    }

    private void unlockAll() {
        for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlockDeferred();
        for (int index3 = 0; index3 < contexts.size(); index3++) {
            var context = contexts.get(index3);
            context.publishChanges();
        }
    }

    /**
     * Exact returned quantities only; a throwing callback leaves its native receipt unresolved.
     */
    public interface METransfer {
        long insert(AEKey key, long amount);

        long extract(AEKey key, long amount);
    }

    public record InputSlot(@Nullable GenericStack mark, @Nullable GenericStack actual) {
    }

    /**
     * The producer publishes a stable list; consumers must not modify it.
     */
    public record InputView(long revision, List<InputSlot> slots) {
    }

    /**
     * The producer publishes a stable list; consumers must not modify it.
     */
    public record OutputView(long revision, List<GenericStack> stacks) {
    }
}
