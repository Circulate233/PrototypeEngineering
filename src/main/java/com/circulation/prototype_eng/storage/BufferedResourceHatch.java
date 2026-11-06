package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import ae2.helpers.externalstorage.GenericStackInv;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** Owns buffers independently of their block entity or GUI. */
public final class BufferedResourceHatch implements ResourceHatch {
    private final Buffer input;
    private final Buffer output;
    private final MEStorage automation = new AutomationPort();
    private boolean valid = true;

    /** Capacities are per slot and per key type, in that type's native units. */
    public BufferedResourceHatch(HatchMode mode, int slotsPerSide,
                                 Map<AEKeyType, Long> capacities, Runnable onChanged) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(onChanged, "onChanged");
        if (slotsPerSide < 1 || capacities.isEmpty()) {
            throw new IllegalArgumentException("A buffer needs slots and supported resource types");
        }
        var limits = new Reference2LongArrayMap<>(capacities);
        if (limits.values().longStream().anyMatch(capacity -> capacity <= 0)) {
            throw new IllegalArgumentException("Capacities must be positive");
        }
        input = mode.hasInput() ? new Buffer(slotsPerSide, limits, onChanged) : null;
        output = mode.hasOutput() ? new Buffer(slotsPerSide, limits, onChanged) : null;
    }

    @Override
    @Nullable
    public GenericStackInv input() {
        return input;
    }

    @Override
    @Nullable
    public GenericStackInv output() {
        return output;
    }

    /** External automation inserts only into input and extracts only from output. */
    public MEStorage automation() {
        return automation;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    /** Retained machine/automation storage references also stop accepting operations. */
    public void invalidate() {
        valid = false;
    }

    public void save(NBTTagCompound tag) {
        if (input != null) {
            input.writeToChildTag(tag, "Input");
        } else {
            tag.removeTag("Input");
        }
        if (output != null) {
            output.writeToChildTag(tag, "Output");
        } else {
            tag.removeTag("Output");
        }
    }

    public void load(NBTTagCompound tag) {
        if (input != null) {
            input.readFromChildTag(tag, "Input");
        }
        if (output != null) {
            output.readFromChildTag(tag, "Output");
        }
    }

    private final class Buffer extends GenericStackInv {
        Buffer(int slots, Map<AEKeyType, Long> capacities, Runnable listener) {
            super(capacities.keySet(), listener, Mode.STORAGE, slots);
            capacities.forEach(this::setCapacity);
        }

        @Override
        public long getMaxAmount(AEKey key) {
            return getCapacity(key.getType());
        }

        @Override
        public boolean canInsert() {
            return valid;
        }

        @Override
        public boolean canExtract() {
            return valid;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (valid) {
                super.getAvailableStacks(out);
            }
        }
    }

    private final class AutomationPort implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return input == null ? 0 : input.insert(what, amount, mode, source);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return output == null ? 0 : output.extract(what, amount, mode, source);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (output != null) {
                output.getAvailableStacks(out);
            }
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentTranslation("prototype_eng.storage.resource_hatch");
        }
    }
}
