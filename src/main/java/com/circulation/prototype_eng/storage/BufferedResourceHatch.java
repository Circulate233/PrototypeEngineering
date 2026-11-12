package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;

import it.unimi.dsi.fastutil.objects.Reference2LongMap;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.localization.GuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Owns buffers independently of their block entity or GUI.
 */
public final class BufferedResourceHatch implements SlottedResourceHatch {
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt();
    private final SlottedResourceBuffer input;
    private final SlottedResourceBuffer output;
    private final MEStorage automation = new AutomationPort();
    private final List<StorageContext> contexts;

    /**
     * Capacities are per slot and per key type, in that type's native units.
     */
    public BufferedResourceHatch(HatchMode mode, int slotsPerSide,
                                 Reference2LongMap<AEKeyType> capacities, Runnable onChanged) {
        this(ResourceHatchConfiguration.symmetric(mode, slotsPerSide, capacities), onChanged);
    }

    public BufferedResourceHatch(ResourceHatchConfiguration configuration, Runnable onChanged) {
        Objects.requireNonNull(onChanged, "onChanged");
        var in = configuration.input();
        var out = configuration.output();
        input = in == null ? null : new SlottedResourceBuffer(in.slots(), in.capacities(), onChanged);
        output = out == null ? null : new SlottedResourceBuffer(out.slots(), out.capacities(), onChanged);
        contexts = input != null && output != null ? List.of(input.context(), output.context())
            : input != null ? List.of(input.context()) : output != null ? List.of(output.context()) : List.of();
    }

    boolean hasTransferReservation() {
        return input != null && input.hasTransferReservation() || output != null && output.hasTransferReservation();
    }

    private static @Nullable List<GenericStack> pack(List<GenericStack> contents, @Nullable ResourceHatchConfiguration.Side side) {
        if (side == null) return List.of();
        var totals = new AEKeyLinkedOpenHashMap(contents.size());
        for (var stack : contents) {
            if (stack == null) continue;
            if (Long.MAX_VALUE - totals.getLong(stack.what()) < stack.amount())
                throw new IllegalArgumentException("Committed resource total exceeds long capacity");
            totals.addTo(stack.what(), stack.amount());
        }
        long needed = 0;
        for (var entry : totals.object2LongEntrySet()) {
            long capacity = side.capacities().getLong(entry.getKey().getType());
            if (capacity <= 0) return null;
            long amount = entry.getLongValue();
            long slots = amount / capacity + (amount % capacity == 0 ? 0 : 1);
            if (slots > side.slots() - needed) return null;
            needed += slots;
        }
        var remaining = new AEKeyLinkedOpenHashMap(totals.size());
        remaining.putAll(totals);
        var packed = new ObjectArrayList<GenericStack>(side.slots());
        packed.size(side.slots());
        // Expansion and compatible capacity changes preserve exact positions and same-key distribution.
        for (int slot = 0; slot < Math.min(contents.size(), packed.size()); slot++) {
            var stack = contents.get(slot);
            if (stack == null) continue;
            long kept = Math.min(stack.amount(), side.capacities().getLong(stack.what().getType()));
            packed.set(slot, new GenericStack(stack.what(), kept));
            remaining.addTo(stack.what(), -kept);
        }
        // Only overflow from clipped/out-of-range slots may use existing same-key headroom.
        for (int slot = 0; slot < packed.size(); slot++) {
            var stack = packed.get(slot);
            if (stack == null) continue;
            long extra = Math.min(remaining.getLong(stack.what()), side.capacities().getLong(stack.what().getType()) - stack.amount());
            if (extra == 0) continue;
            packed.set(slot, new GenericStack(stack.what(), stack.amount() + extra));
            remaining.addTo(stack.what(), -extra);
        }
        if (fillEmptySlots(packed, remaining, side)) return packed;
        // Fragmented same-key positions can prevent another type from obtaining a slot. Compact only
        // after the preserving plan failed; the aggregate preflight already proves complete capacity.
        packed.clear();
        packed.size(side.slots());
        if (!fillEmptySlots(packed, totals, side))
            throw new IllegalStateException("Validated hatch capacity no longer fits");
        return packed;
    }

