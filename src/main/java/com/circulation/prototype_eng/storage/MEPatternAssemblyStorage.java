package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.ResourceRecipeObserver;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Independent transactional ledgers; ME callbacks and recipe workers share these exact balances.
 */
public final class MEPatternAssemblyStorage implements ResourceHatch {
    private static final ResourceKeyNbt KEYS = ResourceKeyNbt.INSTANCE;
    private final ResourceKeyNbt keys;
    private final UnboundedResourceBuffer input;
    private final UnboundedResourceBuffer catalyst;
    private final UnboundedResourceBuffer output;
    private final ObjectArrayList<UnboundedResourceBuffer> isolated = new ObjectArrayList<>();
    private final ObjectArrayList<StorageContext> contexts = new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<UUID, ObjectArrayList<AEKey2LongMap>> started = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RecipeProcess, Boolean> attached = new Reference2ObjectOpenHashMap<>();
    private final Supplier<AEColor> color;
    private final Runnable changed;
    private final UUID partitionId;
    private volatile MEPatternAssemblyBlockEntity.Mode mode = MEPatternAssemblyBlockEntity.Mode.DEFAULT;
    private volatile List<ResourceHatch> partitions;
    private boolean craftingLocked;
    private boolean lockRecipeSeen;
    private volatile boolean valid = true;

    public MEPatternAssemblyStorage(int slots, Supplier<AEColor> color, Runnable changed) {
        this(slots, color, changed, KEYS);
    }

    MEPatternAssemblyStorage(int slots, Supplier<AEColor> color, Runnable changed, ResourceKeyNbt keys) {
        this(slots, color, changed, keys, UUID.randomUUID());
    }

    private MEPatternAssemblyStorage(int slots, Supplier<AEColor> color, Runnable changed, ResourceKeyNbt keys, UUID partitionId) {
        if (slots < 1) throw new IllegalArgumentException("Pattern inventory needs positive slots");
        this.partitionId = partitionId;
        this.color = color;
        this.changed = changed;
        this.keys = keys;
        input = buffer(changed);
        catalyst = buffer(changed);
        output = buffer(changed);
        for (int slot = 0; slot < slots; slot++) isolated.add(buffer(changed));
        rebuildPartitions();
    }

    private static boolean empty(UnboundedResourceBuffer buffer) {
        var context = buffer.context();
        context.lock();
        try {
            return !buffer.hasReservations() && buffer.snapshot().available().isEmpty();
        } finally {
            context.unlock();
        }
    }

    private static void transfer(UnboundedResourceBuffer buffer, MEResourceHatch.METransfer transfer) {
        var snapshot = buffer.snapshot();
        for (var entry : snapshot.available().object2LongEntrySet()) {
            var context = buffer.context();
            if (!context.tryOrdinaryLock()) continue;
            try {
                if (buffer.hasTransferReservation()) return;
                long offer = buffer.extract(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE);
                if (offer == 0) continue;
                var receipt = buffer.reserveTransfer(entry.getKey(), offer, false);
                if (receipt != null) {
                    try {
                        receipt.complete(transfer.insert(entry.getKey(), offer));
                    } catch (RuntimeException | Error failure) {
                        context.invalidate();
                        throw failure;
                    }
                }
            } finally {
                context.unlock();
            }
        }
    }

    public static MEPatternAssemblyStorage load(NBTTagCompound tag, int slots, Supplier<AEColor> color, Runnable changed) {
        return load(tag, slots, color, changed, KEYS);
    }

