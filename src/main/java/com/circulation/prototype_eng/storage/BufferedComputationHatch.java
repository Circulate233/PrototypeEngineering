package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import com.circulation.prototype_eng.api.network.resource.ComputationHatch;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.localization.HatchGuiTitle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.ITextComponent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fixed four-tier shared storage; component discovery is driven by its change notifications.
 */
public final class BufferedComputationHatch implements ComputationHatch {
    private static final ResourceKeyNbt KEYS = ResourceKeyNbt.INSTANCE;
    private final SlottedResourceBuffer inventory;
    private final AEKey[] installedItems;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final MEStorage automation = new AutomationPort();
    private final SlottedResourceHatch guiView = new SlottedResourceHatch() {
        @Override
        public SlottedResourceStorage input() {
            return inventory;
        }

        @Override
        public SlottedResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return inventory.isValid();
        }
    };

    public BufferedComputationHatch(int tier, Runnable onChanged) {
        if (tier < 1 || tier > 4) throw new IllegalArgumentException("Computation hatch tier must be 1-4");
        var capacities = new Reference2LongOpenHashMap<AEKeyType>();
        for (var type : AEKeyTypes.getAll())
            capacities.put(type, type == AEKeyType.items() ? 1 : nonItemCapacity(tier));
        installedItems = new AEKey[tier];
        inventory = new SlottedResourceBuffer(tier, capacities, BufferedComputationHatch::accepts, () -> contentsChanged(onChanged));
    }

    public static long nonItemCapacity(int tier) {
        return 1_000L << (2 * (tier - 1));
    }

    private static boolean accepts(AEKey key) {
        return !(key instanceof AEItemKey item) || item.getItem() instanceof NetworkComputerComponent;
    }

    private void contentsChanged(Runnable onChanged) {
        onChanged.run();
        boolean changed = false;
        inventory.context().lock();
        try {
            for (int slot = 0; slot < inventory.size(); slot++) {
                var stack = inventory.getStack(slot);
                var item = stack != null && stack.what() instanceof AEItemKey ? stack.what() : null;
                if (!Objects.equals(installedItems[slot], item)) {
                    installedItems[slot] = item;
                    changed = true;
                }
            }
        } finally {
            inventory.context().unlock();
        }
        if (changed) listeners.forEach(Runnable::run);
    }

    @Override
    public SlottedResourceBuffer inventory() {
        return inventory;
    }

    @Override
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isValid() {
        return inventory.isValid();
    }

    public MEStorage automation() {
        return automation;
    }

    public SlottedResourceHatch guiView() {
        return guiView;
    }

    public void invalidate() {
        inventory.context().invalidate();
    }

    public List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferred) {
        inventory.context().lock();
        try {
            return inventory.removeForDrops(deferred);
        } finally {
            inventory.context().unlock();
        }
    }

    public void save(NBTTagCompound tag) {
        var entries = new NBTTagList();
        inventory.context().lock();
        try {
            for (int slot = 0; slot < inventory.size(); slot++) {
                var stack = inventory.getStack(slot);
                if (stack == null) continue;
                var entry = new NBTTagCompound();
                entry.setInteger("Slot", slot);
                entry.setTag("Key", KEYS.encode(stack.what()));
                entry.setLong("Quantity", stack.amount());
                entries.appendTag(entry);
            }
        } finally {
            inventory.context().unlock();
        }
        tag.setTag("Contents", entries);
    }

    public void load(NBTTagCompound tag) {
        var contents = new ObjectArrayList<GenericStack>();
        contents.size(inventory.size());
        var entries = tag.getTagList("Contents", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            int slot = entry.getInteger("Slot");
            contents.set(slot, new GenericStack(KEYS.decode(entry.getCompoundTag("Key")), entry.getLong("Quantity")));
        }
        inventory.restore(contents);
    }

    private final class AutomationPort implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            return inventory.insert(what, amount, mode);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            return inventory.extract(what, amount, mode);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            inventory.snapshot().available().forEachEntry(out::add);
        }

        @Override
        public ITextComponent getDescription() {
            return HatchGuiTitle.computation(HatchTier.of(inventory.size())).text();
        }
    }
}