    private static boolean fillEmptySlots(List<GenericStack> packed, AEKey2LongMap remaining, ResourceHatchConfiguration.Side side) {
        int slot = 0;
        for (var entry : remaining.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            long capacity = side.capacities().getLong(entry.getKey().getType());
            while (amount > 0) {
                while (slot < packed.size() && packed.get(slot) != null) slot++;
                if (slot == packed.size()) return false;
                long portion = Math.min(amount, capacity);
                packed.set(slot++, new GenericStack(entry.getKey(), portion));
                amount -= portion;
            }
        }
        return true;
    }

    private static void saveSide(NBTTagCompound tag, String name, @Nullable SlottedResourceStorage storage) {
        if (storage == null) {
            tag.removeTag(name);
            return;
        }
        var list = new NBTTagList();
        for (int slot = 0; slot < storage.size(); slot++) {
            var stack = storage.getStack(slot);
            if (stack == null) {
                continue;
            }
            var entry = new NBTTagCompound();
            entry.setTag("Key", KEYS.encode(stack.what()));
            entry.setLong("Quantity", stack.amount());
            entry.setInteger("Slot", slot);
            list.appendTag(entry);
        }
        tag.setTag(name, list);
    }

    private static List<GenericStack> readSide(NBTTagCompound tag, String name, @Nullable SlottedResourceStorage storage) {
        var result = new ObjectArrayList<GenericStack>(6);
        if (storage == null) {
            if (tag.hasKey(name) && StrictNbt.requireList(tag, name, 10).tagCount() != 0)
                throw new IllegalArgumentException("Saved contents on absent hatch side " + name);
            return result;
        }
        result.size(storage.size());
        if (!tag.hasKey(name)) {
            return result;
        }
        if (!tag.hasKey(name, 9)) {
            throw new IllegalArgumentException("Invalid saved hatch side " + name);
        }
        var list = (NBTTagList) tag.getTag(name);
        if (list.tagCount() > 0 && list.getTagType() != 10) {
            throw new IllegalArgumentException("Invalid saved hatch entries " + name);
        }
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            if (!entry.hasKey("Slot", 3)) {
                throw new IllegalArgumentException("Missing saved resource slot");
            }
            int slot = Objects.checkIndex(entry.getInteger("Slot"), result.size());
            GenericStack stack;
            if (entry.hasKey("Key")) {
                stack = new GenericStack(KEYS.decode(StrictNbt.requireCompound(entry, "Key")), StrictNbt.requireLong(entry, "Quantity"));
            } else {
                // Decode the former GenericStack shape strictly; AE's missing-content fallback changes identity.
                var key = new NBTTagCompound();
                key.setString("Type", StrictNbt.requireString(entry, AEKey.TYPE_FIELD));
                var data = entry.copy();
                data.removeTag(AEKey.TYPE_FIELD);
                data.removeTag("#");
                data.removeTag("Slot");
                key.setTag("Data", data);
                stack = new GenericStack(KEYS.decode(key), StrictNbt.requireLong(entry, "#"));
            }
            if (result.get(slot) != null || stack.amount() <= 0 || !storage.isAllowedIn(slot, stack.what())
                || stack.amount() > storage.getMaxAmount(stack.what())) {
                throw new IllegalArgumentException("Invalid or duplicate saved resource slot " + slot);
            }
            result.set(slot, stack);
        }
        return result;
    }

    /** Captures old ownership, prepares outside its access scope, then retires both sides together.
     * The publication factory prepares adapters before retirement; its returned action only assigns fields.
     */
    public HatchReplacement replace(ResourceHatchConfiguration target, @Nullable String location,
                                    Runnable onChanged, BooleanSupplier current,
                                    Function<BufferedResourceHatch, Runnable> preparePublication) {
        if (target.mode().hasInput() == (input == null) || target.mode().hasOutput() == (output == null))
            throw new IllegalArgumentException("Reconfiguration cannot change physical hatch direction");
        var fence = StorageFence.tryAcquire(contexts);
        if (fence == null) return HatchReplacement.of(HatchReconfigurationStatus.PENDING_BUSY);
        try {
            var captured = fence.access(() -> {
                if ((input != null && !input.idleForReplacement()) || (output != null && !output.idleForReplacement()))
                    return null;
                return new Capture(input == null ? List.of() : input.committedContents(),
                    output == null ? List.of() : output.committedContents(),
                    HatchReplacement.captureVersions(contexts));
            });
            if (captured == null) return HatchReplacement.of(HatchReconfigurationStatus.PENDING_BUSY);
            // The old fence stays held, but its access scope must not touch any new context.
            var nextInput = pack(captured.input, target.input());
            var nextOutput = pack(captured.output, target.output());
            if (nextInput == null || nextOutput == null)
                return new HatchReplacement(HatchReconfigurationStatus.BLOCKED_CAPACITY, captured.versions);
            var changes = new HatchStorageChanges(onChanged);
            var candidate = new BufferedResourceHatch(target, changes);
            candidate.restoreContents(nextInput, nextOutput);
            candidate.bindSourceIdentities(location);
            var publish = preparePublication.apply(candidate);
            return fence.access(() -> {
                if (!current.getAsBoolean() || !HatchReplacement.current(captured.versions))
                    return HatchReplacement.of(HatchReconfigurationStatus.STALE_TARGET);
                contexts.forEach(StorageContext::requireRetirement);
                contexts.forEach(StorageContext::invalidate);
                publish.run();
                changes.enable();
                return HatchReplacement.of(HatchReconfigurationStatus.APPLIED);
            });
        } finally {
            fence.release();
        }
    }

    public void bindSourceIdentities(@Nullable String location) {
        if (location == null) return;
        if (input != null) input.context().bindSourceIdentity(location + "/input");
        if (output != null) output.context().bindSourceIdentity(location + "/output");
    }

    @Override
    @Nullable
    public SlottedResourceStorage input() {
        return input;
    }

    @Override
    @Nullable
    public SlottedResourceStorage output() {
        return output;
    }

    /**
     * External automation inserts only into input and extracts only from output.
     */
    public MEStorage automation() {
        return automation;
    }

    @Override
    public boolean isValid() {
        return (input == null || input.isValid()) && (output == null || output.isValid());
    }

    /**
     * Retained machine/automation storage references also stop accepting operations.
     */
    public void invalidate() {
        locked(() -> {
            if (input != null) {
                input.context().invalidate();
            }
            if (output != null) {
                output.context().invalidate();
            }
        });
    }

    /** Eject each independent side once; unresolved tokens deliver their final balance later. */
    public List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferredDrops) {
        Objects.requireNonNull(deferredDrops);
        var drops = new ObjectArrayList<GenericStack>(6);
        locked(() -> {
            if (input != null) drops.addAll(input.removeForDrops(deferredDrops));
            if (output != null) drops.addAll(output.removeForDrops(deferredDrops));
        });
        return List.copyOf(drops);
    }

    public void save(NBTTagCompound tag) {
        locked(() -> {
            saveSide(tag, "Input", input);
            saveSide(tag, "Output", output);
        });
    }

    public void load(NBTTagCompound tag) {
        var inputContents = readSide(tag, "Input", input);
        var outputContents = readSide(tag, "Output", output);
        restoreContents(inputContents, outputContents);
    }

    /**
     * Commits decoded contents only after both independent sides pass validation.
     */
    void restoreContents(List<GenericStack> inputContents, List<GenericStack> outputContents) {
        locked(() -> {
            var nextInput = input == null ? null : input.prepareRestore(inputContents);
            var nextOutput = output == null ? null : output.prepareRestore(outputContents);
            if (input != null) {
                input.restorePrepared(Objects.requireNonNull(nextInput));
            }
            if (output != null) {
                output.restorePrepared(Objects.requireNonNull(nextOutput));
            }
        });
    }

    private void locked(Runnable action) {
        // These independent contexts are constructed in this order and never replaced.
        StorageContext.lockAll(contexts);
        try {
            action.run();
        } finally {
            if (output != null) {
                output.context().unlockDeferred();
            }
            if (input != null) {
                input.context().unlockDeferred();
            }
            if (input != null) {
                input.context().publishChanges();
            }
            if (output != null) {
                output.context().publishChanges();
            }
        }
    }

    private record Capture(List<GenericStack> input, List<GenericStack> output,
                           List<HatchReplacement.Version> versions) {
    }

    private final class AutomationPort implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return input == null ? 0 : input.insert(what, amount, mode);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return output == null ? 0 : output.extract(what, amount, mode);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (output != null) {
                for (var entry : output.snapshot().available().object2LongEntrySet()) {
                    out.add(entry.getKey(), entry.getLongValue());
                }
            }
        }

        @Override
        public ITextComponent getDescription() {
            return GuiText.ResourceStorage.text();
        }
    }
}