    static MEPatternAssemblyStorage load(NBTTagCompound tag, int slots, Supplier<AEColor> color, Runnable changed, ResourceKeyNbt keys) {
        var next = new MEPatternAssemblyStorage(slots, color, changed, keys,
            UUID.fromString(tag.getString("PartitionId")));
        next.mode = MEPatternAssemblyBlockEntity.Mode.valueOf(tag.getString("Mode"));
        next.craftingLocked = tag.getBoolean("CraftingLocked");
        next.lockRecipeSeen = tag.getBoolean("LockRecipeSeen");
        if (next.craftingLocked && next.mode != MEPatternAssemblyBlockEntity.Mode.CRAFTING_LOCK)
            throw new IllegalArgumentException("Invalid saved crafting lock mode");
        var running = tag.getTagList("Running", 10);
        for (int index = 0; index < running.tagCount(); index++) {
            var record = running.getCompoundTagAt(index);
            var process = UUID.fromString(record.getString("Process"));
            var signatures = new ObjectArrayList<AEKey2LongMap>();
            var savedSignatures = record.getTagList("Signatures", 10);
            for (int entry = 0; entry < savedSignatures.tagCount(); entry++) {
                var signatureTag = savedSignatures.getCompoundTagAt(entry);
                AEKey2LongMap signature = new AEKey2LongMap.OpenHashMap();

                var elements = keys.readStacks(signatureTag.getTagList("Amounts", 10));
                for (int index10 = 0; index10 < elements.size(); index10++) {
                    var stack = elements.get(index10);
                    if (!PatternInputSignature.add(signature, stack.what(), stack.amount()))
                        throw new IllegalArgumentException("Invalid saved resource receipt");
                }

                signature = PatternInputSignature.normalize(signature);
                if (signature.isEmpty() || signatures.contains(signature))
                    throw new IllegalArgumentException("Empty or duplicate resource receipt");
                signatures.add(signature);
            }
            if (next.started.putIfAbsent(process, signatures) != null)
                throw new IllegalArgumentException("Duplicate running process receipt");
        }
        next.input.restore(keys.readStacks(tag.getTagList("Input", 10)));
        next.catalyst.restore(keys.readStacks(tag.getTagList("Catalyst", 10)));
        next.output.restore(keys.readStacks(tag.getTagList("Output", 10)));
        var saved = tag.getTagList("Isolated", 10);
        boolean[] visited = new boolean[slots];
        for (int index = 0; index < saved.tagCount(); index++) {
            var entry = saved.getCompoundTagAt(index);
            int slot = Objects.checkIndex(entry.getInteger("Slot"), slots);
            if (visited[slot]) throw new IllegalArgumentException("Repeated isolated pattern slot");
            visited[slot] = true;
            next.isolated.get(slot).restore(keys.readStacks(entry.getTagList("Contents", 10)));
        }
        if (next.mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION) {
            if (!empty(next.input))
                throw new IllegalArgumentException("Isolated mode has an unpartitioned input balance");
        } else for (int index7 = 0; index7 < next.isolated.size(); index7++) {
            var buffer = next.isolated.get(index7);
            if (!empty(buffer)) throw new IllegalArgumentException("Non-isolated mode has hidden isolated inputs");
        }
        next.rebuildPartitions();
        return next;
    }

    private UnboundedResourceBuffer buffer(Runnable listener) {
        var buffer = new UnboundedResourceBuffer(listener);
        contexts.add(buffer.context());
        return buffer;
    }

    private void rebuildPartitions() {
        var views = new ObjectArrayList<ResourceHatch>();
        var prefix = partitionId + "/";
        if (mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION) {
            for (int slot = 0; slot < isolated.size(); slot++)
                views.add(new Port(isolated.get(slot), prefix + "pattern/" + slot, false));
        } else views.add(new Port(input, "", false));
        views.add(new Port(catalyst, mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? prefix : "", true));
        partitions = views;
    }

    public MEPatternAssemblyBlockEntity.Mode mode() {
        return mode;
    }

    public synchronized boolean setMode(MEPatternAssemblyBlockEntity.Mode next) {
        if (next == mode) return true;
        if (!valid || !idle()) return false;
        mode = next;
        craftingLocked = false;
        rebuildPartitions();
        changed.run();
        return true;
    }

    @Override
    public ResourceInputGroup inputGroup() {
        return new ResourceInputGroup(color.get());
    }

    AEColor inputColor() {
        return color.get();
    }

    /**
     * Published partitions are stable and must not be modified by consumers.
     */
    @Override
    public List<? extends ResourceHatch> inputPartitions() {
        return partitions;
    }

    @Override
    public @Nullable ResourceStorage input() {
        return mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? null : input;
    }

    @Override
    public @NotNull UnboundedResourceBuffer output() {
        return output;
    }

    public UnboundedResourceBuffer catalyst() {
        return catalyst;
    }

    /**
     * Only recipe blocking needs to compare the incoming amounts' normalized ratios.
     */
    public synchronized boolean accepts(AEKey2LongMap amounts) {
        if (!valid) return false;
        return switch (mode) {
            case DEFAULT, PATTERN_ISOLATION -> true;
            case BLOCKING -> inputsEmpty();
            case CRAFTING_LOCK -> !craftingLocked;
            case RECIPE_BLOCKING -> inputsEmpty() || runningSignature(PatternInputSignature.normalize(amounts));
        };
    }

    public synchronized boolean busy() {
        return !valid || mode == MEPatternAssemblyBlockEntity.Mode.BLOCKING && !inputsEmpty()
            || mode == MEPatternAssemblyBlockEntity.Mode.CRAFTING_LOCK && craftingLocked;
    }

    private boolean runningSignature(AEKey2LongMap signature) {
        for (var signatures : started.values()) if (signatures.contains(signature)) return true;
        return false;
    }

