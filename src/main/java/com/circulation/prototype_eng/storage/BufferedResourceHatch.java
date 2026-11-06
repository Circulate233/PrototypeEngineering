package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.localization.GuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.List;
import java.util.Objects;

/**
 * Owns buffers independently of their block entity or GUI.
 */
public final class BufferedResourceHatch implements ResourceHatch {
    private final SlottedResourceBuffer input;
    private final SlottedResourceBuffer output;
    private final MEStorage automation = new AutomationPort();

    /**
     * Capacities are per slot and per key type, in that type's native units.
     */
    public BufferedResourceHatch(HatchMode mode, int slotsPerSide,
                                 Map<AEKeyType, Long> capacities, Runnable onChanged) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onChanged, "onChanged");
        if (slotsPerSide < 1 || capacities.isEmpty()) {
            throw new IllegalArgumentException("A buffer needs slots and supported resource types");
        }
        input = mode.hasInput() ? new SlottedResourceBuffer(slotsPerSide, capacities, onChanged) : null;
        output = mode.hasOutput() ? new SlottedResourceBuffer(slotsPerSide, capacities, onChanged) : null;
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
            if (input != null) { input.context().invalidate(); }
            if (output != null) { output.context().invalidate(); }
        });
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

    /** Commits decoded contents only after both independent sides pass validation. */
    void restoreContents(List<GenericStack> inputContents, List<GenericStack> outputContents) {
        locked(() -> {
            var nextInput = input == null ? null : input.prepareRestore(inputContents);
            var nextOutput = output == null ? null : output.prepareRestore(outputContents);
            if (input != null) { input.restorePrepared(Objects.requireNonNull(nextInput)); }
            if (output != null) { output.restorePrepared(Objects.requireNonNull(nextOutput)); }
        });
    }

    private void locked(Runnable action) {
        // These independent contexts are constructed in this order and never replaced.
        if (input != null) { input.context().lock(); }
        if (output != null) { output.context().lock(); }
        try { action.run(); }
        finally {
            if (output != null) { output.context().unlockDeferred(); }
            if (input != null) { input.context().unlockDeferred(); }
            if (input != null) { input.context().publishChanges(); }
            if (output != null) { output.context().publishChanges(); }
        }
    }

    private static void saveSide(NBTTagCompound tag, String name, @Nullable SlottedResourceStorage storage) {
        if (storage == null) { tag.removeTag(name); return; }
        var list = new NBTTagList();
        for (int slot = 0; slot < storage.size(); slot++) {
            var stack = storage.getStack(slot);
            if (stack == null) { continue; }
            var entry = GenericStack.writeTag(stack);
            entry.setInteger("Slot", slot);
            list.appendTag(entry);
        }
        tag.setTag(name, list);
    }

    private static List<GenericStack> readSide(NBTTagCompound tag, String name, @Nullable SlottedResourceStorage storage) {
        var result = new ObjectArrayList<GenericStack>();
        if (storage == null) { return result; }
        result.size(storage.size());
        if (!tag.hasKey(name)) { return result; }
        if (!tag.hasKey(name, 9)) { throw new IllegalArgumentException("Invalid saved hatch side " + name); }
        var list = (NBTTagList) tag.getTag(name);
        if (list.tagCount() > 0 && list.getTagType() != 10) {
            throw new IllegalArgumentException("Invalid saved hatch entries " + name);
        }
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            if (!entry.hasKey("Slot", 3)) { throw new IllegalArgumentException("Missing saved resource slot"); }
            int slot = Objects.checkIndex(entry.getInteger("Slot"), result.size());
            var stack = Objects.requireNonNull(GenericStack.readTag(entry), "Missing saved resource");
            if (result.get(slot) != null || stack.amount() <= 0 || !storage.isAllowedIn(slot, stack.what())
                || stack.amount() > storage.getMaxAmount(stack.what())) {
                throw new IllegalArgumentException("Invalid or duplicate saved resource slot " + slot);
            }
            result.set(slot, stack);
        }
        return result;
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