    public synchronized boolean push(int slot, AEKey2LongMap amounts) {
        Objects.checkIndex(slot, isolated.size());
        if (amounts.isEmpty() || !accepts(amounts)) return false;
        var buffer = mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? isolated.get(slot) : input;
        var tokens = new ObjectArrayList<ResourceReservation>(amounts.size());
        var context = buffer.context();
        if (!context.tryOrdinaryLock()) return false;
        boolean reserved = true;
        Throwable reservationFailure = null;
        try {
            for (var entry : amounts.object2LongEntrySet()) {
                if (entry.getLongValue() <= 0 || buffer.insert(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE) != entry.getLongValue()) {
                    return false;
                }
            }
            for (var entry : amounts.object2LongEntrySet()) {
                var reservation = buffer.reserveInsert(buffer.snapshot(), entry.getKey(), entry.getLongValue());
                if (reservation.reservation() == null) {
                    reserved = false;
                    break;
                }
                tokens.add(reservation.reservation());
            }
        } catch (RuntimeException | Error failure) {
            reserved = false;
            reservationFailure = failure;
        } finally {
            context.unlock();
        }
        if (!reserved) {
            ResourceTransactions.rollback(tokens);
            if (reservationFailure instanceof RuntimeException runtime) throw runtime;
            if (reservationFailure instanceof Error error) throw error;
            return false;
        }
        return ResourceTransactions.commit(tokens, () -> {
        }, () -> {
            if (mode == MEPatternAssemblyBlockEntity.Mode.CRAFTING_LOCK) {
                craftingLocked = true;
                lockRecipeSeen = false;
            }
        }, () -> {
        });
    }

    public synchronized int maxMultiplier(int slot, AEKey2LongMap perPush, int requested) {
        if (requested <= 0 || perPush.isEmpty() || !accepts(perPush)) return 0;
        var buffer = mode == MEPatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? isolated.get(Objects.checkIndex(slot, isolated.size())) : input;
        long limit = requested;
        for (var entry : perPush.object2LongEntrySet()) {
            long each = entry.getLongValue();
            if (each <= 0) return 0;
            limit = Math.min(limit, buffer.insert(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE) / each);
        }
        return (int) limit;
    }

    public synchronized void attached(RecipeProcess process) {
        attached.put(process, Boolean.TRUE);
    }

    public synchronized void detached(RecipeProcess process) {
        attached.remove(process);
        started.remove(process.identity());
        unlockIfFinished();
    }

    public synchronized void stageCommitted(RecipeProcess process, RecipeStage stage) {
        if (stage == RecipeStage.END) {
            started.remove(process.identity());
            attached.remove(process);
            unlockIfFinished();
        }
    }

    public synchronized void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
        if (amounts.isEmpty()) return;
        var signatures = started.computeIfAbsent(process.identity(), _ -> new ObjectArrayList<>());
        var complete = process.committedInputs(stage);
        if (complete != null && !complete.isEmpty()) {
            var signature = PatternInputSignature.normalize(complete);
            if (!signatures.contains(signature)) signatures.add(signature);
        }
        if (craftingLocked) {
            lockRecipeSeen = true;
        }
    }

    private void unlockIfFinished() {
        if (craftingLocked && lockRecipeSeen && started.isEmpty() && inputsEmpty()) {
            craftingLocked = false;
            lockRecipeSeen = false;
            changed.run();
        }
    }

    private boolean inputsEmpty() {
        if (!empty(input)) return false;
        for (int index2 = 0; index2 < isolated.size(); index2++) {
            var buffer = isolated.get(index2);
            if (!empty(buffer)) return false;
        }
        return true;
    }

    public boolean hasReservations() {
        if (input.hasReservations() || catalyst.hasReservations() || output.hasReservations()) return true;
        for (int index3 = 0; index3 < isolated.size(); index3++) {
            var buffer = isolated.get(index3);
            if (buffer.hasReservations()) return true;
        }
        for (int index9 = 0; index9 < contexts.size(); index9++) {
            var context = contexts.get(index9);
            context.lock();
            try {
                if (!context.idleForReplacement()) return true;
            } finally {
                context.unlock();
            }
        }
        return false;
    }

    public List<GenericStack> inputContents() {
        var values = new ObjectArrayList<GenericStack>();
        input.snapshot().available().forEachEntry((key, amount) -> values.add(new GenericStack(key, amount)));
        for (int index6 = 0; index6 < isolated.size(); index6++) {
            var buffer = isolated.get(index6);
            buffer.snapshot().available().forEachEntry((key, amount) -> values.add(new GenericStack(key, amount)));
        }
        return values;
    }

    public MEPatternAssemblyBlockEntity.BufferView inputView() {
        StorageContext.lockAll(contexts);
        try {
            long revision = input.context().revision();
            for (int index5 = 0; index5 < isolated.size(); index5++) {
                var buffer = isolated.get(index5);
                revision = Math.addExact(revision, buffer.context().revision());
            }
            return new MEPatternAssemblyBlockEntity.BufferView(revision, inputContents());
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
    }

    public synchronized boolean idle() {
        return valid && attached.isEmpty() && started.isEmpty() && !hasReservations() && inputsEmpty();
    }

    public void exportOutput(MEResourceHatch.METransfer transfer) {
        transfer(output, transfer);
    }

    public synchronized boolean returnInputs(MEResourceHatch.METransfer transfer) {
        if (!valid) return false;
        transfer(input, transfer);
        for (int index1 = 0; index1 < isolated.size(); index1++) {
            var buffer = isolated.get(index1);
            transfer(buffer, transfer);
        }
        if (inputsEmpty() && started.isEmpty()) {
            craftingLocked = false;
            lockRecipeSeen = false;
            changed.run();
        }
        return inputsEmpty();
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    public synchronized void invalidate() {
        valid = false;
        for (int index8 = 0; index8 < contexts.size(); index8++) {
            var context = contexts.get(index8);
            context.lock();
            try {
                context.invalidate();
            } finally {
                context.unlock();
            }
        }
    }

    public synchronized void save(NBTTagCompound tag) {
        StorageContext.lockAll(contexts);
        try {
            tag.setString("PartitionId", partitionId.toString());
            tag.setString("Mode", mode.name());
            tag.setBoolean("CraftingLocked", craftingLocked);
            tag.setBoolean("LockRecipeSeen", lockRecipeSeen);
            var running = new NBTTagList();
            started.forEach((process, signatures) -> {
                var record = new NBTTagCompound();
                record.setString("Process", process.toString());
                var savedSignatures = new NBTTagList();
                for (int index11 = 0; index11 < signatures.size(); index11++) {
                    var signature = signatures.get(index11);
                    var stacks = new ObjectArrayList<GenericStack>();
                    signature.forEachEntry((key, amount) -> stacks.add(new GenericStack(key, amount)));
                    var savedSignature = new NBTTagCompound();
                    savedSignature.setTag("Amounts", keys.writeStacks(stacks));
                    savedSignatures.appendTag(savedSignature);
                }
                record.setTag("Signatures", savedSignatures);
                running.appendTag(record);
            });
            tag.setTag("Running", running);
            tag.setTag("Input", keys.writeStacks(input.committedContents()));
            tag.setTag("Catalyst", keys.writeStacks(catalyst.committedContents()));
            tag.setTag("Output", keys.writeStacks(output.committedContents()));
            var slots = new NBTTagList();
            for (int slot = 0; slot < isolated.size(); slot++) {
                var contents = isolated.get(slot).committedContents();
                if (contents.isEmpty()) continue;
                var entry = new NBTTagCompound();
                entry.setInteger("Slot", slot);
                entry.setTag("Contents", keys.writeStacks(contents));
                slots.appendTag(entry);
            }
            tag.setTag("Isolated", slots);
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
    }

    public synchronized List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferred) {
        valid = false;
        var drops = new ObjectArrayList<GenericStack>();
        StorageContext.lockAll(contexts);
        try {
            drops.addAll(input.removeForDrops(deferred));
            drops.addAll(catalyst.removeForDrops(deferred));
            drops.addAll(output.removeForDrops(deferred));
            for (int index4 = 0; index4 < isolated.size(); index4++) {
                var buffer = isolated.get(index4);
                drops.addAll(buffer.removeForDrops(deferred));
            }
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
        return drops;
    }

    private final class Port implements ResourceHatch, ResourceRecipeObserver {
        private final UnboundedResourceBuffer buffer;
        private final String partition;
        private final boolean shared;

        private Port(UnboundedResourceBuffer buffer, String partition, boolean shared) {
            this.buffer = buffer;
            this.partition = partition;
            this.shared = shared;
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return new ResourceInputGroup(color.get(), partition);
        }

        @Override
        public boolean sharedInput() {
            return shared;
        }

        @Override
        public ResourceStorage input() {
            return buffer;
        }

        @Override
        public ResourceStorage output() {
            return output;
        }

        @Override
        public boolean isValid() {
            return valid && buffer.isValid();
        }

        @Override
        public Object observerIdentity() {
            return shared ? this : MEPatternAssemblyStorage.this;
        }

        @Override
        public void attached(RecipeProcess process) {
            if (!shared) MEPatternAssemblyStorage.this.attached(process);
        }

        @Override
        public void detached(RecipeProcess process) {
            if (!shared) MEPatternAssemblyStorage.this.detached(process);
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            if (!shared) MEPatternAssemblyStorage.this.stageCommitted(process, stage);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            if (!shared) MEPatternAssemblyStorage.this.inputsCommitted(process, stage, amounts);
        }
    }
}
